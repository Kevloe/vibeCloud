package de.kevloe.vibecloud.master.scheduler;

import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.server.ServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Soll-Ist-Abgleich der Servergruppen (PLAN.md Abschnitt 6).
 *
 * <p>Der Callback blockiert nie: Er trifft Entscheidungen und schickt Befehle in den
 * Rueckkanal, die Arbeit macht der Wrapper. Im Vorgaengerprojekt lief hier Datei-I/O im
 * Timer-Thread - das hat das Timeout-Erkennen blockiert.
 *
 * <p><b>Der Scheduler ist nach dem Start bewusst noch aus.</b> Er wird erst scharf gemacht,
 * wenn die verbundenen Wrapper ihren Zustand gemeldet und ihre Outbox nachgespielt haben.
 * Sonst wuerde er Server nachstarten, obwohl ihm noch die halbe Wirklichkeit fehlt.
 */
public final class PlacementScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PlacementScheduler.class);

    private final ServerGroupRepository groups;
    private final ServerRegistry registry;
    private final ServerService servers;
    private final ScheduledExecutorService executor;
    private final int intervalSeconds;

    private final AtomicBoolean armed = new AtomicBoolean(false);

    /** Seit wann ein Server leer ist - fuer den idle_timeout. */
    private final Map<String, Instant> emptySince = new ConcurrentHashMap<>();

    /** Wartezeit je Gruppe nach schnell abgestuerzten Servern. */
    private final Map<String, Backoff> backoffs = new ConcurrentHashMap<>();

    public PlacementScheduler(ServerGroupRepository groups, ServerRegistry registry,
                              ServerService servers, int intervalSeconds) {
        this.groups = groups;
        this.registry = registry;
        this.servers = servers;
        this.intervalSeconds = intervalSeconds;
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("placement-scheduler").factory());
    }

    public void start() {
        executor.scheduleAtFixedRate(this::tickSafely,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.info("Scheduler laeuft im {}-Sekunden-Takt (noch nicht scharf)", intervalSeconds);
    }

    /**
     * Macht den Scheduler scharf. Wird gerufen, wenn ein Wrapper seinen Replay beendet hat.
     */
    public void arm() {
        if (armed.compareAndSet(false, true)) {
            LOG.info("Scheduler ist scharf - Gruppen werden ab jetzt automatisch aufgefuellt");
        }
    }

    public boolean isArmed() {
        return armed.get();
    }

    private void tickSafely() {
        if (!armed.get()) {
            return;
        }
        try {
            tick();
        } catch (RuntimeException exception) {
            // Ein Fehler in einer Gruppe darf den Scheduler nicht dauerhaft anhalten.
            LOG.error("Abgleich fehlgeschlagen", exception);
        }
    }

    private void tick() {
        for (ServerGroup group : groups.findAll()) {
            if (group.maintenance()) {
                // Laufende Server der Gruppe bleiben in Ruhe, damit man mit Bypass testen
                // kann - aber es wird nichts Neues gestartet.
                continue;
            }
            Backoff backoff = backoffs.get(group.name());
            if (backoff != null && backoff.isWaiting()) {
                continue;
            }
            List<CloudServer> active = registry.activeOfGroup(group.name());
            scaleUp(group, active);
            scaleDown(group, active);
        }
    }

    private void scaleUp(ServerGroup group, List<CloudServer> active) {
        int missing = group.minOnline() - active.size();
        if (missing > 0) {
            for (int i = 0; i < missing; i++) {
                ServerService.StartResult result = servers.start(group.name(), "SCHEDULER");
                if (!result.started()) {
                    // Nur auf debug: Dieser Fall tritt im Takt von Sekunden auf, solange
                    // z. B. kein Node verbunden ist. Als Warnung wuerde er das Log fluten.
                    LOG.debug("Gruppe {} konnte nicht aufgefuellt werden: {}",
                            group.name(), result.reason());
                    return;
                }
            }
            return;
        }

        if (group.startPercent() <= 0 || active.size() >= group.maxOnline()) {
            return;
        }
        // Auslastung ueber alle laufenden Server der Gruppe.
        int capacity = active.size() * Math.max(1, group.maxPlayers());
        int players = active.stream().mapToInt(CloudServer::players).sum();
        int loadPercent = capacity == 0 ? 0 : (players * 100) / capacity;

        if (loadPercent >= group.startPercent()) {
            LOG.info("Gruppe {} ist bei {} % (Schwelle {} %) - ein weiterer Server wird gestartet",
                    group.name(), loadPercent, group.startPercent());
            servers.start(group.name(), "SCHEDULER");
        }
    }

    /** Leere Server ueber {@code min_online} hinaus abraeumen - nur dynamische. */
    private void scaleDown(ServerGroup group, List<CloudServer> active) {
        if (group.staticGroup() || active.size() <= group.minOnline()) {
            active.forEach(server -> emptySince.remove(server.name()));
            return;
        }

        Instant now = Instant.now();
        List<CloudServer> candidates = active.stream()
                .filter(server -> server.state() == ServerState.RUNNING)
                .filter(server -> server.players() == 0)
                .sorted(Comparator.comparing(CloudServer::startedAt))
                .toList();

        for (CloudServer server : candidates) {
            if (active.size() - 1 < group.minOnline()) {
                return;
            }
            Instant since = emptySince.computeIfAbsent(server.name(), key -> now);
            if (Duration.between(since, now).toSeconds() >= group.idleTimeout()) {
                LOG.info("{} ist seit {} s leer - wird gestoppt",
                        server.name(), group.idleTimeout());
                servers.stop(server.name(), "SCHEDULER", "leer ueber idle_timeout");
                emptySince.remove(server.name());
                return;
            }
        }
        // Server mit Spielern wieder aus der Beobachtung nehmen.
        active.stream()
                .filter(server -> server.players() > 0)
                .forEach(server -> emptySince.remove(server.name()));
    }

    /**
     * Ein Server dieser Gruppe ist beendet. Stuerzt er schnell ab, wird die Gruppe
     * gebremst.
     *
     * <p>Ohne das startet eine fehlkonfigurierte Gruppe im Scheduler-Takt endlos neu - bei
     * drei Sekunden sind das 1200 Startversuche pro Stunde, die den Node belasten und das
     * Log so fluten, dass die eigentliche Fehlermeldung untergeht. Genau so ist es bei der
     * ersten Velocity-Konfiguration passiert.
     *
     * @param uptimeSeconds wie lange der Server gelaufen ist
     * @param crashed       true bei Absturz oder Exit-Code ungleich 0
     */
    public void reportServerEnded(String groupName, long uptimeSeconds, boolean crashed) {
        if (!crashed || uptimeSeconds >= HEALTHY_UPTIME_SECONDS) {
            // Hat lange genug gelaufen: Die Gruppe gilt als gesund.
            backoffs.remove(groupName);
            return;
        }
        Backoff backoff = backoffs.computeIfAbsent(groupName, key -> new Backoff());
        long waitSeconds = backoff.fail();
        LOG.warn("""
                {} ist nach {} s abgestuerzt ({}. Fehlversuch in Folge).
                Die Gruppe wird {} s nicht erneut gestartet. Ursache im Server-Log suchen:
                logs/{}/""",
                groupName, uptimeSeconds, backoff.failures(), waitSeconds, groupName);
    }

    /** Wie lange ein Server laufen muss, damit die Gruppe als gesund gilt. */
    private static final long HEALTHY_UPTIME_SECONDS = 60;

    @Override
    public void close() {
        executor.close();
    }

    /** Exponentielle Wartezeit nach aufeinanderfolgenden Abstuerzen. */
    private static final class Backoff {

        private static final long BASE_SECONDS = 10;
        private static final long MAX_SECONDS = 300;

        private int failures;
        private Instant waitUntil = Instant.EPOCH;

        synchronized long fail() {
            failures++;
            long seconds = Math.min(MAX_SECONDS, BASE_SECONDS * (1L << Math.min(failures - 1, 5)));
            waitUntil = Instant.now().plusSeconds(seconds);
            return seconds;
        }

        synchronized boolean isWaiting() {
            return Instant.now().isBefore(waitUntil);
        }

        synchronized int failures() {
            return failures;
        }
    }
}
