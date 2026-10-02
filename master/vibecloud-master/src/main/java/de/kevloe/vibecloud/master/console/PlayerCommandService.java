package de.kevloe.vibecloud.master.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Cloud-Befehle aus dem Spiel (PLAN.md Abschnitt 9 und 12).
 *
 * <p>Derselbe Befehl, dieselbe Logik wie in der Konsole - nur die Rechteprüfung kommt
 * hinzu. <b>Der Master prueft selbst</b>: Der Proxy uebertraegt, <i>wer</i> etwas wollte,
 * und niemals ein "darf das"-Flag. Verbirgt der Proxy einen Befehl vor einem Spieler, ist
 * das nur Bequemlichkeit; die Entscheidung faellt hier.
 *
 * <p>Die Ausgabe wird gesammelt und als Zeilen zurueckgegeben. Das Plugin macht daraus
 * Chat-Nachrichten - der Master weiss nichts von MiniMessage oder Chatfarben.
 */
public final class PlayerCommandService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerCommandService.class);

    /** Mehr Vorschlaege sind im Spiel unbrauchbar - die Liste scrollt dann weg. */
    private static final int MAX_CANDIDATES = 50;

    private final CommandRegistry commands;
    private final PermissionCheck permissions;
    private final Audit audit;

    /**
     * @param permissions die Rechteprüfung des Masters - absichtlich als schmale
     *                    Schnittstelle, damit dieser Dienst ohne Datenbank pruefbar ist
     * @param audit       Protokoll der Eingriffe aus dem Spiel
     */
    public PlayerCommandService(CommandRegistry commands, PermissionCheck permissions,
                                Audit audit) {
        this.commands = commands;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** Darf dieser Spieler diesen Knoten? Im Betrieb der {@code PermissionService}. */
    @FunctionalInterface
    public interface PermissionCheck {

        boolean has(UUID uuid, String node);
    }

    /** Nimmt einen Eingriff aus dem Spiel ins Protokoll auf. */
    @FunctionalInterface
    public interface Audit {

        void record(String actor, String command, String args);
    }

    /** Die Befehle, die der Proxy im Spiel anbieten soll. */
    public List<CommandRegistry.Command> inGameCommands() {
        return commands.all().stream().filter(CommandRegistry.Command::availableInGame).toList();
    }

    /**
     * Fuehrt einen Befehl im Namen eines Spielers aus.
     *
     * @return gesammelte Ausgabe, oder {@link Result#denied()} wenn das Recht fehlt
     */
    public Result run(UUID uuid, String playerName, String commandName, List<String> args) {
        Optional<CommandRegistry.Command> found = commands.find(commandName);

        // Unbekannt und "gibt es nur in der Konsole" werden gleich behandelt: Ein Spieler
        // soll nicht erfahren, dass es 'stop' gibt.
        if (found.isEmpty() || !found.get().availableInGame()) {
            return Result.denied();
        }
        CommandRegistry.Command command = found.get();

        if (!mayUse(uuid, command, args)) {
            return Result.denied();
        }

        Collector output = new Collector();
        try {
            command.execute(new CommandRegistry.CommandActor(uuid, playerName), output, args);
        } catch (RuntimeException exception) {
            // Dieselbe Behandlung wie in der Konsole: Die Meldung an den Spieler, die
            // Ursache ins Log.
            output.error("Befehl fehlgeschlagen: " + exception.getMessage());
            LOG.warn("Befehl '{} {}' von {} fehlgeschlagen", commandName,
                    String.join(" ", args), playerName, exception);
        }

        // Jeder Eingriff aus dem Spiel wird protokolliert - in der Konsole sitzt der
        // Betreiber selbst, im Spiel kann es jeder mit dem Recht sein.
        audit.record(playerName, commandName, String.join(" ", args));

        return new Result(true, output.lines);
    }

    /**
     * Vorschlaege fuer die naechste Stelle.
     *
     * <p>Gefiltert nach Rechten: Wer {@code server kill} nicht darf, soll es beim Tippen
     * auch nicht angeboten bekommen. Ohne Filter waere die Vervollstaendigung eine
     * Aufzaehlung aller Befehle, die es gibt.
     */
    public List<String> suggest(UUID uuid, String commandName, List<String> args) {
        Optional<CommandRegistry.Command> found = commands.find(commandName);
        if (found.isEmpty() || !found.get().availableInGame()) {
            return List.of();
        }
        CommandRegistry.Command command = found.get();

        if (!mayUseAnything(uuid, command)) {
            return List.of();
        }

        List<String> candidates;
        try {
            candidates = command.complete(args);
        } catch (RuntimeException exception) {
            // Eine fehlende Vervollstaendigung ist harmlos; eine Ausnahme im
            // Tastendruck-Pfad waere es nicht.
            LOG.debug("Vervollstaendigung von '{}' fehlgeschlagen", commandName, exception);
            return List.of();
        }

        // Am Anfang der Zeile: nur die Unterbefehle zeigen, die der Spieler nutzen darf.
        if (args.size() <= 1) {
            candidates = candidates.stream()
                    .filter(candidate -> !command.subCommands().contains(
                            candidate.toLowerCase(Locale.ROOT))
                            || permissions.has(uuid, command.permission() + "." + candidate))
                    .toList();
        }

        String prefix = args.isEmpty() ? "" : args.getLast().toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    /** Darf der Spieler diesen Aufruf mit diesen Argumenten? */
    public boolean mayUse(UUID uuid, CommandRegistry.Command command, List<String> args) {
        if (args.isEmpty()) {
            // Der Aufruf ohne Argumente zeigt nur die Syntax. Wer irgendeinen Unterbefehl
            // darf, soll sie sehen duerfen.
            return mayUseAnything(uuid, command);
        }
        return permissions.has(uuid, CommandRegistry.permissionFor(command, args));
    }

    /**
     * Darf der Spieler mit diesem Befehl ueberhaupt etwas?
     *
     * <p>Grundrecht oder irgendein Unterbefehl - damit jemand mit
     * {@code vibecloud.command.server.list} den Befehl im Spiel sieht, auch wenn ihm
     * {@code vibecloud.command.server} selbst fehlt.
     */
    public boolean mayUseAnything(UUID uuid, CommandRegistry.Command command) {
        if (permissions.has(uuid, command.permission())) {
            return true;
        }
        return command.subCommands().stream()
                .anyMatch(sub -> permissions.has(uuid, command.permission() + "." + sub));
    }

    /** Ausgabe eines Befehls, Zeile fuer Zeile mit ihrer Dringlichkeit. */
    public record Result(boolean allowed, List<Line> lines) {

        public static Result denied() {
            return new Result(false, List.of());
        }
    }

    public record Line(Level level, String text) {
    }

    public enum Level {
        INFO, SUCCESS, WARN, ERROR
    }

    /** Sammelt statt zu schreiben - der Master hat hier kein Terminal. */
    private static final class Collector implements CommandOutput {

        private final List<Line> lines = new ArrayList<>();

        @Override
        public void info(String message) {
            lines.add(new Line(Level.INFO, message));
        }

        @Override
        public void success(String message) {
            lines.add(new Line(Level.SUCCESS, message));
        }

        @Override
        public void warn(String message) {
            lines.add(new Line(Level.WARN, message));
        }

        @Override
        public void error(String message) {
            lines.add(new Line(Level.ERROR, message));
        }
    }
}
