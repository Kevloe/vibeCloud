package de.kevloe.vibecloud.master.server;

import de.kevloe.vibecloud.api.ServerState;
import de.kevloe.vibecloud.api.node.NodeInfo;
import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.api.server.ServerGroup;
import de.kevloe.vibecloud.api.server.ServerPlatformType;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.grpc.ServerSessionStore;
import de.kevloe.vibecloud.master.node.NodeRegistry;
import de.kevloe.vibecloud.master.template.TemplateStore;
import de.kevloe.vibecloud.protocol.ExecuteCommand;
import de.kevloe.vibecloud.protocol.KillServer;
import de.kevloe.vibecloud.protocol.NodeCommand;
import de.kevloe.vibecloud.protocol.ServerPlatform;
import de.kevloe.vibecloud.protocol.StartServer;
import de.kevloe.vibecloud.protocol.StopServer;
import de.kevloe.vibecloud.protocol.TemplateManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Startet und stoppt Server (PLAN.md Abschnitt 6).
 *
 * <p>Alle Befehle gehen als {@code NodeCommand} durch den Rueckkanal des zustaendigen
 * Wrappers. Der Master macht selbst keine Prozesse auf - das war der Hauptgrund fuer den
 * Rewrite.
 */
public final class ServerService {

    private static final Logger LOG = LoggerFactory.getLogger(ServerService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerGroupRepository groups;
    private final ServerRegistry registry;
    private final ServerNameGenerator names;
    private final StaticBindingRepository bindings;
    private final ServerHistoryRepository history;
    private final NodeRegistry nodes;
    private final TemplateStore templates;
    private final AuditLog audit;
    private final MasterConfig.Servers config;
    private final ServerSessionStore sessions;

    public ServerService(ServerGroupRepository groups, ServerRegistry registry,
                         ServerNameGenerator names, StaticBindingRepository bindings,
                         ServerHistoryRepository history, NodeRegistry nodes,
                         TemplateStore templates, AuditLog audit, MasterConfig.Servers config,
                         ServerSessionStore sessions) {
        this.groups = groups;
        this.registry = registry;
        this.names = names;
        this.bindings = bindings;
        this.history = history;
        this.nodes = nodes;
        this.templates = templates;
        this.audit = audit;
        this.config = config;
        this.sessions = sessions;
    }

    /**
     * Startet einen weiteren Server der Gruppe.
     *
     * @param actor wer das ausgeloest hat ({@code CONSOLE}, {@code SCHEDULER}, Spielername)
     * @return Ergebnis mit Servernamen oder Begruendung
     */
    public StartResult start(String groupName, String actor) {
        Optional<ServerGroup> found = groups.find(groupName);
        if (found.isEmpty()) {
            return StartResult.failure("Gruppe " + groupName + " existiert nicht");
        }
        ServerGroup group = found.get();

        List<CloudServer> active = registry.activeOfGroup(groupName);
        if (active.size() >= group.maxOnline()) {
            return StartResult.failure("Gruppe " + groupName + " ist bei max_online ("
                                       + group.maxOnline() + ")");
        }

        if (group.staticGroup()) {
            return startStatic(group, active, actor);
        }
        return startDynamic(group, actor);
    }

    /**
     * Statische Server: Die Bindung entscheidet, nicht die Auslastung. Fuer jede noch nicht
     * laufende Bindung wird deren Node angesprochen; gibt es noch keine Bindung, wird
     * einmalig eine angelegt.
     */
    private StartResult startStatic(ServerGroup group, List<CloudServer> active, String actor) {
        Set<String> running = active.stream().map(CloudServer::name).collect(Collectors.toSet());

        for (StaticBindingRepository.Binding binding : bindings.ofGroup(group.name())) {
            if (running.contains(binding.serverName())) {
                continue;
            }
            if (!nodes.isConnected(binding.node())) {
                // Genau hier NICHT ausweichen - die Welt liegt auf diesem Node.
                LOG.warn("{} wartet auf Node {} (nicht verbunden). Der Server wird NICHT "
                         + "woanders gestartet, weil seine Welt dort liegt.",
                        binding.serverName(), binding.node());
                registry.put(new CloudServer(binding.serverName(), group.name(), group.platform(),
                        binding.node(), binding.port(), ServerState.WAITING_FOR_NODE, true,
                        java.time.Instant.now(), 0, group.maxPlayers()));
                continue;
            }
            return dispatchStart(group, binding.serverName(), binding.node(), binding.port(), actor);
        }

        if (bindings.ofGroup(group.name()).size() >= group.maxOnline()) {
            return StartResult.failure("Alle statischen Server der Gruppe " + group.name()
                                       + " sind gebunden; die zugehoerigen Nodes sind nicht da");
        }

        // Neue Bindung: einmalig wie bei dynamischen Servern waehlen und festschreiben.
        Optional<String> node = chooseNode(group);
        if (node.isEmpty()) {
            return StartResult.failure(noNodeReason(group));
        }
        Set<String> taken = new HashSet<>(registry.takenNames());
        bindings.all().forEach(binding -> taken.add(binding.serverName()));

        String name = names.nextName(group, node.get(), taken);
        int port = choosePort(group, node.get());
        bindings.bind(name, group.name(), node.get(), port);
        return dispatchStart(group, name, node.get(), port, actor);
    }

    private StartResult startDynamic(ServerGroup group, String actor) {
        Optional<String> node = chooseNode(group);
        if (node.isEmpty()) {
            return StartResult.failure(noNodeReason(group));
        }
        String name = names.nextName(group, node.get(), registry.takenNames());
        int port = choosePort(group, node.get());
        return dispatchStart(group, name, node.get(), port, actor);
    }

    private StartResult dispatchStart(ServerGroup group, String name, String node, int port,
                                      String actor) {
        TemplateManifest manifest;
        try {
            manifest = templates.buildManifest(group);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return StartResult.failure("Vorbereitung unterbrochen");
        } catch (Exception exception) {
            LOG.error("Dateisatz fuer Gruppe {} konnte nicht zusammengestellt werden",
                    group.name(), exception);
            return StartResult.failure("Dateisatz nicht bereit: " + exception.getMessage());
        }

        CloudServer server = ServerRegistry.newServer(name, group, node, port);
        registry.put(server);

        // Einmal-Secret hinterlegen, bevor der Befehl rausgeht: Das Plugin kann sich sonst
        // schneller anmelden, als der Master sein eigenes Secret kennt.
        String secret = generateSecret();
        sessions.registerSecret(name, node, secret);

        StartServer.Builder command = StartServer.newBuilder()
                .setServerName(name)
                .setGroupName(group.name())
                .setPlatform(toProto(group))
                .setPort(port)
                .setMemoryMb(group.memoryMb())
                .addAllJvmFlags(group.jvmFlags())
                .setStaticServer(group.staticGroup())
                .setManifestId(manifest.getManifestId())
                .setConnectSecret(secret)
                .setForwardingSecret(forwardingSecret);

        boolean sent = nodes.send(node, NodeCommand.newBuilder()
                .setCommandId(UUID.randomUUID().toString())
                .setStartServer(command)
                .build());

        if (!sent) {
            registry.remove(name);
            return StartResult.failure("Node " + node + " ist nicht erreichbar");
        }

        history.recordStart(name, group.name(), node);
        audit.record(actor, "server.start", name,
                Map.of("group", group.name(), "node", node, "port", port));
        LOG.info("{} wird auf Node {} gestartet (Port {}, {} MB)",
                name, node, port, group.memoryMb());
        return StartResult.success(name, node, port);
    }

    /**
     * Velocity-Forwarding-Secret, das Proxy und Gameserver gemeinsam brauchen
     * (PLAN.md Abschnitt 13). Wird einmal erzeugt und an jeden Server verteilt.
     */
    private String forwardingSecret = "";

    public void setForwardingSecret(String secret) {
        this.forwardingSecret = secret;
    }

    public boolean stop(String serverName, String actor, String reason) {
        Optional<CloudServer> found = registry.find(serverName);
        if (found.isEmpty()) {
            return false;
        }
        CloudServer server = found.get();
        registry.updateState(serverName, ServerState.STOPPING);

        boolean sent = nodes.send(server.node(), NodeCommand.newBuilder()
                .setCommandId(UUID.randomUUID().toString())
                .setStopServer(StopServer.newBuilder()
                        .setServerName(serverName)
                        .setGraceSeconds(config.stopGraceSeconds)
                        .setReason(reason))
                .build());

        audit.record(actor, "server.stop", serverName, Map.of("reason", reason));
        return sent;
    }

    /** Beim Ende eines Servers aufraeumen, damit Token nicht gueltig bleiben. */
    public void forgetSession(String serverName) {
        sessions.forget(serverName);
    }

    public boolean kill(String serverName, String actor) {
        Optional<CloudServer> found = registry.find(serverName);
        if (found.isEmpty()) {
            return false;
        }
        audit.record(actor, "server.kill", serverName);
        return nodes.send(found.get().node(), NodeCommand.newBuilder()
                .setCommandId(UUID.randomUUID().toString())
                .setKillServer(KillServer.newBuilder()
                        .setServerName(serverName)
                        .setReason("von " + actor + " abgeschossen"))
                .build());
    }

    /** Schreibt eine Zeile in die Standardeingabe des Servers. */
    public boolean execute(String serverName, String commandLine, String actor) {
        Optional<CloudServer> found = registry.find(serverName);
        if (found.isEmpty()) {
            return false;
        }
        audit.record(actor, "server.command", serverName, Map.of("command", commandLine));
        return nodes.send(found.get().node(), NodeCommand.newBuilder()
                .setCommandId(UUID.randomUUID().toString())
                .setExecuteCommand(ExecuteCommand.newBuilder()
                        .setServerName(serverName)
                        .setCommandLine(commandLine))
                .build());
    }

    /**
     * Waehlt einen Node fuer einen neuen Server: erlaubte Nodes der Gruppe, verbunden,
     * genug freier Arbeitsspeicher - und darunter der am wenigsten belegte.
     */
    private Optional<String> chooseNode(ServerGroup group) {
        Map<String, ServerGroup> allGroups = groups.findAll().stream()
                .collect(Collectors.toMap(ServerGroup::name, Function.identity()));

        boolean isProxy = group.platform() == ServerPlatformType.VELOCITY;

        return nodes.listAll().stream()
                .filter(NodeInfo::enabled)
                .filter(NodeInfo::connected)
                .filter(node -> group.allowsNode(node.name()))
                // Zwei Proxys auf einem Node wuerden sich um Port 25565 streiten,
                // und der zweite wuerde endlos neu starten.
                .filter(node -> !isProxy || !hasProxy(node.name()))
                .filter(node -> {
                    long used = registry.usedMemoryOnNode(node.name(), allGroups);
                    return node.maxMemoryMb() - used >= group.memoryMb();
                })
                .min(Comparator.comparingDouble(node -> {
                    long used = registry.usedMemoryOnNode(node.name(), allGroups);
                    return node.maxMemoryMb() <= 0 ? 1.0 : (double) used / node.maxMemoryMb();
                }))
                .map(NodeInfo::name);
    }

    /** Laeuft auf diesem Node schon ein Proxy? */
    private boolean hasProxy(String node) {
        return registry.onNode(node).stream()
                .filter(CloudServer::isActive)
                .anyMatch(server -> server.platform() == ServerPlatformType.VELOCITY);
    }

    /** Erklaert, warum kein Node passt - sonst steht da nur "geht nicht". */
    private String noNodeReason(ServerGroup group) {
        List<NodeInfo> all = nodes.listAll();
        if (all.isEmpty()) {
            return "Es ist kein Node angelegt (node add <name>)";
        }
        if (all.stream().noneMatch(NodeInfo::connected)) {
            return "Kein Node ist verbunden";
        }
        if (!group.allowedNodes().isEmpty()
            && all.stream().filter(NodeInfo::connected)
                    .noneMatch(node -> group.allowsNode(node.name()))) {
            return "Keiner der erlaubten Nodes " + group.allowedNodes() + " ist verbunden";
        }
        if (group.platform() == ServerPlatformType.VELOCITY) {
            return "Auf jedem erlaubten Node laeuft schon ein Proxy (Port "
                   + config.proxyPort + " kann nur einmal belegt werden)";
        }
        return "Kein Node hat noch " + group.memoryMb() + " MB frei";
    }

    /**
     * Port fuer einen neuen Server.
     *
     * <p>Proxys bekommen den festen oeffentlichen Port, Gameserver einen freien aus dem
     * internen Bereich. Beides zu vermischen waere ein Betriebsfehler: Der interne
     * Bereich ist per Firewall auf die Proxy-IPs begrenzt, ein Proxy dort waere fuer
     * Spieler unerreichbar.
     */
    private int choosePort(ServerGroup group, String node) {
        if (group.platform() == ServerPlatformType.VELOCITY) {
            return config.proxyPort;
        }
        Set<Integer> used = registry.portsOnNode(node);
        bindings.all().stream()
                .filter(binding -> binding.node().equals(node))
                .forEach(binding -> used.add(binding.port()));

        for (int port = config.portRangeStart; port <= config.portRangeEnd; port++) {
            if (!used.contains(port)) {
                return port;
            }
        }
        throw new IllegalStateException("Keine freien Ports auf Node " + node + " im Bereich "
                                        + config.portRangeStart + "-" + config.portRangeEnd);
    }

    /**
     * Einmal-Secret, mit dem sich das Plugin dieses Servers am Master anmeldet.
     * Gilt nur einmal und verfaellt (PLAN.md Abschnitt 13). Genutzt ab M3.
     */
    private static String generateSecret() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static ServerPlatform toProto(ServerGroup group) {
        return switch (group.platform()) {
            case PAPER -> ServerPlatform.SERVER_PLATFORM_PAPER;
            case VELOCITY -> ServerPlatform.SERVER_PLATFORM_VELOCITY;
            case MINESTOM -> ServerPlatform.SERVER_PLATFORM_MINESTOM;
        };
    }

    public record StartResult(boolean started, String serverName, String node, int port,
                              String reason) {

        static StartResult success(String serverName, String node, int port) {
            return new StartResult(true, serverName, node, port, null);
        }

        static StartResult failure(String reason) {
            return new StartResult(false, null, null, 0, reason);
        }
    }
}
