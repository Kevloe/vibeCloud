package de.kevloe.vibecloud.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.kevloe.vibecloud.api.plugin.CloudConnection;
import de.kevloe.vibecloud.protocol.CloudCommand;
import de.kevloe.vibecloud.protocol.CommandLine;
import de.kevloe.vibecloud.protocol.CommandRunResponse;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Cloud-Befehle im Spiel (PLAN.md Abschnitt 12).
 *
 * <p>Jeder Befehl, den der Master anbietet, wird hier am Proxy registriert. Velocity faengt
 * sie ab, bevor sie an den Gameserver gehen - deshalb funktionieren sie auf jedem Server,
 * ohne dass dort etwas installiert sein muss.
 *
 * <p><b>Hier wird nichts entschieden.</b> Die Zeile geht samt Spieler-UUID an den Master,
 * der prueft das Recht selbst neu und fuehrt aus. Das {@code hasPermission} unten verbirgt
 * den Befehl lediglich vor Spielern, die ihn nicht brauchen - es ersetzt die Pruefung
 * nicht (PLAN.md Abschnitt 13).
 *
 * <p>Absichtlich {@link RawCommand} und nicht {@code SimpleCommand}: Nur so ist zu sehen,
 * ob die Eingabe auf ein Leerzeichen endet. {@code /server start} und {@code /server start }
 * brauchen verschiedene Vorschlaege - einmal den Unterbefehl selbst, einmal die Gruppen
 * dahinter.
 */
final class CloudCommands {

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final CloudConnection connection;

    /**
     * Ab dieser Laenge wird eine Zeile im Chat umgebrochen.
     *
     * <p>Grob die Breite des Standard-Chatfensters; genau geht es nicht, weil die Schrift
     * nicht monospace ist.
     */
    private static final int CHAT_LINE = 90;

    /** Was gerade registriert ist - zum Abmelden, wenn ein Modul entladen wird. */
    private final Map<String, CloudCommand> registered = new LinkedHashMap<>();

    CloudCommands(ProxyServer proxy, Object plugin, Logger logger,
                  CloudConnection connection) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.connection = connection;
    }

    /**
     * Ersetzt die registrierten Befehle durch die genannten.
     *
     * <p>Immer die vollstaendige Liste, nicht einzelne Aenderungen: Nach dem Entladen eines
     * Moduls muss sein Befehl verschwinden, und eine verpasste Meldung darf nicht dazu
     * fuehren, dass ein Befehl dauerhaft falsch registriert bleibt.
     */
    void replaceAll(List<CloudCommand> commands) {
        CommandManager manager = proxy.getCommandManager();

        for (String name : List.copyOf(registered.keySet())) {
            if (commands.stream().noneMatch(command -> command.getName().equals(name))) {
                manager.unregister(name);
                registered.remove(name);
                logger.info("Befehl /{} entfernt", name);
            }
        }

        for (CloudCommand command : commands) {
            // Schon registriert: nur den Eintrag erneuern, Beschreibung oder Recht
            // koennen sich geaendert haben.
            if (!registered.containsKey(command.getName())) {
                register(manager, command);
            }
            registered.put(command.getName(), command);
        }

        logger.info("{} Cloud-Befehle im Spiel verfuegbar", registered.size());
    }

    /**
     * {@code /cloudhelp} - zeigt, was dieser Spieler darf.
     *
     * <p>Am Proxy und nicht im Master: Hier sind die Rechte des Spielers schon bekannt, die
     * Liste kann also ohne Rueckfrage gefiltert werden. Und sie heisst nicht {@code /help},
     * weil das das {@code /help} des Gameservers verdecken wuerde.
     */
    void registerHelp() {
        CommandManager manager = proxy.getCommandManager();
        CommandMeta meta = manager.metaBuilder("cloudhelp")
                .aliases("chelp")
                .plugin(plugin)
                .build();

        manager.register(meta, (RawCommand) invocation -> {
            CommandSource source = invocation.source();

            List<CloudCommand> allowed = registered.values().stream()
                    .filter(command -> mayUse(source, command))
                    .toList();

            if (allowed.isEmpty()) {
                source.sendMessage(Component.text(
                        "Du hast keine Cloud-Befehle.", NamedTextColor.GRAY));
                return;
            }
            source.sendMessage(Component.text("Cloud-Befehle:", NamedTextColor.AQUA));
            for (CloudCommand command : allowed) {
                // Nur Name und Beschreibung: Die vollstaendige Syntax von /rank oder
                // /perm ist eine Zeile mit neun Varianten - im Chat unlesbar. Sie
                // erscheint, wenn der Befehl ohne Argumente aufgerufen wird.
                source.sendMessage(Component.text("  /" + command.getName(),
                                NamedTextColor.WHITE)
                        .append(Component.text("  " + command.getDescription(),
                                NamedTextColor.GRAY)));
            }
            source.sendMessage(Component.text(
                    "Ein Befehl ohne Argumente zeigt seine Syntax.", NamedTextColor.DARK_GRAY));
        });
    }

    /** Meldet alles ab - beim Herunterfahren des Plugins. */
    void unregisterAll() {
        CommandManager manager = proxy.getCommandManager();
        registered.keySet().forEach(manager::unregister);
        registered.clear();
        manager.unregister("cloudhelp");
    }

    private void register(CommandManager manager, CloudCommand command) {
        String name = command.getName();
        CommandMeta meta = manager.metaBuilder(name).plugin(plugin).build();

        manager.register(meta, new RawCommand() {

            @Override
            public void execute(Invocation invocation) {
                run(name, invocation.source(), invocation.arguments());
            }

            @Override
            public boolean hasPermission(Invocation invocation) {
                return mayUse(invocation.source(), registered.getOrDefault(name, command));
            }

            @Override
            public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
                return suggestionsFor(name, invocation.source(), invocation.arguments());
            }
        });
        logger.info("Befehl /{} registriert ({})", name, command.getPermission());
    }

    /**
     * Ob der Befehl dem Spieler ueberhaupt angezeigt wird.
     *
     * <p>Grundrecht oder irgendein Unterbefehl: Wer nur {@code vibecloud.command.server.list}
     * hat, soll {@code /server} sehen. Was er damit darf, entscheidet der Master.
     */
    private boolean mayUse(CommandSource source, CloudCommand command) {
        if (source.hasPermission(command.getPermission())) {
            return true;
        }
        for (String sub : command.getSubCommandsList()) {
            if (source.hasPermission(command.getPermission() + "." + sub)) {
                return true;
            }
        }
        return false;
    }

    private void run(String command, CommandSource source, String raw) {
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text(
                    "Dieser Befehl ist fuer Spieler. In der Proxy-Konsole gibt es die "
                    + "Cloud-Befehle nicht - sie stehen in der Master-Konsole.",
                    NamedTextColor.YELLOW));
            return;
        }
        List<String> args = raw.isBlank() ? List.of() : Arrays.asList(raw.trim().split("\\s+"));

        // Der Aufruf zum Master blockiert; der Befehls-Thread von Velocity darf das nicht.
        CompletableFuture.runAsync(() -> {
            var answer = connection.runCommand(player.getUniqueId(), player.getUsername(),
                    command, args);

            if (answer.isEmpty()) {
                player.sendMessage(Component.text(
                        "Die Cloud antwortet nicht. Bitte spaeter erneut versuchen.",
                        NamedTextColor.RED));
                return;
            }
            send(player, answer.get());
        });
    }

    private void send(Player player, CommandRunResponse answer) {
        if (!answer.getAllowed()) {
            // Dieselbe Antwort fuer "kein Recht" und "gibt es nicht": Sonst waere die
            // Fehlermeldung eine Auskunft darueber, welche Befehle existieren.
            player.sendMessage(Component.text(
                    "Dafuer fehlt dir die Berechtigung.", NamedTextColor.RED));
            return;
        }
        if (answer.getLinesCount() == 0) {
            player.sendMessage(Component.text("Erledigt.", NamedTextColor.GREEN));
            return;
        }
        for (CommandLine line : answer.getLinesList()) {
            for (String part : wrap(line.getText())) {
                player.sendMessage(Component.text(part, colorOf(line)));
            }
        }
    }

    /**
     * Bricht eine zu lange Syntaxzeile in ihre Varianten um.
     *
     * <p>{@code rank list | info <rang> | create ...} ist in der Konsole eine Zeile und im
     * Chat ein Absatz ohne Struktur. Getrennt wird an " | ", also genau zwischen den
     * Varianten - ein Umbruch nach Zeichenzahl zerschnitte mitten im Platzhalter.
     */
    private static List<String> wrap(String text) {
        if (text.length() <= CHAT_LINE || !text.contains(" | ")) {
            return List.of(text);
        }
        String[] parts = text.split(java.util.regex.Pattern.quote(" | "));
        List<String> lines = new java.util.ArrayList<>(parts.length);
        lines.add(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            lines.add("  " + parts[i]);
        }
        return lines;
    }

    private static NamedTextColor colorOf(CommandLine line) {
        return switch (line.getLevel()) {
            case COMMAND_LEVEL_SUCCESS -> NamedTextColor.GREEN;
            case COMMAND_LEVEL_WARN -> NamedTextColor.YELLOW;
            case COMMAND_LEVEL_ERROR -> NamedTextColor.RED;
            default -> NamedTextColor.GRAY;
        };
    }

    /**
     * Vorschlaege vom Master.
     *
     * <p>Asynchron, weil es an einem Tastendruck haengt: Ein blockierender Aufruf im
     * Netzwerk-Thread wuerde den Proxy fuer alle anhalten.
     *
     * <p>Die Argumentliste endet immer mit dem angefangenen Text - bei einem Leerzeichen am
     * Ende ist das ein leerer Eintrag. Genau diese Form erwartet die Konsole auch, deshalb
     * koennen sich beide dieselbe {@code complete}-Methode teilen.
     */
    private CompletableFuture<List<String>> suggestionsFor(String command,
                                                           CommandSource source,
                                                           String raw) {
        if (!(source instanceof Player player)) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<String> args = Arrays.asList(raw.split(" ", -1));

        return CompletableFuture.supplyAsync(() ->
                connection.suggestCommand(player.getUniqueId(), command, args));
    }
}
