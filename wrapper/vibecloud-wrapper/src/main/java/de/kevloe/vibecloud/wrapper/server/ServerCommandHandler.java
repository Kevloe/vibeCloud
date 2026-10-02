package de.kevloe.vibecloud.wrapper.server;

import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.protocol.BlobChunk;
import de.kevloe.vibecloud.protocol.BlobRequest;
import de.kevloe.vibecloud.protocol.ConsoleLine;
import de.kevloe.vibecloud.protocol.ManifestRequest;
import de.kevloe.vibecloud.protocol.NodeEvent;
import de.kevloe.vibecloud.protocol.NodeServiceGrpc;
import de.kevloe.vibecloud.protocol.ServerStateChanged;
import de.kevloe.vibecloud.protocol.ServerStateProto;
import de.kevloe.vibecloud.protocol.StartServer;
import de.kevloe.vibecloud.protocol.TemplateManifest;
import de.kevloe.vibecloud.wrapper.runtime.ServerRuntime;
import de.kevloe.vibecloud.wrapper.template.TemplateCache;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fuehrt die Server-Befehle des Masters aus (PLAN.md Abschnitt 7).
 *
 * <p>Laeuft bewusst auf einem eigenen Thread je Befehl: Ein Serverstart zieht
 * moeglicherweise hundert Megabyte Dateien, und der Control-Stream darf dabei nicht warten.
 */
public final class ServerCommandHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ServerCommandHandler.class);

    private final LocalServerManager servers;
    private final TemplateCache cache;
    private final NodeServiceGrpc.NodeServiceBlockingStub blocking;
    private final Consumer<NodeEvent> publisher;
    private final Consumer<ConsoleLine> consoleSink;
    private final Consumer<LocalServerManager.PendingLog> logUploader;

    public ServerCommandHandler(LocalServerManager servers, TemplateCache cache,
                                NodeServiceGrpc.NodeServiceBlockingStub blocking,
                                Consumer<NodeEvent> publisher,
                                Consumer<ConsoleLine> consoleSink,
                                Consumer<LocalServerManager.PendingLog> logUploader) {
        this.servers = servers;
        this.cache = cache;
        this.blocking = blocking;
        this.publisher = publisher;
        this.consoleSink = consoleSink;
        this.logUploader = logUploader;
    }

    public void handleStart(StartServer request) {
        String name = request.getServerName();
        reportState(name, request.getGroupName(), ServerState.PREPARING, request.getPort(), 0, "");

        try {
            TemplateManifest manifest = blocking.getManifest(ManifestRequest.newBuilder()
                    .setManifestId(request.getManifestId())
                    .build());

            List<String> missing = cache.missingBlobs(manifest);
            if (!missing.isEmpty()) {
                LOG.info("{}: {} von {} Dateien fehlen und werden geholt",
                        name, missing.size(), manifest.getEntriesCount());
                pullBlobs(missing);
            }

            reportState(name, request.getGroupName(), ServerState.STARTING,
                    request.getPort(), 0, "");
            servers.start(request, manifest, new Listener(request));

        } catch (Exception exception) {
            LOG.error("{} konnte nicht gestartet werden", name, exception);
            reportState(name, request.getGroupName(), ServerState.CRASHED,
                    request.getPort(), -1, exception.getMessage());
        }
    }

    /**
     * Holt fehlende Blobs vom Master.
     *
     * <p>Erst in eine Teil-Datei, dann Hash pruefen, dann umbenennen - das macht
     * {@link TemplateCache#store}. Ein Abbruch laesst damit keinen beschaedigten Blob zurueck.
     */
    private void pullBlobs(List<String> hashes) throws IOException {
        Iterator<BlobChunk> chunks = blocking.pullBlobs(
                BlobRequest.newBuilder().addAllSha256(hashes).build());

        String current = null;
        OutputStream out = null;
        try {
            while (chunks.hasNext()) {
                BlobChunk chunk = chunks.next();
                if (!chunk.getSha256().equals(current)) {
                    closeQuietly(out);
                    current = chunk.getSha256();
                    out = cache.openTemporary(current);
                }
                chunk.getData().writeTo(out);
                if (chunk.getLast()) {
                    out.close();
                    out = null;
                    cache.store(current, cache.temporaryFile(current));
                    current = null;
                }
            }
        } finally {
            closeQuietly(out);
        }
    }

    public void handleStop(String serverName, int graceSeconds, String reason) {
        servers.find(serverName).ifPresentOrElse(server -> {
            LOG.info("{} wird gestoppt: {}", serverName, reason);
            reportState(serverName, server.groupName(), ServerState.STOPPING,
                    server.port(), 0, reason);
            // In einem eigenen Thread: stop() wartet die Gnadenfrist ab.
            Thread.ofVirtual().name("stop-" + serverName)
                    .start(() -> servers.stop(serverName, graceSeconds));
        }, () -> LOG.debug("Stopp fuer unbekannten Server {} ignoriert", serverName));
    }

    public void handleKill(String serverName, String reason) {
        LOG.warn("{} wird hart beendet: {}", serverName, reason);
        servers.kill(serverName);
    }

    public void handleExecute(String serverName, String commandLine) {
        servers.sendCommand(serverName, commandLine);
    }

    private void reportState(String serverName, String groupName, ServerState state,
                             int port, int exitCode, String detail) {
        publisher.accept(NodeEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setServerStateChanged(ServerStateChanged.newBuilder()
                        .setServerName(serverName)
                        .setGroupName(groupName)
                        .setState(toProto(state))
                        .setPort(port)
                        .setExitCode(exitCode)
                        .setDetail(detail == null ? "" : detail))
                .build());
    }

    private static ServerStateProto toProto(ServerState state) {
        return switch (state) {
            case PREPARING -> ServerStateProto.SERVER_STATE_PREPARING;
            case STARTING -> ServerStateProto.SERVER_STATE_STARTING;
            case RUNNING -> ServerStateProto.SERVER_STATE_RUNNING;
            case STOPPING -> ServerStateProto.SERVER_STATE_STOPPING;
            case STOPPED -> ServerStateProto.SERVER_STATE_STOPPED;
            case CRASHED -> ServerStateProto.SERVER_STATE_CRASHED;
            case WAITING_FOR_NODE -> ServerStateProto.SERVER_STATE_UNSPECIFIED;
        };
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // Beim Aufraeumen nicht weiter wichtig.
        }
    }

    /** Verbindet die Prozess-Ereignisse mit dem Master. */
    private final class Listener implements ServerRuntime.ServerListener {

        private final StartServer request;

        Listener(StartServer request) {
            this.request = request;
        }

        @Override
        public void onLine(String serverName, String line, boolean errorStream) {
            // Konsolenzeilen gehen ueber einen eigenen Stream und NICHT in die Outbox -
            // sie sind Momentaufnahmen, kein Fakt, der nachgereicht werden muss.
            consoleSink.accept(ConsoleLine.newBuilder()
                    .setServerName(serverName)
                    .setAt(Protos.now())
                    .setLine(line)
                    .setErrorStream(errorStream)
                    .build());
        }

        @Override
        public void onReady(String serverName) {
            reportState(serverName, request.getGroupName(), ServerState.RUNNING,
                    request.getPort(), 0, "");
        }

        @Override
        public void onExit(String serverName, int exitCode) {
            // Exit-Code 0 heisst regulaer beendet, alles andere ist ein Absturz.
            ServerState state = exitCode == 0 ? ServerState.STOPPED : ServerState.CRASHED;
            reportState(serverName, request.getGroupName(), state,
                    request.getPort(), exitCode, "");

            servers.cleanupAfterExit(serverName).ifPresent(logUploader);
        }
    }

    /** Beim Start und nach einem Reconnect: liegen gebliebene Logs nachschicken. */
    public void uploadPendingLogs() {
        List<LocalServerManager.PendingLog> pending = servers.pendingLogs();
        if (pending.isEmpty()) {
            return;
        }
        LOG.info("{} gesicherte Logs werden nachgereicht", pending.size());
        pending.forEach(logUploader);
    }

    /** Nur fuer Tests und das Aufraeumen. */
    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Beim Aufraeumen nicht weiter wichtig.
        }
    }
}
