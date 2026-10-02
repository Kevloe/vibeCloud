package de.kevloe.vibecloud.master.console;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Befehle der Cloud (PLAN.md Abschnitt 9).
 *
 * <p>Absichtlich eine gemeinsame Abstraktion: Derselbe Befehl soll spaeter in der
 * Master-Konsole, im Spiel ueber den Proxy und als REST-Endpunkt verfuegbar sein, ohne
 * dass die Logik dreimal existiert.
 *
 * <p><b>Die Konsole hat implizit alle Rechte</b> - wer am Terminal sitzt, hat ohnehin Zugriff
 * auf Config und Datenbank. Eine Rechteprüfung dort waere Theater. Ab M4 prueft derselbe
 * Code bei Aufrufen aus dem Spiel die Permission des Spielers.
 */
public final class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(Command command) {
        commands.put(command.name().toLowerCase(java.util.Locale.ROOT), command);
    }

    /**
     * Entfernt einen Befehl - fuer das Entladen eines Moduls.
     *
     * <p>Ohne das wuerde ein Befehl nach einem Modul-Reload auf eine Klasse zeigen, deren
     * ClassLoader geschlossen ist.
     */
    public boolean unregister(String name) {
        return commands.remove(name.toLowerCase(java.util.Locale.ROOT)) != null;
    }

    public Optional<Command> find(String name) {
        return Optional.ofNullable(commands.get(name.toLowerCase(java.util.Locale.ROOT)));
    }

    public List<Command> all() {
        return List.copyOf(commands.values());
    }

    /**
     * Alle Rechte-Knoten, die es sinnvoll vorzuschlagen gibt.
     *
     * <p>Zweierlei: Was Module angemeldet haben (etwa {@code vibecloud.punishment.ban}) und
     * was sich aus den Befehlen selbst ergibt ({@code vibecloud.command.server.kill}).
     * Letzteres wird berechnet und nicht gepflegt - eine Liste von Hand waere nach dem
     * ersten neuen Unterbefehl falsch.
     *
     * <p>Hier und nicht in der Konsole, weil inzwischen zwei Oberflaechen davon leben: die
     * Tab-Vervollstaendigung von {@code perm} und der Rechte-Editor im Dashboard. Zwei
     * Listen waeren zwei Vorstellungen davon, welche Rechte es gibt - und die im Dashboard
     * waere die, die niemand pflegt.
     *
     * @param declared die von Modulen angemeldeten Knoten
     *                 ({@code PermissionService.declaredNodes()})
     */
    public List<String> permissionNodes(List<String> declared) {
        java.util.Set<String> nodes = new java.util.LinkedHashSet<>(declared);

        for (Command command : commands.values()) {
            if (!command.availableInGame()) {
                // Rechte fuer Befehle, die es im Spiel nicht gibt, braucht niemand.
                continue;
            }
            nodes.add(command.permission());
            for (String sub : command.subCommands()) {
                nodes.add(command.permission() + "." + sub);
            }
        }

        return nodes.stream().sorted().toList();
    }

    /**
     * Befehle, die mit {@code prefix} anfangen - alphabetisch.
     *
     * <p>Fuer die Tab-Vervollstaendigung und fuer die Vorschlaege bei einer unvollstaendigen
     * Eingabe. Module registrieren ihre Befehle spaeter als der Core; unsortiert stuenden
     * sie deshalb immer hinten, was beim Lesen stoert.
     *
     * <p>Findet der Praefix nichts, wird nach Befehlen gesucht, die den Text irgendwo
     * enthalten - so fuehrt auch "ban" noch zu "tempban" und "unban".
     */
    public List<String> completeNames(String prefix) {
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);

        List<String> starting = commands.keySet().stream()
                .filter(name -> name.startsWith(lower))
                .sorted()
                .toList();

        if (!starting.isEmpty() || lower.isEmpty()) {
            return starting;
        }
        return commands.keySet().stream()
                .filter(name -> name.contains(lower))
                .sorted()
                .toList();
    }

    /**
     * Das Recht fuer einen Aufruf - der Unterbefehl entscheidet, wenn es einen gibt.
     *
     * <p>{@code server list} und {@code server kill} sind verschiedene Dinge: Schauen darf
     * ein Moderator, eingreifen nicht. Deshalb {@code vibecloud.command.server.kill} statt
     * eines Rechts fuer den ganzen Befehl. {@code vibecloud.command.server.*} deckt alles
     * darunter ab, {@code vibecloud.*} ohnehin alles.
     *
     * <p>Ohne Argumente gilt das Recht des Befehls selbst - das ist der Aufruf, der nur die
     * Syntax zeigt.
     */
    public static String permissionFor(Command command, List<String> args) {
        if (args.isEmpty()) {
            return command.permission();
        }
        String first = args.getFirst().toLowerCase(java.util.Locale.ROOT);
        return command.subCommands().contains(first)
                ? command.permission() + "." + first
                : command.permission();
    }

    /**
     * Wer einen Befehl gegeben hat.
     *
     * <p>In der Konsole sitzt niemand mit einer UUID - dort ist {@link #uuid()} leer. Ein
     * Befehl, der sich auf den Aufrufer bezieht, muss das abfangen statt es anzunehmen.
     */
    public record CommandActor(java.util.UUID uuid, String name) {

        /** Die Konsole des Masters. */
        public static CommandActor console() {
            return new CommandActor(null, "CONSOLE");
        }

        public boolean isConsole() {
            return uuid == null;
        }
    }

    /** Ein Befehl. Die Ausgabe laeuft ueber {@link CommandOutput}, nie ueber System.out. */
    public interface Command {

        String name();

        String description();

        /** Kurze Syntaxangabe, z. B. {@code node add <name> [maxMemoryMb]}. */
        String usage();

        void execute(CommandOutput out, List<String> args);

        /**
         * Ausfuehrung mit Angabe, wer den Befehl gegeben hat.
         *
         * <p>Nur fuer Befehle, die das brauchen - {@code switch} bezieht sich auf den
         * Aufrufer selbst. Alle anderen bleiben bei {@link #execute(CommandOutput, List)}
         * und sehen den Aufrufer nicht; das ist die engere und damit richtige Schnittstelle.
         */
        default void execute(CommandActor actor, CommandOutput out, List<String> args) {
            execute(out, args);
        }

        /** Vorschlaege fuer das naechste Argument. */
        default List<String> complete(List<String> args) {
            return List.of();
        }

        /**
         * Grundrecht dieses Befehls.
         *
         * <p>Module liefern ihr eigenes (z. B. {@code vibecloud.punishment.ban}), die
         * Core-Befehle bleiben beim Schema {@code vibecloud.command.<name>}.
         */
        default String permission() {
            return "vibecloud.command." + name();
        }

        /**
         * Die Unterbefehle der ersten Ebene - fuer Rechte und Vervollstaendigung.
         *
         * <p>Leer heisst: Der Befehl hat keine, sein Grundrecht gilt fuer alles.
         */
        default List<String> subCommands() {
            return List.of();
        }

        /**
         * Ob der Befehl im Spiel verfuegbar ist.
         *
         * <p>{@code false} fuer {@code stop} (faehrt den Master herunter und sperrt damit
         * alle Logins aus), {@code module} (laedt Code zur Laufzeit) und {@code screen}
         * (haengt sich an einen Live-Log, im Chat nicht darstellbar). Diese drei bleiben
         * der Konsole vorbehalten - wer dort sitzt, hat ohnehin Zugriff auf Config und
         * Datenbank.
         */
        default boolean availableInGame() {
            return true;
        }
    }
}
