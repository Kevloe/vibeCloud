package de.kevloe.vibecloud.master.grpc;

import com.google.protobuf.ByteString;
import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.identity.CallIdentity;
import de.kevloe.vibecloud.api.identity.NodeIdentity;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.common.VibeCloud;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.console.ConsoleBuffer;
import de.kevloe.vibecloud.master.event.EventCursorStore;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.scheduler.PlacementScheduler;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.server.ServerHistoryRepository;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.sftp.SftpAccountService;
import de.kevloe.vibecloud.master.template.LogArchive;
import de.kevloe.vibecloud.master.template.TemplateStore;
import de.kevloe.vibecloud.protocol.Ack;
import de.kevloe.vibecloud.protocol.AckEvents;
import de.kevloe.vibecloud.protocol.BlobChunk;
import de.kevloe.vibecloud.protocol.BlobRequest;
import de.kevloe.vibecloud.protocol.ConsoleLine;
import de.kevloe.vibecloud.protocol.LogChunk;
import de.kevloe.vibecloud.protocol.ManifestRequest;
import de.kevloe.vibecloud.protocol.NodeCommand;
import de.kevloe.vibecloud.protocol.NodeEvent;
import de.kevloe.vibecloud.protocol.NodeServiceGrpc;
import de.kevloe.vibecloud.protocol.RegisterRequest;
import de.kevloe.vibecloud.protocol.RegisterResponse;
import de.kevloe.vibecloud.protocol.RunningServer;
import de.kevloe.vibecloud.protocol.ServerStateChanged;
import de.kevloe.vibecloud.protocol.SftpAuthRequest;
import de.kevloe.vibecloud.protocol.SftpAuthResponse;
import de.kevloe.vibecloud.protocol.TemplateManifest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Master-Seite der Wrapper-Verbindung (PLAN.md Abschnitt 5 und 7).
 *
 * <p>Die Anmeldung hat der {@link AuthInterceptor} schon erledigt; hier geht es nur noch um
 * <b>Befugnis</b>: Jede eingehende Nachricht wird gegen die Verbindungsidentitaet geprueft.
 * Ein Node darf nur ueber seine eigenen Server sprechen.
 */
public final class NodeServiceImpl extends NodeServiceGrpc.NodeServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(NodeServiceImpl.class);
    private static final int BLOB_CHUNK_BYTES = 256 * 1024;

    private final NodeRegistry nodes;
    private final EventCursorStore cursors;
    private final AuditLog audit;
    private final ServerRegistry servers;
    private final ServerGroupRepository groups;
    private final ServerHistoryRepository history;
    private final TemplateStore templates;
    private final ConsoleBuffer console;
    private final LogArchive logs;
    private final PlacementScheduler scheduler;
    private final int heartbeatIntervalSeconds;
    private final PluginConnectionRegistry plugins;
    private final PluginServiceImpl pluginService;
    private final ServerSessionStore sessions;
    private final SftpAccountService sftp;

    public NodeServiceImpl(NodeRegistry nodes, EventCursorStore cursors, AuditLog audit,
                           ServerRegistry servers, ServerGroupRepository groups,
                           ServerHistoryRepository history, TemplateStore templates,
                           ConsoleBuffer console, LogArchive logs,
                           PlacementScheduler scheduler, int heartbeatIntervalSeconds,
                           PluginConnectionRegistry plugins, PluginServiceImpl pluginService,
                           ServerSessionStore sessions, SftpAccountService sftp) {
        this.sftp = sftp;
        this.nodes = nodes;
        this.cursors = cursors;
        this.audit = audit;
        this.servers = servers;
        this.groups = groups;
        this.history = history;
        this.templates = templates;
        this.console = console;
        this.logs = logs;
        this.scheduler = scheduler;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.plugins = plugins;
        this.pluginService = pluginService;
        this.sessions = sessions;
    }

    // ---------------------------------------------------------------- Anmeldung

    @Override
    public void register(RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        String node = requireNode().node();

        if (request.getApiVersion() < VibeCloud.MIN_SUPPORTED_API_VERSION
            || request.getApiVersion() > VibeCloud.API_VERSION) {
            // Klartext statt Raten: Ein Wrapper mit unpassender Version soll wissen,
            // woran es liegt (PLAN.md Abschnitt 5, Update-Reihenfolge).
            String message = "Protokoll-Version %d wird nicht unterstuetzt (Master: %d, mindestens %d). "
                             .formatted(request.getApiVersion(), VibeCloud.API_VERSION,
                                     VibeCloud.MIN_SUPPORTED_API_VERSION)
                             + "Update-Reihenfolge ist immer: erst Master, dann Wrapper.";
            LOG.error("Node {} abgewiesen: {}", node, message);
            responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(message)
                    .asRuntimeException());
            return;
        }

        // Uhrabweichung des Wrappers merken, damit nachgespielte Ereignisse mit der
        // richtigen Zeit in der Datenbank landen.
        long skew = request.hasWrapperTime()
                ? Duration.between(Protos.fromProto(request.getWrapperTime()), Instant.now()).toMillis()
                : 0L;
        if (Math.abs(skew) > 5_000) {
            LOG.warn("Uhr von Node {} weicht um {} ms ab - Zeitstempel werden korrigiert",
                    node, skew);
        }

        long cursor = cursors.lastSeq(originOf(node));
        if (request.getHighestOutboxSeq() < cursor) {
            LOG.warn("Node {} meldet seq {} obwohl der Master schon bei {} steht - "
                     + "wurde das Outbox-Verzeichnis geleert?",
                    node, request.getHighestOutboxSeq(), cursor);
        }

        // Adresse des Nodes: Angabe des Wrappers, sonst die Quell-IP der Verbindung.
        // Der Node-NAME ist hier ausdruecklich keine Option - ein Proxy kann ihn nicht
        // auflösen und wuerde die Gameserver nicht erreichen.
        String observedIp = AuthInterceptor.REMOTE_IP.get();
        String serverAddress = !request.getServerAddress().isBlank()
                ? request.getServerAddress()
                : observedIp == null || observedIp.equals("unbekannt") ? "" : observedIp;

        if (serverAddress.isBlank()) {
            LOG.error("Fuer Node {} ist keine erreichbare Adresse bekannt. Die Proxys koennen "
                      + "seine Gameserver nicht finden. Abhilfe: serverAddress in der "
                      + "wrapper.json setzen.", node);
        } else {
            LOG.info("Gameserver von {} sind unter {} erreichbar", node, serverAddress);
        }

        nodes.attach(node, new NodeRegistry.RegisterInfo(
                request.getMaxMemoryMb(),
                request.getCpuCores(),
                request.getOsName(),
                request.getWrapperVersion(),
                skew,
                serverAddress,
                request.getSftpPort(),
                request.getSftpHostKey(),
                request.getJavaVersionsList()));
        if (!request.getJavaVersionsList().isEmpty()) {
            LOG.info("{} hat Java {}", node, request.getJavaVersionsList());
        }

        audit.record("SYSTEM", "node.registered", node,
                Map.of("apiVersion", request.getApiVersion(),
                        "wrapperVersion", request.getWrapperVersion()));

        responseObserver.onNext(RegisterResponse.newBuilder()
                .setApiVersion(VibeCloud.API_VERSION)
                .setReplayFromSeq(cursor)
                .setClockSkewMillis(skew)
                .setHeartbeatIntervalSeconds(heartbeatIntervalSeconds)
                .build());
        responseObserver.onCompleted();
    }

    // ---------------------------------------------------------------- Control-Stream

    @Override
    public StreamObserver<NodeEvent> control(StreamObserver<NodeCommand> toWrapper) {
        String node = requireNode().node();
        nodes.attachChannel(node, toWrapper);

        return new StreamObserver<>() {

            @Override
            public void onNext(NodeEvent event) {
                try {
                    handle(node, event, toWrapper);
                } catch (RuntimeException exception) {
                    // Ein fehlerhaftes Ereignis darf den Stream nicht abreissen lassen -
                    // sonst wuerde ein einzelner Fehler den ganzen Node abhaengen.
                    LOG.error("Ereignis von {} konnte nicht verarbeitet werden", node, exception);
                }
            }

            @Override
            public void onError(Throwable error) {
                detach(node, "Stream-Fehler: " + error.getMessage());
            }

            @Override
            public void onCompleted() {
                detach(node, "regulaer beendet");
                toWrapper.onCompleted();
            }
        };
    }

    /**
     * Ein Node ist weg. Seine Server verschwinden aus der Registry - aber statische
     * Server behalten ihre Bindung und warten auf den Node, sie werden NICHT woanders
     * gestartet (PLAN.md Abschnitt 6).
     */
    private void detach(String node, String reason) {
        List<CloudServer> affected = servers.dropNode(node);
        nodes.detach(node, reason);
        if (!affected.isEmpty()) {
            LOG.info("{} Server von Node {} gelten als weg: {}", affected.size(), node,
                    affected.stream().map(CloudServer::name).toList());
            affected.forEach(server -> console.forget(server.name()));
        }
    }

    private void handle(String node, NodeEvent event, StreamObserver<NodeCommand> toWrapper) {
        switch (event.getEventCase()) {
            case HEARTBEAT -> nodes.heartbeat(node);

            case RESOURCE_REPORT -> nodes.connection(node).ifPresent(connection ->
                    connection.reportCpuLoad(event.getResourceReport().getHost().getCpuLoad()));

            case FULL_STATE -> adoptServers(node, event);

            case SERVER_STATE_CHANGED -> {
                if (cursors.isDuplicate(originOf(node), event.getSeq())) {
                    // Doppelter Replay ist harmlos - genau dafuer gibt es den Cursor.
                    LOG.debug("Ereignis seq {} von {} bereits verarbeitet", event.getSeq(), node);
                    break;
                }
                applyServerState(node, event.getServerStateChanged());
            }

            case REPLAY_FINISHED -> {
                long lastSeq = event.getReplayFinished().getLastSeq();
                cursors.advanceTo(originOf(node), lastSeq);
                LOG.info("Replay von {} abgeschlossen bis seq {}", node, lastSeq);
                acknowledge(toWrapper, lastSeq);
                // Erst jetzt darf der Scheduler Entscheidungen treffen.
                scheduler.arm();
            }

            case EVENT_NOT_SET -> LOG.warn("Leeres Ereignis von {} verworfen", node);
        }

        if (event.getSeq() > 0) {
            cursors.advanceTo(originOf(node), event.getSeq());
        }
    }

    /** Uebernimmt die Server, die der Wrapper beim Reconnect meldet. */
    private void adoptServers(String node, NodeEvent event) {
        List<CloudServer> reported = new ArrayList<>();

        for (RunningServer running : event.getFullState().getServersList()) {
            Optional<ServerGroup> group = groups.find(running.getGroupName());
            ServerState state = parseState(running.getState());
            reported.add(ServerRegistry.adopted(
                    running.getServerName(),
                    group.orElse(null),
                    group.map(ServerGroup::platform).orElse(
                            de.kevloe.vibecloud.api.server.ServerPlatformType.PAPER),
                    node,
                    running.getPort(),
                    state,
                    running.hasStartedAt() ? Protos.fromProto(running.getStartedAt())
                            : Instant.now()));
        }

        List<String> vanished = servers.reconcileNode(node, reported);
        vanished.forEach(console::forget);
        nodes.heartbeat(node);
    }

    private void applyServerState(String node, ServerStateChanged change) {
        String name = change.getServerName();
        ServerState state = fromProto(change.getState());

        servers.find(name).ifPresentOrElse(
                server -> servers.put(server.withState(state)),
                () -> {
                    // Kann nach einem Master-Neustart vorkommen: Der Wrapper meldet einen
                    // Server, den der Master noch nicht adoptiert hatte.
                    groups.find(change.getGroupName()).ifPresent(group ->
                            servers.put(ServerRegistry.adopted(name, group, group.platform(),
                                    node, change.getPort(), state, Instant.now())));
                });

        switch (state) {
            case RUNNING -> {
                LOG.info("{} laeuft (Port {})", name, change.getPort());
                announceToProxies(name);
            }
            case STOPPED -> {
                reportEnded(name, change.getGroupName(), change.getExitCode() != 0);
                history.recordStop(name, change.getExitCode(), change.getDetail());
                withdrawFromProxies(name);
                servers.remove(name);
                console.forget(name);
                sessions.forget(name);
                LOG.info("{} beendet", name);
            }
            case CRASHED -> {
                reportEnded(name, change.getGroupName(), true);
                history.recordStop(name, change.getExitCode(), change.getDetail());
                withdrawFromProxies(name);
                servers.remove(name);
                console.forget(name);
                sessions.forget(name);
                LOG.warn("{} abgestuerzt (Exit-Code {}){}", name, change.getExitCode(),
                        change.getDetail().isBlank() ? "" : ": " + change.getDetail());
            }
            default -> LOG.debug("{} ist jetzt {}", name, state);
        }
    }

    /**
     * Meldet einen neu laufenden Gameserver an alle Proxys.
     *
     * <p>Ohne das kennt kein Proxy den Server und niemand kann dorthin verbunden werden -
     * der Server laeuft dann unsichtbar.
     */
    private void announceToProxies(String serverName) {
        servers.find(serverName)
                .filter(server -> server.platform()
                        != de.kevloe.vibecloud.api.server.ServerPlatformType.VELOCITY)
                .ifPresent(server -> {
                    int delivered = plugins.broadcastToProxies(PluginConnectionRegistry.command(
                            builder -> builder.setAddServer(
                                    de.kevloe.vibecloud.protocol.AddBackendServer.newBuilder()
                                            .setServer(pluginService.toBackend(server)))));
                    if (delivered > 0) {
                        LOG.debug("{} an {} Proxy(s) gemeldet", serverName, delivered);
                    }
                });
    }

    private void withdrawFromProxies(String serverName) {
        plugins.broadcastToProxies(PluginConnectionRegistry.command(
                builder -> builder.setRemoveServer(
                        de.kevloe.vibecloud.protocol.RemoveBackendServer.newBuilder()
                                .setName(serverName))));
    }

    /** Meldet dem Scheduler, wie lange der Server gelaufen ist - fuer den Crash-Backoff. */
    private void reportEnded(String serverName, String groupName, boolean crashed) {
        long uptime = servers.find(serverName)
                .map(server -> Duration.between(server.startedAt(), Instant.now()).toSeconds())
                .orElse(0L);
        scheduler.reportServerEnded(groupName, uptime, crashed);
    }

    private void acknowledge(StreamObserver<NodeCommand> toWrapper, long upToSeq) {
        toWrapper.onNext(NodeCommand.newBuilder()
                .setCommandId(UUID.randomUUID().toString())
                .setAckEvents(AckEvents.newBuilder().setUpToSeq(upToSeq))
                .build());
    }

    // ---------------------------------------------------------------- Dateien

    @Override
    public void getManifest(ManifestRequest request,
                            StreamObserver<TemplateManifest> responseObserver) {
        requireNode();
        Optional<TemplateManifest> manifest = templates.manifest(request.getManifestId());
        if (manifest.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Unbekannte manifest_id " + request.getManifestId()
                                     + " - wurde der Master zwischendurch neu gestartet?")
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(manifest.get());
        responseObserver.onCompleted();
    }

    @Override
    public void pullBlobs(BlobRequest request, StreamObserver<BlobChunk> responseObserver) {
        String node = requireNode().node();

        for (String sha256 : request.getSha256List()) {
            Optional<Path> blob = templates.blob(sha256);
            if (blob.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Blob " + sha256 + " ist dem Master nicht bekannt")
                        .asRuntimeException());
                return;
            }
            try {
                streamBlob(sha256, blob.get(), responseObserver);
            } catch (IOException exception) {
                LOG.error("Blob {} konnte nicht an {} gesendet werden", sha256, node, exception);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Blob nicht lesbar: " + exception.getMessage())
                        .asRuntimeException());
                return;
            }
        }
        responseObserver.onCompleted();
    }

    private void streamBlob(String sha256, Path file, StreamObserver<BlobChunk> target)
            throws IOException {

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BLOB_CHUNK_BYTES];
            int read;
            while ((read = in.read(buffer)) > 0) {
                target.onNext(BlobChunk.newBuilder()
                        .setSha256(sha256)
                        .setData(ByteString.copyFrom(buffer, 0, read))
                        .build());
            }
            // Abschlusschunk: Erst daran erkennt der Wrapper, dass der Blob vollstaendig
            // ist und sein Hash geprueft werden kann.
            target.onNext(BlobChunk.newBuilder().setSha256(sha256).setLast(true).build());
        }
    }

    // ---------------------------------------------------------------- Konsole

    @Override
    public StreamObserver<ConsoleLine> pushConsole(StreamObserver<Ack> responseObserver) {
        String node = requireNode().node();

        return new StreamObserver<>() {

            @Override
            public void onNext(ConsoleLine line) {
                // Befugnis: Ein Node darf nur ueber seine eigenen Server reden.
                Optional<CloudServer> server = servers.find(line.getServerName());
                if (server.isPresent() && !server.get().node().equals(node)) {
                    audit.record("NODE:" + node, "authz.rejected", line.getServerName(),
                            Map.of("reason", "fremder Server", "owner", server.get().node()));
                    LOG.warn("Node {} schickte Konsolenzeilen fuer {}, der auf {} laeuft - "
                             + "verworfen", node, line.getServerName(), server.get().node());
                    return;
                }
                console.append(line.getServerName(), line.getLine());
            }

            @Override
            public void onError(Throwable error) {
                LOG.debug("Konsolen-Stream von {} beendet: {}", node, error.getMessage());
            }

            @Override
            public void onCompleted() {
                responseObserver.onNext(Ack.newBuilder().setAccepted(true).build());
                responseObserver.onCompleted();
            }
        };
    }

    // ---------------------------------------------------------------- Log-Upload

    @Override
    public StreamObserver<LogChunk> uploadServerLog(StreamObserver<Ack> responseObserver) {
        String node = requireNode().node();

        return new StreamObserver<>() {

            private LogArchive.Upload upload;
            private String serverName;

            @Override
            public void onNext(LogChunk chunk) {
                try {
                    if (upload == null) {
                        serverName = chunk.getServerName();
                        String group = chunk.getGroupName().isBlank()
                                ? servers.find(serverName).map(CloudServer::groupName).orElse("")
                                : chunk.getGroupName();
                        upload = logs.beginUpload(serverName, group);
                    }
                    if (!chunk.getData().isEmpty()) {
                        upload.write(chunk.getData().toByteArray());
                    }
                    if (chunk.getLast()) {
                        Path stored = upload.finish();
                        upload = null;
                        LOG.info("Log von {} archiviert: {}", serverName, stored.getFileName());
                        responseObserver.onNext(Ack.newBuilder().setAccepted(true).build());
                        responseObserver.onCompleted();
                    }
                } catch (IOException exception) {
                    LOG.error("Log-Upload von {} fehlgeschlagen", node, exception);
                    closeUpload();
                    responseObserver.onNext(Ack.newBuilder()
                            .setAccepted(false)
                            .setDetail(exception.getMessage())
                            .build());
                    responseObserver.onCompleted();
                }
            }

            @Override
            public void onError(Throwable error) {
                // Abbruch mitten im Upload: Teil-Datei wegraeumen. Der Wrapper behaelt seine
                // Kopie in logs/pending und schickt sie beim naechsten Mal erneut.
                LOG.warn("Log-Upload von {} abgebrochen: {}", node, error.getMessage());
                closeUpload();
            }

            @Override
            public void onCompleted() {
                closeUpload();
            }

            private void closeUpload() {
                if (upload != null) {
                    upload.close();
                    upload = null;
                }
            }
        };
    }

    // ---------------------------------------------------------------- SFTP

    /**
     * Ein Wrapper fragt, ob eine SFTP-Anmeldung gilt.
     *
     * <p>Der Node-Name kommt aus der Verbindung, nicht aus der Nachricht - und
     * {@link SftpAccountService#authenticate} prueft damit, dass der Server an genau
     * diesen Node gebunden ist.
     */
    @Override
    public void authenticateSftp(SftpAuthRequest request,
                                 StreamObserver<SftpAuthResponse> responseObserver) {
        String node = requireNode().node();

        SftpAccountService.Decision decision = sftp.authenticate(node,
                request.getUsername(), request.getServerName(), request.getPassword(),
                request.getClientIp().isBlank() ? "unbekannt" : request.getClientIp());

        responseObserver.onNext(SftpAuthResponse.newBuilder()
                .setAllowed(decision.allowed())
                .setDetail(decision.detail())
                .build());
        responseObserver.onCompleted();
    }

    // ---------------------------------------------------------------- Hilfsmittel

    private static String originOf(String node) {
        return "node:" + node;
    }

    private static ServerState parseState(String name) {
        try {
            return ServerState.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return ServerState.RUNNING;
        }
    }

    private static ServerState fromProto(de.kevloe.vibecloud.protocol.ServerStateProto state) {
        return switch (state) {
            case SERVER_STATE_PREPARING -> ServerState.PREPARING;
            case SERVER_STATE_STARTING -> ServerState.STARTING;
            case SERVER_STATE_RUNNING -> ServerState.RUNNING;
            case SERVER_STATE_STOPPING -> ServerState.STOPPING;
            case SERVER_STATE_STOPPED -> ServerState.STOPPED;
            case SERVER_STATE_CRASHED -> ServerState.CRASHED;
            default -> ServerState.PREPARING;
        };
    }

    /**
     * Holt die Identitaet des Aufrufs und stellt sicher, dass es ein Node ist.
     *
     * <p>Ein Gameserver-Plugin darf diesen Service nicht benutzen - es spricht ab M3 mit
     * einem eigenen Service und darf nie ueber Nodes reden.
     */
    private NodeIdentity requireNode() {
        CallIdentity identity = AuthInterceptor.currentIdentity();
        if (identity instanceof NodeIdentity nodeIdentity) {
            return nodeIdentity;
        }
        audit.record(identity.describe(), "authz.rejected", "NodeService",
                Map.of("reason", "keine Node-Identitaet"));
        throw Status.PERMISSION_DENIED
                .withDescription("Dieser Dienst ist nur fuer Wrapper")
                .asRuntimeException();
    }
}
