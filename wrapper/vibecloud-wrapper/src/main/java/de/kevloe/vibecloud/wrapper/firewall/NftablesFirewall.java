package de.kevloe.vibecloud.wrapper.firewall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Haelt die Gameserver-Ports fuer fremde Adressen geschlossen (PLAN.md Abschnitt 13).
 *
 * <p><b>Warum das nicht optional ist:</b> Gameserver laufen hinter Velocity mit
 * {@code online-mode=false}. Ein von aussen erreichbarer Gameserver-Port bedeutet, dass sich
 * jemand ohne Mojang-Pruefung direkt verbindet und sich als beliebiger Spieler ausgibt -
 * auch als Administrator.
 *
 * <p>Der Master kennt alle Proxy-IPs und schickt sie bei jeder Aenderung. Der Wrapper pflegt
 * daraus ein nftables-Set. Damit stimmt die Whitelist automatisch, auch wenn ein Proxy
 * dazukommt - genau die Handarbeit, die sonst irgendwann vergessen wird.
 *
 * <p><b>Ungetestet.</b> Diese Klasse laeuft nur unter Linux mit {@code nftables} und
 * {@code CAP_NET_ADMIN}. Die Entwicklungsumgebung ist Windows, hier konnte also nur der
 * erzeugte Regelsatz geprueft werden, nicht seine Wirkung. Vor dem Produktivbetrieb auf
 * einem Root verifizieren: von aussen auf einen Port im Bereich verbinden - es muss
 * abgelehnt werden.
 */
public final class NftablesFirewall {

    private static final Logger LOG = LoggerFactory.getLogger(NftablesFirewall.class);

    private static final String TABLE = "vibecloud";
    private static final String SET = "proxies";

    private final int portRangeStart;
    private final int portRangeEnd;
    private final boolean enabled;

    /** Zuletzt angewendete Proxy-Liste, um unnoetige Aufrufe zu vermeiden. */
    private final AtomicReference<Set<String>> applied = new AtomicReference<>(Set.of());

    public NftablesFirewall(boolean enabled, int portRangeStart, int portRangeEnd) {
        this.enabled = enabled;
        this.portRangeStart = portRangeStart;
        this.portRangeEnd = portRangeEnd;
    }

    /**
     * Prueft die Voraussetzungen und meldet deutlich, wenn etwas fehlt.
     *
     * @return {@code true} wenn die Verwaltung aktiv werden kann
     */
    public boolean checkPrerequisites() {
        if (!enabled) {
            LOG.warn("""
                    Firewall-Verwaltung ist ausgeschaltet (firewall.manage = false).
                    Die Gameserver-Ports {}-{} muessen dann von Hand auf die Proxy-IPs
                    begrenzt werden. Ohne diese Begrenzung kann sich jemand direkt mit einem
                    Gameserver verbinden und sich als beliebiger Spieler ausgeben, weil die
                    Gameserver hinter dem Proxy mit online-mode=false laufen.""",
                    portRangeStart, portRangeEnd);
            return false;
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("linux")) {
            LOG.error("firewall.manage ist aktiv, aber dieser Node laeuft nicht unter Linux. "
                      + "nftables gibt es hier nicht - die Verwaltung bleibt aus.");
            return false;
        }
        if (!commandExists("nft")) {
            LOG.error("firewall.manage ist aktiv, aber 'nft' ist nicht installiert. "
                      + "Entweder nftables nachinstallieren oder firewall.manage abschalten "
                      + "und die Regeln selbst pflegen.");
            return false;
        }
        return true;
    }

    /**
     * Setzt die erlaubten Proxy-Adressen.
     *
     * <p>Der ganze Regelsatz wird in einem Durchgang ersetzt, nicht Zeile fuer Zeile
     * geaendert: Ein halb angewendeter Regelsatz waere der eine Moment, in dem die Ports
     * offen stehen.
     */
    public void applyProxyAddresses(Set<String> proxyAddresses) {
        if (!enabled) {
            return;
        }
        if (proxyAddresses.equals(applied.get())) {
            return;
        }
        if (proxyAddresses.isEmpty()) {
            // Keine bekannten Proxys: Dann wird alles gesperrt, nicht alles geoeffnet.
            LOG.warn("Keine Proxy-Adressen bekannt - die Gameserver-Ports bleiben fuer alle "
                     + "gesperrt, bis sich ein Proxy angemeldet hat.");
        }

        String ruleset = buildRuleset(proxyAddresses);
        try {
            Path temporary = Files.createTempFile("vibecloud-nft", ".conf");
            Files.writeString(temporary, ruleset, StandardCharsets.UTF_8);
            try {
                run(List.of("nft", "-f", temporary.toString()));
                applied.set(Set.copyOf(proxyAddresses));
                LOG.info("Firewall aktualisiert: Ports {}-{} nur fuer {}",
                        portRangeStart, portRangeEnd,
                        proxyAddresses.isEmpty() ? "niemanden" : proxyAddresses);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("""
                    Firewall konnte NICHT gesetzt werden: {}
                    Die Gameserver-Ports sind damit moeglicherweise offen. Bitte pruefen -
                    ein offener Port bedeutet, dass sich jemand als beliebiger Spieler
                    ausgeben kann.""", exception.getMessage());
        }
    }

    /**
     * Der Regelsatz. Bewusst als vollstaendige Tabelle: {@code flush table} am Anfang
     * macht den Vorgang wiederholbar, ohne dass sich Regeln ansammeln.
     */
    String buildRuleset(Set<String> proxyAddresses) {
        String elements = proxyAddresses.isEmpty()
                ? ""
                : "        elements = { " + String.join(", ", proxyAddresses) + " }\n";

        return """
                #!/usr/sbin/nft -f
                # Von vibeCloud verwaltet. Nicht von Hand aendern - wird bei jeder
                # Proxy-Aenderung neu geschrieben.
                table inet %s {
                    set %s {
                        type ipv4_addr
                %s    }

                    chain input {
                        type filter hook input priority 0; policy accept;

                        # Bereits bestehende Verbindungen nicht abschneiden.
                        ct state established,related accept

                        # Gameserver-Ports: nur die Proxys duerfen hinein.
                        tcp dport %d-%d ip saddr @%s accept
                        tcp dport %d-%d drop
                    }
                }
                """.formatted(TABLE, SET, elements,
                portRangeStart, portRangeEnd, SET,
                portRangeStart, portRangeEnd);
    }

    /** Raeumt die Tabelle weg, z. B. beim Abschalten der Verwaltung. */
    public void clear() {
        if (!enabled) {
            return;
        }
        try {
            run(List.of("nft", "delete", "table", "inet", TABLE));
            applied.set(Set.of());
            LOG.info("Firewall-Tabelle entfernt");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("Tabelle war nicht vorhanden: {}", exception.getMessage());
        }
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("'" + String.join(" ", command) + "' hat nicht geantwortet");
        }
        if (process.exitValue() != 0) {
            throw new IOException("'" + String.join(" ", command) + "' endete mit "
                                  + process.exitValue() + ": " + output.strip());
        }
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", "command -v " + command)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
