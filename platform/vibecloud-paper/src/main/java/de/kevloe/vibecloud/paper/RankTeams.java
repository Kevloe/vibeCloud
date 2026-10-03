package de.kevloe.vibecloud.paper;

import de.kevloe.vibecloud.protocol.RankData;

/**
 * Wie das Scoreboard-Team eines Rangs heisst.
 *
 * <p>Eine eigene Datei, weil das Legacy-Plugin ({@code platform/vibecloud-paper-legacy})
 * sie mitkompiliert: Die Reihenfolge in der Tab-Liste soll auf einem 1.16-Server dieselbe
 * sein wie auf einem 26.2-Server. <b>Muss mit Java 17 kompilieren.</b>
 */
final class RankTeams {

    /** Teamnamen sind auf 16 Zeichen begrenzt - deshalb Praefix kurz und Rang gekuerzt. */
    private static final int MAX_TEAM_NAME = 16;

    private RankTeams() {
    }

    /**
     * Teamname mit invertiertem Gewicht als Praefix.
     *
     * <p>Minecraft sortiert Teams alphabetisch. {@code 999 - weight} dreht die Reihenfolge,
     * sodass ein hoeherer Rang weiter oben in der Tab-Liste steht.
     */
    static String teamName(RankData rank) {
        int inverted = Math.max(0, Math.min(999, 999 - rank.getWeight()));
        String prefix = String.format("%04d_", inverted);
        String id = rank.getId();
        int room = MAX_TEAM_NAME - prefix.length();
        return prefix + (id.length() > room ? id.substring(0, room) : id);
    }
}
