package de.kevloe.vibecloud.master.player;

import de.kevloe.vibecloud.api.event.EventBus;
import de.kevloe.vibecloud.api.event.events.PlayerPreLoginEvent;
import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.permission.RankRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * Spielerdaten und das Login-Gate (PLAN.md Abschnitt 6 und 10a).
 *
 * <p>Beim Login passiert hier zweierlei: Der Datensatz wird angelegt oder aktualisiert, und
 * das abbrechbare {@link PlayerPreLoginEvent} wird ausgeloest. Letzteres ist die Stelle, an
 * der ab M6 Bans greifen - <b>der Core kennt keine Bans</b>.
 */
public final class PlayerService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerService.class);

    private final PlayerRepository players;
    private final RankRepository ranks;
    private final PermissionService permissions;
    private final EventBus events;

    public PlayerService(PlayerRepository players, RankRepository ranks,
                         PermissionService permissions, EventBus events) {
        this.players = players;
        this.ranks = ranks;
        this.permissions = permissions;
        this.events = events;
    }

    /**
     * Prueft, ob ein Spieler joinen darf, und legt seinen Datensatz an.
     *
     * <p>Reihenfolge ist Absicht: Erst der Datensatz, dann das Event. Ein Ban-Modul muss
     * den Spieler nachschlagen koennen, auch wenn er zum ersten Mal verbindet.
     *
     * @return das Ergebnis mit Begruendungs-Schluessel, falls abgelehnt
     */
    public LoginResult login(UUID uuid, String name, String ip, String platform, String xuid) {
        String defaultRank = ranks.findDefault()
                .map(RankRepository.Rank::id)
                .orElse(null);

        if (defaultRank == null) {
            // Ohne Default-Rang kann kein Spieler angelegt werden. Das waere ein
            // Installationsfehler, nicht ein Spielerproblem - deshalb deutlich loggen.
            LOG.error("Es gibt keinen Default-Rang. Kein Spieler kann angelegt werden. "
                      + "Pruefen: SELECT * FROM ranks WHERE is_default;");
            return LoginResult.denied("error.internal");
        }

        PlayerRepository.PlayerRecord record =
                players.recordLogin(uuid, name, platform, xuid, ip, defaultRank);

        // Abgelaufene Raenge auch hier pruefen, nicht nur im Minuten-Scheduler: Der Master
        // koennte zwischendurch aus gewesen sein (PLAN.md Abschnitt 9).
        if (record.hasExpiredRank()) {
            permissions.expireRanks();
        }

        PlayerPreLoginEvent event = events.postSync(
                new PlayerPreLoginEvent(uuid, name, ip, platform));

        if (event.isCancelled()) {
            LOG.info("{} wurde abgelehnt: {}", name, event.cancelReasonKey());
            return new LoginResult(false, event.cancelReasonKey(), event.placeholders(),
                    record.locale());
        }
        return new LoginResult(true, null, java.util.Map.of(), record.locale());
    }

    public void logout(UUID uuid, String lastServer, long sessionSeconds) {
        players.recordLogout(uuid, lastServer, sessionSeconds);
    }

    public Optional<PlayerRepository.PlayerRecord> find(UUID uuid) {
        return players.find(uuid);
    }

    public Optional<PlayerRepository.PlayerRecord> findByName(String name) {
        return players.findByName(name);
    }

    /**
     * Speichert die Sprachwahl.
     *
     * @return {@code false} wenn es diese Sprache nicht gibt
     */
    public boolean setLocale(UUID uuid, String locale, java.util.Set<String> available) {
        if (!available.contains(locale)) {
            return false;
        }
        players.setLocale(uuid, locale);
        return true;
    }

    public java.util.List<PlayerRepository.RankChange> rankHistory(UUID uuid, int limit) {
        return players.rankHistory(uuid, limit);
    }

    /** Hash der zuletzt benutzten IP - Grundlage fuer IP-Bans. */
    public Optional<String> lastIpHash(UUID uuid) {
        return players.lastIpHash(uuid);
    }

    /** Namensvorschlaege fuer die Vervollstaendigung. */
    public java.util.List<String> suggestNames(String prefix, int limit) {
        return players.suggestNames(prefix, limit);
    }

    /** Wie viele Spieler die Cloud kennt. */
    public int countAll() {
        return players.countAll();
    }

    public PlayerRepository repository() {
        return players;
    }

    /**
     * Ergebnis einer Login-Pruefung.
     *
     * @param reasonKey    Nachrichten-Schluessel, falls abgelehnt
     * @param placeholders Platzhalter fuer die Meldung
     * @param locale       gespeicherte Sprache, {@code null} = noch keine Wahl
     */
    public record LoginResult(
            boolean allowed,
            String reasonKey,
            java.util.Map<String, String> placeholders,
            String locale) {

        static LoginResult denied(String reasonKey) {
            return new LoginResult(false, reasonKey, java.util.Map.of(), null);
        }
    }
}
