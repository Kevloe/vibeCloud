package de.kevloe.vibecloud.wrapper.grpc;

import com.google.protobuf.ByteString;
import de.kevloe.vibecloud.api.Protos;
import de.kevloe.vibecloud.api.outbox.Outbox;
import de.kevloe.vibecloud.common.VibeCloud;
import de.kevloe.vibecloud.common.tls.FingerprintTrustManager;
import de.kevloe.vibecloud.protocol.Ack;
import de.kevloe.vibecloud.protocol.ConsoleLine;
import de.kevloe.vibecloud.protocol.FullState;
import de.kevloe.vibecloud.protocol.Heartbeat;
import de.kevloe.vibecloud.protocol.LogChunk;
import de.kevloe.vibecloud.protocol.NodeCommand;
import de.kevloe.vibecloud.protocol.NodeEvent;
import de.kevloe.vibecloud.protocol.NodeServiceGrpc;
import de.kevloe.vibecloud.protocol.RegisterRequest;
import de.kevloe.vibecloud.protocol.RegisterResponse;
import de.kevloe.vibecloud.protocol.ReplayFinished;
import de.kevloe.vibecloud.protocol.ResourceReport;
import de.kevloe.vibecloud.protocol.ResourceUsage;
import de.kevloe.vibecloud.protocol.RunningServer;
import de.kevloe.vibecloud.protocol.SftpAuthRequest;
import de.kevloe.vibecloud.protocol.SftpAuthResponse;
import de.kevloe.vibecloud.wrapper.config.WrapperConfig;
import de.kevloe.vibecloud.wrapper.server.LocalServerManager;
import de.kevloe.vibecloud.wrapper.server.ServerCommandHandler;
import de.kevloe.vibecloud.sftp.SftpAuthority;
import de.kevloe.vibecloud.wrapper.template.TemplateCache;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verbindung des Wrappers zum Master (PLAN.md Abschnitt 7).
 *
 * <p><b>Immer der Wrapper waehlt an.</b> Der Master oeffnet nie eine Verbindung zu einem Root,
 * sondern schickt Befehle durch den {@code Control}-Stream, den der Wrapper aufgebaut hat.
 * Deshalb braucht kein Root einen offenen Port.
 *
 * <p>Reconnect mit exponentiellem Backoff: Ein Master-Neustart darf keinen Gameserver
 * beenden - der Wrapper wartet einfach, bis er wieder da ist.
 */
public final class MasterConnection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MasterConnection.class);

    private static final int REPLAY_BATCH_SIZE = 500;
    private static final int LOG_CHUNK_BYTES = 256 * 1024;
    private static final long BACKOFF_START_MILLIS = 1_000;
    private static final long BACKOFF_MAX_MILLIS = 30_000;

    private final WrapperConfig config;
    private final Outbox outbox;
    private final LocalServerManager servers;
    private final TemplateCache cache;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ManagedChannel channel;
    private StreamObserver<NodeEvent> toMaster;
    private StreamObserver<ConsoleLine> consoleStream;
    private NodeServiceGrpc.NodeServiceStub async;
    /** Fuer Rueckfragen von ausserhalb des Verbindungs-Threads, etwa der SFTP-Anmeldung. */
    private volatile NodeServiceGrpc.NodeServiceBlockingStub blockingStub;
    /** Der SFTP-Port dieses Nodes, wie er dem Master gemeldet wird. 0 = aus. */
    private volatile int sftpPort;
    private volatile String sftpHostKey = "";
    private ServerCommandHandler commands;
    private volatile boolean connected;
    private volatile long heartbeatIntervalSeconds = 10;

    public MasterConnection(WrapperConfig config, Outbox outbox,
                            LocalServerManager servers, TemplateCache cache) {
        this.config = config;
        this.outbox = outbox;
        this.servers = servers;
        this.cache = cache;
        this.scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("wrapper-", 0).factory());
    }

    /** Verbindet und haelt die Verbindung, bis {@link #close()} gerufen wird. Blockiert. */
    public void runForever() {
        long backoff = BACKOFF_START_MILLIS;

        while (running.get()) {
            try {
                CountDownLatch finished = connectOnce();
                backoff = BACKOFF_START_MILLIS;
                finished.await();
                if (running.get()) {
                    LOG.warn("Verbindung zum Master verloren - die Gameserver laufen weiter. "
                             + "Neuer Versuch in {} s", backoff / 1000);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                LOG.warn("Verbindung zum Master fehlgeschlagen ({}). Neuer Versuch in {} s",
                        exception.getMessage(), backoff / 1000);
            } finally {
                connected = false;
                shutdownChannel();
            }

            if (!running.get()) {
                return;
            }
            sleep(backoff);
            backoff = Math.min(BACKOFF_MAX_MILLIS, backoff * 2);
        }
    }

    /** @return Latch, der ausgeloest wird, wenn diese Verbindung endet */
    private CountDownLatch connectOnce() throws IOException {
        LOG.info("Verbinde zu {}:{} als Node {}",
                config.masterHost, config.masterPort, config.node);

        channel = NettyChannelBuilder
                .forAddress(config.masterHost, config.masterPort)
                .sslContext(GrpcSslContexts.forClient()
                        .trustManager(new FingerprintTrustManager(config.masterFingerprint))
                        .build())
                // Grosse Blobs werden gechunkt, aber ein Manifest kann viele Eintraege haben.
                .maxInboundMessageSize(16 * 1024 * 1024)
                .build();

        // Node-Name und Token gehen als Metadaten mit JEDEM Aufruf raus, damit der
        // AuthInterceptor im Master eine einzige Pruefstelle bleibt.
        Metadata metadata = new Metadata();
        metadata.put(Keys.NODE, config.node);
        metadata.put(Keys.TOKEN, config.token);

        var blocking = NodeServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        blockingStub = blocking;
        async = NodeServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));

        RegisterResponse response = blocking.register(buildRegisterRequest());
        heartbeatIntervalSeconds = Math.max(1, response.getHeartbeatIntervalSeconds());

        LOG.info("Angemeldet. Master-Protokoll {}, Replay ab seq {}, Uhrabweichung {} ms",
                response.getApiVersion(), response.getReplayFromSeq(),
                response.getClockSkewMillis());

        CountDownLatch finished = new CountDownLatch(1);
        toMaster = async.control(new CommandHandler(finished));
        consoleStream = async.pushConsole(new AckObserver("Konsolen-Stream"));
        connected = true;

        commands = new ServerCommandHandler(servers, cache, blocking,
                this::publishFact, this::sendConsoleLine, this::uploadLog);

        // Reihenfolge wie in PLAN.md Abschnitt 7: erst der Ist-Zustand, dann der Replay,
        // erst danach der normale Betrieb. Sonst wuerde der Master Entscheidungen treffen,
        // obwohl ihm noch die halbe Wirklichkeit fehlt.
        sendFullState();
        replayOutbox(response.getReplayFromSeq());
        commands.uploadPendingLogs();
        startHeartbeat();
        return finished;
    }

    private RegisterRequest buildRegisterRequest() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return RegisterRequest.newBuilder()
                .setApiVersion(VibeCloud.API_VERSION)
                .setWrapperVersion(VibeCloud.API_VERSION + ".0")
                .setMaxMemoryMb(config.maxMemoryMb)
                .setCpuCores(Runtime.getRuntime().availableProcessors())
                .setOsName(os.getName() + " " + os.getVersion())
                .setWrapperTime(Protos.toProto(Instant.now()))
                .setServerAddress(config.serverAddress)
                .setHighestOutboxSeq(outbox.highestSeq())
                .setSftpPort(sftpPort)
                .setSftpHostKey(sftpHostKey)
                .addAllJavaVersions(servers.javaVersions())
                .build();
    }

    /**
     * Vor {@link #runForever()} zu setzen - Port und Fingerprint gehen mit der Anmeldung
     * zum Master, damit er sagen kann, wohin man sich verbindet und woran man den Node
     * erkennt.
     */
    public void announceSftp(int port, String hostKeyFingerprint) {
        this.sftpPort = port;
        this.sftpHostKey = hostKeyFingerprint;
    }

    /**
     * Fragt den Master, ob diese SFTP-Anmeldung gilt.
     *
     * <p>Ohne Verbindung lautet die Antwort nein. Der Wrapper kennt weder Zugaenge noch
     * Rechte, und ein Zwischenspeicher fuer "hat vorhin gestimmt" liesse jemanden herein,
     * dem das Recht inzwischen entzogen wurde.
     */
    public SftpAuthority.Decision authenticateSftp(
            String account, String serverName, String password, String clientIp) {
        NodeServiceGrpc.NodeServiceBlockingStub stub = blockingStub;
        if (!connected || stub == null) {
            return SftpAuthority.Decision.denied(
                    "Master nicht erreichbar");
        }
        try {
            SftpAuthResponse response = stub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .authenticateSftp(SftpAuthRequest.newBuilder()
                            .setUsername(account)
                            .setServerName(serverName)
                            .setPassword(password)
                            .setClientIp(clientIp)
                            .build());
            return new SftpAuthority.Decision(
                    response.getAllowed(), response.getDetail());
        } catch (RuntimeException exception) {
            return SftpAuthority.Decision.denied(
                    "Master antwortet nicht: " + exception.getMessage());
        }
    }

    /**
     * Was gerade laeuft. Der Master adoptiert diese Server statt sie neu zu starten -
     * ein Master-Neustart wirft also niemanden aus dem Spiel.
     */
    private void sendFullState() {
        FullState.Builder state = FullState.newBuilder();
        for (LocalServerManager.LocalServer server : servers.all()) {
            state.addServers(RunningServer.newBuilder()
                    .setServerName(server.name())
                    .setGroupName(server.groupName())
                    .setPort(server.port())
                    .setState(server.state().name())
                    .setStartedAt(Protos.toProto(server.startedAt())));
        }
        send(NodeEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setFullState(state)
                .build());
    }

    /**
     * Spielt die Outbox in seq-Reihenfolge nach. Ereignisse behalten ihren
     * Original-Zeitstempel - der Master schreibt sie mit der echten Zeit, nicht mit
     * der Replay-Zeit.
     */
    private void replayOutbox(long fromSeqExclusive) {
        long cursor = fromSeqExclusive;
        long total = 0;

        while (running.get()) {
            List<NodeEvent> batch = outbox.replayFrom(cursor, REPLAY_BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (NodeEvent event : batch) {
                send(event);
                cursor = Math.max(cursor, event.getSeq());
            }
            total += batch.size();
        }

        if (total > 0) {
            LOG.info("{} gepufferte Ereignisse nachgereicht (bis seq {})", total, cursor);
        }

        // Der Master macht seinen Scheduler erst nach dieser Nachricht wieder scharf.
        send(NodeEvent.newBuilder()
                .setOccurredAt(Protos.now())
                .setReplayFinished(ReplayFinished.newBuilder().setLastSeq(cursor))
                .build());
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!connected) {
                return;
            }
            // Heartbeats und Ressourcenwerte sind Momentaufnahmen und gehen deshalb
            // NICHT in die Outbox (PLAN.md Abschnitt 7).
            send(NodeEvent.newBuilder()
                    .setOccurredAt(Protos.now())
                    .setHeartbeat(Heartbeat.newBuilder())
                    .build());
            send(NodeEvent.newBuilder()
                    .setOccurredAt(Protos.now())
                    .setResourceReport(ResourceReport.newBuilder().setHost(currentUsage()))
                    .build());
        }, 0, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    private ResourceUsage currentUsage() {
        Runtime runtime = Runtime.getRuntime();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemLoadAverage();
        return ResourceUsage.newBuilder()
                // getSystemLoadAverage() liefert auf Windows -1 - dann lieber 0 melden
                // als einen Wert, der nach Entlastung aussieht.
                .setCpuLoad(load < 0 ? 0.0 : load / Math.max(1, os.getAvailableProcessors()))
                .setMemoryUsedMb((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
                .setMemoryMaxMb(config.maxMemoryMb)
                .build();
    }

    /**
     * Fakt, der nicht verloren gehen darf: erst in die Outbox, dann senden.
     * Server-Zustaende gehoeren hierher, Heartbeats nicht.
     */
    private void publishFact(NodeEvent event) {
        NodeEvent stamped = outbox.append(event);
        if (connected) {
            send(stamped);
        }
    }

    private synchronized void sendConsoleLine(ConsoleLine line) {
        StreamObserver<ConsoleLine> target = consoleStream;
        if (target == null) {
            return;
        }
        try {
            target.onNext(line);
        } catch (RuntimeException exception) {
            // Konsolenzeilen sind entbehrlich - ein Fehler hier darf nichts weiter ausloesen.
            LOG.debug("Konsolenzeile nicht gesendet: {}", exception.getMessage());
        }
    }

    /**
     * Schickt einen gesicherten Log zum Master und loescht ihn erst nach der Quittung.
     * Ist der Master weg, bleibt die Datei in {@code logs/pending/} liegen.
     */
    private void uploadLog(LocalServerManager.PendingLog pending) {
        Path file = pending.file();
        if (!connected || async == null) {
            LOG.debug("Master nicht erreichbar - {} bleibt liegen", file.getFileName());
            return;
        }
        String fileName = file.getFileName().toString();

        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<LogChunk> upload = async.uploadServerLog(new StreamObserver<>() {

            @Override
            public void onNext(Ack ack) {
                if (ack.getAccepted()) {
                    ServerCommandHandler.deleteQuietly(file);
                    ServerCommandHandler.deleteQuietly(LocalServerManager.metaFileOf(file));
                    LOG.debug("Log {} ist beim Master angekommen", fileName);
                } else {
                    LOG.warn("Master hat Log {} abgelehnt: {}", fileName, ack.getDetail());
                }
            }

            @Override
            public void onError(Throwable error) {
                LOG.warn("Log {} konnte nicht hochgeladen werden ({}) - bleibt liegen",
                        fileName, error.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[LOG_CHUNK_BYTES];
            int read;
            boolean first = true;
            while ((read = in.read(buffer)) > 0) {
                LogChunk.Builder chunk = LogChunk.newBuilder()
                        .setData(ByteString.copyFrom(buffer, 0, read));
                if (first) {
                    // Gruppenname mitschicken: Der Master kann ihn nicht mehr nachschlagen,
                    // weil der Server zu diesem Zeitpunkt schon aus seiner Registry ist.
                    chunk.setServerName(pending.serverName())
                            .setGroupName(pending.groupName())
                            .setStoppedAt(Protos.now());
                    first = false;
                }
                upload.onNext(chunk.build());
            }
            upload.onNext(LogChunk.newBuilder().setLast(true).build());
            upload.onCompleted();
            done.await(30, TimeUnit.SECONDS);
        } catch (IOException exception) {
            upload.onError(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void send(NodeEvent event) {
        StreamObserver<NodeEvent> target = toMaster;
        if (target == null) {
            return;
        }
        try {
            target.onNext(event);
        } catch (RuntimeException exception) {
            LOG.debug("Senden fehlgeschlagen: {}", exception.getMessage());
            connected = false;
        }
    }

    private void shutdownChannel() {
        toMaster = null;
        consoleStream = null;
        async = null;
        blockingStub = null;
        commands = null;
        if (channel != null) {
            channel.shutdownNow();
            channel = null;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @Override
    public void close() {
        running.set(false);
        StreamObserver<NodeEvent> target = toMaster;
        if (target != null) {
            try {
                target.onCompleted();
            } catch (RuntimeException ignored) {
                // Stream war schon zu.
            }
        }
        scheduler.close();
        shutdownChannel();
    }

    /** Verarbeitet die Befehle des Masters. */
    private final class CommandHandler implements StreamObserver<NodeCommand> {

        private final CountDownLatch finished;

        CommandHandler(CountDownLatch finished) {
            this.finished = finished;
        }

        @Override
        public void onNext(NodeCommand command) {
            ServerCommandHandler handler = commands;
            switch (command.getCommandCase()) {
                case ACK_EVENTS -> {
                    long upTo = command.getAckEvents().getUpToSeq();
                    // Erst jetzt darf die Outbox freigeben - vorher weiss niemand,
                    // ob die Ereignisse wirklich angekommen sind.
                    outbox.ackUpTo(upTo);
                    LOG.debug("Master hat bis seq {} quittiert", upTo);
                }
                case START_SERVER -> runAsync("start",
                        () -> handler.handleStart(command.getStartServer()));
                case STOP_SERVER -> runAsync("stop", () -> handler.handleStop(
                        command.getStopServer().getServerName(),
                        command.getStopServer().getGraceSeconds(),
                        command.getStopServer().getReason()));
                case KILL_SERVER -> runAsync("kill", () -> handler.handleKill(
                        command.getKillServer().getServerName(),
                        command.getKillServer().getReason()));
                case EXECUTE_COMMAND -> runAsync("command", () -> handler.handleExecute(
                        command.getExecuteCommand().getServerName(),
                        command.getExecuteCommand().getCommandLine()));
                case SHUTDOWN -> {
                    var shutdown = command.getShutdown();
                    LOG.info("Master fordert Beenden an (Server mitnehmen: {}): {}",
                            shutdown.getStopServers(), shutdown.getReason());
                    if (shutdown.getStopServers()) {
                        servers.shutdownAll(30);
                    }
                    running.set(false);
                    finished.countDown();
                }
                case COMMAND_NOT_SET -> LOG.warn("Leerer Befehl vom Master verworfen");
            }
        }

        /**
         * Befehle laufen auf eigenen Threads. Ein Serverstart zieht moeglicherweise
         * hundert Megabyte Dateien - der Control-Stream darf dabei nicht blockieren,
         * sonst kommen in der Zeit keine Heartbeats durch.
         */
        private void runAsync(String what, Runnable action) {
            if (commands == null) {
                LOG.warn("Befehl '{}' kam, bevor die Verbindung fertig war", what);
                return;
            }
            Thread.ofVirtual().name("cmd-" + what).start(() -> {
                try {
                    action.run();
                } catch (RuntimeException exception) {
                    LOG.error("Befehl '{}' fehlgeschlagen", what, exception);
                }
            });
        }

        @Override
        public void onError(Throwable error) {
            LOG.debug("Control-Stream beendet: {}", error.getMessage());
            connected = false;
            finished.countDown();
        }

        @Override
        public void onCompleted() {
            LOG.info("Master hat den Stream regulaer beendet");
            connected = false;
            finished.countDown();
        }
    }

    /** Quittungen eines einfachen Streams, bei dem nur Fehler interessieren. */
    private static final class AckObserver implements StreamObserver<Ack> {

        private final String what;

        AckObserver(String what) {
            this.what = what;
        }

        @Override
        public void onNext(Ack ack) {
            // Nichts zu tun.
        }

        @Override
        public void onError(Throwable error) {
            LOG.debug("{} beendet: {}", what, error.getMessage());
        }

        @Override
        public void onCompleted() {
            LOG.debug("{} geschlossen", what);
        }
    }

    /** Metadaten-Schluessel, identisch zu denen im {@code AuthInterceptor} des Masters. */
    private static final class Keys {
        static final Metadata.Key<String> NODE =
                Metadata.Key.of("vibecloud-node", Metadata.ASCII_STRING_MARSHALLER);
        static final Metadata.Key<String> TOKEN =
                Metadata.Key.of("vibecloud-token", Metadata.ASCII_STRING_MARSHALLER);
    }
}
