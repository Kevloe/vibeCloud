package de.kevloe.vibecloud.api.server;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Eine Servergruppe (PLAN.md Abschnitt 8).
 *
 * @param staticGroup  statische Server behalten Verzeichnis und Node, dynamische werden
 *                     nach dem Stopp geloescht
 * @param allowedNodes leer = jeder Node ist erlaubt
 * @param startPercent ab dieser Auslastung in Prozent wird nachgestartet, 0 = aus
 * @param idleTimeout  Sekunden, die ein leerer Server ueber {@code minOnline} hinaus laufen darf
 * @param namePattern  muss {@code %id%} enthalten
 * @param jarSource    {@code paper}, {@code velocity}, {@code custom:<url>} oder
 *                     {@code template} (Jar liegt schon im Gruppen-Template)
 */
public record ServerGroup(
        String name,
        ServerPlatformType platform,
        boolean staticGroup,
        int minOnline,
        int maxOnline,
        int maxPlayers,
        int memoryMb,
        List<String> jvmFlags,
        List<String> allowedNodes,
        int startPercent,
        int idleTimeout,
        String namePattern,
        String template,
        String mcVersion,
        String jarSource,
        boolean fallback,
        int joinPriority,
        boolean maintenance,
        int priority) {

    /**
     * Vorgaben fuer eine neue Gruppe.
     *
     * <p>An einer Stelle, weil es zwei Wege gibt, eine Gruppe anzulegen: {@code group
     * create} in der Konsole und die REST-Schnittstelle. Zwei Saetze Vorgaben waeren
     * zwei Verhaltensweisen - und der Unterschied faellt erst auf, wenn ein Server mit
     * falschem Speicher startet.
     *
     * <p>Velocity-Proxys sind immer statisch und nie Fallback-Ziel, Gameserver
     * standardmaessig dynamisch (PLAN.md Abschnitt 11).
     */
    public static ServerGroup defaults(String name, ServerPlatformType platform,
                                       String template) {
        boolean proxy = platform == ServerPlatformType.VELOCITY;

        return new ServerGroup(
                name, platform, proxy,
                1, proxy ? 1 : 2,
                100,
                proxy ? 512 : 2048,
                java.util.List.of(), java.util.List.of(),
                0, 300,
                // %id% ist Pflicht - sonst hiessen alle Server der Gruppe gleich.
                "%group%-%id%",
                template,
                "latest",
                proxy ? "velocity"
                        : platform == ServerPlatformType.MINESTOM ? "template" : "paper",
                !proxy && platform == ServerPlatformType.PAPER,
                100, false, 0);
    }

    /** Dieselbe Gruppe mit einer anderen Version - fuer {@code group create ... <version>}. */
    public ServerGroup withMcVersion(String version) {
        return new ServerGroup(name, platform, staticGroup, minOnline, maxOnline, maxPlayers,
                memoryMb, jvmFlags, allowedNodes, startPercent, idleTimeout, namePattern,
                template, version, jarSource, fallback, joinPriority, maintenance, priority);
    }

    /** Erlaubte Zeichen im Servernamen - Velocity begrenzt zusaetzlich auf 32 Zeichen. */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_#.-]+");
    public static final int MAX_SERVER_NAME_LENGTH = 32;

    public ServerGroup {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Gruppenname fehlt");
        }
        if (!namePattern.contains("%id%")) {
            throw new IllegalArgumentException(
                    "name_pattern muss %id% enthalten, sonst waeren alle Server gleich benannt");
        }
        if (maxOnline < minOnline) {
            throw new IllegalArgumentException("max_online darf nicht kleiner als min_online sein");
        }
        if (memoryMb < 128) {
            throw new IllegalArgumentException("memory_mb ist zu klein: " + memoryMb);
        }
    }

    /**
     * Prueft ein Namensmuster, bevor eine Gruppe angelegt wird.
     *
     * @return Fehlermeldung oder {@code null} wenn alles passt
     */
    public static String validateNamePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "Muster fehlt";
        }
        if (!pattern.contains("%id%")) {
            return "Muster muss %id% enthalten";
        }
        String probe = pattern
                .replace("%group%", "gruppe")
                .replace("%id%", "99")
                .replace("%node%", "node-a");
        if (!VALID_NAME.matcher(probe).matches()) {
            return "Nur Buchstaben, Zahlen und _ # . - sind erlaubt (ergibt sonst: " + probe + ")";
        }
        if (probe.length() > MAX_SERVER_NAME_LENGTH) {
            return "Ergibt zu lange Namen (" + probe.length() + " Zeichen, erlaubt sind "
                   + MAX_SERVER_NAME_LENGTH + " - Velocity-Grenze)";
        }
        return null;
    }

    public boolean allowsNode(String node) {
        return allowedNodes.isEmpty() || allowedNodes.contains(node);
    }
}
