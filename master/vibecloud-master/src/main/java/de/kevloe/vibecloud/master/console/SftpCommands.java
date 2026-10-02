package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.player.PlayerRepository;
import de.kevloe.vibecloud.master.player.PlayerService;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.StaticBindingRepository;
import de.kevloe.vibecloud.master.sftp.SftpAccountService;
import de.kevloe.vibecloud.master.sftp.TemplateSftp;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * SFTP-Zugaenge zu den Verzeichnissen statischer Server verwalten.
 *
 * <p>Nur in der Konsole, wie {@code api token}: Das Passwort wird genau einmal angezeigt,
 * und anders als beim Dashboard gibt es danach keinen erzwungenen Wechsel - was im Chat
 * stuende, gaelte fuer immer.
 *
 * <p>Ein Zugang allein oeffnet nichts. In welches Verzeichnis jemand darf, sagt das Recht
 * {@code vibecloud.sftp.<server>} fuer einen statischen Server und
 * {@code vibecloud.sftp.template.<gruppe>} fuer ein Template - vergeben mit {@code perm},
 * wie jedes andere.
 */
public final class SftpCommands implements CommandRegistry.Command {

    private final SftpAccountService accounts;
    private final PlayerService players;
    private final StaticBindingRepository bindings;
    private final NodeRegistry nodes;
    private final ServerGroupRepository groups;
    private final Supplier<TemplateSftp> templateSftp;

    /**
     * @param templateSftp als Lieferant, weil der SFTP-Zugang des Masters erst nach der
     *                     Konsole entsteht
     */
    public SftpCommands(SftpAccountService accounts, PlayerService players,
                        StaticBindingRepository bindings, NodeRegistry nodes,
                        ServerGroupRepository groups, Supplier<TemplateSftp> templateSftp) {
        this.accounts = accounts;
        this.players = players;
        this.bindings = bindings;
        this.nodes = nodes;
        this.groups = groups;
        this.templateSftp = templateSftp;
    }

    @Override
    public String name() {
        return "sftp";
    }

    @Override
    public String description() {
        return "SFTP-Zugaenge zu statischen Servern verwalten";
    }

    @Override
    public String usage() {
        return "sftp create <spieler> | password <spieler> | remove <spieler> | list | servers";
    }

    @Override
    public List<String> subCommands() {
        return List.of("create", "password", "remove", "list", "servers");
    }

    /** Nur Konsole: Das Passwort wird einmal angezeigt und darf nicht im Chat landen. */
    @Override
    public boolean availableInGame() {
        return false;
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return subCommands();
        }
        if (args.size() != 2) {
            return List.of();
        }
        return switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "create" -> players.suggestNames(args.getLast(), 20);
            case "password", "remove" -> accounts.all().stream()
                    .map(SftpAccountService.SftpAccount::username).toList();
            default -> List.of();
        };
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "create" -> withPlayer(out, args, this::create);
            case "password" -> withPlayer(out, args, this::password);
            case "remove" -> withPlayer(out, args, this::remove);
            case "list" -> list(out);
            case "servers" -> servers(out);
            default -> out.error("Unbekannt. Syntax: " + usage());
        }
    }

    private interface PlayerAction {
        void run(CommandOutput out, PlayerRepository.PlayerRecord player);
    }

    private void withPlayer(CommandOutput out, List<String> args, PlayerAction action) {
        if (args.size() < 2) {
            out.error("Syntax: sftp " + args.getFirst() + " <spieler>");
            return;
        }
        Optional<PlayerRepository.PlayerRecord> player = players.findByName(args.get(1));
        if (player.isEmpty()) {
            out.error(args.get(1) + " ist unbekannt - der Spieler muss einmal verbunden "
                      + "gewesen sein.");
            return;
        }
        action.run(out, player.get());
    }

    private void create(CommandOutput out, PlayerRepository.PlayerRecord player) {
        String password;
        try {
            password = accounts.create(player.uuid(), player.name(), "CONSOLE");
        } catch (IllegalStateException exception) {
            out.error(exception.getMessage());
            out.info("Ein neues Passwort gibt es mit: sftp password " + player.name());
            return;
        }
        out.success("SFTP-Zugang fuer " + player.name() + " angelegt.");
        showPassword(out, password);
        out.info("");
        out.info("Der Zugang allein oeffnet nichts. Je Ziel braucht es ein Recht:");
        out.info("  perm player add " + player.name() + " "
                 + SftpAccountService.PERMISSION_PREFIX + "<server>           (statischer Server)");
        out.info("  perm player add " + player.name() + " "
                 + SftpAccountService.TEMPLATE_PERMISSION_PREFIX + "<gruppe>  (Template)");
        out.warn("Wer Dateien hochladen darf, kann Plugins hochladen - also Code auf dem "
                 + "Node ausfuehren. Das Recht ist eines fuer Administratoren.");
        out.info("Wohin verbunden wird, zeigt: sftp servers");
    }

    private void password(CommandOutput out, PlayerRepository.PlayerRecord player) {
        Optional<String> password = accounts.resetPassword(player.uuid(), "CONSOLE");
        if (password.isEmpty()) {
            out.error(player.name() + " hat keinen SFTP-Zugang. Anlegen mit: sftp create "
                      + player.name());
            return;
        }
        out.success("Neues Passwort fuer " + player.name() + " - das alte gilt nicht mehr.");
        showPassword(out, password.get());
        out.info("Bestehende Verbindungen bleiben offen, bis sie getrennt werden.");
    }

    private static void showPassword(CommandOutput out, String password) {
        out.info("");
        out.info(password);
        out.info("");
        out.warn("Das Passwort wird nur jetzt angezeigt.");
    }

    private void remove(CommandOutput out, PlayerRepository.PlayerRecord player) {
        if (accounts.remove(player.uuid(), "CONSOLE")) {
            out.success("SFTP-Zugang von " + player.name() + " entfernt.");
            out.info("Bestehende Verbindungen bleiben offen, bis sie getrennt werden.");
        } else {
            out.error(player.name() + " hat keinen SFTP-Zugang.");
        }
    }

    private void list(CommandOutput out) {
        List<SftpAccountService.SftpAccount> all = accounts.all();
        if (all.isEmpty()) {
            out.info("Keine SFTP-Zugaenge. Anlegen mit: sftp create <spieler>");
            return;
        }
        out.info(String.format("%-18s %-18s %s", "ZUGANG", "ZULETZT", "AUF"));
        for (SftpAccountService.SftpAccount account : all) {
            out.info(String.format("%-18s %-18s %s",
                    account.username(),
                    account.lastLoginAt() == null ? "nie" : Times.format(account.lastLoginAt()),
                    account.lastServer() == null ? "-" : account.lastServer()));
        }
    }

    /**
     * Welche Server es gibt und wohin man sich verbindet.
     *
     * <p>Die Adresse ist die des Nodes, nicht die des Masters: Der SFTP-Server laeuft
     * dort, wo die Dateien liegen.
     */
    private void servers(CommandOutput out) {
        List<StaticBindingRepository.Binding> all = bindings.all();
        if (all.isEmpty()) {
            out.info("Es gibt noch keinen statischen Server. Das Verzeichnis eines "
                     + "dynamischen ist nach dem Stopp weg - dafuer gibt es das Template.");
        } else {
            out.info(String.format("%-20s %-12s %s", "SERVER", "NODE", "VERBINDUNG"));
            for (StaticBindingRepository.Binding binding : all) {
                out.info(String.format("%-20s %-12s %s", binding.serverName(), binding.node(),
                        connectionOf(binding)));
            }
            out.info("Benutzername ist <zugang>." + "<server>, das Recht dazu "
                     + SftpAccountService.PERMISSION_PREFIX + "<server>.");
        }
        out.info("");
        templates(out);
    }

    /**
     * Die Templates der Gruppen - sie liegen auf dem Master, also verbindet man sich mit
     * ihm und nicht mit einem Node.
     */
    private void templates(CommandOutput out) {
        TemplateSftp sftp = templateSftp.get();
        int port = sftp == null ? 0 : sftp.port();
        if (port == 0) {
            out.info("Templates: SFTP ist am Master aus (sftp.enabled in der config.json).");
            return;
        }
        out.info(String.format("%-20s %s", "TEMPLATE VON", "VERBINDUNG"));
        for (ServerGroup group : groups.findAll()) {
            out.info(String.format("%-20s %s", group.name(), sftp.isEditable(group.name())
                    ? "sftp://<zugang>." + group.name() + "@<adresse-des-masters>:" + port
                    : "Template '" + group.template() + "' ist ueber SFTP nicht erreichbar"));
        }
        out.info("Benutzername ist <zugang>." + "<gruppe>, das Recht dazu "
                 + SftpAccountService.TEMPLATE_PERMISSION_PREFIX + "<gruppe>.");
        out.info("Host-Schluessel des Masters: " + sftp.hostKey());
    }

    private String connectionOf(StaticBindingRepository.Binding binding) {
        if (!nodes.isConnected(binding.node())) {
            return "Node nicht verbunden";
        }
        int port = nodes.sftpPortOf(binding.node());
        if (port == 0) {
            return "SFTP ist auf diesem Node aus (sftp.enabled in der wrapper.json)";
        }
        return "sftp://<zugang>." + binding.serverName() + "@"
               + nodes.addressOf(binding.node()) + ":" + port;
    }
}
