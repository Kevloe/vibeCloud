package de.kevloe.vibecloud.master.http;

import de.kevloe.vibecloud.master.permission.PermissionService;
import de.kevloe.vibecloud.master.security.Jwt;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wer darf was an der REST-Schnittstelle (PLAN.md Abschnitt 12 und 13).
 *
 * <p>Zwei Arten von Aufrufern, und sie werden verschieden geprueft:
 *
 * <ul>
 *   <li><b>API-Token</b> (Maschine, etwa ein Discord-Bot): hat Rechte-Bereiche wie
 *       {@code servers.read}.</li>
 *   <li><b>Dashboard-Benutzer</b> (Mensch, JWT): hat <b>die Rechte aus dem Spiel</b>. Wer
 *       in-game keinen Server stoppen darf, kann es im Dashboard auch nicht - kein zweites
 *       Rechtesystem, das auseinanderlaeuft.</li>
 * </ul>
 *
 * <p>Jeder Endpunkt nennt deshalb beides: den Bereich fuer Token und den Rechte-Knoten fuer
 * Menschen. Die Pruefung passiert bei <b>jedem</b> Aufruf neu, nicht nur beim Login - und
 * sie vergleicht die {@code sessionVersion} aus dem Token mit der in der Datenbank. Damit
 * wirkt ein Rechte-Entzug binnen Sekunden statt erst beim naechsten Anmelden.
 */
public final class ApiAuth {

    private static final Logger LOG = LoggerFactory.getLogger(ApiAuth.class);

    /** Attribut, unter dem der geprueft Aufrufer am Context haengt. */
    private static final String PRINCIPAL = "vibecloud.principal";

    /**
     * Anfragen je Aufrufer und Minute.
     *
     * <p>Grosszuegig fuer ein Dashboard, knapp genug, dass ein durchgedrehtes Skript nicht
     * den Master beschaeftigt (PLAN.md Abschnitt 13).
     */
    private static final int REQUESTS_PER_MINUTE = 120;

    private final ApiTokenService tokens;
    private final AccountService accounts;
    private final PermissionService permissions;
    private final Jwt jwt;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ApiAuth(ApiTokenService tokens, AccountService accounts,
                   PermissionService permissions, Jwt jwt) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.permissions = permissions;
        this.jwt = jwt;
    }

    /**
     * Prueft eine Anfrage.
     *
     * @param scope      was ein API-Token dafuer braucht
     * @param permission was ein Mensch dafuer im Spiel braucht
     * @return false, wenn die Antwort schon geschrieben wurde
     */
    public boolean require(Context context, String scope, String permission) {
        Optional<Principal> principal = resolve(context);

        if (principal.isEmpty()) {
            // Dieselbe Antwort fuer "nichts mitgeschickt" und "ungueltig": Ein
            // Unterschied waere eine Auskunft darueber, welche Zugaenge es gibt.
            fail(context, HttpStatus.UNAUTHORIZED, "Anmeldung fehlt oder ist ungueltig");
            return false;
        }
        Principal caller = principal.get();

        if (!rateLimit(caller.id())) {
            fail(context, HttpStatus.TOO_MANY_REQUESTS,
                    "Zu viele Anfragen - hoechstens " + REQUESTS_PER_MINUTE + " pro Minute");
            return false;
        }

        // Solange das Start-Passwort gilt, ist nur das Setzen eines neuen erlaubt. Sonst
        // koennte jemand mit dem im Chat gezeigten Passwort dauerhaft arbeiten.
        if (caller.mustChangePassword()) {
            fail(context, HttpStatus.FORBIDDEN,
                    "Bitte zuerst ein eigenes Passwort setzen");
            return false;
        }

        if (!caller.allows(scope, permission, permissions)) {
            fail(context, HttpStatus.FORBIDDEN, caller.isUser()
                    ? "Dir fehlt das Recht " + permission
                    : "Dem Token fehlt das Recht " + scope);
            return false;
        }
        context.attribute(PRINCIPAL, caller);
        return true;
    }

    /**
     * Prueft nur die Anmeldung, ohne ein Recht.
     *
     * <p>Fuer die Endpunkte, die zu jedem Zugang gehoeren: das eigene Profil und das Setzen
     * des eigenen Passworts. Letzteres muss auch gehen, solange das Start-Passwort gilt -
     * sonst kaeme man nie heraus.
     */
    public Optional<Principal> authenticated(Context context) {
        Optional<Principal> principal = resolve(context);
        if (principal.isEmpty()) {
            fail(context, HttpStatus.UNAUTHORIZED, "Anmeldung fehlt oder ist ungueltig");
            return Optional.empty();
        }
        if (!rateLimit(principal.get().id())) {
            fail(context, HttpStatus.TOO_MANY_REQUESTS, "Zu viele Anfragen");
            return Optional.empty();
        }
        context.attribute(PRINCIPAL, principal.get());
        return principal;
    }

    /**
     * Ob der Aufrufer der laufenden Anfrage auch dieses Recht haette.
     *
     * <p>Fuer Antworten, die der Oberflaeche sagen, was sie anbieten soll - einen Schalter,
     * den man nicht umlegen darf, zeigt sie besser gar nicht erst als benutzbar. Das ist
     * eine Auskunft, keine Pruefung: Der Endpunkt, der wirklich schaltet, ruft weiter
     * {@link #require}.
     *
     * @return false auch dann, wenn die Anfrage noch nicht geprueft wurde
     */
    public boolean allows(Context context, String scope, String permission) {
        return of(context)
                .map(caller -> caller.allows(scope, permission, permissions))
                .orElse(false);
    }

    /** Der geprueft Aufrufer der laufenden Anfrage. */
    public static Optional<Principal> of(Context context) {
        return Optional.ofNullable(context.attribute(PRINCIPAL));
    }

    // ---------------------------------------------------------------- Innereien

    private Optional<Principal> resolve(Context context) {
        String header = context.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String presented = header.substring("Bearer ".length()).trim();

        // Ein JWT hat drei durch Punkte getrennte Teile, ein API-Token zwei. Deshalb
        // zuerst der eindeutige Fall.
        if (presented.chars().filter(character -> character == '.').count() == 2) {
            return asUser(presented);
        }
        return tokens.verify(presented).map(Principal::ofToken);
    }

    /**
     * Prueft ein Dashboard-Token.
     *
     * <p>Drei Dinge muessen stimmen: Signatur und Ablauf (im {@link Jwt}), der Zugang muss
     * noch existieren und nicht gesperrt sein, und die {@code sessionVersion} muss zu der
     * in der Datenbank passen. Das Letzte ist der Hebel, mit dem ein Rechte-Entzug oder
     * ein Passwortwechsel laufende Sitzungen sofort beendet.
     */
    private Optional<Principal> asUser(String token) {
        Optional<Jwt.Claims> claims = jwt.verify(token, "access");
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        Optional<AccountService.Account> account = accounts.find(claims.get().uuid());

        if (account.isEmpty()) {
            LOG.debug("Token von {} - Zugang existiert nicht mehr", claims.get().username());
            return Optional.empty();
        }
        if (account.get().disabled()) {
            return Optional.empty();
        }
        if (account.get().sessionVersion() != claims.get().sessionVersion()) {
            LOG.debug("Token von {} ist veraltet (Version {} statt {})",
                    account.get().username(), claims.get().sessionVersion(),
                    account.get().sessionVersion());
            return Optional.empty();
        }
        return Optional.of(Principal.ofUser(account.get()));
    }

    private boolean rateLimit(String id) {
        Bucket bucket = buckets.compute(id, (key, existing) ->
                existing == null || existing.isStale() ? new Bucket() : existing);
        return bucket.count.incrementAndGet() <= REQUESTS_PER_MINUTE;
    }

    static void fail(Context context, HttpStatus status, String message) {
        context.status(status).json(Map.of("error", message));
    }

    /**
     * Ein geprueft Aufrufer - entweder ein Token oder ein Mensch.
     *
     * <p>Als ein Typ und nicht zwei, weil jeder Endpunkt beide bedienen muss. Die
     * Unterscheidung steckt nur in {@link #allows}.
     */
    public record Principal(
            UUID uuid,
            String name,
            java.util.List<String> scopes,
            boolean isUser,
            boolean mustChangePassword) {

        static Principal ofToken(ApiTokenService.ApiToken token) {
            return new Principal(null, "API:" + token.name(), token.scopes(), false, false);
        }

        static Principal ofUser(AccountService.Account account) {
            return new Principal(account.uuid(), account.username(), java.util.List.of(),
                    true, account.mustChangePassword());
        }

        /** Fuer Protokoll und Drosselung. */
        String id() {
            return isUser ? "user:" + uuid : "token:" + name;
        }

        boolean allows(String scope, String permission, PermissionService permissions) {
            if (!isUser) {
                return scopes.contains(ApiTokenService.SCOPE_ALL) || scopes.contains(scope);
            }
            // Ein Mensch bringt seine Rechte aus dem Spiel mit - dieselbe Auswertung,
            // dieselben Wildcards.
            return permissions.has(uuid, permission);
        }
    }

    /** Anfragen eines Aufrufers innerhalb einer Minute. */
    private static final class Bucket {

        private final Instant start = Instant.now();
        private final AtomicInteger count = new AtomicInteger();

        boolean isStale() {
            return Duration.between(start, Instant.now()).toSeconds() >= 60;
        }
    }
}
