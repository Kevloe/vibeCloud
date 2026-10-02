package de.kevloe.vibecloud.master.http;

import com.google.gson.Gson;
import de.kevloe.vibecloud.common.VibeCloud;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.player.OnlinePlayers;
import de.kevloe.vibecloud.master.console.CommandRegistry;
import de.kevloe.vibecloud.master.console.ConsoleBuffer;
import de.kevloe.vibecloud.master.module.ModuleManager;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.node.NodeRepository;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import de.kevloe.vibecloud.master.player.PlayerService;
import de.kevloe.vibecloud.master.security.Jwt;
import de.kevloe.vibecloud.master.server.PlayerTransferService;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.server.ServerService;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Die REST-Schnittstelle (PLAN.md Abschnitt 12).
 *
 * <p>Diese Klasse startet nur den Server und haengt die Routengruppen ein. Die Endpunkte
 * selbst stehen in {@link AuthRoutes} (Anmeldung), {@link CloudRoutes} (Lesen),
 * {@link AdminRoutes} (Schreiben), {@link PermissionRoutes} (Rechte) und
 * {@link MessageRoutes} (Sprachen) - eine Klasse mit allem waere mit dem Dashboard schnell
 * unleserlich geworden.
 *
 * <p>Standardmaessig <b>aus</b> ({@code http.enabled} in der config.json). Ein offener Port
 * mit Schreibzugriff auf das Netzwerk soll eine Entscheidung sein, kein Nebeneffekt.
 */
public final class HttpApi implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpApi.class);
    private static final Gson GSON = new Gson();

    private final AuthRoutes authRoutes;
    private final CloudRoutes cloudRoutes;
    private final AdminRoutes adminRoutes;
    private final PermissionRoutes permissionRoutes;
    private final MessageRoutes messageRoutes;
    private final WsRoutes wsRoutes;

    private Javalin javalin;

    /**
     * Nimmt die Dienste und baut die Routengruppen selbst.
     *
     * <p>Die Gruppen bleiben damit paketprivat: Von aussen gibt es nur diese eine Klasse,
     * und niemand kann versehentlich einen Endpunkt ohne Rechtepruefung registrieren.
     *
     * @param secureCookies ob das Sitzungs-Cookie nur ueber HTTPS gesendet werden darf
     */
    public HttpApi(ApiTokenService tokens, AccountService accounts,
                   PermissionService permissions, Jwt jwt, boolean secureCookies,
                   ServerRegistry servers, ServerService serverService,
                   ServerGroupRepository groups, NodeRepository nodeRepository,
                   NodeRegistry nodes, PlayerService players, RankRepository ranks,
                   PlayerTransferService transfers, ModuleManager modules,
                   ConsoleBuffer console, OnlinePlayers online, AuditLog audit,
                   Path modulesDirectory,
                   de.kevloe.vibecloud.master.message.MessageDistributor messages,
                   CommandRegistry commands) {

        ApiAuth auth = new ApiAuth(tokens, accounts, permissions, jwt);
        WsTickets wsTickets = new WsTickets();

        this.authRoutes = new AuthRoutes(accounts, auth, jwt, wsTickets, secureCookies);
        this.cloudRoutes = new CloudRoutes(auth, servers, serverService, groups,
                nodeRepository, nodes, players, permissions, ranks, transfers, modules,
                online);
        this.adminRoutes = new AdminRoutes(auth, groups, servers, nodeRepository, ranks,
                permissions, modules, audit, modulesDirectory);
        this.permissionRoutes = new PermissionRoutes(auth, permissions, ranks, players,
                commands);
        this.messageRoutes = new MessageRoutes(auth, messages, audit);
        this.wsRoutes = new WsRoutes(wsTickets, permissions, servers, console);
    }

    /**
     * Startet den Server. Tut nichts, wenn die Schnittstelle abgeschaltet ist.
     *
     * @param dashboard Verzeichnis mit dem gebauten Dashboard; fehlt es, laeuft nur die
     *                  Schnittstelle
     */
    public void start(MasterConfig.Http config, Path dashboard) {
        if (!config.enabled) {
            LOG.info("REST-Schnittstelle ist aus (http.enabled in der config.json)");
            return;
        }

        // Javalin 7 nimmt die Routen in der Konfiguration, nicht mehr am fertigen Server.
        javalin = Javalin.create(settings -> {
            // Javalin erwartet von sich aus Jackson. Gson ist hier ohnehin ueberall im
            // Einsatz - eine zweite JSON-Bibliothek nur fuer die Schnittstelle waere
            // Ballast und eine weitere Quelle fuer Versionskonflikte.
            settings.jsonMapper(new JsonMapper() {

                @Override
                public String toJsonString(Object value, java.lang.reflect.Type type) {
                    return GSON.toJson(value, type);
                }

                @Override
                public <T> T fromJsonString(String json, java.lang.reflect.Type type) {
                    return GSON.fromJson(json, type);
                }
            });

            // Ohne Anmeldung: nur das Lebenszeichen. Es verraet nichts, was nicht schon
            // daran zu sehen waere, dass der Port offen ist.
            settings.routes.get("/api/v1/health", context -> context.json(Map.of(
                    "status", "ok",
                    "apiVersion", VibeCloud.API_VERSION)));

            authRoutes.register(settings.routes);
            cloudRoutes.register(settings.routes);
            adminRoutes.register(settings.routes);
            permissionRoutes.register(settings.routes);
            messageRoutes.register(settings.routes);
            wsRoutes.register(settings.routes);

            // Das gebaute Dashboard liegt als Dateien daneben - ein eigener Webserver
            // davor waere ein zweiter Dienst, den jemand pflegen muesste.
            if (Files.isDirectory(dashboard)) {
                // normalize(): Ohne das steht ein "." mitten im Pfad
                // (G:\...\master\.\dashboard), und Jetty findet die Dateien nicht -
                // jede Anfrage landet dann beim SPA-Fallback und liefert HTML statt
                // JavaScript aus.
                String directory = dashboard.toAbsolutePath().normalize().toString();

                settings.staticFiles.add(staticFiles -> {
                    staticFiles.directory = directory;
                    staticFiles.location = Location.EXTERNAL;
                });
                // Eine Oberflaeche mit mehreren Seiten: Jede Adresse, die keine Datei
                // und kein Endpunkt ist, bekommt die index.html - sonst waere ein
                // Neuladen auf einer Unterseite ein 404.
                settings.spaRoot.addFile("/", directory + java.io.File.separator
                                              + "index.html", Location.EXTERNAL);
                LOG.info("Dashboard wird aus {} ausgeliefert", directory);
            } else {
                LOG.info("Kein Dashboard in {} - nur die Schnittstelle laeuft. "
                         + "Bauen mit: cd dashboard && npm run build", dashboard);
            }
        });

        javalin.start(config.port);
        LOG.info("REST-Schnittstelle auf Port {} - Zugaenge mit 'acp create <spieler>', "
                 + "Tokens mit 'api token add'", config.port);
    }

    @Override
    public void close() {
        wsRoutes.close();
        if (javalin != null) {
            javalin.stop();
            LOG.info("REST-Schnittstelle beendet");
        }
    }
}
