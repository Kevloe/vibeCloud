package de.kevloe.vibecloud.master.http;

import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.StaticBindingRepository;
import de.kevloe.vibecloud.master.sftp.SftpAccountService;
import de.kevloe.vibecloud.master.sftp.TemplateSftp;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Der eigene SFTP-Zugang im Dashboard: wohin man sich verbindet und das Passwort dazu.
 *
 * <p><b>Jeder sieht nur sich selbst.</b> Die Liste enthaelt die statischen Server, fuer die
 * der Angemeldete {@code vibecloud.sftp.<server>} hat, und die Gruppen, deren Template er
 * mit {@code vibecloud.sftp.template.<gruppe>} bearbeiten darf - dieselbe Pruefung, die der Master
 * bei der SFTP-Anmeldung macht ({@link SftpAccountService#mayAccess}). Ein eigenes Recht
 * fuer diese Seite gibt es deshalb nicht: Wer kein SFTP-Recht hat, sieht eine leere Liste.
 *
 * <p><b>Das Passwort erzeugt man sich selbst</b>, und es steht genau einmal in der Antwort.
 * Das ist kein Loch in der Regel "{@code sftp} nur in der Konsole": Dort ging es um den
 * Chat, in dem die Zeile stehen bleibt. Hier bekommt es nur der, dem es gehoert - und der
 * Zugang allein oeffnet nichts.
 *
 * <p>Fremde Zugaenge verwaltet weiter die Konsole. Ein API-Token hat keinen Spieler und
 * damit hier nichts zu tun.
 */
final class SftpRoutes {

    private final ApiAuth auth;
    private final SftpAccountService accounts;
    private final StaticBindingRepository bindings;
    private final NodeRegistry nodes;
    private final ServerGroupRepository groups;
    private final TemplateSftp templateSftp;

    SftpRoutes(ApiAuth auth, SftpAccountService accounts, StaticBindingRepository bindings,
               NodeRegistry nodes, ServerGroupRepository groups, TemplateSftp templateSftp) {
        this.auth = auth;
        this.accounts = accounts;
        this.bindings = bindings;
        this.nodes = nodes;
        this.groups = groups;
        this.templateSftp = templateSftp;
    }

    void register(RoutesConfig routes) {
        routes.get("/api/v1/sftp", this::overview)
                .post("/api/v1/sftp/password", this::newPassword);
    }

    /** Der eigene Zugang und die Server, in deren Verzeichnis er fuehrt. */
    private void overview(Context context) {
        Optional<ApiAuth.Principal> caller = user(context);
        if (caller.isEmpty()) {
            return;
        }
        ApiAuth.Principal user = caller.get();
        Optional<SftpAccountService.SftpAccount> account = accounts.find(user.uuid());

        // Der Name im SFTP-Benutzernamen ist der des Zugangs. Gibt es noch keinen, ist es
        // der, mit dem er gleich angelegt wuerde.
        String name = account.map(SftpAccountService.SftpAccount::username).orElse(user.name());

        List<Map<String, Object>> servers = new ArrayList<>();
        for (StaticBindingRepository.Binding binding : bindings.all()) {
            if (!accounts.mayAccess(user.uuid(), binding.serverName())) {
                continue;
            }
            boolean connected = nodes.isConnected(binding.node());
            int port = connected ? nodes.sftpPortOf(binding.node()) : 0;
            String username = name + "." + binding.serverName();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("server", binding.serverName());
            entry.put("group", binding.groupName());
            entry.put("node", binding.node());
            entry.put("nodeConnected", connected);
            // 0 heisst: Auf diesem Node ist SFTP aus.
            entry.put("port", port);
            entry.put("username", username);
            if (port > 0) {
                // Die Adresse des Nodes, nicht die des Masters: Der SFTP-Server laeuft dort,
                // wo die Dateien liegen.
                String host = nodes.addressOf(binding.node());
                entry.put("host", host);
                entry.put("hostKey", nodes.sftpHostKeyOf(binding.node()));
                // Ohne Passwort: Eine Adresse landet in Verlauf und Protokollen.
                entry.put("url", "sftp://" + encode(username) + "@" + bracket(host) + ":" + port + "/");
            }
            servers.add(entry);
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("username", name);
        answer.put("hasAccount", account.isPresent());
        answer.put("lastLogin", account.map(SftpAccountService.SftpAccount::lastLoginAt)
                .map(Times::format).orElse(""));
        answer.put("lastServer", account.map(SftpAccountService.SftpAccount::lastServer)
                .orElse(""));
        answer.put("servers", servers);
        answer.put("templates", templatesFor(user, name));
        // Templates liegen auf dem Master. Seine Adresse kennt die Oberflaeche besser als
        // er selbst - es ist die, unter der sie gerade geladen wurde.
        answer.put("templatePort", templateSftp.port());
        answer.put("templateHostKey", templateSftp.hostKey());
        context.json(answer);
    }

    /**
     * Die Gruppen, deren Template der Angemeldete bearbeiten darf.
     *
     * <p>Auch statische Gruppen: Ihr Template wird bei jedem Start ueber das
     * Serververzeichnis gelegt. Wer dort eine Datei dauerhaft aendern will, die das
     * Template mitbringt, muss sie hier aendern.
     */
    private List<Map<String, Object>> templatesFor(ApiAuth.Principal user, String name) {
        List<Map<String, Object>> templates = new ArrayList<>();
        for (ServerGroup group : groups.findAll()) {
            if (!accounts.mayEditTemplate(user.uuid(), group.name())
                || !templateSftp.isEditable(group.name())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("group", group.name());
            entry.put("template", group.template());
            entry.put("platform", group.platform().name());
            entry.put("static", group.staticGroup());
            entry.put("username", name + "." + group.name());
            templates.add(entry);
        }
        return templates;
    }

    /**
     * Ein neues Passwort fuer den eigenen Zugang - der dabei entsteht, falls es ihn noch
     * nicht gibt. Ein vorheriges Passwort gilt danach nicht mehr.
     */
    private void newPassword(Context context) {
        Optional<ApiAuth.Principal> caller = user(context);
        if (caller.isEmpty()) {
            return;
        }
        ApiAuth.Principal user = caller.get();
        SftpAccountService.NewPassword created;
        try {
            created = accounts.createOrReset(user.uuid(), user.name(), user.name());
        } catch (IllegalStateException exception) {
            ApiAuth.fail(context, HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
            return;
        }
        context.json(Map.of(
                "username", created.username(),
                "password", created.password(),
                "created", created.created(),
                "note", "Das Passwort wird nur jetzt angezeigt"));
    }

    // ---------------------------------------------------------------- Hilfsmittel

    /**
     * Der Angemeldete - als Mensch, mit eigenem Passwort.
     *
     * <p>{@link ApiAuth#authenticated} prueft bewusst kein Recht und laesst auch das
     * Start-Passwort durch (es ist fuer "Passwort setzen" gedacht). Beides wird deshalb
     * hier nachgeholt: Mit dem im Chat gezeigten Start-Passwort soll sich niemand einen
     * SFTP-Zugang erzeugen koennen.
     */
    private Optional<ApiAuth.Principal> user(Context context) {
        Optional<ApiAuth.Principal> principal = auth.authenticated(context);
        if (principal.isEmpty()) {
            return Optional.empty();
        }
        if (!principal.get().isUser()) {
            ApiAuth.fail(context, HttpStatus.FORBIDDEN,
                    "SFTP-Zugaenge gehoeren zu einem Spieler - ein API-Token hat keinen");
            return Optional.empty();
        }
        if (principal.get().mustChangePassword()) {
            ApiAuth.fail(context, HttpStatus.FORBIDDEN,
                    "Bitte zuerst ein eigenes Passwort setzen");
            return Optional.empty();
        }
        return principal;
    }

    /** Ein Bedrock-Name beginnt mit einem Punkt, und ein Name darf kein {@code @} sein. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Eine IPv6-Adresse steht in einer Adresse in eckigen Klammern. */
    private static String bracket(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
