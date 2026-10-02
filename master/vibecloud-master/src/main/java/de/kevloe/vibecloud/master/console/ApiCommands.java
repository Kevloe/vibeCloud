package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.common.Times;
import de.kevloe.vibecloud.master.audit.AuditLog;
import de.kevloe.vibecloud.master.http.ApiTokenService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tokens fuer die REST-Schnittstelle verwalten (PLAN.md Abschnitt 12).
 *
 * <p>Nur in der Konsole: Ein Token wird genau einmal angezeigt, und wer eines anlegen darf,
 * kann damit alles, was sein Rechteumfang zulaesst. Das gehoert nicht in einen Chat, wo die
 * Zeile im Verlauf stehen bleibt und von Dritten mitgelesen werden kann.
 */
public final class ApiCommands implements CommandRegistry.Command {

    /** Bekannte Rechte - zugleich die Vorschlagsliste. */
    private static final List<String> SCOPES =
            List.of("*", "servers.read", "players.write");

    private final ApiTokenService tokens;
    private final AuditLog audit;

    public ApiCommands(ApiTokenService tokens, AuditLog audit) {
        this.tokens = tokens;
        this.audit = audit;
    }

    @Override
    public String name() {
        return "api";
    }

    @Override
    public String description() {
        return "Tokens der REST-Schnittstelle verwalten";
    }

    @Override
    public String usage() {
        return "api token add <name> <recht...> [tage] | api token list | api token remove <id>";
    }

    @Override
    public List<String> subCommands() {
        return List.of("token");
    }

    /** Nur Konsole: Das Token wird einmal angezeigt und darf nicht im Chat landen. */
    @Override
    public boolean availableInGame() {
        return false;
    }

    @Override
    public List<String> complete(List<String> args) {
        if (args.size() <= 1) {
            return List.of("token");
        }
        if (args.size() == 2) {
            return List.of("add", "list", "remove");
        }
        if (args.get(1).equals("remove") && args.size() == 3) {
            return tokens.all().stream().map(ApiTokenService.ApiTokenInfo::id).toList();
        }
        // add <name> <recht...> - ab der vierten Stelle Rechte.
        return args.get(1).equals("add") && args.size() >= 4 ? SCOPES : List.of();
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.size() < 2 || !args.getFirst().equalsIgnoreCase("token")) {
            out.error("Syntax: " + usage());
            return;
        }
        switch (args.get(1).toLowerCase(Locale.ROOT)) {
            case "add" -> add(out, args);
            case "list" -> list(out);
            case "remove" -> remove(out, args);
            default -> out.error("Syntax: " + usage());
        }
    }

    private void add(CommandOutput out, List<String> args) {
        if (args.size() < 4) {
            out.error("Syntax: api token add <name> <recht...> [tage]");
            out.info("Rechte: " + String.join(", ", SCOPES));
            return;
        }
        List<String> rest = args.subList(3, args.size());

        // Letztes Argument als Gueltigkeit, falls es eine Zahl ist.
        Instant expires = null;
        String last = rest.getLast();
        if (rest.size() > 1 && last.chars().allMatch(Character::isDigit)) {
            expires = Instant.now().plus(Duration.ofDays(Long.parseLong(last)));
            rest = rest.subList(0, rest.size() - 1);
        }

        List<String> unknown = rest.stream().filter(scope -> !SCOPES.contains(scope)).toList();
        if (!unknown.isEmpty()) {
            // Ein vertipptes Recht waere ein Token, das stillschweigend nichts darf.
            out.error("Unbekannte Rechte: " + String.join(", ", unknown));
            out.info("Moeglich: " + String.join(", ", SCOPES));
            return;
        }

        String token = tokens.create(args.get(2), rest, "CONSOLE", expires);
        audit.record("CONSOLE", "api.token.created", args.get(2),
                Map.of("scopes", String.join(",", rest)));

        out.success("Token '" + args.get(2) + "' angelegt"
                    + (expires == null ? "" : ", gueltig bis " + Times.format(expires)));
        out.info("");
        out.info(token);
        out.info("");
        out.warn("Das Token wird nur jetzt angezeigt. Verwendung:");
        out.info("  curl -H \"Authorization: Bearer <token>\" http://localhost:8080/api/v1/servers");
    }

    private void list(CommandOutput out) {
        List<ApiTokenService.ApiTokenInfo> all = tokens.all();
        if (all.isEmpty()) {
            out.info("Keine Tokens. Anlegen mit: api token add <name> <recht...>");
            return;
        }
        out.info(String.format("%-10s %-20s %-24s %-16s %s",
                "ID", "NAME", "RECHTE", "ZULETZT", "LAEUFT AB"));
        for (ApiTokenService.ApiTokenInfo token : all) {
            out.info(String.format("%-10s %-20s %-24s %-16s %s",
                    token.id(),
                    token.name(),
                    String.join(",", token.scopes()),
                    token.lastUsedAt() == null ? "nie" : Times.format(token.lastUsedAt()),
                    token.expiresAt() == null ? "nie" : Times.format(token.expiresAt())));
        }
    }

    private void remove(CommandOutput out, List<String> args) {
        if (args.size() < 3) {
            out.error("Syntax: api token remove <id>");
            return;
        }
        if (tokens.revoke(args.get(2))) {
            audit.record("CONSOLE", "api.token.revoked", args.get(2));
            out.success("Token " + args.get(2) + " zurueckgezogen.");
        } else {
            out.error("Kein Token mit der Id " + args.get(2));
        }
    }
}
