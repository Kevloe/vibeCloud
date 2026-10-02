package de.kevloe.vibecloud.example;

import de.kevloe.vibecloud.api.event.Subscribe;
import de.kevloe.vibecloud.api.event.events.PlayerJoinNetworkEvent;
import de.kevloe.vibecloud.example.api.ExampleGreetedEvent;
import de.kevloe.vibecloud.module.CloudModule;
import de.kevloe.vibecloud.module.ModuleCommands;
import de.kevloe.vibecloud.module.ModuleContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Beispielmodul (PLAN.md Abschnitt 10).
 *
 * <p>Absichtlich klein, benutzt aber jeden Teil der Modul-API genau einmal - damit ein
 * Fehler in der API hier auffaellt und nicht erst beim ersten echten Modul:
 *
 * <ul>
 *   <li>eigene Datenbanktabelle mit eigener Flyway-Historie</li>
 *   <li>Event abonnieren ({@link PlayerJoinNetworkEvent})</li>
 *   <li>eigenes Event werfen ({@link ExampleGreetedEvent}) aus einem exportierten Paket</li>
 *   <li>Befehl registrieren</li>
 *   <li>Kanal-Handler fuer Anfragen der eigenen Plugin-Teile</li>
 *   <li>eigene Konfiguration</li>
 * </ul>
 */
public final class ExampleModule implements CloudModule {

    private ModuleContext context;
    private Settings settings;

    @Override
    public void onLoad(ModuleContext context) {
        // Hier noch nichts registrieren - andere Module sind moeglicherweise nicht aktiv.
        this.context = context;
    }

    @Override
    public void onEnable(ModuleContext context) {
        this.context = context;
        settings = context.config().load(Settings.class, new Settings());

        int migrations = context.database().migrate();
        context.logger().info("Beispielmodul aktiv ({} Migration(en), Begruessung: {})",
                migrations, settings.greetOnJoin);

        context.events().register(this);
        context.commands().register("example", "Zeigt die Begruessungszahl",
                "example [spieler]", "vibecloud.example.use", new ExampleCommand());

        // Kanal: Die Plugin-Teile dieses Moduls koennen die Zahl abfragen.
        context.channel().respond("greetings", GreetingsRequest.class,
                request -> new GreetingsResponse(greetingsOf(request.uuid())));
    }

    @Override
    public void onDisable() {
        // Events und Befehle meldet der ModuleManager ab. Hier bleibt nichts zu tun -
        // das Modul haelt keine eigenen Threads und keine offenen Dateien.
        if (context != null) {
            context.logger().info("Beispielmodul entladen");
        }
    }

    /**
     * Zaehlt Begruessungen und wirft ein eigenes Event.
     *
     * <p>Das Event liegt im exportierten Paket, damit andere Module darauf reagieren
     * koennen - genau der Fall aus PLAN.md Abschnitt 10a.
     */
    @Subscribe
    public void onJoin(PlayerJoinNetworkEvent event) {
        if (!settings.greetOnJoin) {
            return;
        }
        long count = recordGreeting(event.uuid(), event.name());
        context.events().post(new ExampleGreetedEvent(event.uuid(), event.name(), count));
        context.logger().info("{} zum {}. Mal begruesst", event.name(), count);
    }

    /** Der Befehl dieses Moduls - in Konsole, Spiel und REST derselbe Code. */
    private final class ExampleCommand implements ModuleCommands.ModuleCommand {

        @Override
        public void execute(ModuleCommands.CommandSender sender, List<String> args) {
            if (args.isEmpty()) {
                sender.replyRaw("Insgesamt begruesst: " + totalGreetings());
                return;
            }
            context.players().findByName(args.getFirst()).ifPresentOrElse(
                    player -> sender.replyRaw(player.name() + " wurde "
                                              + greetingsOf(player.uuid()) + " mal begruesst"),
                    () -> sender.replyRaw("Unbekannter Spieler: " + args.getFirst()));
        }
    }

    // ---------------------------------------------------------------- Datenbank

    private long recordGreeting(UUID uuid, String name) {
        String sql = "INSERT INTO example_greetings (uuid, name, count) VALUES (?, ?, 1) "
                     + "ON CONFLICT (uuid) DO UPDATE "
                     + "SET count = example_greetings.count + 1, name = EXCLUDED.name "
                     + "RETURNING count";
        try (Connection connection = context.database().connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uuid);
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            context.logger().error("Begruessung konnte nicht gezaehlt werden", exception);
            return 0;
        }
    }

    private long greetingsOf(UUID uuid) {
        try (Connection connection = context.database().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count FROM example_greetings WHERE uuid = ?")) {
            statement.setObject(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            return 0;
        }
    }

    private long totalGreetings() {
        try (Connection connection = context.database().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COALESCE(SUM(count), 0) FROM example_greetings");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0;
        } catch (SQLException exception) {
            return 0;
        }
    }

    /** Konfiguration unter {@code modules/example/config.json}. */
    public static final class Settings {
        public boolean greetOnJoin = true;
    }

    /** Nutzlast einer Kanal-Anfrage. */
    public record GreetingsRequest(UUID uuid) {
    }

    public record GreetingsResponse(long count) {
    }
}
