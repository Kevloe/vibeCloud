# Bedrock-Spieler (Geyser + Floodgate)

Entschieden in PLAN.md 16.12: Bedrock wird von Anfang an unterstuetzt. Es gibt dafuer
**keinen Code in der Cloud** - Geyser und Floodgate sind normale Plugins, die ueber die
Templates verteilt werden. Dokumentiert, weil die Reihenfolge der Schritte wichtig ist.

## Einrichtung

1. Geyser-Velocity und Floodgate-Velocity nach `templates/global/proxy/plugins/` legen.
   Damit bekommt **jeder** Proxy sie automatisch, auch ein spaeter dazukommender.

2. Floodgate-Paper nach `templates/global/server/plugins/` legen - fuer Skins und die
   Floodgate-API auf den Gameservern.

3. Den Geyser-Port freigeben: **UDP 19132** auf den Proxy-Nodes. Die Cloud verwaltet nur
   TCP-Ports; UDP muss in der Firewall des Roots offen sein.

4. In `templates/global/proxy/plugins/Geyser-Velocity/config.yml`:
   `remote.auth-type: floodgate`

## Was die Cloud dafuer schon mitbringt

- `players.platform` unterscheidet `JAVA` und `BEDROCK`
- `players.xuid` haelt die Bedrock-ID
- Floodgate-UUIDs (`00000000-0000-0000-000X-...`) sind der normale Primaerschluessel;
  Raenge, Permissions und Strafen funktionieren damit unveraendert
- Der Master speichert Bedrock-Namen **ohne** den Floodgate-Prefix (`.Kevin` -> `Kevin`),
  damit `/ban Kevin` fuer beide Plattformen gleich funktioniert

## Noch nicht geplant

Account-Verknuepfung (Global Linking), damit ein Spieler mit Java- und Bedrock-Client
denselben Datensatz nutzt. Braucht eine `player_links`-Tabelle - siehe PLAN.md Abschnitt 11.
