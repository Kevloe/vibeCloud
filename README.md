# vibeCloud

Eine Minecraft-Cloud mit Master/Wrapper-Architektur: Ein Master verwaltet Server über
mehrere Root-Server hinweg, hält Spielerdaten, Ränge und Rechte zentral und lädt Features
als austauschbare Module — ohne dass dafür der Core angefasst wird.

Java 25 · Gradle 9 · gRPC über TLS · PostgreSQL 17 · Velocity 4.2 · Paper 26.2

> **Stand:** In Entwicklung. Der Core läuft — Proxy und Gameserver starten unter
> Cloud-Kontrolle, beide Plugins verbinden sich, Ränge und Rechte werden verteilt. Was
> dabei **nicht** mit einem echten Minecraft-Client geprüft werden konnte, steht weiter
> unten unter [Was nicht geprüft ist](#was-nicht-geprüft-ist). Die Liste ist bewusst
> vollständig.

---

## Was es tut

- **Server über mehrere Roots verteilen.** Der Master entscheidet, der Wrapper auf dem
  jeweiligen Root startet die Prozesse. Fällt der Master aus, laufen die Gameserver weiter
  und werden beim Reconnect adoptiert.
- **Servergruppen statt Einzelserver.** `min_online`, `max_online`, Auslastungsschwelle,
  Leerlauf-Timeout, Namensmuster — eine Gruppe beschreibt, wie viele Server wovon laufen
  sollen.
- **Templates inhaltsadressiert.** Dateien werden über ihren Hash abgeglichen und per
  Hardlink ausgelegt, statt jedes Mal kopiert zu werden.
- **Spieler, Ränge und Rechte zentral.** Ein Spieler hat genau einen Rang; Rechte-Sets
  kombiniert man über Vererbung. Regeln kennen Kontexte (global, pro Gruppe, pro Server)
  und können ablaufen.
- **Module zur Laufzeit laden und entladen.** Eigener ClassLoader, topologische
  Ladereihenfolge, eigene Flyway-Migrations, eigene Texte, eigene Befehle. Mitgeliefert:
  `punishment` (Bans, Mutes, Kicks, Verwarnungen, Historie).
- **Dashboard und REST-Schnittstelle.** Dieselben Rechte wie im Spiel, kein zweites
  Rechtesystem.
- **Dateizugriff per SFTP.** In das Verzeichnis eines statischen Servers und in die
  Templates der Gruppen - mit WinSCP, FileZilla oder jedem anderen SFTP-Programm. Wer
  wohin darf, sagen auch hier die Rechte aus dem Spiel.

## Aufbau

```
common/vibecloud-protocol    .proto-Dateien und generierte gRPC-Stubs
common/vibecloud-common      Zeit, Nachrichten-Bundles, TLS-Hilfen
common/vibecloud-api         Rechte-Auswertung, Server-Modelle, Events, Outbox
common/vibecloud-sftp        Der SFTP-Zugang, den Master und Wrapper beide anbieten
master/vibecloud-master      Der Master: gRPC-Server, DB, Scheduler, Konsole, REST
wrapper/vibecloud-wrapper    Läuft auf jedem Root, startet und überwacht die Prozesse
platform/vibecloud-velocity  Proxy-Plugin (Login-Gate, Backend-Registrierung, Befehle)
platform/vibecloud-paper     Gameserver-Plugin (Rechte-Bridge, Anzeige)
platform/vibecloud-minestom  Bibliothek für Minestom-Server
modules/module-api           Was ein Modul sieht — und nur das
modules/module-punishment    Strafen als Modul, mit Paper-Bundle
modules/module-example       Vorlage für eigene Module
dashboard/                   Vite + React + Tailwind, wird von Javalin ausgeliefert
build-logic/                 Convention-Plugins, damit jedes Modul sagt, was für es gilt
```

Die Abhängigkeitsrichtung ist strikt:

```
protocol + common  ->  api  ->  { master, wrapper, platform/*, module-api }  ->  modules/*
```

Ein Modul sieht `module-api`, `vibecloud-api`, `common` und `protocol` — niemals
Master-Interna. Das ist keine Konvention, sondern wird vom ClassLoader durchgesetzt.

`vibecloud-sftp` steht daneben: Es hängt von nichts im Projekt ab, und nur Master und
Wrapper hängen davon ab. In `vibecloud-api` gehört es nicht — dort hängen die
Plattform-Plugins dran, und ein SSH-Server hat in einem Paper-Plugin nichts verloren.

## Voraussetzungen

| | |
|---|---|
| **Java 25** | Der Gradle-Build lädt sich das JDK bei Bedarf selbst nach. Zum Starten der fertigen Jars mit `java -jar` muss Java 25 aber auf dem `PATH` liegen. |
| **Docker** | Nur für die Entwicklung: PostgreSQL und Redis kommen aus `docker-compose.yml`. Im Betrieb läuft PostgreSQL eigenständig. |
| **Node.js** | Nur für das Dashboard (`dashboard/`, gebaut mit npm — nicht mit Gradle). |

Auf jedem Root-Server, der Gameserver betreiben soll, muss ebenfalls **Java 25** liegen,
nicht nur die JRE für die Gameserver selbst.

## Bauen

```bash
./gradlew build          # alles inklusive Tests und Fat-Jars
./gradlew test           # nur die Tests
./gradlew versions       # Gradle- und Java-Version dieses Builds

cd dashboard && npm ci && npm run build
```

Die Fat-Jars liegen danach unter `master/vibecloud-master/build/libs/CloudMaster.jar`
und `wrapper/vibecloud-wrapper/build/libs/CloudWrapper.jar`.

Alle Bibliotheksversionen stehen zentral in `gradle/libs.versions.toml`, die Java-Version
in `gradle.properties` — nie direkt in einem Build-Skript.

## Erste Inbetriebnahme

```bash
docker compose up -d
```

1. **Master starten.** Er legt `config.json` an und beendet sich. Werte prüfen.
2. **Master erneut starten.** Er migriert die Datenbank, erzeugt `tls/master-cert.pem`
   und zeigt den Zertifikat-Fingerprint — ohne den verbindet sich kein Wrapper.
3. In der Master-Konsole:
   ```
   node add <name> [maxMemoryMb] [ip,ip]
   ```
   Der Befehl gibt eine fertige `wrapper.json` samt Token und Fingerprint aus.
   **Das Token wird nur einmal angezeigt.**
4. Diese `wrapper.json` auf den Root legen, Wrapper starten. `node list` zeigt ihn online.

Gestartet wird entweder über Gradle (nutzt dessen Toolchain) oder direkt:

```bash
./gradlew :master:vibecloud-master:run
./gradlew :wrapper:vibecloud-wrapper:run

java --enable-native-access=ALL-UNNAMED -jar CloudMaster.jar
```

`--enable-native-access=ALL-UNNAMED` gehört immer dazu — sonst warnt Java 25 bei jedem
Start wegen der nativen Netty-Bibliotheken.

> Läuft der Master aus seinem Projektordner (`gradlew run`), legt er seine Laufzeitdaten
> dort ab: `config.json`, `tls/`, `secrets/`, `jars/`, `templates/`, `logs/`. Die
> `.gitignore` hält das alles draußen — insbesondere den privaten TLS-Schlüssel und den
> JWT-Schlüssel.

## Dashboard

Ein Zugang entsteht **nur** über `acp create <spieler>` in der Master-Konsole und hängt an
der Minecraft-UUID. Es gibt keine Registrierung, und es gibt kein zweites Rechtesystem:
Wer `vibecloud.command.server.stop` im Spiel nicht hat, darf es im Dashboard auch nicht.
Fällt `vibecloud.dashboard.login` weg, verschwindet der Zugang von selbst.

Ausgeliefert wird das gebaute Dashboard von Javalin selbst, aus `master/dashboard/`.

| Seite | Was man dort tut |
|---|---|
| **Übersicht** | Zahlen, laufende Server, wer online ist |
| **Server** | Starten, stoppen, hart beenden. Je Server das Live-Log — und darunter ein Eingabefeld für Befehle an seine Konsole |
| **Gruppen** | Anlegen, alle Felder in einem Formular bearbeiten, einzeln in Wartung setzen |
| **Nodes** | Anlegen, sperren, Token erneuern |
| **Spieler** | Suchen, Rang setzen (auch auf Zeit), einzelne Rechte, auf einen Server schicken |
| **Ränge** | Anlegen, bearbeiten, Vererbung, einzelne Rechte samt `perm check` |
| **Sprachen** | Sprachen anlegen und übersetzen — die Texte der Cloud und die jedes Moduls |
| **Dateien** | Der eigene SFTP-Zugang: Verbindungsdaten je Server und Template, Passwort erzeugen, in WinSCP öffnen |
| **Module** | Die `config.json` eines Moduls bearbeiten |
| **Einstellungen** | Die änderbaren Felder der `config.json` des Masters |

Unten in der Seitenleiste sitzt der Schalter für den **Wartungsmodus** des ganzen
Netzwerks — von jeder Seite aus zu sehen und zu schalten, sofern man das Recht dazu hat.

Ein paar Dinge, die man wissen sollte:

- **`perm check` sagt nicht nur ja oder nein**, sondern welche Regel entschieden hat und
  welche überstimmt wurden. Dort steht meist der Denkfehler.
- **Befehle an eine Serverkonsole brauchen ein eigenes Recht**
  (`vibecloud.command.screen.send`). Mitlesen (`vibecloud.command.screen`) deckt es
  bewusst nicht ab: In der Konsole eines Gameservers gibt es `op`.
- **Eigene Modul-Texte liegen in `modules/<id>/messages/<sprache>.yml`**, und dort steht
  nur, was vom Text im Modul-JAR abweicht. Alles andere folgt weiter den Updates des Moduls.
- **Einstellungen wirken nach einem Neustart des Masters.** Nicht änderbar sind die
  Felder, mit denen man sich selbst aussperren könnte (Datenbank, gRPC-Adresse und -Port,
  HTTP) — die bleiben in der Datei. In der Konsole gibt es dasselbe als `cloud config`.
- **Wartung gibt es global und je Gruppe**, nicht je Server. Die Server einer Gruppe sind
  austauschbar; einen davon zu sperren hieße nur, dass der Proxy den nächsten nimmt.

Die REST-Schnittstelle ist **standardmäßig aus** (`http.enabled` in der `config.json`).
Ein offener Port mit Schreibzugriff auf das Netzwerk soll eine Entscheidung sein, kein
Nebeneffekt.

## SFTP

Dateien liegen an zwei Orten, und entsprechend gibt es zwei SFTP-Zugänge — mit demselben
Zugang und demselben Passwort, nur unter verschiedenen Adressen:

| | Statischer Server | Template einer Gruppe |
|---|---|---|
| **Was** | Das Verzeichnis des Servers: Welt, Plugin-Daten | Die Vorlage, aus der jeder Server der Gruppe entsteht |
| **Wo** | Auf dem Node (Wrapper) | Auf dem Master |
| **Einschalten** | `"sftp": { "enabled": true }` in der `wrapper.json` | `"sftp": { "enabled": true }` in der `config.json` |
| **Port** | 2222 | 2223 |
| **Benutzername** | `<spieler>.<server>` | `<spieler>.<gruppe>` |
| **Recht** | `vibecloud.sftp.<server>` | `vibecloud.sftp.template.<gruppe>` |

Beides ist **standardmäßig aus**. `vibecloud.sftp.*` deckt alles ab.

```
sftp create <spieler>      Zugang anlegen - das Passwort wird einmal angezeigt
sftp password <spieler>    neues Passwort, das alte gilt sofort nicht mehr
sftp remove <spieler>
sftp list
sftp servers               wohin man sich verbindet
```

Im Dashboard erzeugt sich jeder sein Passwort selbst (Seite „Dateien") und sieht dort
Adresse, Port, Benutzername und den Fingerprint des Host-Schlüssels, nach dem das
SFTP-Programm beim ersten Verbinden fragt.

**Einen dynamischen Server bearbeitet man nicht — man bearbeitet sein Template.** Sein
Verzeichnis entsteht bei jedem Start neu und ist nach dem Stopp weg. Eine Änderung am
Template wirkt auf jeden neu gestarteten Server der Gruppe; laufende behalten ihre Dateien.

Das gilt auch für statische Server: Template-Dateien werden bei **jedem** Start über das
Serververzeichnis gelegt. Wer dort eine `server.properties` ändert, die auch im Template
liegt, hat nach dem nächsten Start wieder die aus dem Template. Bestand hat, was das
Template nicht kennt — Welten, Plugin-Daten, eigene Plugins.

> **Das SFTP-Recht ist ein Recht für Administratoren.** Wer Dateien hochladen darf, kann
> ein Plugin hochladen, und das läuft beim nächsten Start als Code auf dem Node. Die
> Sitzung ist in ihr Verzeichnis eingesperrt; das hält Versehen ab, keinen Angreifer mit
> diesem Recht.

Das SFTP-Passwort ist ein eigenes und nicht das des Dashboards: Es geht bei der Anmeldung
im Klartext durch den Wrapper. Der Master erzeugt es, niemand wählt eines. Angeboten wird
nur SFTP — keine Shell, keine Befehle, keine Port-Weiterleitung.

## Konfiguration und Geheimnisse

Nichts davon gehört ins Repo, und nichts davon ist im Repo:

| Datei | Was drinsteht |
|---|---|
| `config.json` | Master: Datenbank, Ports, HTTP, SFTP |
| `wrapper.json` | Wrapper: Node-Token, Zertifikat-Fingerprint, SFTP |
| `tls/master-key.pem` | Privater Schlüssel des Masters |
| `secrets/jwt.key` | Signiert die Dashboard-Token |
| `secrets/forwarding.secret` | Gemeinsames Geheimnis Proxy ↔ Gameserver |
| `secrets/sftp-host.key` | Host-Schlüssel des SFTP-Zugangs, je einer bei Master und Wrapper |

Zeitangaben werden durchgehend in UTC gespeichert und erst bei der Anzeige umgerechnet.

## Minecraft-Versionen

Minecraft nutzt seit der 26er-Reihe ein neues Schema: nach `1.21.11` kam `26.1` → `26.2` →
`26.3`. **`26.2` ist die aktuelle stabile Reihe**, `26.3` gibt es bisher nur als Beta.

Die Version gehört zur Servergruppe (`mc_version`), nicht in den Core. `mc_version: latest`
heißt dabei „neueste Version mit **stabilen** Builds" — sonst liefe ein Netzwerk unbemerkt
auf einer Beta.

Bedrock-Spieler über Geyser und Floodgate: siehe [`docs/GEYSER.md`](docs/GEYSER.md). Dafür
gibt es keinen Code in der Cloud, das sind normale Plugins über die Templates.

## Was nicht geprüft ist

Hier steht ehrlich, was noch niemand gesehen hat. Alles davon kompiliert, ist durch Tests
abgedeckt und läuft im Betrieb an — aber ohne Minecraft-Client oder ohne Linux-Root fehlt
der letzte Beweis.

- **Die Paper-Rechte-Bridge.** `PermissibleInjector` tauscht per Reflection das
  `PermissibleBase`-Feld von `CraftHumanEntity` aus. Dass das Feld existiert, ist mit
  `javap` am entpackten Server-Jar geprüft. Die Injektion in einen echten Spieler nicht.
  Scheitert sie, läuft der Server weiter und warnt einmal.
- **Der Chat-Filter des Punishment-Moduls.** Paper lädt das Bundle und meldet „Mute-Filter
  aktiv", der Master beantwortet die Mute-Abfrage korrekt — eine echte Chatnachricht fehlt.
- **Die Firewall-Verwaltung** (`NftablesFirewall`). Läuft nur unter Linux mit nftables;
  geprüft ist der erzeugte Regelsatz, nicht seine Wirkung. Vor dem Produktivbetrieb von
  außen auf einen Port aus 30000–30999 verbinden — das muss abgelehnt werden.
- **Das Zusammenspiel mehrerer Module.** Exporte, Abhängigkeiten und Ladereihenfolge sind
  nur durch Tests abgedeckt; im Betrieb gibt es bisher genau ein Modul.
- **Das Dashboard am Telefon.** Geprüft am Breitbild. Die Schublade der Seitenleiste unter
  1024 px hat nie jemand gesehen.
- **SFTP unter Linux und von außen.** Durchgespielt ist es unter Windows auf einem
  Rechner, mit dem OpenSSH-Client: hochladen, holen, löschen, falsches Passwort, fehlendes
  Recht, Server eines anderen Nodes. Nicht geprüft sind ein Linux-Root, der Weg durch eine
  echte Firewall und Programme wie WinSCP oder FileZilla. Auch der Knopf „In WinSCP öffnen"
  ist nie geklickt worden — ob Windows die `sftp://`-Adresse an WinSCP übergibt, hängt an
  dessen Installation (Einstellungen → Integration).
- **Befehle an eine Serverkonsole aus dem Dashboard.** Geprüft gegen einen
  Stellvertreter-Server, den die Cloud aus einem Template gestartet hat — der Befehl stand
  danach in seinem Log. Ein echter Paper-Server war nicht dabei, und das Eingabefeld selbst
  hat im Browser noch niemand gesehen.
- **Was ein Proxy aus dem Wartungsmodus macht.** Der Schalter im Dashboard speichert,
  protokolliert und meldet an die Proxys — beim Prüfen war aber keiner verbunden. Dasselbe
  gilt für die Wartung einer einzelnen Gruppe.
- **Rang-Dialog, Übersetzungs-Seite und Einstellungen im Browser.** Tests grün, Endpunkte
  antworten — angemeldet durchgeklickt hat sie niemand.

### Bekannter Befund

Ein Gameserver, der einen **Master-Neustart** überlebt, bekommt sein Plugin nicht mehr
verbunden: Das Einmal-Secret ist verbraucht, und der Master hält `pending` und `sessions`
nur im Speicher. Der Wrapper meldet den Server weiter als `RUNNING` und der Master
adoptiert ihn — aber ohne Plugin gibt es keine Spielerzahlen und keine Befehle dorthin.
Die Zusage „ein Master-Neustart stoppt keine Gameserver" stimmt damit nur zur Hälfte.

Lösungsweg steht fest: Der Wrapper hat das Secret jedes Servers noch in dessen
`vibecloud-connection.json` und kann es im `FullState` mitschicken. Umgesetzt ist es nicht.

## Mitarbeiten

- `PLAN.md` ist die verbindliche Quelle für Architektur und Entscheidungen.
- `CLAUDE.md` hält fest, **warum** etwas so ist, wie es ist — besonders dort, wo die
  naheliegende Lösung die falsche war. Vor einer Änderung lohnt ein Blick hinein.
- Spielersichtbare Texte immer als Nachrichten-Schlüssel, nie als String im Code.
- Logging über SLF4J. Nie `System.out`, nie `printStackTrace()`, nie `§`-Farbcodes.
- Der Master prüft **jede** Berechtigung selbst neu. Plugins übertragen nur, *wer* etwas
  angefordert hat — niemals ein „darf das"-Flag.

## Lizenz

**GNU General Public License, Version 3** — der vollständige Text steht in
[`LICENSE`](LICENSE).

```
vibeCloud — Minecraft-Cloud mit Master/Wrapper-Architektur
Copyright (C) 2026 Kevin Löber

Dieses Programm ist freie Software: Sie können es weitergeben und/oder
verändern unter den Bedingungen der GNU General Public License, Version 3,
wie von der Free Software Foundation veröffentlicht.

Die Veröffentlichung erfolgt in der Hoffnung, dass es nützlich sein wird,
aber OHNE JEDE GEWÄHRLEISTUNG — sogar ohne die implizite Gewährleistung
der MARKTREIFE oder der EIGNUNG FÜR EINEN BESTIMMTEN ZWECK.
```

Verbindlich ist der englische Text in `LICENSE`; die Fassung oben ist nur eine
Lesehilfe. Übersetzungen der GPL sind von der Free Software Foundation ausdrücklich
nicht als rechtsgültig anerkannt.

**Warum GPL-3.0:** `paper-api` und `velocity-api` stehen selbst unter GPL-3.0, und die
beiden Plugin-Module kompilieren dagegen. Mit einer permissiven Lizenz wäre offen, ob
die ausgelieferten Plugin-Jars damit vereinbar sind — eine Frage, die in der
Minecraft-Szene seit Jahren großzügig gehandhabt, aber nie geklärt wurde. Dieselbe
Lizenz wie die Abhängigkeiten lässt sie gar nicht erst entstehen.

Was das praktisch heißt: Betreiben, ändern und auf dem eigenen Netzwerk einsetzen
darf jeder, ohne irgendetwas offenlegen zu müssen — die GPL greift erst beim
**Weitergeben**. Wer eine veränderte Fassung an andere herausgibt, muss den
vollständigen Quellcode unter derselben Lizenz mitliefern.
