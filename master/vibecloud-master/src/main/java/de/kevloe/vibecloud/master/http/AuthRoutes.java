package de.kevloe.vibecloud.master.http;

import com.google.gson.Gson;
import de.kevloe.vibecloud.master.security.Jwt;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Anmeldung am Dashboard (PLAN.md Abschnitt 12).
 *
 * <p>Es gibt keine Registrierung - ein Zugang entsteht nur in-game ueber {@code /acp create}.
 * Hier wird er nur benutzt.
 *
 * <p>Zwei Token: ein kurzlebiges Zugangstoken fuer die Anfragen und ein Refresh-Token in
 * einem {@code HttpOnly}-Cookie. Das Zugangstoken bleibt im Speicher der Seite - so kommt
 * kein Skript auf der Seite an das langlebige heran, und ein Neuladen wirft niemanden
 * hinaus.
 */
final class AuthRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(AuthRoutes.class);
    private static final Gson GSON = new Gson();

    /** Name des Cookies mit dem Refresh-Token. */
    private static final String REFRESH_COOKIE = "vibecloud_refresh";

    private final AccountService accounts;
    private final ApiAuth auth;
    private final Jwt jwt;
    private final WsTickets tickets;
    private final boolean secureCookies;

    /**
     * @param secureCookies ob das Cookie nur ueber HTTPS gesendet werden darf. In der
     *                      Entwicklung laeuft das Dashboard ueber HTTP, dort muss es aus
     *                      sein - sonst kommt das Cookie nie an.
     */
    AuthRoutes(AccountService accounts, ApiAuth auth, Jwt jwt, WsTickets tickets,
               boolean secureCookies) {
        this.accounts = accounts;
        this.auth = auth;
        this.jwt = jwt;
        this.tickets = tickets;
        this.secureCookies = secureCookies;
    }

    void register(RoutesConfig routes) {
        routes.post("/api/v1/auth/login", this::login)
                .post("/api/v1/auth/refresh", this::refresh)
                .post("/api/v1/auth/logout", this::logout)
                .post("/api/v1/auth/password", this::changePassword)
                .get("/api/v1/auth/me", this::me)
                .post("/api/v1/auth/ws-ticket", this::wsTicket);
    }

    /** {@code POST /api/v1/auth/login} mit {@code {"username":"...","password":"..."}}. */
    private void login(Context context) {
        Map<?, ?> body = body(context);
        if (body == null) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, "Rumpf ist kein JSON");
            return;
        }
        String username = text(body, "username");
        String password = text(body, "password");

        if (username == null || password == null) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST,
                    "Felder 'username' und 'password' sind Pflicht");
            return;
        }

        AccountService.LoginResult result = accounts.login(username, password);

        if (result.isLocked()) {
            ApiAuth.fail(context, HttpStatus.TOO_MANY_REQUESTS,
                    "Zu viele Fehlversuche. Gesperrt bis "
                    + de.kevloe.vibecloud.common.Times.formatWithSeconds(result.lockedUntil()));
            return;
        }
        if (!result.isSuccess()) {
            // Immer dieselbe Antwort: Ein Unterschied zwischen "Name unbekannt" und
            // "Passwort falsch" waere eine Auskunft darueber, welche Zugaenge es gibt.
            ApiAuth.fail(context, HttpStatus.UNAUTHORIZED, "Anmeldung fehlgeschlagen");
            return;
        }

        AccountService.Account account = result.account();
        LOG.info("{} hat sich am Dashboard angemeldet", account.username());

        setRefreshCookie(context, jwt.refreshToken(account.uuid(), account.username(),
                account.sessionVersion()));

        context.json(session(account));
    }

    /**
     * {@code POST /api/v1/auth/refresh} - neues Zugangstoken aus dem Cookie.
     *
     * <p>Geprueft wird dabei auch die {@code sessionVersion}: Ein Rechte-Entzug beendet die
     * Sitzung damit auch dann, wenn der Browser nur noch das Refresh-Token hat.
     */
    private void refresh(Context context) {
        Optional<Jwt.Claims> claims = jwt.verify(context.cookie(REFRESH_COOKIE), "refresh");
        if (claims.isEmpty()) {
            clearRefreshCookie(context);
            ApiAuth.fail(context, HttpStatus.UNAUTHORIZED, "Sitzung abgelaufen");
            return;
        }
        Optional<AccountService.Account> account = accounts.find(claims.get().uuid());

        if (account.isEmpty() || account.get().disabled()
            || account.get().sessionVersion() != claims.get().sessionVersion()) {
            clearRefreshCookie(context);
            ApiAuth.fail(context, HttpStatus.UNAUTHORIZED, "Sitzung ist nicht mehr gueltig");
            return;
        }
        context.json(session(account.get()));
    }

    /** {@code POST /api/v1/auth/logout} - entwertet das Cookie dieses Browsers. */
    private void logout(Context context) {
        clearRefreshCookie(context);
        context.json(Map.of("ok", true));
    }

    /**
     * {@code POST /api/v1/auth/password} mit {@code {"password":"..."}}.
     *
     * <p>Der einzige Endpunkt, der auch mit einem Start-Passwort erreichbar ist - sonst
     * kaeme man aus dem Zwangswechsel nie heraus. Deshalb {@code authenticated} statt
     * {@code require}.
     */
    private void changePassword(Context context) {
        Optional<ApiAuth.Principal> principal = auth.authenticated(context);
        if (principal.isEmpty()) {
            return;
        }
        if (!principal.get().isUser()) {
            ApiAuth.fail(context, HttpStatus.FORBIDDEN,
                    "Ein API-Token hat kein Passwort");
            return;
        }
        Map<?, ?> body = body(context);
        String password = body == null ? null : text(body, "password");

        try {
            accounts.changePassword(principal.get().uuid(), password);
        } catch (IllegalArgumentException exception) {
            ApiAuth.fail(context, HttpStatus.BAD_REQUEST, exception.getMessage());
            return;
        }
        // Alle Sitzungen sind jetzt ungueltig, auch diese. Ein frisches Paar ausstellen,
        // damit der Benutzer nicht sofort wieder vor dem Login steht.
        AccountService.Account account = accounts.find(principal.get().uuid()).orElseThrow();
        setRefreshCookie(context, jwt.refreshToken(account.uuid(), account.username(),
                account.sessionVersion()));

        context.json(session(account));
    }

    /** {@code GET /api/v1/auth/me} - wer bin ich und was muss ich noch tun. */
    private void me(Context context) {
        Optional<ApiAuth.Principal> principal = auth.authenticated(context);
        if (principal.isEmpty()) {
            return;
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("name", principal.get().name());
        answer.put("user", principal.get().isUser());
        answer.put("mustChangePassword", principal.get().mustChangePassword());
        if (principal.get().isUser()) {
            answer.put("uuid", principal.get().uuid().toString());
        }
        context.json(answer);
    }

    /**
     * {@code POST /api/v1/auth/ws-ticket} - Eintrittskarte fuer einen WebSocket.
     *
     * <p>Ein Browser kann beim WebSocket-Aufbau keine Kopfzeilen setzen, also muesste das
     * Zugangstoken in die Adresse - und damit in Verlauf und Protokolle. Diese Karte gilt
     * 30 Sekunden und genau einmal.
     */
    private void wsTicket(Context context) {
        Optional<ApiAuth.Principal> principal = auth.authenticated(context);
        if (principal.isEmpty()) {
            return;
        }
        if (principal.get().mustChangePassword()) {
            ApiAuth.fail(context, HttpStatus.FORBIDDEN,
                    "Bitte zuerst ein eigenes Passwort setzen");
            return;
        }
        context.json(Map.of(
                "ticket", tickets.issue(principal.get().uuid(), principal.get().name()),
                "expiresInSeconds", tickets.lifetime().toSeconds()));
    }

    // ---------------------------------------------------------------- Hilfsmittel

    /** Zugangstoken und was die Oberflaeche sofort wissen muss. */
    private Map<String, Object> session(AccountService.Account account) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("token", jwt.accessToken(account.uuid(), account.username(),
                account.sessionVersion()));
        answer.put("expiresInSeconds", Jwt.ACCESS_LIFETIME.toSeconds());
        answer.put("name", account.username());
        answer.put("uuid", account.uuid().toString());
        answer.put("mustChangePassword", account.mustChangePassword());
        return answer;
    }

    private void setRefreshCookie(Context context, String token) {
        context.cookie(cookie(token, (int) Jwt.REFRESH_LIFETIME.toSeconds()));
    }

    private void clearRefreshCookie(Context context) {
        context.cookie(cookie("", 0));
    }

    /**
     * Das Cookie mit dem Refresh-Token.
     *
     * <p>{@code HttpOnly}, damit kein Skript es lesen kann, und {@code SameSite=Strict},
     * damit es bei Anfragen von fremden Seiten nicht mitgeschickt wird - das ersetzt einen
     * eigenen CSRF-Schutz fuer die Erneuerung.
     */
    private Cookie cookie(String value, int maxAge) {
        // (name, value, path, maxAge, secure, httpOnly, domain, sameSite)
        return new Cookie(REFRESH_COOKIE, value, "/api/v1/auth", maxAge, secureCookies,
                true, null, SameSite.STRICT);
    }

    private static Map<?, ?> body(Context context) {
        try {
            return GSON.fromJson(context.body(), Map.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String text(Map<?, ?> body, String key) {
        Object value = body.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
