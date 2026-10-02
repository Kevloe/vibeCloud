package de.kevloe.vibecloud.punishment;

import de.kevloe.vibecloud.api.Hashing;
import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.api.event.Subscribe;
import de.kevloe.vibecloud.api.event.TimeoutAction;
import de.kevloe.vibecloud.api.event.events.PlayerPreLoginEvent;
import de.kevloe.vibecloud.module.CloudModule;
import de.kevloe.vibecloud.module.ModuleCommands;
import de.kevloe.vibecloud.module.ModuleCommands.CommandSender;
import de.kevloe.vibecloud.module.ModuleContext;
import de.kevloe.vibecloud.module.ModulePlayers.PlayerInfo;
import de.kevloe.vibecloud.punishment.api.PlayerPardonedEvent;
import de.kevloe.vibecloud.punishment.api.PlayerPunishedEvent;
import de.kevloe.vibecloud.punishment.api.Punishment;
import de.kevloe.vibecloud.punishment.api.PunishmentAnswer;
import de.kevloe.vibecloud.punishment.api.PunishmentQuery;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Ban / Mute / Kick / Warn (PLAN.md Abschnitt 10).
 *
 * <p><b>Der Core kennt keine Bans.</b> Dieses Modul haengt sich an
 * {@link PlayerPreLoginEvent} und bricht ab, wenn eine Sperre vorliegt - das Login-Gate
 * weiss davon nichts und fragt nur. Ein spaeteres Whitelist- oder Laendersperr-Modul nutzt
 * dieselbe Stelle, ohne dass am Core etwas geaendert wird (PLAN.md Abschnitt 10a).
 */
public final class PunishmentModule implements CloudModule {

    /**
     * Gaengige Dauern fuer {@code tempban} und {@code tempmute}.
     *
     * <p>Von lang nach kurz, weil die laengeren oefter gebraucht werden.
     */
    private static final List<String> DURATIONS =
            List.of("30d", "14d", "7d", "3d", "24h", "12h", "2h", "30m");

    private ModuleContext context;
    private PunishmentRepository repository;
    private Settings settings;

    @Override
    public void onEnable(ModuleContext context) {
        this.context = context;
        this.settings = context.config().load(Settings.class, new Settings());

        context.database().migrate();
        context.loadMessages();

        this.repository = new PunishmentRepository(context.database());

        context.events().register(this);
        registerCommands();

        // Der Gameserver fragt hier nach, wenn jemand schreibt. Die Entscheidung faellt im
        // Master - der Gameserver hat die Strafen nicht und soll sie nicht haben.
        context.channel().respond("check", PunishmentQuery.class, this::answerQuery);

        context.permissions().declare("vibecloud.punishment.ban", "Spieler bannen");
        context.permissions().declare("vibecloud.punishment.banip", "IP-Bann aussprechen");
        context.permissions().declare("vibecloud.punishment.mute", "Spieler stummschalten");
        context.permissions().declare("vibecloud.punishment.kick", "Spieler kicken");
        context.permissions().declare("vibecloud.punishment.history", "Strafen einsehen");
        context.permissions().declare("vibecloud.punishment." + settings.announcePermission,
                "Strafmeldungen sehen");

        context.logger().info("Punishment aktiv (IP-Bans werden {})",
                settings.enforceIpBans ? "beim Login geprueft" : "ignoriert");
    }

    @Override
    public void onDisable() {
        if (context != null) {
            context.logger().info("Punishment entladen");
        }
    }

    // ---------------------------------------------------------------- Login-Gate

    /**
     * Prueft beim Login auf Sperren.
     *
     * <p>{@link TimeoutAction#DENY}: Haengt diese Pruefung, darf ein gebannter Spieler nicht
     * durchrutschen. Ein Modul, das nur eine Begruessung vorbereitet, wuerde hier
     * {@code ALLOW} nutzen (PLAN.md Abschnitt 10a).
     */
    @Subscribe(onTimeout = TimeoutAction.DENY)
    public void onPreLogin(PlayerPreLoginEvent event) {
        try {
            Optional<Punishment> ban = repository.findActive(event.uuid(), Punishment.Type.BAN);

            // Kein Bann auf dem Konto? Dann noch die IP pruefen - ein neues Konto vom
            // selben Anschluss ist genau der Fall, fuer den es IP-Bans gibt.
            if (ban.isEmpty() && settings.enforceIpBans) {
                ban = repository.findActiveByIp(Hashing.ipHash(event.ip()));
                ban.ifPresent(found -> context.logger().info(
                        "{} wird wegen eines IP-Banns abgewiesen (urspruenglich gegen {})",
                        event.name(), found.name()));
            }

            ban.ifPresent(found -> event.cancel("punishment.ban.screen", Map.of(
                    "grund", found.reason(),
                    "ablauf", formatExpiry(found),
                    "kennung", found.appealId())));

        } catch (SQLException exception) {
            // Entscheidend: Bei einem Datenbankfehler wird NICHT durchgelassen. Sonst waere
            // ein Ausfall der Datenbank ein Weg, jeden Bann zu umgehen.
            context.logger().error("Ban-Pruefung fuer {} fehlgeschlagen - Login abgelehnt",
                    event.name(), exception);
            event.cancel("error.internal");
        }
    }

    /** Beantwortet die Frage eines Gameservers, ob jemand stummgeschaltet ist. */
    private PunishmentAnswer answerQuery(PunishmentQuery query) {
        try {
            Punishment.Type type = Punishment.Type.valueOf(query.type());
            return repository.findActive(query.uuid(), type)
                    .map(found -> new PunishmentAnswer(true, found.reason(),
                            formatExpiry(found), found.appealId()))
                    .orElseGet(PunishmentAnswer::none);

        } catch (SQLException | IllegalArgumentException exception) {
            context.logger().error("Abfrage {} fehlgeschlagen", query, exception);
            // Hier umgekehrt als beim Login: Im Zweifel darf geschrieben werden. Ein
            // stummer Chat waere eine stille Strafe fuer alle, nicht nur fuer den Gemeinten.
            return PunishmentAnswer.none();
        }
    }

    // ---------------------------------------------------------------- Befehle

    private void registerCommands() {
        var commands = context.commands();

        commands.register("ban", "Spieler dauerhaft bannen",
                "ban <spieler> <grund...>", "vibecloud.punishment.ban",
                reasonCommand((sender, args) -> punish(sender, args, Punishment.Type.BAN, false)));

        commands.register("tempban", "Spieler auf Zeit bannen",
                "tempban <spieler> <dauer> <grund...>", "vibecloud.punishment.ban",
                timedCommand((sender, args) -> punishTimed(sender, args, Punishment.Type.BAN)));

        // Getrennt von "ban": Ein IP-Bann trifft auch Mitbewohner und Geschwister. Das soll
        // eine bewusste Entscheidung sein, kein Nebeneffekt jedes Banns.
        commands.register("banip", "Spieler samt letzter IP bannen",
                "banip <spieler> <grund...>", "vibecloud.punishment.banip",
                reasonCommand((sender, args) -> punish(sender, args, Punishment.Type.BAN, true)));

        commands.register("unban", "Bann aufheben",
                "unban <spieler>", "vibecloud.punishment.ban",
                playerCommand((sender, args) -> pardon(sender, args, Punishment.Type.BAN)));

        commands.register("mute", "Spieler dauerhaft stummschalten",
                "mute <spieler> <grund...>", "vibecloud.punishment.mute",
                reasonCommand((sender, args) -> punish(sender, args, Punishment.Type.MUTE, false)));

        commands.register("tempmute", "Spieler auf Zeit stummschalten",
                "tempmute <spieler> <dauer> <grund...>", "vibecloud.punishment.mute",
                timedCommand((sender, args) -> punishTimed(sender, args, Punishment.Type.MUTE)));

        commands.register("unmute", "Stummschaltung aufheben",
                "unmute <spieler>", "vibecloud.punishment.mute",
                playerCommand((sender, args) -> pardon(sender, args, Punishment.Type.MUTE)));

        commands.register("kick", "Spieler vom Netzwerk trennen",
                "kick <spieler> <grund...>", "vibecloud.punishment.kick",
                reasonCommand((sender, args) -> punish(sender, args, Punishment.Type.KICK, false)));

        commands.register("warn", "Spieler verwarnen",
                "warn <spieler> <grund...>", "vibecloud.punishment.kick",
                reasonCommand((sender, args) -> punish(sender, args, Punishment.Type.WARN, false)));

        commands.register("punishments", "Strafen eines Spielers zeigen",
                "punishments <spieler>", "vibecloud.punishment.history",
                playerCommand(this::showHistory));

        commands.register("appeal", "Strafe zu einer Einspruchs-Kennung suchen",
                "appeal <kennung>", "vibecloud.punishment.history",
                (ModuleCommands.ModuleCommand) this::showAppeal);
    }

    // ---------------------------------------------------------------- Vorschlaege

    /**
     * {@code <spieler>} - nur die erste Stelle.
     *
     * <p>Namen kommen aus der Datenbank, nicht aus der Liste der Online-Spieler: Gebannt
     * oder entbannt wird meist jemand, der gerade nicht da ist.
     */
    private ModuleCommands.ModuleCommand playerCommand(ModuleCommands.ModuleCommand action) {
        return new ModuleCommands.ModuleCommand() {

            @Override
            public void execute(CommandSender sender, List<String> args) {
                action.execute(sender, args);
            }

            @Override
            public List<String> complete(CommandSender sender, List<String> args) {
                return args.size() <= 1 ? names(args) : List.of();
            }
        };
    }

    /** {@code <spieler> <grund...>} - Spieler, dann die Begruendungs-Kuerzel. */
    private ModuleCommands.ModuleCommand reasonCommand(ModuleCommands.ModuleCommand action) {
        return new ModuleCommands.ModuleCommand() {

            @Override
            public void execute(CommandSender sender, List<String> args) {
                action.execute(sender, args);
            }

            @Override
            public List<String> complete(CommandSender sender, List<String> args) {
                return switch (args.size()) {
                    case 0, 1 -> names(args);
                    // Nur an der Stelle direkt nach dem Spieler: Danach schreibt der
                    // Moderator einen freien Text, und Vorschlaege wuerden stoeren.
                    case 2 -> reasons(args);
                    default -> List.of();
                };
            }
        };
    }

    /** {@code <spieler> <dauer> <grund...>}. */
    private ModuleCommands.ModuleCommand timedCommand(ModuleCommands.ModuleCommand action) {
        return new ModuleCommands.ModuleCommand() {

            @Override
            public void execute(CommandSender sender, List<String> args) {
                action.execute(sender, args);
            }

            @Override
            public List<String> complete(CommandSender sender, List<String> args) {
                return switch (args.size()) {
                    case 0, 1 -> names(args);
                    case 2 -> DURATIONS;
                    case 3 -> reasons(args);
                    default -> List.of();
                };
            }
        };
    }

    private List<String> names(List<String> args) {
        return context.players().suggestNames(args.isEmpty() ? "" : args.getLast(), 20);
    }

    /** Die Kuerzel aus der Konfiguration, alphabetisch. */
    private List<String> reasons(List<String> args) {
        return settings.reasonTemplates.keySet().stream().sorted().toList();
    }

    private void punish(CommandSender sender, List<String> args, Punishment.Type type,
                        boolean withIp) {
        if (args.size() < 2) {
            sender.replyRaw("Ein Grund ist Pflicht - er steht spaeter im Ban-Bildschirm.");
            return;
        }
        apply(sender, args.getFirst(), type, null,
                String.join(" ", args.subList(1, args.size())), withIp);
    }

    private void punishTimed(CommandSender sender, List<String> args, Punishment.Type type) {
        if (args.size() < 3) {
            sender.replyRaw("Syntax: <spieler> <dauer> <grund...>  (z. B. 30d, 12h, 90m)");
            return;
        }
        Duration duration = parseDuration(args.get(1));
        if (duration == null) {
            sender.reply("punishment.common.invalid_duration", Map.of("eingabe", args.get(1)));
            return;
        }
        apply(sender, args.getFirst(), type, duration,
                String.join(" ", args.subList(2, args.size())), false);
    }

    private void apply(CommandSender sender, String playerName, Punishment.Type type,
                       Duration duration, String rawReason, boolean withIp) {

        Optional<PlayerInfo> found = context.players().findByName(playerName);
        if (found.isEmpty()) {
            sender.reply("punishment.common.unknown_player", Map.of("spieler", playerName));
            return;
        }
        PlayerInfo player = found.get();

        // Kuerzel aus der Konfiguration aufloesen, damit im Ban-Bildschirm einheitliche
        // Gruende stehen und nicht zwanzig Schreibweisen desselben Vergehens.
        String reason = settings.reasonTemplates.getOrDefault(
                rawReason.toLowerCase(Locale.ROOT), rawReason);

        try {
            if (type.isEnforced() && repository.findActive(player.uuid(), type).isPresent()) {
                sender.reply(type == Punishment.Type.BAN
                                ? "punishment.ban.already" : "punishment.mute.already",
                        Map.of("spieler", player.name()));
                return;
            }

            String ipHash = withIp
                    ? context.players().lastIpHash(player.uuid()).orElse(null) : null;
            if (withIp && ipHash == null) {
                sender.replyRaw("Keine IP bekannt - der Bann gilt nur fuer das Konto.");
            }

            Instant expires = duration == null ? null : Instant.now().plus(duration);
            Punishment punishment = repository.create(type, player.uuid(), player.name(),
                    ipHash, reason, sender.name(), expires);

            // Andere Module hoeren mit: Ein Discord-Modul meldet den Bann, ohne dass dieses
            // Modul Discord kennen muss (PLAN.md Abschnitt 10a).
            context.events().post(new PlayerPunishedEvent(punishment));

            announce(punishment);
            sender.replyRaw("%s: %s %s - %s (Kennung %s)".formatted(
                    player.name(), type,
                    expires == null ? "dauerhaft" : "bis " + Times.format(expires),
                    reason, punishment.appealId()));

            // Ein Bann oder Kick wirkt sonst erst beim naechsten Login - der Spieler bliebe
            // bis zum naechsten Neustart online.
            switch (type) {
                case BAN, KICK -> disconnect(punishment);
                // Der Mute meldet sich beim naechsten Schreibversuch von selbst.
                case MUTE -> { }
                case WARN -> notifyPlayer(punishment, "punishment.warn.received");
            }

        } catch (SQLException exception) {
            context.logger().error("Strafe gegen {} konnte nicht angelegt werden",
                    player.name(), exception);
            sender.reply("error.internal");
        }
    }

    private void pardon(CommandSender sender, List<String> args, Punishment.Type type) {
        if (args.isEmpty()) {
            sender.replyRaw("Syntax: <spieler>");
            return;
        }
        Optional<PlayerInfo> found = context.players().findByName(args.getFirst());
        if (found.isEmpty()) {
            sender.reply("punishment.common.unknown_player",
                    Map.of("spieler", args.getFirst()));
            return;
        }
        PlayerInfo player = found.get();
        try {
            repository.revoke(player.uuid(), type, sender.name(), null).ifPresentOrElse(
                    revoked -> {
                        context.events().post(new PlayerPardonedEvent(revoked, sender.name()));
                        sender.reply(type == Punishment.Type.BAN
                                        ? "punishment.ban.lifted" : "punishment.mute.lifted",
                                Map.of("spieler", player.name()));
                    },
                    () -> sender.reply(type == Punishment.Type.BAN
                                    ? "punishment.ban.not_banned" : "punishment.mute.not_muted",
                            Map.of("spieler", player.name())));

        } catch (SQLException exception) {
            context.logger().error("Strafe gegen {} nicht aufhebbar", player.name(), exception);
            sender.reply("error.internal");
        }
    }

    private void showHistory(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sender.replyRaw("Syntax: punishments <spieler>");
            return;
        }
        Optional<PlayerInfo> found = context.players().findByName(args.getFirst());
        if (found.isEmpty()) {
            sender.reply("punishment.common.unknown_player",
                    Map.of("spieler", args.getFirst()));
            return;
        }
        PlayerInfo player = found.get();
        try {
            List<Punishment> history = repository.history(player.uuid(), 25);
            if (history.isEmpty()) {
                sender.replyRaw(player.name() + " hat keine Strafen.");
                return;
            }
            sender.replyRaw("Strafen von " + player.name() + ":");
            for (Punishment entry : history) {
                sender.replyRaw("  %s  %-5s  %-30s  von %-16s  [%s]  %s".formatted(
                        Times.format(entry.createdAt()), entry.type(), entry.reason(),
                        entry.actor(), entry.appealId(), state(entry)));
            }
        } catch (SQLException exception) {
            context.logger().error("Historie von {} nicht lesbar", player.name(), exception);
            sender.reply("error.internal");
        }
    }

    private void showAppeal(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sender.replyRaw("Syntax: appeal <kennung>");
            return;
        }
        try {
            repository.findByAppealId(args.getFirst()).ifPresentOrElse(
                    entry -> {
                        sender.replyRaw("Kennung " + entry.appealId());
                        sender.replyRaw("  Spieler   " + entry.name());
                        sender.replyRaw("  Art       " + entry.type());
                        sender.replyRaw("  Grund     " + entry.reason());
                        sender.replyRaw("  Von       " + entry.actor());
                        sender.replyRaw("  Am        " + Times.format(entry.createdAt()));
                        sender.replyRaw("  Laeuft ab " + formatExpiry(entry));
                        sender.replyRaw("  Zustand   " + state(entry));
                    },
                    () -> sender.replyRaw("Keine Strafe mit der Kennung "
                                          + args.getFirst().toUpperCase(Locale.ROOT)));

        } catch (SQLException exception) {
            context.logger().error("Kennung {} nicht lesbar", args.getFirst(), exception);
            sender.reply("error.internal");
        }
    }

    // ---------------------------------------------------------------- Wirkung im Netzwerk

    /**
     * Trennt den Spieler, falls er gerade online ist.
     *
     * <p>Ueber den Kick-Befehl des Masters, nicht ueber einen eigenen Kanal: Den Weg gibt
     * es schon, und die Proxys verstehen ihn ohne Zutun dieses Moduls.
     */
    private void disconnect(Punishment punishment) {
        context.players().kick(punishment.uuid(),
                punishment.type() == Punishment.Type.BAN
                        ? "punishment.ban.screen" : "punishment.kick.screen",
                placeholders(punishment));
    }

    /** Schickt dem Spieler eine Nachricht, falls er online ist. */
    private void notifyPlayer(Punishment punishment, String messageKey) {
        context.players().message(punishment.uuid(), messageKey, placeholders(punishment));
    }

    /** Informiert das Team ueber die Strafe. */
    private void announce(Punishment punishment) {
        if (!settings.announce) {
            return;
        }
        String key = switch (punishment.type()) {
            case BAN -> "punishment.ban.announced";
            case MUTE -> "punishment.mute.announced";
            case KICK -> "punishment.kick.announced";
            case WARN -> "punishment.warn.announced";
        };
        context.players().broadcast("vibecloud.punishment." + settings.announcePermission,
                key, placeholders(punishment));
    }

    private Map<String, String> placeholders(Punishment punishment) {
        Map<String, String> values = new HashMap<>();
        values.put("spieler", punishment.name());
        values.put("grund", punishment.reason());
        values.put("ablauf", formatExpiry(punishment));
        values.put("kennung", punishment.appealId());
        return values;
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private String formatExpiry(Punishment punishment) {
        return punishment.isPermanent() ? "nie" : Times.format(punishment.expiresAt());
    }

    private static String state(Punishment entry) {
        if (entry.isRevoked()) {
            return "aufgehoben";
        }
        return entry.isActive() ? "aktiv" : "abgelaufen";
    }

    /**
     * Versteht {@code 30d}, {@code 12h}, {@code 90m}, {@code 45s}.
     *
     * <p>Die Schreibweise steht in {@link Times}, nicht hier: {@code /tempban 7d} und
     * {@code perm player add ... 7d} muessen dasselbe bedeuten, und ein Unterschied waere
     * erst nach sieben Tagen zu sehen.
     */
    static Duration parseDuration(String text) {
        return Times.parseDuration(text);
    }

    /** Konfiguration unter {@code modules/punishment/config.json}. */
    public static final class Settings {

        /**
         * Beim Login auch die IP pruefen.
         *
         * <p>Betrifft nur das Durchsetzen: Ein mit {@code banip} gesetzter Hash bleibt
         * gespeichert und greift wieder, sobald das hier eingeschaltet wird.
         */
        public boolean enforceIpBans = true;

        /** Strafen im Netzwerk melden. */
        public boolean announce = true;

        /** Wer die Meldungen sieht - ergibt {@code vibecloud.punishment.<wert>}. */
        public String announcePermission = "notify";

        /**
         * Kuerzel fuer haeufige Gruende (PLAN.md Abschnitt 10, Begruendungs-Templates).
         *
         * <p>{@code ban Kevin hacking} schreibt den ausformulierten Text in die Strafe -
         * so stehen im Ban-Bildschirm einheitliche Gruende statt zwanzig Schreibweisen
         * desselben Vergehens.
         */
        public Map<String, String> reasonTemplates = Map.of(
                "hacking", "Unerlaubte Modifikationen",
                "cheating", "Unerlaubte Modifikationen",
                "spam", "Spam im Chat",
                "werbung", "Werbung fuer andere Server",
                "beleidigung", "Beleidigung anderer Spieler",
                "griefing", "Zerstoerung fremder Bauwerke",
                "bugusing", "Ausnutzen von Fehlern");
    }
}
