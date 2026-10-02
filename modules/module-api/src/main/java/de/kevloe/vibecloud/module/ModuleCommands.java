package de.kevloe.vibecloud.module;

import java.util.List;

/**
 * Befehle eines Moduls (PLAN.md Abschnitt 10).
 *
 * <p>Ein registrierter Befehl steht in der Master-Konsole, im Spiel ueber den Proxy und
 * als REST-Endpunkt zur Verfuegung - die Logik existiert nur einmal.
 *
 * <p>Alle Befehle eines Moduls werden beim Entladen automatisch abgemeldet.
 */
public interface ModuleCommands {

    /**
     * @param name       ohne Schraegstrich, z. B. {@code ban}
     * @param permission benoetigtes Recht; {@code null} = nur Konsole
     */
    void register(String name, String description, String usage, String permission,
                  ModuleCommand command);

    /** Ein Befehl. */
    interface ModuleCommand {

        /**
         * @param sender wer den Befehl ausloest
         * @param args   Argumente ohne den Befehlsnamen
         */
        void execute(CommandSender sender, List<String> args);

        /** Vorschlaege fuer das naechste Argument. */
        default List<String> complete(CommandSender sender, List<String> args) {
            return List.of();
        }
    }

    /**
     * Wer einen Befehl ausloest.
     *
     * <p>Die Rechtepruefung macht <b>der Master</b>: {@link #hasPermission} fragt dort nach.
     * Ein Modul bekommt nie ein "darf das"-Flag uebergeben (PLAN.md Abschnitt 13).
     */
    interface CommandSender {

        /** {@code CONSOLE} oder der Spielername. */
        String name();

        /** {@code null} bei der Konsole. */
        java.util.UUID uuid();

        boolean isConsole();

        boolean hasPermission(String node);

        /** Antwort als Nachrichten-Schluessel, nicht als fertiger Text. */
        void reply(String messageKey, java.util.Map<String, String> placeholders);

        default void reply(String messageKey) {
            reply(messageKey, java.util.Map.of());
        }

        /** Nur fuer die Konsole: Rohtext ohne Uebersetzung. */
        void replyRaw(String text);
    }
}
