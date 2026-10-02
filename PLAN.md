# vibeCloud — Architektur- und Tech-Stack-Plan

> **Hinweis:** Dieses Dokument wird als erster Schritt der Umsetzung nach
> `G:\Minecraft PRG\vibeCloud\PLAN.md` kopiert, damit du es direkt im Projekt anpassen kannst.
> Alles mit 🔧 markierte ist bewusst als Stellschraube gedacht.

---

## 1. Kontext

`G:\Minecraft PRG\vibeCloud` ist leer — ein Greenfield-Rewrite. Daneben liegt dein bisheriges
Projekt `G:\Minecraft PRG\cloud` (Java 21, Gradle, 14 Module) sowie CloudNet v3 als Referenz.

**Warum ein Rewrite statt Weiterentwicklung?** Die Altlasten aus `cloud/ARCHITECTURE.md` sind
strukturell, nicht kosmetisch:

| Problem im alten `cloud` | Konsequenz für vibeCloud |
|---|---|
| Kein Wrapper — Master startet Server selbst per `ProcessBuilder` | **Wrapper als eigene Komponente**, Multi-Root ist das zentrale neue Feature |
| Netty + `ObjectEncoder`/`java.io.Serializable` | **gRPC + Protobuf**, typsicher, versionierbar, kein Deserialisierungs-RCE |
| Pakettypen als Strings (`eventType.equals("PLAYER_LOGIN")`) | Generierte Stubs, Fehler zur Compile-Zeit |
| Hardcodierte Credentials im Code | Config-Dateien + Secrets, nichts im Repo |
| `new Thread()`, `printStackTrace()`, §-Codes im Terminal | Virtual Threads, SLF4J, ANSI/MiniMessage |
| Keine Migrations, 14 Tabellen per `CREATE TABLE IF NOT EXISTS` | Flyway-Migrations, auch pro Modul |

**Ziel:** Eine Cloud, die (a) Server über mehrere Root-Server verteilt verwaltet, (b) Spielerdaten,
Ränge und Permissions zentral hält und (c) Features als austauschbare Module lädt — ohne den
Core anzufassen. Den Anfang macht genau ein Modul (`punishment`); Party, Friends und der Rest
folgen erst, wenn der Core im echten Betrieb trägt.

---

## 2. Tech-Stack (entschieden)

| Bereich | Wahl | Begründung |
|---|---|---|
| Sprache | **Java 25 LTS** | Records, sealed interfaces, Pattern-Matching-switch, Virtual Threads, Structured Concurrency |
| Build | **Gradle 9 + Kotlin DSL**, Version Catalog, Convention-Plugins in `build-logic/` | Multi-Projekt ohne `mvn install`, zentrale Versionen |
| Transport Master↔Wrapper↔Plugins | **gRPC + Protobuf über TLS** | Bidirektionales Streaming, Schema-Versionierung, Code-Gen |
| Prozess-Isolation | **Native Prozesse** hinter `ServerRuntime`-Interface, Docker-Runtime später | Schneller Start, keine Docker-Pflicht; Abstraktion steht von Tag 1 |
| Datenbank | **PostgreSQL 17** + HikariCP + **Flyway** | JSONB für Modul-Daten, saubere Migrations |
| Cache / Pub-Sub | **Redis 7** (Lettuce) | Presence, Permission-Cache-Invalidierung, Cluster-Events |
| Proxy | **Velocity 4.2** | Adventure/MiniMessage, dynamische Server-Registrierung |
| Gameserver | **Paper 26.2** (Version pro Gruppe) + **Minestom** | Paper für Survival/Minigames, Minestom für Lobby |
| Bedrock | **Geyser + Floodgate** | Bedrock-Spieler über denselben Proxy, UDP 19132 |
| Konsole | **JLine 3** | Tab-Completion, Live-Log-Attach |
| REST/WS | **Javalin 6** + JWT | Dashboard, Discord-Bot, Automatisierung |
| Dashboard | **Vite + React + TypeScript**, TanStack Query, Tailwind, shadcn/ui | Eigener Projektteil, kein Gradle |
| Logging | SLF4J + Logback | Strukturiert, Log-Level statt §-Codes |
| Metriken | Micrometer → Prometheus-Endpoint | 🔧 optional, ab Phase M8 |
| Tests | JUnit 5, Testcontainers, gRPC in-process | Integrationstests ohne lokale DB-Installation |
| Package-Root | `de.kevloe.vibecloud` | Konsistent zu `de.kevloe.cloud` |

**Lokal vorhanden und geprüft:** JDK 23 (Gradle-Toolchain lädt 25 automatisch nach), Node 20,
Docker 28.

---

## 3. Architektur-Überblick

```
                        ┌───────────────────────────────────────────┐
                        │              vibeCloud MASTER             │
                        │  (eine JVM, der einzige Schreiber der DB) │
                        │                                           │
   Browser ──HTTPS──►   │  Javalin REST :8080  +  WebSocket         │
                        │  JLine-Konsole (stdin)                    │
                        │  ModuleManager  ─ lädt modules/*.jar      │
                        │  Scheduler  ─ wer startet wo?             │
                        │  PermissionService / PlayerService        │
                        │  gRPC-Server :5000 (TLS)                  │
                        └────┬──────────────────┬───────────────┬───┘
                             │ bidi stream      │               │
            ┌────────────────┘                  │               └──────────┐
            │                                   │                          │
     ┌──────▼───────┐                   ┌───────▼──────┐          ┌────────▼────────┐
     │ WRAPPER      │  Root-Server A    │ WRAPPER      │ Root B   │ Velocity-Proxy  │
     │ (JVM)        │                   │ (JVM)        │          │ (Plugin)        │
     │              │                   │              │          │                 │
     │ ServerRuntime│                   │ ServerRuntime│          │ Player-Gateway  │
     │  ├ lobby-1   │                   │  ├ bedwars-1 │          │ Server-Registry │
     │  ├ lobby-2   │                   │  └ bedwars-2 │          └─────────────────┘
     │  └ survival-1│                   │              │
     │ TemplateCache│                   │ TemplateCache│
     └──────────────┘                   └──────────────┘
            │                                   │
            └── Gameserver-Plugins (Paper/Minestom) verbinden sich
                ihrerseits per gRPC direkt zum Master :5000
                           │
            ┌──────────────┴──────────────┐
            │  PostgreSQL        Redis    │
            └─────────────────────────────┘
```

### Zwei Regeln, die alles vereinfachen

1. **Nur der Wrapper wählt, der Master nie.** Jeder Wrapper baut die Verbindung selbst auf und
   hält einen bidirektionalen Stream offen. Der Master schickt Befehle über den Rückkanal.
   → Keine Portfreigaben auf den Roots, NAT-tauglich, funktioniert über das Internet.
2. **Nur der Master schreibt in die Datenbank.** Wrapper und Plugins haben keine DB-Credentials.
   Alles läuft über gRPC-Calls. → Ein Ort für Caching, Validierung und Rechteprüfung.

---

## 4. Projektstruktur

```
vibeCloud/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml          # Version Catalog (alle Versionen zentral)
├── build-logic/                       # Convention-Plugins (java-25, shadow, protobuf)
├── docker-compose.yml                 # postgres + redis für lokale Entwicklung
├── PLAN.md                            # dieses Dokument
├── CLAUDE.md                          # Projektregeln für Claude Code
│
├── common/
│   ├── vibecloud-protocol/            # *.proto + generierte gRPC-Stubs
│   ├── vibecloud-api/                 # Interfaces, Records, Events — was Module sehen
│   └── vibecloud-common/              # Config, Logging, IDs, Utils
│
├── master/vibecloud-master/           # Master-JVM (shadowJar → CloudMaster.jar)
├── wrapper/
│   ├── vibecloud-wrapper/             # Wrapper-JVM (shadowJar → CloudWrapper.jar)
│   └── vibecloud-wrapper.service      # systemd-Vorlage für die Roots
│
├── platform/
│   ├── vibecloud-velocity/            # Velocity-Plugin
│   ├── vibecloud-paper/               # Paper-Plugin
│   └── vibecloud-minestom/            # Minestom-Bibliothek
│
├── modules/
│   ├── module-api/                    # Module-Interface, Manifest, Context
│   └── module-punishment/             # Ban / Mute / Kick / Warn — vorerst das einzige Modul
│
└── dashboard/                         # Vite + React (npm, kein Gradle)
```

**Abhängigkeitsrichtung — strikt einhalten:**
`protocol` → `api` → `common` → {`master`, `wrapper`, `platform/*`} → `modules/*`
Ein Modul sieht **nur** `module-api` + `vibecloud-api`, niemals Master-Interna.

**API-Trennung (wie im alten Projekt, aber sauberer):** Jedes `platform/*`-Modul importiert
ausschließlich seine eigene Plattform-API. Kein Bukkit in Velocity, kein Velocity in Minestom.

---

## 5. Protokoll (`common/vibecloud-protocol/src/main/proto/`)

| Datei | Inhalt |
|---|---|
| `common.proto` | `Uuid`, `ServerId`, `PlayerRef`, `Timestamp`, `ResourceUsage` |
| `node.proto` | `NodeService` — Wrapper ↔ Master |
| `server.proto` | Server-Definitionen, Gruppen, Zustände |
| `template.proto` | Template-Sync (chunked, hash-basiert) |
| `player.proto` | Login/Logout, Spielerdaten, Permissions |
| `punishment.proto` | 🔧 Modul-eigene Protos liegen beim Modul, nicht im Core |

### Kernservice

```protobuf
service NodeService {
  rpc Register(RegisterRequest) returns (RegisterResponse);
  // Der Dauerkanal: Wrapper sendet Events, Master antwortet mit Befehlen
  rpc Control(stream NodeEvent) returns (stream NodeCommand);
  rpc PullTemplate(TemplateRequest) returns (stream TemplateChunk);
  rpc PushConsole(stream ConsoleLine) returns (Ack);
}
```

`NodeCommand` ist ein `oneof`: `StartServer`, `StopServer`, `KillServer`, `ExecuteCommand`,
`SyncTemplate`, `UpdateConfig`, `Shutdown`.
`NodeEvent` ist ein `oneof`: `Heartbeat`, `ServerStateChanged`, `ServerLog`, `ResourceReport`.

> **Versionierung:** Feldnummern nie wiederverwenden, nie umordnen. `api_version` im
> `RegisterRequest`; der Master lehnt inkompatible Wrapper mit klarer Fehlermeldung ab.

### Update-Reihenfolge

Der Master akzeptiert `api_version` der **aktuellen und der vorherigen** Version. Daraus folgt
die Regel für jedes Update: **erst den Master, dann die Wrapper.** So kannst du die Roots
einzeln nacheinander aktualisieren, ohne das Netzwerk anzuhalten — während ein Root noch alt
läuft, redet er trotzdem mit dem neuen Master.

Umgekehrt verweigert ein neuerer Wrapper die Verbindung zu einem älteren Master, statt sich
mit halb verstandenen Nachrichten durchzuschlagen. Die Fehlermeldung nennt beide Versionen.
Über zwei Versionen hinweg gibt es keine Kompatibilität — dann ist ein Wrapper so alt, dass
Raten teurer wäre als ein Update.

---

## 6. Master

`master/vibecloud-master/src/main/java/de/kevloe/vibecloud/master/`

| Komponente | Aufgabe |
|---|---|
| `VibeCloudMaster` | Bootstrap, Lifecycle, Dependency-Graph der Services |
| `grpc/NodeServiceImpl` | Wrapper-Verbindungen, Stream-Registry |
| `grpc/AuthInterceptor` | Einziger Prüfpunkt: Anmeldung und Befugnis je Nachricht (Abschnitt 13) |
| `node/NodeRegistry` | Bekannte Wrapper, Ressourcen, Heartbeat-Timeouts |
| `server/ServerRegistry` | Alle laufenden Server + Zustände |
| `server/ServerGroupService` | Gruppen/Tasks aus DB, CRUD |
| `scheduler/PlacementScheduler` | Alle 3 s: Soll-Ist-Abgleich, Platzierungsentscheidung |
| `template/TemplateStore` | Templates zentral, Hash-Index für Sync, Jar-Download je MC-Version |
| `acp/AccountService` | Dashboard-Accounts, Argon2id-Hashing, Passwort-Zwangswechsel |
| `player/PlayerService` | Spielerdaten, Name-History, Playtime, Presence |
| `permission/PermissionService` | Ränge, Vererbung, Auflösung, Cache-Invalidierung |
| `module/ModuleManager` | Laden/Entladen von `modules/*.jar` |
| `event/EventBus` | Typisierte Events, lokal + über Redis clusterweit — Katalog in Abschnitt 10a |
| `command/CommandRegistry` | Konsolen- und In-Game-Commands (gemeinsame Abstraktion) |
| `console/JLineTerminal` | Terminal, `screen <server>` hängt sich an Live-Logs |
| `http/HttpServer` | Javalin: REST + WebSocket |
| `db/Database` | HikariCP + Flyway, Einzel-Master-Sperre (siehe unten) |

### Placement-Scheduler

```
alle 3 s (eigener Scheduler-Thread, dispatcht nur):
  für jede ServerGroup:
    online = Server im Zustand RUNNING|STARTING
    wenn online < minOnline            → starte (minOnline - online)
    wenn Auslastung > startPercent
         und online < maxOnline        → starte 1 weiteren
    wenn online > minOnline
         und Server leer > idleTimeout → stoppe den ältesten   (nur DYNAMIC)

  Node-Auswahl:
    DYNAMIC → erlaubte Nodes der Gruppe
              ∩ Node hat freien RAM ≥ group.memory
              → der mit dem niedrigsten (belegter RAM / max RAM)
    STATIC  → der in static_server_bindings festgeschriebene Node.
              Noch keine Bindung? Einmalig wie bei DYNAMIC wählen und festschreiben.
              Node offline? NICHT ausweichen — Server bleibt aus, Warnung ausgeben.
```

Der Scheduler-Callback blockiert nie: Start-Befehle gehen als `NodeCommand` in den Stream raus,
die eigentliche Arbeit macht der Wrapper.

### Statische Server sind an ihren Node gebunden

Ein `STATIC`-Server wird beim ersten Start einmalig auf einen freien Node gelegt; diese
Zuordnung landet in `static_server_bindings` und bleibt dann fest. Welt, Plugin-Daten und
Logs liegen dauerhaft auf genau diesem Root.

**Daraus folgt bewusst:** Fällt der Node aus, startet der Server **nicht** woanders neu. Das
wäre schlimmer als ein Ausfall — der Server käme mit einer leeren Welt hoch, und Spieler würden
auf einem frischen Survival landen, während die echte Welt auf dem ausgefallenen Root liegt.
Der Master meldet stattdessen `WAITING_FOR_NODE` in Konsole und Dashboard und startet
automatisch, sobald der Node zurück ist.

Umziehen geht nur bewusst und mit angehaltenem Server: `server move <name> <node>` kopiert das
Verzeichnis über den Master zum neuen Node und schreibt die Bindung um. 🔧 Erst ab M8 nötig.

### Nur ein Master darf laufen

Der Plan setzt auf genau einen Master (16.1). Zwei Master gleichzeitig auf derselben Datenbank
wären aber kein offensichtlicher Fehler, sondern ein stiller: Beide Scheduler würden Server
nachstarten, beide dieselben Nodes ansprechen, und du hättest doppelte Lobbys ohne zu wissen,
warum.

Deshalb nimmt der Master beim Start eine Advisory-Lock in PostgreSQL
(`pg_try_advisory_lock`). Bekommt er sie nicht, beendet er sich sofort mit der Meldung, dass
schon ein Master auf dieser Datenbank läuft. Die Sperre hängt an der Verbindung und löst sich
beim Absturz von allein — ein Neustart ist also nie blockiert. Kostet eine Zeile und verhindert
einen Fehler, den man sonst stundenlang sucht.

### Templates und Server-Jars

Zwei Ebenen, mehr nicht (siehe 16.4):

```
templates/                         (zentral beim Master)
├── global/
│   ├── server/                    → in jeden Paper-/Minestom-Server
│   └── proxy/                     → in jeden Velocity-Proxy
├── lobby/                         → nur Gruppen mit template: lobby
└── bedwars/
jars/
└── paper-1.21.4-<build>.jar       vom Master geladen, per Hash an die Wrapper verteilt
```

**Kopierreihenfolge beim Start:** `global/*` → Gruppen-Template (darf überschreiben) →
Modul-Bundles nach `plugins/` → `server.jar` aus dem Jar-Store.

**Jar-Beschaffung:** `mc_version` + `jar_source` der Gruppe bestimmen das Jar. Bei `latest`
fragt der Master die PaperMC-API (`fill.papermc.io/v3`) nach dem neuesten stabilen Build, lädt ihn
**einmal** herunter und legt ihn im Jar-Store ab. Die Wrapper ziehen ihn wie jede andere
Template-Datei über den Hash — kein Download pro Root, keine Versions-Drift zwischen Nodes.

| `jar_source` | Herkunft |
|---|---|
| `paper` | PaperMC-API, Version aus `mc_version` |
| `velocity` | PaperMC-API (Velocity-Projekt) |
| `custom:<url>` | Einmaliger Download von dieser Adresse |
| `template` | **Kein Download** — das Jar liegt schon im Gruppen-Template |

`template` ist der Fall für **Minestom**: Deine Lobby ist ein selbst gebautes Fat-Jar, das du
nach `templates/lobby/server.jar` legst. Der Master lädt dafür nichts herunter und `mc_version`
bleibt leer. Genauso nutzbar für Forks oder selbst gepatchte Server-Jars.

**Änderungen an einer Gruppe** (`mc_version`, `memory_mb`, JVM-Flags, Template) wirken immer
erst auf **neu gestartete** Server. Laufende bleiben unangetastet — ein `server restart`
übernimmt sie einzeln, ohne dass die ganze Gruppe gleichzeitig durchstartet.

### Server-Benennung

`ServerNameGenerator` setzt `group.name_pattern` ein (`%group%`, `%id%`, `%node%`); `%id%`
kommt aus `server_counter` und wird pro Gruppe hochgezählt. Bei `DYNAMIC`-Gruppen werden freie
IDs wiederverwendet, damit die Zahlen nicht endlos wachsen. Validierung beim Anlegen der
Gruppe, nicht erst beim Start.

---

## 7. Wrapper

`wrapper/vibecloud-wrapper/src/main/java/de/kevloe/vibecloud/wrapper/`

| Komponente | Aufgabe |
|---|---|
| `VibeCloudWrapper` | Bootstrap, liest `wrapper.json` (Master-Host, Node-Name, Token, maxMemory) |
| `grpc/MasterConnection` | Verbindung + Reconnect mit exponentiellem Backoff |
| `runtime/ServerRuntime` | **Interface** — `prepare / start / stop / kill / sendCommand / logs` |
| `runtime/ProcessServerRuntime` | `ProcessBuilder`, eigene Prozessgruppe, stdout/stderr-Pump |
| `runtime/DockerServerRuntime` | 🔧 Phase M8 |
| `template/TemplateCache` | Lokaler Cache nach Content-Hash, zieht nur fehlende Dateien |
| `server/LocalServerManager` | Verzeichnisse anlegen, Ports vergeben, Aufräumen |
| `metrics/ResourceReporter` | CPU/RAM des Hosts und je Prozess |

### Ablauf eines Server-Starts auf dem Wrapper

```
NodeCommand.StartServer { serverId, group, template-hash, port, memory, env, secret }
  1. Arbeitsverzeichnis  servers/<group>-<nr>/  anlegen
  2. TemplateCache.ensure(hash)       → fehlende Dateien per PullTemplate streamen
  3. Template + globale Plugins + Modul-Bundles hineinkopieren (Hardlinks wo möglich)
  4. vibecloud-connection.json schreiben: { masterHost, masterPort, serverId, secret }
  5. ProcessBuilder: java <jvmFlags> -Xmx<memory>M -jar server.jar --port <port>
  6. stdout/stderr → PushConsole-Stream
  7. Zustand melden: PREPARING → STARTING → (Plugin meldet sich am Master) → RUNNING
```

Stop: `stop`-Befehl in stdin → 30 s Gnadenfrist 🔧 → `destroy()` → `destroyForcibly()`.
Danach: Log sichern (siehe unten), Verzeichnis löschen (nur bei `DYNAMIC`, nie bei `STATIC`).

**Vorher gehen die Spieler raus.** Ist beim Stopp noch jemand auf dem Server, verschiebt der
Master sie erst auf ein Fallback-Ziel (Auswahl wie in Abschnitt 11) und schickt den
Stopp-Befehl danach. Erst wenn kein Fallback erreichbar ist, werden sie mit Meldung getrennt.
Ohne diesen Schritt würde ein `server stop` die Leute aus dem Netzwerk werfen, statt sie in die
Lobby zu setzen — bei `ServerCrashedEvent` greift dieselbe Umleitung, nur eben unfreiwillig.

### Logs beim Herunterfahren

| Server-Typ | Wohin |
|---|---|
| `DYNAMIC` | Wird zum **Master** hochgeladen, weil das Verzeichnis danach gelöscht wird. Ablage: `logs/<gruppe>/<server>-<zeitstempel>.log.gz` |
| `STATIC` | Bleibt auf dem Node im Server-Verzeichnis — das Verzeichnis bleibt ja bestehen |

Ablauf beim Stopp eines `DYNAMIC`-Servers: Log gzip-komprimieren → nach `logs/pending/`
verschieben → per gRPC (`UploadServerLog`, gechunkt) zum Master → erst nach dessen Quittung
lokal löschen. Ist der Master gerade weg, bleibt die Datei in `logs/pending/` liegen und wird
beim Reconnect nachgeschickt. Das Server-Verzeichnis selbst wird sofort gelöscht, der Log liegt
zu diesem Zeitpunkt schon in `logs/pending/`.

Aufbewahrung auf dem Master: 🔧 14 Tage, danach löschen. Live-Logs eines *laufenden* Servers
gehen weiterhin über `PushConsole` und werden nicht gepuffert (siehe oben).

**Master-Neustart überlebt die Server.** Der Wrapper läuft weiter und meldet beim Reconnect
seinen kompletten Zustand; der Master adoptiert die Server statt sie neu zu starten.
Der Wrapper beendet **nie** Server, nur weil der Master weg ist.

### Master-Ausfall: Outbox und Reconciliation

Gilt gleichermaßen für den Wrapper und für die Plattform-Plugins (Abschnitt 11) — beide nutzen
denselben `Outbox`-Baustein aus `vibecloud-api`.

**Was gepuffert wird — und was ausdrücklich nicht:**

| Gepuffert (Fakten mit Zeitstempel) | Verworfen (nur der aktuelle Wert zählt) |
|---|---|
| `ServerStateChanged`, Exit-Codes, Crashes | `Heartbeat` |
| `PlayerLogin` / `PlayerLogout`, Spielzeit-Deltas | `ResourceReport` (CPU/RAM) |
| In-game ausgesprochene Strafen (Ban/Mute/Kick/Warn) | Aktuelle Spielerzahlen |
| Modul-Events, die etwas festhalten | Konsolen-Logs (nur Ringpuffer der letzten 500 Zeilen) |

Ohne diese Trennung spielt man nach 30 Minuten Ausfall tausende nutzlose Heartbeats nach.

**Speicherort:** `outbox/` im Arbeitsverzeichnis, append-only mit length-prefixed Protobuf —
dasselbe Format wie auf der Leitung, also kein zweiter Serializer. Übersteht einen Neustart
des Wrappers. Begrenzt auf 🔧 50 MB bzw. 7 Tage; läuft sie voll, werden die ältesten Einträge
verworfen und eine Warnung geloggt (lieber Datenverlust mit Hinweis als eine vollgelaufene Platte).

**Deduplizierung:** Jeder Eintrag bekommt eine fortlaufende `seq` pro Herkunft, die *mit* der
Outbox auf der Platte liegt und Neustarts überlebt. Der Master führt in `event_cursors` je
Herkunft die höchste verarbeitete `seq`. Beim Replay werden Einträge `≤ cursor` übersprungen;
Anwendung und Cursor-Update passieren in **einer** Transaktion. Damit ist ein doppelter Replay
harmlos — wichtig, weil eine abgerissene Verbindung nie sauber sagt, was noch angekommen ist.

**Ablauf beim Reconnect:**

```
1. Register  → Wrapper schickt seine Uhr-Abweichung mit (Clock-Skew-Korrektur)
2. FullState → "das läuft bei mir gerade": alle Server mit Zustand und Port
               Master adoptiert sie, legt fehlende in der Registry an,
               markiert verschwundene als gestoppt
3. Replay    → Outbox in seq-Reihenfolge, Batches von 500, Master quittiert den Cursor
4. Live      → erst jetzt normaler Betrieb, Scheduler wird wieder scharf gemacht
```

Schritt 4 ist wichtig: Der `PlacementScheduler` bleibt während Schritt 2–3 **aus**. Sonst
startet er Server nach, während ihm noch die halbe Wirklichkeit fehlt.

**Degradierter Betrieb im Proxy — keine neuen Logins.** Ist der Master weg, lehnt der Proxy
jeden Login-Versuch mit einer klaren Meldung ab („Das Netzwerk wird gerade gewartet, bitte
versuch es in wenigen Minuten erneut“) und zeigt dieselbe Aussage im MOTD. Begründung: Ohne
Master gibt es keine verlässliche Ban-Prüfung und keine Rang-Auflösung für neue Spieler — wer
in dieser Lücke reinkommt, hat unklare Rechte.

**Wer schon online ist, bleibt online.** Niemand wird gekickt. Dafür hält das Velocity-Plugin
weiterhin einen Snapshot (Ränge, aufgelöste Permissions, aktive Mutes) in `cache/snapshot.bin`:
Permissions, Prefixe, Mutes und Serverwechsel funktionieren für die bereits verbundenen Spieler
unverändert weiter. Der Snapshot übersteht auch einen Proxy-Neustart.
🔧 Alternative per Config: Logins trotzdem erlauben und aus dem Snapshot entscheiden.

**Was während eines Ausfalls bewusst abgelehnt wird:** `/rank`, `/perm` und `/acp`. Diese
Commands antworten mit „Cloud ist offline, bitte später erneut versuchen“ statt zu puffern.
Grund: Zwei Admins auf zwei Proxys könnten denselben Rang unterschiedlich setzen, beide bekämen
ein „erfolgreich“, und beim Replay gewinnt stillschweigend der spätere Zeitstempel. Eine klare
Fehlermeldung ist besser als eine Änderung, die scheinbar funktioniert und dann verschwindet.
Strafen sind davon ausgenommen — sie sind Fakten, keine Zustandsbearbeitung, und werden
gepuffert. 🔧

---

## 8. Datenmodell (PostgreSQL)

Flyway-Migrations unter `master/vibecloud-master/src/main/resources/db/migration/`.
Module bringen eigene Migrations mit eigenem Präfix mit (siehe Abschnitt 10).

**Alle Zeitangaben sind `timestamptz` und werden in UTC gespeichert.** Umgerechnet wird erst
bei der Anzeige (Konsole, Dashboard, Chat). Damit gehen Sommerzeit-Sprünge und Roots in
anderen Zeitzonen nicht in Bann-Laufzeiten oder Spielzeit-Berechnungen ein.

```sql
players            uuid PK, name, name_lower, first_login, last_login,
                   last_server, playtime_seconds, locale, settings jsonb,
                   platform,                            -- 'JAVA' | 'BEDROCK'
                   xuid,                                -- nur Bedrock (Floodgate), sonst NULL
                   -- genau ein Rang pro Spieler, direkt am Spieler:
                   rank_id FK NOT NULL,                 -- Default-Rang beim ersten Login
                   rank_expires_at,                     -- NULL = permanent
                   rank_fallback_id FK,                 -- Rang nach Ablauf (sonst Default)
                   rank_granted_by, rank_granted_at
player_names       uuid FK, name, changed_at           -- Name-History
player_connections uuid FK, ip_hash, connected_at, disconnected_at, protocol
player_rank_history id PK, uuid FK, old_rank, new_rank, actor, reason, created_at

ranks              id PK, name UNIQUE, display_name, prefix, suffix, color,
                   weight int, is_default bool, created_at,
                   chat_format            -- MiniMessage-Template, NULL = globaler Standard
rank_inheritance   child_id FK, parent_id FK           -- Rang erbt von Rang (DAG)
rank_permissions   rank_id FK, permission, value bool, context jsonb, expires_at
player_permissions uuid FK, permission, value bool, context jsonb,
                   granted_by, granted_at, expires_at

server_groups      name PK, platform, static bool, min_online, max_online,
                   max_players, memory_mb, start_port, jvm_flags text[],
                   allowed_nodes text[], start_percent, idle_timeout, template, priority,
                   name_pattern DEFAULT '%group%-%id%',  -- siehe 16.2
                   mc_version,                           -- '1.21.4' oder 'latest'
                   jar_source,                           -- 'paper' | 'velocity' | 'custom:<url>'
                   fallback bool DEFAULT false,          -- Einstiegsziel beim Join
                   join_priority int DEFAULT 100,        -- niedriger = zuerst
                   maintenance bool DEFAULT false        -- Wartung nur dieser Gruppe

cloud_settings     key PK, value jsonb, updated_at
                   -- globale Schalter: maintenance, motd, message_default_locale, …
server_counter     group_name PK, next_id                -- fortlaufende %id% je Gruppe
static_server_bindings
                   server_name PK, group_name, node, port, bound_at
                   -- STATIC-Server bleiben dauerhaft auf diesem Node (Welt liegt dort)
nodes              name PK, token_hash, max_memory_mb, last_seen, enabled,
                   allowed_ips inet[],                  -- erlaubte Quell-IPs dieses Nodes
                   token_rotated_at, failed_auths
server_history     id PK, server_name, group_name, node, started_at, stopped_at, exit_code

cloud_audit        id PK, actor, action, target, data jsonb, created_at
api_tokens         id PK, name, token_hash, scopes text[], created_at, expires_at
dashboard_accounts uuid PK FK→players, username UNIQUE, password_hash,  -- Argon2id
                   must_change_password bool, enabled bool,
                   created_by, created_at, last_login, failed_logins
                   -- wird automatisch gelöscht, sobald vibecloud.dashboard.login fehlt
event_cursors      origin PK, last_seq, updated_at    -- Outbox-Dedup, siehe Abschnitt 7
```

### Redis-Keyspaces

| Key | Zweck | TTL |
|---|---|---|
| `vc:presence:<uuid>` | Auf welchem Server ist der Spieler | 60 s, Refresh |
| `vc:perm:<uuid>` | Aufgelöster Permission-Baum (serialisiert) | 10 min |
| `vc:mod:<id>:*` | Freier Namensraum je Modul (`vc:mod:punishment:…`) | je Modul |
| `vc:servers` | Hash aller Server für schnelle Reads | — |
| **Pub/Sub** `vc:events` | Clusterweite Events für Plugins + Module | — |
| **Pub/Sub** `vc:perm:invalidate` | Cache-Invalidierung bei Rang- oder Permission-Änderung | — |

---

## 9. Rang- und Permission-System

**Modell — zwei Ebenen, mehr nicht:**

1. **Genau ein Rang pro Spieler.** `players.rank_id` ist `NOT NULL`; beim ersten Login bekommt
   jeder Spieler den Rang mit `is_default = true`. Keine Rang-Listen, keine Mehrfachzuweisung.
   Der Rang eines Spielers ist damit immer eindeutig — für Prefix, Tab-Sortierung und
   Chat-Format gibt es nie eine Mehrdeutigkeit, die man auflösen müsste.
2. **Individuelle Spieler-Permissions** in `player_permissions`. Sie liegen *über* dem Rang:
   ein einzelner Spieler kann damit etwas zusätzlich bekommen (`worldedit.use`) oder gezielt
   entzogen bekommen (`-vibecloud.rank.set`), ohne dass dafür ein eigener Rang nötig wäre.

**Ränge erben weiterhin voneinander (DAG, Mehrfachvererbung).** Das ist jetzt sogar wichtiger
als vorher: Weil ein Spieler nur einen Rang tragen kann, ist Vererbung der einzige Weg,
Rechte-Sets zu kombinieren. Beispiel:

```
spieler  (default, weight 0)
   └─ vip        (weight 10)
        └─ premium (weight 20)
builder  (weight 15, erbt von spieler)
moderator (weight 50, erbt von premium UND builder)   ← Mehrfachvererbung
admin    (weight 100, erbt von moderator)
```

Ein Moderator braucht also keine zweite Rang-Zuweisung für Builder-Rechte — `moderator` erbt sie.

**Befristete Ränge** laufen über `rank_expires_at` am Spieler statt über mehrere parallele
Einträge: `/rank set Kevin vip 30d` setzt `rank_id = vip`, `rank_expires_at = jetzt + 30 Tage`
und `rank_fallback_id` auf den vorherigen Rang. Ein Scheduler im Master prüft minütlich auf
abgelaufene Ränge, setzt auf `rank_fallback_id` (oder den Default-Rang) zurück, schreibt einen
Eintrag in `player_rank_history` und publiziert auf `vc:perm:invalidate`. Dieselbe Prüfung
läuft zusätzlich beim Login, falls der Master währenddessen aus war.

**Permission-Nodes:**

| Form | Bedeutung |
|---|---|
| `vibecloud.server.start` | Normale Permission |
| `vibecloud.*` | Wildcard |
| `-vibecloud.server.stop` | Negation — gewinnt **immer** gegen eine positive |
| Context `{"group":"bedwars"}` | Gilt nur in dieser Servergruppe |
| Context `{"server":"lobby-1"}` | Gilt nur auf diesem Server |

**Auflösung (im Master, `PermissionService`):**

```
1. Rang des Spielers laden; wenn rank_expires_at < jetzt
   → auf rank_fallback_id bzw. Default-Rang zurücksetzen und persistieren
2. Vererbungskette dieses einen Rangs auflösen (Breitensuche über rank_inheritance),
   Zyklen erkennen und die Rang-Änderung ablehnen, nicht erst zur Laufzeit scheitern
3. Geerbte Ränge nach weight aufsteigend mergen — höheres weight überschreibt,
   der eigene Rang des Spielers zuletzt
4. player_permissions darüberlegen (abgelaufene verwerfen) — schlagen immer den Rang
5. Negationen zuletzt anwenden — ein "-node" gewinnt gegen jedes positive "node"
6. Nach Context filtern (global → group → server, spezifischer gewinnt)
→ Ergebnis: flacher Baum, in Redis unter vc:perm:<uuid> gecacht
```

Die Reihenfolge ist die eigentliche Fachlogik und gehört in Unit-Tests: Rang-Vererbung über
mehrere Ebenen, Mehrfachvererbung mit konkurrierenden `weight`-Werten, Spieler-Permission
schlägt Rang, Negation schlägt alles, Zyklus wird abgelehnt, abgelaufener Rang fällt zurück.

**Caching-Kette:** Plugin (Caffeine, lokal) → Redis → PostgreSQL.
Bei jeder Änderung publiziert der Master auf `vc:perm:invalidate`; alle Plugins werfen ihren
lokalen Eintrag weg. Prefix/Suffix werden als MiniMessage gespeichert und über Adventure
gerendert — **keine §-Codes mehr**, nirgendwo.

**Bridges:** Das Paper-Plugin registriert einen eigenen `Permissible`, sodass
`player.hasPermission(...)` und Fremd-Plugins automatisch funktionieren. Velocity nutzt den
`PermissionProvider`.

### Erst-Einrichtung

Nach einer frischen Installation hat niemand Rechte — deshalb legt die erste Migration zwei
Ränge an:

| Rang | weight | `is_default` | Permissions |
|---|---|---|---|
| `spieler` | 0 | ✅ | `vibecloud.language` (mehr nicht) |
| `admin` | 100 | ❌ | `vibecloud.*` |

**Die Master-Konsole hat implizit alle Rechte.** Wer am Terminal sitzt, hat ohnehin Zugriff auf
Config und Datenbank — eine Rechteprüfung dort wäre Theater. Dein erster Schritt ist also:

```
rank set <deinName> admin
```

Solange niemand den Rang `admin` hat, weist die Konsole beim Start mit einem Hinweis darauf hin.
Jede Konsolen-Aktion landet trotzdem im `cloud_audit` mit Akteur `CONSOLE`.

**Danach fehlen immer noch Gruppen** — ohne die startet kein Server. Deshalb gibt es
`cloud setup`: Es legt einmalig eine `proxy`-Gruppe (Velocity, `static`, 1 Instanz) und eine
`lobby`-Gruppe (`fallback = true`, `min_online = 1`) mit brauchbaren Vorgaben an. Eine frische
Installation ist damit in drei Befehlen spielbar:

```
rank set <deinName> admin
cloud setup
server list          → proxy-1 und lobby-1 starten von selbst
```

Der Hinweis in der Konsole nennt diesen Befehl, solange noch keine Gruppe existiert.

**Commands** (🔧 Namen anpassbar, abgesichert über `vibecloud.rank.*` / `vibecloud.perm.*`):

| Command | Wirkung |
|---|---|
| `/rank create\|delete\|edit\|info\|list` | Ränge selbst verwalten (Prefix, Suffix, Farbe, weight, Default-Flag) |
| `/rank inherit add\|remove <rang> <parent>` | Vererbung pflegen; lehnt Zyklen sofort ab |
| `/rank set <spieler> <rang> [dauer]` | Setzt **den** Rang. Ohne Dauer permanent, mit `30d`/`12h` befristet |
| `/rank reset <spieler>` | Zurück auf den Default-Rang |
| `/rank history <spieler>` | Wer hat wann welchen Rang gesetzt |
| `/perm rank add\|remove <rang> <node> [context]` | Rechte am Rang |
| `/perm player add\|remove <spieler> <node> [dauer] [context]` | Individuelle Rechte eines Spielers |
| `/perm check <spieler> <node>` | Zeigt **warum**: aus Rang `moderator`, geerbt von `builder`, oder individuell — das wichtigste Debug-Werkzeug |

Alle Commands laufen über dieselbe `CommandRegistry` und sind damit gleichzeitig in der
Master-Konsole, im Spiel über den Proxy und als REST-Endpunkt verfügbar.

---

## 10. Modul-System

Ein Modul ist **ein JAR mit optionalen Plattform-Bundles darin**:

```
module-punishment.jar
├── module.json
├── de/kevloe/vibecloud/punishment/...      (Master-Teil)
├── db/migration/V1__punishment.sql         (eigene Flyway-Migrations)
└── bundles/
    ├── paper.jar                            (wird in plugins/ der Paper-Server injiziert)
    └── velocity.jar                         (wird in plugins/ des Proxys injiziert)
```

```json
{
  "id": "punishment",
  "name": "Punishment",
  "version": "1.0.0",
  "main": "de.kevloe.vibecloud.punishment.PunishmentModule",
  "apiVersion": "1.0",
  "depends": [],
  "softDepends": ["discord"],
  "bundles": { "paper": "bundles/paper.jar", "velocity": "bundles/velocity.jar" }
}
```

**Lifecycle:** `onLoad()` → `onEnable(ModuleContext)` → `onDisable()`.
Jedes Modul bekommt einen eigenen child-first `ModuleClassLoader`; `vibecloud-api` kommt vom
Parent, damit Typen identisch sind. Entladen schließt den Loader und deregistriert alles.

**Was der `ModuleContext` bietet:**

```java
public interface ModuleContext {
    EventBus         events();        // abonnieren und eigene Events werfen, siehe 10a
    CommandRegistry  commands();      // Konsole, Proxy-Chat und REST gleichzeitig
    Database         database();      // eigenes Schema, eigene Migrations
    RedisClient      redis();
    PlayerService    players();
    PermissionService permissions();  // eigene Permission-Nodes registrieren
    ServerService    servers();
    ModuleConfig     config();        // modules/<id>/config.json
    ScheduledExecutorService scheduler();
    HttpRouter       http();          // eigene REST-Routen unter /api/modules/<id>/
    Logger           logger();
}
```

**Bundle-Verteilung:** Der Master kennt alle Bundles; der Wrapper erhält sie beim Template-Sync
und kopiert sie in `plugins/` des jeweiligen Servers. Du musst nie manuell Plugins verteilen.

**Ladereihenfolge:** `depends` und `softDepends` werden topologisch sortiert; ein Zyklus wird
beim Start abgelehnt, mit Nennung der beteiligten Module. Ein fehlendes `depends` verhindert
nur das eine Modul, nicht den Start der Cloud.

### Wie ein Modul mit seinen eigenen Plugin-Teilen redet

Der Master-Teil von `punishment` und sein Paper-Bundle müssen miteinander sprechen — das Bundle
fragt „ist dieser Spieler stummgeschaltet?", der Master meldet „dieser Spieler wurde gerade
gemutet". Dafür gibt es **keine eigenen gRPC-Services pro Modul.** Sonst bräuchte jedes Modul
eigene `.proto`-Dateien, Code-Generierung im Build und einen Eintrag im `AuthInterceptor` —
für Modul-Entwickler eine hohe Einstiegshürde.

Stattdessen trägt die bestehende Verbindung einen generischen Kanal:

```java
// im Paper-Bundle von punishment
MuteInfo info = context.channel()
        .request(Target.MASTER, "is_muted", new IsMutedRequest(uuid), MuteInfo.class)
        .join();

// im Master-Teil
context.channel().respond("is_muted", IsMutedRequest.class, req -> lookupMute(req.uuid()));
```

- Der Schlüssel wird automatisch unter der Modul-ID geführt, zwei Module können sich also nicht
  in die Quere kommen.
- Nutzlast sind Records als JSON — derselbe Serializer wie bei den Cluster-Events, kein zweites
  Format und kein Schema-Pflegeaufwand.
- Ziele: `MASTER`, `SERVER:<id>`, `ALL_SERVERS`, `ALL_PROXIES`. **Server zu Server direkt geht
  nicht** — alles läuft über den Master. Damit bleibt das Firewall-Modell aus Abschnitt 13
  unangetastet und es gibt genau eine Stelle, die Befugnisse prüft.
- `request()` hat einen Timeout und liefert ein `CompletableFuture`; nichts blockiert einen
  Server-Thread.

**Abgrenzung zu den Events:** Der Kanal ist für Fragen, auf die es eine Antwort braucht. Reine
Benachrichtigungen („Spieler wurde gebannt") laufen über die clusterweiten Events aus
Abschnitt 10a — nicht beides für dasselbe benutzen.

### Geplante Module

**Zunächst nur eines: `punishment`.** Es ist gleichzeitig der Praxistest für das Modul-System —
es braucht alles, was ein Modul brauchen kann: eigene Tabellen und Migrations, Commands,
Events, Redis, REST-Routen und Plattform-Bundles für Proxy *und* Paper. Wenn `punishment`
sauber läuft, hat sich die Modul-API bewiesen; erst dann kommen weitere dazu.

| Modul | Inhalt | Phase |
|---|---|---|
| `punishment` | Ban / TempBan / Mute / TempMute / Kick / Warn, IP-Bans, Begründungs-Templates, History, Appeal-ID, Proxy-Enforcement beim Login, Mute-Filter im Chat | M6 |

**Später, bewusst noch nicht geplant** — erst wenn der Core und `punishment` im echten Betrieb
stabil sind: `party`, `friends`, `discord`, `report`, `ticket`, `clan`, `nick`, `chatlog`
(letztere aus dem alten Projekt portierbar). Diese Liste ist nur eine Merkhilfe, keine
Verpflichtung — der Zuschnitt wird neu entschieden, wenn es soweit ist.

> Für den Core heißt das: Die `ModuleContext`-API muss von Anfang an so breit sein, dass
> `party` später *ohne Core-Änderung* passt (Redis-State, clusterweite Events, Spieler folgen
> beim Serverwechsel). Darauf achte ich beim Entwurf von M5 — gebaut wird aber nichts davon.

---

## 10a. Event-System

Ein `EventBus` in `vibecloud-api`, derselbe für Master, Module **und** Plattform-Plugins. Wer
ein Event abonnieren will, braucht keine Ahnung davon, wo es entsteht.

```java
public class WelcomeListener {
    @Subscribe(priority = Priority.NORMAL)
    public void onJoin(PlayerJoinNetworkEvent event) {
        event.player().sendMessage("welcome.first_join");   // Nachrichten-Schlüssel, siehe 11a
    }
}
```

### Zwei Familien, und der Unterschied ist wichtig

| | **Pre-Events** | **Post-Events** |
|---|---|---|
| Beispiel | `PlayerPreLoginEvent` | `PlayerJoinNetworkEvent` |
| Abbrechbar | ✅ mit Begründung (Nachrichten-Schlüssel) | ❌ es ist schon passiert |
| Ausführung | synchron, **im Master**, mit Timeout | asynchron, Virtual Thread, überall |
| Zweck | Entscheiden, ob etwas passieren darf | Reagieren, nachdem es passiert ist |

Pre-Events kosten eine Rückfrage: Der Proxy fragt beim Login per gRPC `CheckLogin` beim Master
nach, der Master ruft die Handler synchron auf und antwortet mit erlauben/ablehnen plus
Begründung. Deshalb gibt es sie nur dort, wo man wirklich eingreifen muss.

**Das ist der Grund, warum Bans nicht im Core verdrahtet sind.** Das `punishment`-Modul
registriert einfach einen Handler auf `PlayerPreLoginEvent` und bricht ab, wenn ein Ban
vorliegt. Der Core weiß nichts von Bans — und ein späteres Whitelist- oder Länder-Sperr-Modul
hängt sich an dieselbe Stelle, ohne dass ich eine Zeile am Login-Gate ändere.

**Timeout:** Jeder Pre-Handler erklärt, was bei Zeitüberschreitung gelten soll
(`@Subscribe(onTimeout = DENY)` bzw. `ALLOW`), Standard 🔧 1500 ms. `punishment` nutzt `DENY` —
wenn die Ban-Prüfung hängt, darf ein gebannter Spieler nicht durchrutschen. Ein Modul, das nur
eine Begrüßung vorbereitet, nutzt `ALLOW`.

### Katalog

Namen sind 🔧 anpassbar, die Aufteilung ist das Entscheidende.

**Spieler** (netzwerkweit)

| Event | Wann | Abbrechbar |
|---|---|---|
| `PlayerPreLoginEvent` | Vor dem Login, im Master | ✅ |
| `PlayerJoinNetworkEvent` | Spieler ist im Netzwerk angekommen | — |
| `PlayerQuitNetworkEvent` | Spieler hat das Netzwerk verlassen (mit Sitzungsdauer) | — |
| `PlayerPreSwitchServerEvent` | Vor einem Serverwechsel (von → nach) | ✅ |
| `PlayerSwitchServerEvent` | Nach dem Wechsel | — |
| `PlayerChatEvent` | Chat auf einem Server (hier greift der Mute-Filter) | ✅ |
| `PlayerRankChangeEvent` | Rang gesetzt, zurückgesetzt oder abgelaufen (alt, neu, Akteur) | — |
| `PlayerPermissionsChangedEvent` | Rechte haben sich geändert — egal aus welchem Grund | — |

**Server und Nodes**

| Event | Wann | Abbrechbar |
|---|---|---|
| `ServerPreStartEvent` | Bevor der Scheduler einen Start auslöst | ✅ |
| `ServerStartingEvent` / `ServerStartedEvent` | Prozess läuft / Plugin hat sich gemeldet | — |
| `ServerStoppingEvent` / `ServerStoppedEvent` | Stopp beginnt / beendet (mit Exit-Code) | — |
| `ServerCrashedEvent` | Unerwartet beendet | — |
| `ServerStateChangeEvent` | `LOBBY` → `INGAME` → `ENDING` | — |
| `NodeConnectEvent` / `NodeDisconnectEvent` / `NodeTimeoutEvent` | Wrapper kommt, geht, fällt aus | — |

**Cloud**

`CloudReadyEvent`, `CloudShutdownEvent`, `MaintenanceChangeEvent`,
`ModuleEnableEvent` / `ModuleDisableEvent`.

### Module veröffentlichen eigene Events

Das ist der eigentliche Gewinn. `punishment` wirft `PlayerBanEvent`, `PlayerMuteEvent`,
`PlayerPardonEvent` — und ein späteres Discord-Modul reagiert darauf, **ohne dass `punishment`
davon weiß**:

```java
// im Discord-Modul
@Subscribe
public void onBan(PlayerBanEvent event) { postToChannel(event); }
```

Dafür nötig und im Modul-System vorgesehen: Ein Modul deklariert in `module.json`, welche
Pakete es exportiert; andere Module sehen diese Klassen über ihr `depends`. Ohne das würde die
child-first-Isolation aus Abschnitt 10 die Event-Klassen gegenseitig verstecken.

### Mechanik

- **Clusterweit** laufen Post-Events über Redis `vc:events`, serialisiert als JSON mit
  Typ-Kennung (voll qualifizierter Klassenname). Records serialisieren sauber, und ein
  unbekannter Typ wird still verworfen — so bricht ein Plugin mit älterer `vibecloud-api` nicht,
  wenn der Master ein neues Event einführt.
- **Lokal** laufen sie direkt im Prozess, ohne Redis-Umweg. Ein Event, das nur den Master
  betrifft (`NodeTimeoutEvent`), verlässt ihn nicht.
- **Ausführung** auf Virtual Threads: Ein langsamer Handler blockiert keinen anderen. Eine
  Ausnahme in einem Handler wird geloggt und beendet die Verteilung nicht.
- **Priorität** über `Priority.FIRST…LAST`. `LAST` ist der Platz für reines Mitschreiben, wenn
  alle Änderungen schon durch sind.
- **Registrierung** über `context.events().register(this)`; beim Entladen eines Moduls werden
  seine Handler automatisch abgemeldet.

---

## 11. Plattform-Plugins

| Plugin | Aufgaben |
|---|---|
| `vibecloud-velocity` | Server beim Master registrieren/deregistrieren, Login-Gate (fragt `PlayerPreLoginEvent` beim Master ab — Bans kommen aus dem Modul, nicht aus dem Core), Wartungsmodus, Vollauslastung, Rang-Prefixe in Tab/Chat, Spieler-Transfers, MOTD, In-Game-Commands `/cloud`, `/ban`, `/rank` |
| `vibecloud-paper` | `vibecloud-connection.json` lesen, am Master anmelden, Permission-Bridge (`Permissible`), Mute-Filter, Server-Zustand melden (`playerCount`, `LOBBY`/`INGAME`), API für Server-Plugins |
| `vibecloud-minestom` | Dieselbe Funktionalität als Bibliothek — eigener `main()`, kein `plugin.yml`. Mitgeliefertes Lobby-Beispiel. |

Alle drei nutzen denselben `CloudConnection`-Kern aus `vibecloud-api`, nur die Plattform-Adapter
unterscheiden sich. Beide Outbox-Puffer und den Degradiert-Snapshot aus Abschnitt 7 nutzen sie
ebenfalls gemeinsam.

### Proxy-Gruppen

Der Proxy ist eine **normale Gruppe** (`platform: VELOCITY`, `static: true`) und wird von der
Cloud gestartet, aktualisiert und überwacht — kein Sonderfall. Mehrere Instanzen sind erlaubt:

- Pro Node höchstens **ein** Proxy, weil alle auf dem öffentlichen Port 25565 lauschen.
- Spieler verteilst du über DNS (A-Records auf mehrere Root-IPs, oder SRV).
- Der Master kennt alle Proxys und aggregiert die Spielerzahlen über `vc:presence:*` in Redis,
  damit MOTD, Limits und `/glist` proxy-übergreifend stimmen.
- Alle Proxys einer Gruppe bekommen dasselbe Forwarding-Secret (Abschnitt 13).

### Wohin Spieler beim Join kommen

Gruppen mit `fallback = true` sind Einstiegsziele. Der Proxy wählt:

```
Kandidaten = Fallback-Gruppen, nicht in Wartung, mit laufenden Servern
  → nach join_priority aufsteigend
  → innerhalb der Gruppe: der Server mit den MEISTEN Spielern,
    der noch nicht voll ist          (auffüllen statt verteilen —
                                      sonst sitzen überall einzelne Spieler)
  → kein Kandidat? Join ablehnen mit klarer Meldung
```

Das Auffüllen statt Verteilen ist Absicht: Bei leeren Lobbys wirkt ein Netzwerk schnell tot,
und der `idleTimeout` kann leere Server wieder abräumen.

Dieselbe Auswahl greift, wenn ein Server abstürzt und Spieler zurückfallen müssen. Gibt es
dann kein Ziel, werden sie mit Meldung getrennt statt in einen Timeout zu laufen.

### Wartungsmodus

Zwei Ebenen, Bypass über `vibecloud.maintenance.bypass`, Verwaltung über
`vibecloud.maintenance.manage`:

| Command | Wirkung |
|---|---|
| `/maintenance on` / `off` | Ganzes Netzwerk. Logins werden abgelehnt, MOTD zeigt den Zustand. Flag in `cloud_settings` |
| `/maintenance on <gruppe>` / `off <gruppe>` | Nur diese Gruppe. Keine neuen Verbindungen dorthin, die Gruppe fällt als Fallback-Ziel weg. Flag in `server_groups.maintenance` |
| `/maintenance list` | Was ist gerade gesperrt |

Wer Bypass hat, kommt trotzdem rein — das ist der eigentliche Zweck der Gruppen-Wartung: ein
Minigame testen, während der Rest des Netzwerks normal läuft. Spieler, die beim Einschalten
noch auf der Gruppe sind, werden auf ein Fallback-Ziel verschoben 🔧. Der Scheduler lässt
laufende Server der Gruppe in Ruhe, damit du überhaupt etwas zum Testen hast.

### Darstellung der Ränge (Core, nicht Modul)

Ohne das sieht man von den Rängen im Spiel nichts — deshalb gehört es in die Plattform-Plugins
und nicht in ein späteres Modul. Alles aus demselben `RankDisplayService`:

| Was | Wie |
|---|---|
| **Chat-Format** | MiniMessage-Template, Standard global in der Config, pro Rang über `ranks.chat_format` überschreibbar. Platzhalter `<prefix>`, `<player>`, `<suffix>`, `<message>`. Gerendert in Papers `AsyncChatEvent` über einen `ChatRenderer`, Chat bleibt also serverlokal. 🔧 Per Config abschaltbar, falls auf einem Server ein eigenes Chat-Plugin arbeitet. |
| **Tab-Liste** | Sortiert nach `rank.weight` absteigend. Umsetzung über Scoreboard-Teams mit Namen `<0000-weight>_<rang>` — Minecraft sortiert Teams alphabetisch, deshalb das invertierte, nullgepolsterte Gewicht. |
| **Nametags** | Prefix/Suffix über dem Kopf, ebenfalls über Scoreboard-Teams (Paper) bzw. das Team-Paket direkt (Minestom). |

Bei einer Rang-Änderung kommt über `vc:perm:invalidate` ein Hinweis an alle Plugins; sie
aktualisieren Team und Tab-Eintrag sofort, ohne Relog.

### Bedrock-Spieler (Geyser + Floodgate)

Geyser und Floodgate liegen im `global/proxy`-Template, Floodgate zusätzlich im
`global/server`-Template für die Skin- und API-Unterstützung auf den Gameservern.

Für das Datenmodell heißt das:

- `players.platform` unterscheidet `JAVA` und `BEDROCK`, `players.xuid` hält die Bedrock-ID.
- Floodgate erzeugt UUIDs der Form `00000000-0000-0000-000X-XXXXXXXXXXXX`. Die bleiben der
  Primärschlüssel — Ränge, Permissions und Strafen funktionieren damit unverändert.
- Bedrock-Namen bekommen standardmäßig den Floodgate-Prefix (`.Kevin`). Der Master speichert
  den Namen **ohne** Prefix, damit `/ban Kevin` für beide Plattformen gleich funktioniert.
- Geyser lauscht auf UDP 19132 auf den Proxy-Nodes.

🔧 Noch nicht geplant: Account-Verknüpfung (Global Linking), damit ein Spieler mit Java- und
Bedrock-Client denselben Datensatz nutzt. Später als Erweiterung, braucht eine `player_links`-Tabelle.

---

## 11a. Sprachsystem

**Kein einziger spielersichtbarer Text steht im Code.** Alles läuft über Schlüssel, die der
`MessageService` aus `vibecloud-common` auflöst — Plugins, Master-Konsole, REST-Antworten und
Module gleichermaßen.

```
messages/
├── de.yml        Standard, immer vollständig
├── en.yml
└── <weitere>.yml     einfach dazulegen, kein Code nötig
```

```yaml
# de.yml
punishment:
  ban:
    screen: "<red>Du bist gebannt.</red>\n<gray>Grund:</gray> <reason>\n<gray>Läuft ab:</gray> <expires>"
maintenance:
  kick: "<yellow>Das Netzwerk wird gerade gewartet.</yellow>"
language:
  changed: "<green>Sprache geändert auf <lang>.</green>"
  unknown: "<red>Unbekannte Sprache. Verfügbar: <list></red>"
```

**Auflösungskette:** gewählte Sprache des Spielers → `de` → der Schlüsselname selbst. Der
letzte Schritt ist wichtig: Ein fehlender Eintrag zeigt `punishment.ban.screen` statt eines
leeren Bildschirms, damit die Lücke sofort auffällt statt still zu verschwinden.

**Sprachwahl:**

| Command | Wirkung |
|---|---|
| `/language` | Zeigt die verfügbaren Sprachen und die aktuell gewählte |
| `/language <kürzel>` | Wechselt sofort, speichert in `players.locale` |

Beim **ersten** Join wird die Client-Sprache von Minecraft übernommen, falls es dafür eine
Datei gibt — sonst Deutsch. Danach gilt immer die eigene Wahl, auch wenn der Client wechselt.
Permission: `vibecloud.language` (hat der Default-Rang).

**Verteilung:** Die Dateien liegen beim Master als einzige Quelle. Beim Verbinden schickt er
sie an Proxys und Gameserver (wenige Kilobyte), die sie lokal und im Degradiert-Snapshot aus
Abschnitt 7 halten. `cloud messages reload` liest neu ein und verteilt sofort — kein
Server-Neustart für eine Textkorrektur.

**Module** bringen ihre eigenen Dateien mit (`messages/de.yml` im Modul-JAR) und werden unter
ihrer Modul-ID eingehängt, also `punishment.ban.screen`. So kann ein Modul keine Core-Texte
überschreiben.

Platzhalter sind benannt (`<reason>`, `<expires>`, `<lang>`), nicht positionsbasiert — bei
Übersetzungen steht die Reihenfolge sonst nie fest. Gerendert wird mit MiniMessage über
Adventure, passend zu Prefix und Chat-Format aus Abschnitt 11.

---

## 12. Bedienung

**1. JLine-Konsole im Master** — `help`, `server list|start|stop|info`, `node list|info`,
`group create|edit`, `player info|rank`, `module list|load|unload|reload`,
`screen <server>` (hängt sich an den Live-Log, `Strg+D` löst wieder)

**2. REST-API** (`:8080`, Token-Auth, Scopes) —
`GET /api/v1/servers`, `POST /api/v1/servers/{id}/stop`, `GET /api/v1/nodes`,
`GET/PUT /api/v1/players/{uuid}`, `/api/v1/ranks`, `/api/v1/modules`,
WebSocket `/ws/events` (Server-Zustände, Spielerzahlen) und `/ws/console/{server}`

**3. Web-Dashboard** (`dashboard/`) — Server-Übersicht mit Live-Status, Konsole im Browser,
Node-Auslastung, Rang- und Permission-Editor, Spielersuche, Ban-Verwaltung, Modul-Übersicht.
Läuft in Dev gegen `localhost:8080`, in Prod als statische Dateien von Javalin ausgeliefert.

### Wie das Dashboard mit Modulen umgeht

Hier steckte ein Widerspruch im Plan: „Ban-Verwaltung" ist eine Dashboard-Seite, aber Bans
gehören dem `punishment`-**Modul**. Würde die Seite fest eingebaut, wäre das Dashboard nicht
mehr modul-unabhängig.

Die Auflösung: **Die Seite liegt im Dashboard, die Logik bleibt im Modul.** Die Ban-Verwaltung
spricht ausschließlich gegen `/api/modules/punishment/…` — Routen, die das Modul selbst über
`context.http()` registriert. Beim Laden fragt das Dashboard `/api/v1/modules` ab, welche
Module aktiv sind, und **blendet Seiten ohne passendes Modul aus**. Ist `punishment` nicht
geladen, gibt es keine Ban-Verwaltung, und nichts bricht.

Damit ist die Kopplung rein im Frontend und optisch sichtbar statt im Backend versteckt. Was
ich bewusst **nicht** baue: Module, die eigenes JavaScript ins Dashboard einhängen. Das wäre
ein kleines Plugin-Framework im Browser samt Sicherheitsfragen — für aktuell ein Modul nicht
zu rechtfertigen. 🔧 Wenn später viele Module eigene Oberflächen brauchen, ist der nächste
Schritt eine deklarative Beschreibung (das Modul liefert Formular- und Tabellen-Definitionen
als JSON, das Dashboard rendert sie) statt mitgelieferter Skripte.

### Dashboard-Login über ACP-Accounts

Es gibt **keine Registrierung im Dashboard**. Ein Account entsteht nur in-game:

```
/acp create Kevin
  → Account anlegen (username = Minecraft-Name, uuid = Minecraft-UUID)
  → zufälliges Start-Passwort erzeugen (12 Zeichen)
  → must_change_password = true
  → Passwort EINMALIG im Chat anzeigen (klickbar zum Kopieren), nie geloggt
  → Audit-Eintrag: wer hat für wen angelegt

Login im Dashboard:
  Benutzername + Passwort  →  Argon2id prüfen
  wenn must_change_password  →  nur die Seite „Passwort setzen“ erreichbar,
                                jeder andere API-Call wird mit 403 abgelehnt
  danach  →  JWT (kurzlebig) + Refresh-Token als HttpOnly-Cookie

/acp changepw Kevin
  → neues Start-Passwort, must_change_password = true, alle Sessions ungültig
```

**Automatische Löschung bei Rechte-Entzug.** `vibecloud.dashboard.login` ist die
Zugangs-Permission. `AccountService` hängt sich auf das `PlayerPermissionsChangedEvent` des
`PermissionService` — das löst bei *jeder* Ursache aus: `/rank set`, `/perm remove`, geänderte
Rang-Vererbung, abgelaufener Rang über den Minuten-Scheduler.

```
PlayerPermissionsChangedEvent(uuid)
  → hat der Spieler einen dashboard_accounts-Satz?
      → hat er noch vibecloud.dashboard.login?
          ja   → nichts tun
          nein → Satz löschen
                 Session-Version in Redis hochzählen  (alle JWTs sofort ungültig)
                 Refresh-Token-Cookie entwerten
                 Audit-Eintrag: "dashboard account auto-removed", Auslöser + Akteur
```

Zusätzlich prüft **jeder** API-Call die Permission mit, nicht nur der Login. Das JWT trägt eine
`session_version`; stimmt sie nicht mehr mit Redis überein, kommt `401`. Dadurch wirkt der
Entzug im laufenden Betrieb binnen Sekunden und nicht erst beim nächsten Login.

Ändert sich ein **Rang**, sind potenziell viele Spieler betroffen. Deshalb prüft der
`AccountService` nicht alle Spieler, sondern nur die, die überhaupt einen Account haben — das
sind eine Handvoll, die Abfrage ist billig.

**Rechte im Dashboard = Rechte im Spiel.** Der Account hängt an der Minecraft-UUID, also gilt
dasselbe Rang- und Permission-System aus Abschnitt 9. Wer in-game kein `vibecloud.server.stop`
hat, sieht den Knopf im Dashboard gar nicht erst. Kein zweites Rechtesystem, das auseinanderläuft.

Dazu: Rate-Limit auf dem Login (`failed_logins`, Sperre nach 5 Fehlversuchen 🔧),
`/acp disable <spieler>` sperrt sofort, Passwörter nur als Argon2id-Hash in der Datenbank.

**4. In-Game** über den Proxy, permission-geschützt über das Rang-System.

---

## 13. Sicherheit

- **Wrapper- und Plugin-Auth:** siehe eigener Abschnitt unten — Anmeldung *und* Befugnisse.
- **Keine Credentials im Repo.** `config.json` / `.env` sind in `.gitignore`; eine
  `config.example.json` liegt daneben.
- **Keine DB-Zugriffe von Wrapper oder Plugins.**
- **Dashboard-Accounts:** Argon2id, keine Registrierung, nur `/acp create` durch Berechtigte.
  Start-Passwörter werden einmalig im Chat gezeigt und nie geloggt oder im Klartext gespeichert.
- **Audit-Log** (`cloud_audit`) für alle administrativen Aktionen inkl. Akteur.
- **Rate-Limiting** auf der REST-API, IPs nur gehasht speichern.

### Wer darf sich an den Master hängen — und was darf er dann?

Vier Schichten. Jede einzelne reicht nicht, zusammen ergeben sie ein brauchbares Bild.

#### 1. Erreichbarkeit

Port 5000 ist per Firewall nur von den bekannten Root-IPs erreichbar. Zusätzlich hat jeder
Node in `nodes.allowed_ips` eine Liste erlaubter Quell-IPs: Ein gestohlenes Token nützt nichts,
wenn die Verbindung nicht von der passenden Adresse kommt. Das ist der billigste zweite Faktor,
den es hier gibt.

#### 2. Wen der Wrapper vor sich hat (TLS-Vertrauen)

Der Master erzeugt beim ersten Start ein selbstsigniertes Zertifikat (oder du legst dein eigenes
hinein) und gibt den SHA-256-Fingerprint in der Konsole aus. Dieser Fingerprint steht in der
`wrapper.json` und wird **geprüft**:

```json
{ "masterHost": "…", "masterPort": 5000,
  "masterFingerprint": "sha256:3f:a1:…",
  "node": "node-a", "token": "…" }
```

Ohne diese Prüfung könnte sich jemand als Master ausgeben und den Wrappern Befehle schicken —
die Richtung, an die man zuerst nicht denkt. Stimmt der Fingerprint nicht, verbindet der
Wrapper sich nicht und sagt deutlich, warum.

#### 3. Anmeldung

| | |
|---|---|
| **Wrapper** | Ein Token **pro Node**, 32 Byte, vom Master über `node add <name>` erzeugt und einmalig angezeigt. Gespeichert wird nur ein Argon2id-Hash in `nodes.token_hash`. Das Token ist an genau diesen Node-Namen gebunden — mit dem Token von `node-a` kann man sich nicht als `node-b` anmelden. Rotation über `node token rotate <name>`. |
| **Gameserver-Plugin** | Pro Serverstart erzeugt der Wrapper ein Einmal-Secret in `vibecloud-connection.json`. Es ist **einmal verwendbar** und verfällt nach 🔧 120 s — wer die Datei später findet, kann damit nichts mehr anfangen. |

Die Prüfung sitzt in **einem** gRPC-Interceptor, nicht in jedem Service verstreut. Vor
erfolgreicher Anmeldung wird kein anderer RPC bedient und der `Control`-Stream nicht geöffnet.
Fehlversuche werden pro IP gedrosselt (exponentiell), nach 🔧 5 Fehlschlägen wird der Node
deaktiviert und es gibt einen lauten Log- und Audit-Eintrag.

#### 4. Befugnisse — der eigentlich wichtige Teil

Eine erfolgreiche Anmeldung macht niemanden zum Administrator. Jede Verbindung trägt eine
Identität (`NODE:node-a` oder `SERVER:lobby-1`), und **jede eingehende Nachricht wird dagegen
geprüft**:

| Identität | Darf | Darf nicht |
|---|---|---|
| `NODE:node-a` | Zustände, Logs, Exit-Codes und Ressourcen nur für Server melden, die der Master **diesem** Node zugewiesen hat | Über fremde Server sprechen · Spieler-Events senden · Ränge, Permissions oder Strafen verändern |
| `SERVER:lobby-1` | Nur über sich selbst berichten (`serverId` muss der Identität entsprechen); Spieler-Events nur für UUIDs, die laut Presence gerade auf **diesem** Server sind | Für andere Server sprechen · Logins/Quits erfinden (das darf nur der Proxy) |
| `SERVER:proxy-1` | Netzwerkweite Spieler-Events (`PreLogin`, `Join`, `Quit`, `Switch`), Server-Registrierung quittieren | Server starten oder stoppen ohne Permission-Prüfung |

**Der entscheidende Satz: Der Master prüft jede Berechtigung selbst neu.** Kommt ein
`/rank set`-Befehl über einen Proxy, überträgt das Plugin nur, *wer* ihn abgeschickt hat — nie
ein Feld wie „darf das". Der Master löst die Permission dieses Spielers aus der Datenbank auf
und entscheidet. Sonst könnte ein kompromittierter Gameserver sich selbst `vibecloud.*` geben.

Nachrichten außerhalb der Befugnis werden verworfen, nicht stillschweigend ignoriert: Audit-
Eintrag mit Identität und Inhalt, und nach mehreren Verstößen wird die Verbindung getrennt und
der Node deaktiviert. Wiederholte Grenzüberschreitungen sind entweder ein Bug oder ein
Einbruch — beides will man sofort sehen.

**Ressourcengrenzen:** gRPC-Nachrichten auf 🔧 4 MB begrenzt (Template- und Log-Übertragungen
sind ohnehin gechunkt), begrenzte Zahl gleichzeitiger Streams pro Identität.

#### Was damit noch möglich bleibt

Ehrlich bleiben: Wer **root** auf einem deiner Roots hat, kann das Token lesen und sich als
dieser Node anmelden. Er kann dann Unsinn über die Server *dieses* Nodes behaupten — aber nicht
über fremde, keine Ränge ändern und keine Spielerdaten fälschen. Das ist die Grenze dieses
Modells, und sie ist bewusst dort: Wer root auf dem Node hat, kontrolliert die Gameserver
sowieso.

🔧 **Phase M8 — mTLS:** Der Master wird eigene CA, jeder Wrapper erhält bei der Erstanmeldung
ein Client-Zertifikat, und das Token wird zum reinen Einmal-Bootstrap. Dann genügt ein
abgeflossenes Langzeit-Token nicht mehr, weil zusätzlich der private Schlüssel nötig ist.

### Netzwerk zwischen den Roots

Nur diese Ports sind öffentlich erreichbar:

| Port | Wo | Für wen |
|---|---|---|
| 25565/tcp | Proxy-Nodes | Spieler (Java) |
| 19132/udp | Proxy-Nodes | Spieler (Bedrock, Geyser) |
| 5000/tcp | Master | nur die Roots, per Firewall auf deren IPs begrenzt |
| 8080/tcp | Master | Dashboard — 🔧 besser hinter einem Reverse-Proxy mit TLS |

**Gameserver-Ports sind nicht öffentlich.** Sie liegen in einem festen Bereich
(`30000–30999` 🔧) und dürfen **nur von den Proxy-IPs** erreicht werden. Das ist zwingend, nicht
optional: Gameserver laufen bei Velocity-Forwarding mit `online-mode: false`. Ein von außen
erreichbarer Gameserver-Port heißt, dass sich jemand ohne Mojang-Prüfung direkt verbindet und
sich als beliebiger Spieler ausgibt — inklusive dir.

**Damit die Whitelist nicht manuell gepflegt werden muss** (das war die Schwäche dieser
Variante), verwaltet der Wrapper sie selbst:

```
wrapper.json:  "firewall": { "manage": true, "backend": "nftables" }

Master kennt alle Proxy-IPs  →  schickt sie bei jeder Änderung im Control-Stream
Wrapper pflegt daraus ein nftables-Set:

  table inet vibecloud {
    set proxies { type ipv4_addr; elements = { 203.0.113.5, 198.51.100.9 } }
    chain input {
      tcp dport 30000-30999 ip saddr @proxies accept
      tcp dport 30000-30999 drop
    }
  }
```

Der Wrapper braucht dafür `CAP_NET_ADMIN`. 🔧 Mit `"manage": false` macht er nichts und du
pflegst die Regeln selbst — dann aber bitte wirklich, siehe oben.

Zusätzlich bindet jeder Gameserver nur auf die IP des eigenen Nodes (`server-ip`), nicht auf
`0.0.0.0`, damit ein vergessener Port nicht auf allen Interfaces offen steht.

### Velocity-Forwarding-Secret

Beim ersten Start erzeugt der Master ein 32-Byte-Secret und legt es in seinem Config-Verzeichnis
ab (Modus `600`, nicht im Repo). Verteilt wird es automatisch:

- in den Proxy als `forwarding.secret`
- in jeden Paper-Server als `paper-global.yml` → `proxies.velocity` (`enabled: true`,
  `online-mode: true`, `secret`) plus `online-mode=false` in der `server.properties`
- in Minestom-Server über `VelocityProxy.enable(secret)`

Das Secret geht nie über einen ungesicherten Kanal: Es kommt im TLS-geschützten Template-Sync
an und landet nur in Verzeichnissen, die dem Server selbst gehören. `cloud forwarding rotate`
erzeugt ein neues — wirkt erst nach Neustart aller Server, deshalb mit Warnung. 🔧

---

## 14. Umsetzungs-Roadmap

| Phase | Inhalt | Ergebnis |
|---|---|---|
| **M0** | Gradle-Setup, Version Catalog, Convention-Plugins, `docker-compose.yml`, `.gitignore`, `CLAUDE.md` (kein `git init`, kein CI — machst du selbst) | `./gradlew build` läuft grün |
| **M1** | `protocol` + `api`, Master-Bootstrap, gRPC-Server, Wrapper-Bootstrap, Register/Heartbeat/Control-Stream, `Outbox`-Baustein + `event_cursors`, `EventBus` (lokal) mit Node- und Cloud-Events, `AuthInterceptor` mit Token-Prüfung, Fingerprint-Pinning, `allowed_ips` und Befugnis-Prüfung, `node add`/`node token rotate`, **DB-Fundament** (HikariCP, Flyway, Einzel-Master-Sperre, Tabellen `nodes` + `event_cursors`), JLine-Konsole | Wrapper verbindet sich, `node list` zeigt ihn; ein Wrapper ohne gültiges Token kommt nicht rein |
| **M2** | `ServerRuntime` + `ProcessServerRuntime`, Templates (global + Gruppe), Template-Sync, Jar-Download per MC-Version, Gruppen, `name_pattern`, `PlacementScheduler`, `static_server_bindings`, Port-Vergabe, Konsolen-Streaming, Log-Upload beim Stopp, `FullState`-Reconciliation beim Reconnect, `vibecloud-wrapper.service`-Vorlage | `server start lobby` startet einen echten Paper-Server; Master-Neustart adoptiert laufende Server; `STATIC` bleibt auf seinem Node |
| **M3** | Velocity-Plugin, Paper-Plugin, Minestom-Bibliothek + Lobby-Beispiel, Proxy als Gruppe, Forwarding-Secret-Verteilung, Firewall-Verwaltung im Wrapper, Geyser/Floodgate im Proxy-Template, `MessageService` + `de.yml`, Fallback-Auswahl beim Join | Spieler joint auf `:25565` (Java) bzw. `:19132` (Bedrock) und landet in der Lobby; alle Meldungen kommen aus `de.yml` |
| **M4** | Spieler- und Rang-Schema (DB-Fundament steht seit M1), `PlayerService` (Java + Bedrock), `PermissionService`, Redis-Cache, Permission-Bridges, `/rank`- und `/perm`-Commands, `RankDisplayService` (Chat, Tab, Nametags), Seed-Ränge `spieler`/`admin`, `/language`, Wartungsmodus, clusterweite Events über Redis + Spieler-Event-Katalog, `PlayerPreLoginEvent`-Gate | Ränge sind im Spiel sichtbar und wirken netzwerkweit; `rank set` aus der Konsole macht dich zum Admin; Events kommen auf allen Servern an |
| **M5** | `module-api`, `ModuleManager`, ClassLoader-Isolation, Bundle-Injektion, Beispielmodul | `module load` lädt ein Modul zur Laufzeit |
| **M6** | `module-punishment` (einziges Modul vorerst) | Ban/Mute greifen beim Login und im Chat, History und Begründungs-Templates stehen |
| **M7** | Javalin REST + WebSocket, API-Tokens, `AccountService` + `/acp`-Commands, automatische Account-Löschung bei Rechte-Entzug, Dashboard mit Login und Passwort-Zwangswechsel | Cloud im Browser bedienbar, Zugang nur über in-game angelegte Accounts |
| **M8** | Härtung: mTLS, Micrometer/Prometheus, `DockerServerRuntime`, Welt-Backups für `STATIC`-Server, Entscheidung zur Master-Reserve (16.1). **Kein** DB-Backup — das läuft außerhalb über Postgres-Replikation | Produktionsreif |

> **Korrektur vom 2026-10-01:** Das Datenbank-Fundament (HikariCP, Flyway, Advisory-Lock,
> Tabellen `nodes` und `event_cursors`) ist von M4 nach **M1** gewandert. Grund: Der
> `AuthInterceptor` braucht `nodes.token_hash` und `allowed_ips` dauerhaft gespeichert, und die
> Outbox braucht `event_cursors`. Beides in M1 provisorisch in Dateien zu legen und in M4 neu zu
> bauen wäre doppelte Arbeit. M4 ergänzt nur noch das Spieler- und Rang-Schema.

Phasen M1–M4 sind das Fundament und sollten in dieser Reihenfolge bleiben. M6 und M7 sind
unabhängig voneinander und könnten getauscht werden. 🔧

---

## 15. Verifikation

**Lokal (ein Rechner, zwei JVMs):**
```bash
docker compose up -d                  # postgres + redis
./gradlew build                       # alle Module + Fat-Jars
java -jar master/build/libs/CloudMaster.jar
java -jar wrapper/build/libs/CloudWrapper.jar    # zweites Terminal
```
Erwartung: `node list` zeigt den Wrapper → `rank set <deinName> admin` → `server start lobby`
→ Prozess läuft auf dem Wrapper → Join auf `localhost:25565` → Lobby, Rang-Prefix im Chat.

**Erst-Einrichtung prüfen:** Frische Datenbank, Master starten → Hinweis „noch kein Admin
vorhanden“ erscheint → `rank set` → Hinweis verschwindet. Ohne diesen Schritt ist die Cloud
nicht bedienbar, der Test gehört also an den Anfang.

**Multi-Root-Test:** Zweiter Wrapper mit anderem `node`-Namen (lokal auf anderem Port, oder
echter zweiter Root). Gruppe `bedwars` auf `allowed_nodes: [node-b]` beschränken und prüfen,
dass der Scheduler korrekt platziert. Dann Wrapper hart killen:

- **`DYNAMIC`-Server** → Master erkennt den Timeout und startet sie auf einem anderen Node neu.
- **`STATIC`-Server** → bleiben aus, Zustand `WAITING_FOR_NODE`, Warnung in der Konsole.
  Kommt der Node zurück, startet der Server automatisch mit seiner Welt. **Er darf unter keinen
  Umständen auf einem anderen Node hochkommen** — das ist der wichtigste Einzeltest hier.

**Automatisiert:**
- Unit-Tests: Permission-Auflösung (Vererbung über mehrere Ebenen, Mehrfachvererbung mit
  konkurrierenden `weight`-Werten, Spieler-Permission schlägt Rang, Negation schlägt alles,
  Zyklus wird abgelehnt, abgelaufener Rang fällt auf Fallback zurück), Scheduler-Entscheidungen,
  Template-Hashing
- Integration mit Testcontainers: Postgres + Redis, Migrations, `PlayerService`-Roundtrip
- Sprachdateien: jeder in `de.yml` vorhandene Schlüssel existiert auch in jeder weiteren
  Sprachdatei, und jeder im Code benutzte Schlüssel existiert in `de.yml` — beides als Test,
  sonst fallen fehlende Texte erst im Spiel auf
- Fallback-Auswahl: kein Fallback-Server verfügbar → Join wird abgelehnt, nicht Timeout;
  Gruppe in Wartung fällt als Ziel weg
- Event-System: Pre-Event bricht ab und die Begründung kommt beim Spieler an · ein Handler, der
  eine Ausnahme wirft, stoppt die Verteilung nicht · ein Handler, der hängt, läuft in den
  Timeout und greift auf `DENY` bzw. `ALLOW` zurück · unbekannter Event-Typ aus Redis wird
  still verworfen statt eine Verbindung abzureißen
- gRPC in-process: Master + Wrapper ohne echte Sockets, Start/Stop/Reconnect-Szenarien
- `./gradlew test` muss vor jedem Commit grün sein

**Resilienz-Checkliste:**
- Master-Neustart → Wrapper reconnected, Server überleben und werden adoptiert
- Master 10 min aus, dabei einen Server abstürzen lassen und einen Spieler joinen → nach dem
  Reconnect stehen beide Ereignisse **mit Original-Zeitstempel** in der DB
- Master zweimal hintereinander neu starten, ohne dass die Outbox quittiert wurde →
  kein doppelter Eintrag (Cursor-Dedup)
- Wrapper-Neustart mit gefüllter Outbox → Einträge sind noch da, `seq` zählt weiter
- Master-Ausfall mit Spielern online → niemand wird gekickt, Serverwechsel und Mutes
  funktionieren weiter, **neue Logins werden mit Wartungsmeldung abgelehnt**
- `DYNAMIC`-Server stoppen während Master-Ausfall → Log bleibt in `logs/pending/` und landet
  nach dem Reconnect auf dem Master
- `/rank` während Master-Ausfall → klare Fehlermeldung, keine stille Pufferung
- Permission `vibecloud.dashboard.login` entziehen, während die Person im Dashboard eingeloggt
  ist → Account gelöscht, nächster API-Call `401`
- DB kurz weg (Master läuft weiter, degradiert) · Redis weg (Fallback auf DB) ·
  Server-Crash (Scheduler startet nach)

**Sicherheits-Check (einmal vor dem Produktivbetrieb):** Von außen auf einen Gameserver-Port
im Bereich 30000–30999 verbinden → muss abgelehnt werden. Gelingt es, ist `online-mode: false`
offen im Netz und jeder kann sich als beliebiger Spieler einloggen. Dazu: `nmap` auf jeden Root,
nur 25565, 19132 und ggf. 22 dürfen antworten.

**Angriffstests auf den Master-Port** (als Tests automatisierbar, gRPC in-process):
- Verbindung ohne Token → abgewiesen, kein `Control`-Stream
- Token von `node-a` mit Node-Namen `node-b` → abgewiesen
- Richtiges Token, aber Quell-IP nicht in `allowed_ips` → abgewiesen
- Falscher Master-Fingerprint in der `wrapper.json` → Wrapper verbindet sich nicht
- `node-a` meldet einen Zustand für einen Server von `node-b` → verworfen, Audit-Eintrag
- Gameserver schickt `PlayerJoinNetworkEvent` → verworfen (nur der Proxy darf das)
- Gameserver schickt einen `/rank set`-Befehl mit „darf das"-Flag → Master prüft die Permission
  des Spielers selbst neu und lehnt ab
- Einmal-Secret eines Servers zweimal verwenden → zweiter Versuch abgewiesen
- Einmal-Secret nach 120 s verwenden → abgewiesen

---

## 16. Offene Punkte zum Anpassen 🔧

### 1. Master-Ausfall — **entschieden: ein Master + Outbox-Zwischenspeicher**

Es gibt nur **einen** Master, keine Reserve. Wenn er abstürzt, bricht nichts zusammen:

| Läuft weiter | Geht nicht mehr |
|---|---|
| Alle Gameserver laufen normal weiter | Keine neuen Server starten/stoppen |
| Spieler spielen und joinen weiter | Keine Rang-/Permission-Änderungen, kein ACP |
| Bans und Mutes greifen weiter (lokaler Cache) | Keine *neuen* Rang-Änderungen, kein Dashboard |

**Nichts geht verloren:** Wrapper und Plugins schreiben alles, was in der Zeit passiert, in eine
**Outbox** auf die Festplatte — Server-Zustände, Logins, Spielzeit, in-game ausgesprochene Bans.
Beim Neuverbinden wird die Outbox in der richtigen Reihenfolge nachgespielt und der Master
schreibt es mit dem **Original-Zeitstempel** in die Datenbank, nicht mit dem Replay-Zeitpunkt.

Der Leitsatz dahinter: **Fakten werden gepuffert, Absichten neu entschieden.** Was tatsächlich
passiert ist (ein Server ist abgestürzt, ein Spieler hat sich eingeloggt, jemand wurde gebannt),
wird nachgereicht. Was der Master *wollte* („starte einen zweiten Lobby-Server“) wird beim
Reconnect verworfen und aus dem aktuellen Zustand neu berechnet — sonst startet nach einem
30-minütigen Ausfall ein Schwung veralteter Befehle los.

Vollständige Mechanik inkl. Deduplizierung und Reconciliation: **Abschnitt 7**.

Eine Master-Reserve mit automatischer Übernahme bleibt bewusst draußen. Sie würde nur die paar
Sekunden Neustart einsparen, kostet aber eine Leader-Election zwischen zwei Mastern. Thema für
M8, wenn du weißt, wie oft so ein Ausfall real vorkommt.

### 2. Server-Namensschema — **entschieden: pro Gruppe einstellbar**

Jede Gruppe hat ein Feld `name_pattern`, Standard `%group%-%id%`.

| Pattern | Ergebnis |
|---|---|
| `%group%-%id%` | `lobby-1`, `lobby-2` |
| `%group%#%id%` | `lobby#1` |
| `%group%_%id%` | `bedwars_1` |
| `%node%-%group%-%id%` | `node-a-lobby-1` |

Platzhalter: `%group%`, `%id%` (fortlaufend je Gruppe), `%node%`.
Der Master validiert beim Anlegen: muss `%id%` enthalten, nur `[a-zA-Z0-9_#-]`, max. 32 Zeichen
(Velocity-Limit). Siehe Abschnitt 8, `server_groups.name_pattern`.

### 3. Minecraft-Version — **entschieden: 26.2 (aktuelle stabile Reihe)**

> **Korrektur vom 2026-10-01.** Dieser Punkt stand ursprünglich auf „1.21.x bis zur neuesten".
> Minecraft hat die Versionierung inzwischen umgestellt: nach `1.21.11` kam `26.1` → `26.2` →
> `26.3`. Die 1.21er-Reihe ist damit Altbestand. Geprüft gegen `repo.papermc.io`:
> `26.2` hat stabile Builds (`26.2.build.129-stable`), `26.3` bisher nur Beta.
> Velocity steht bei `4.2.0`, nicht bei 3.5.

Die Version ist **keine globale Einstellung**, sondern gehört zur Gruppe: `mc_version` und
`jar_source`. Damit kannst du `lobby` auf 26.2 lassen und `bedwars` später auf 26.3 ziehen, ohne
die Cloud anzufassen. `mc_version: "latest"` holt automatisch den neuesten stabilen Build.
Der Master lädt das Jar einmal über die PaperMC-API, legt es im Template-Store ab und verteilt
es per Hash an die Wrapper — kein Download auf jedem Root. Siehe Abschnitt 8.

### 4. Template-Strategie — **entschieden: ein Template pro Gruppe + globale Templates**

Keine stapelbaren Layer. Genau zwei Ebenen, wie in deinem alten Projekt:

```
templates/
├── global/
│   ├── server/        → in JEDEN Paper-/Minestom-Server kopiert
│   └── proxy/         → in JEDEN Velocity-Proxy kopiert
└── <template-name>/   → nur in Server dieser Gruppe
```

Kopierreihenfolge beim Start: `global/*` zuerst, dann das Gruppen-Template (darf überschreiben),
dann die Modul-Bundles in `plugins/`. Siehe Abschnitt 6 und 7.

### 5. ~~Weitere Module aus dem alten Projekt~~ — **entschieden: vorerst nur `punishment`**

Welche Altmodule portiert werden, wird nach dem ersten stabilen Betrieb neu gestellt.

### 6. Dashboard-Auth — **entschieden: ACP-Accounts, in-game angelegt**

Kein öffentliches Registrieren. Accounts entstehen nur in-game durch jemanden mit den
passenden Rechten:

| Command | Wirkung | Permission |
|---|---|---|
| `/acp create <spieler>` | Legt einen Dashboard-Account für diesen Spieler an und gibt ein Start-Passwort aus. Beim ersten Login im Dashboard **muss** ein eigenes Passwort gesetzt werden. | `vibecloud.acp.create` |
| `/acp changepw <spieler>` | Setzt das Passwort zurück auf ein neues Start-Passwort, das im Dashboard wieder geändert werden muss. | `vibecloud.acp.changepw` |
| `/acp list` / `/acp delete <spieler>` / `/acp disable <spieler>` | Verwaltung | `vibecloud.acp.manage` |

**Zum Start-Passwort:** Statt eines *festen* Standard-Passworts schlage ich ein **zufällig
generiertes** vor (12 Zeichen), das einmalig im Chat angezeigt wird. Grund: Ein festes
Standard-Passwort ist nach dem ersten Mal jedem bekannt, und zwischen `/acp create` und dem
ersten Login steht der Account offen. Technisch ist beides gleich aufwändig. 🔧 Wenn du
lieber ein festes Passwort willst, ist das eine Zeile in der Config.

**Rechte weg → Account weg.** Der Zugang hängt an einer einzigen Permission:
`vibecloud.dashboard.login`. Verliert ein Spieler sie — egal wie: Rang geändert, Rang
abgelaufen, Permission entzogen, Rang-Vererbung umgebaut — wird sein `dashboard_accounts`-Satz
**automatisch gelöscht** und alle laufenden Sessions werden sofort ungültig. Ein Login ist dann
nicht mehr möglich; ein neuer Zugang entsteht nur über ein neues `/acp create`.

Damit kann der Zugang nicht aus Versehen überleben, wenn jemand degradiert wird — das ist
genau der Fall, in dem man es am wenigsten merkt. Zwei Konsequenzen, die du kennen solltest:

- Die Löschung ist **endgültig**. Wird die Permission versehentlich entzogen, ist der Account
  weg und muss mit neuem Start-Passwort neu angelegt werden. 🔧 Alternative, falls dir das zu
  hart ist: nur `enabled = false` setzen und beim Wiedererlangen der Permission reaktivieren.
- `/acp create` für einen Spieler **ohne** diese Permission wird mit einer klaren Meldung
  abgelehnt — sonst würde der Account im selben Moment wieder gelöscht.

Details zum Ablauf und zur Tabelle `dashboard_accounts`: Abschnitt 12.

### 7. Wrapper-Deployment — **entschieden: manuell, systemd geplant**

Der Wrapper wird von Hand gestartet. Im Repo liegt eine fertige
`wrapper/vibecloud-wrapper.service` als Vorlage (Restart=always, eigener User `vibecloud`,
`WorkingDirectory`), die du auf den Roots nur noch kopieren und aktivieren musst.

### 8. ~~Repository / CI~~ — **entschieden: machst du selbst**

Kein `git init`, kein CI-Setup durch mich. Ich lege nur eine passende `.gitignore` an
(Gradle-Caches, `build/`, `.idea/`, `config.json`, `templates/`, `servers/`, `*.jar`).

### 9. Netzwerk zwischen den Roots — **entschieden: Firewall-Whitelist**

Gameserver-Ports (30000–30999) nur für die Proxy-IPs offen. Damit die Regeln nicht von Hand
gepflegt werden müssen, verwaltet der Wrapper ein nftables-Set, das der Master mit den aktuellen
Proxy-IPs füttert. Details und die Begründung, warum das nicht optional ist: **Abschnitt 13**.

### 10. Proxy — **entschieden: Cloud-verwaltete Gruppe, mehrere Instanzen**

`platform: VELOCITY`, `static: true`, ein Proxy pro Node (Port 25565), Verteilung über DNS.
Der Master aggregiert Spielerzahlen proxy-übergreifend über Redis. Siehe **Abschnitt 11**.

### 11. Rang-Darstellung — **entschieden: Chat, Tab-Liste und Nametags im Core**

Ein `RankDisplayService` in den Plattform-Plugins, Chat-Format als MiniMessage-Template
(global in der Config, pro Rang über `ranks.chat_format` überschreibbar), Tab und Nametags über
Scoreboard-Teams mit invertiertem `weight` zur Sortierung. Siehe **Abschnitt 11**.

### 12. Bedrock — **entschieden: Geyser + Floodgate von Anfang an**

`players.platform` und `players.xuid` sind von der ersten Migration an dabei, Geyser/Floodgate
liegen in den globalen Templates. Account-Verknüpfung (Java ↔ Bedrock derselbe Datensatz)
bleibt bewusst draußen. Siehe **Abschnitt 11**.

---

## 17. Betriebsentscheidungen

### 1. `STATIC`-Server — **an einen Node gebunden, Welt bleibt dort**

Beim ersten Start wählt der Master einen freien Node, schreibt die Bindung in
`static_server_bindings` und ändert sie nie von allein. Welt, Plugin-Daten und Logs bleiben
auf diesem Root. Fällt der Node aus, bleibt der Server aus (`WAITING_FOR_NODE`) und startet
automatisch, sobald der Node zurück ist — er weicht **nie** auf einen anderen Node aus, weil er
dort mit leerer Welt hochkäme. Mechanik und Umzugs-Command: **Abschnitt 6**.

### 2. Master und Wrapper — **dürfen auf demselben Server laufen**

Für den Anfang läuft beides zusammen. Das ist kein Sonderfall im Code: Der Wrapper verbindet
sich dann einfach auf `127.0.0.1:5000`, alles andere bleibt identisch. Zwei Dinge dazu:

- Die Ports beißen sich nicht (Master 5000/8080, Gameserver ab 30000).
- Die Firewall-Regel aus Abschnitt 13 gilt auch auf diesem Node. Wenn der Proxy ebenfalls dort
  läuft, steht in der Whitelist eben `127.0.0.1` — die Regel bleibt trotzdem nötig, damit die
  Gameserver-Ports nicht von außen erreichbar sind.

Später ziehst du den Master einfach auf eine eigene Maschine: `masterHost` in der `wrapper.json`
ändern, fertig. Deshalb gibt es auch keinen „lokalen Modus“, der Code kennt nur den Netzwerkfall.

### 3. Logs — **entschieden: `DYNAMIC` zum Master, `STATIC` bleibt am Node**

Beim Stopp eines `DYNAMIC`-Servers geht der komprimierte Log an den Master nach
`logs/<gruppe>/<server>-<zeitstempel>.log.gz`, weil das Server-Verzeichnis danach gelöscht wird.
`STATIC`-Server behalten ihre Logs vor Ort. Ist der Master beim Stopp nicht erreichbar, bleibt
der Log in `logs/pending/` und wird beim Reconnect nachgeschickt — er geht also auch bei einem
Ausfall nicht verloren. Ablauf im Detail: **Abschnitt 7**.
Aufbewahrung auf dem Master: 🔧 14 Tage.

### 4. Bestätigte Standardwerte

| Thema | Umsetzung |
|---|---|
| ACP-Start-Passwort | Zufällig generiert (12 Zeichen) statt festes Standard-PW |
| `/rank`, `/perm`, `/acp` bei Master-Ausfall | Ablehnen mit klarer Meldung, nicht puffern |
| **Spieler-Logins bei Master-Ausfall** | **Werden abgelehnt.** Wer schon online ist, bleibt online und spielt weiter |
| Dashboard-Account bei Rechte-Entzug | Hart löschen (nicht nur deaktivieren) |
| Firewall-Verwaltung durch den Wrapper | Aktiv (`manage: true`), braucht `CAP_NET_ADMIN` |
| Gameserver-Port-Bereich | 30000–30999 |
| Outbox-Grenze | 50 MB bzw. 7 Tage, dann älteste verwerfen |
| Stop-Gnadenfrist | 30 Sekunden, dann `destroyForcibly()` |
| Dashboard-Login-Sperre | Nach 5 Fehlversuchen |
| Log-Aufbewahrung auf dem Master | 14 Tage |

### 5. Erst-Einrichtung — **entschieden: Seed-Ränge + Konsole hat alle Rechte**

Erste Migration legt `spieler` (Default) und `admin` (`vibecloud.*`) an. Die Master-Konsole ist
implizit berechtigt, dein erster Befehl ist `rank set <deinName> admin`. Siehe **Abschnitt 9**.

### 6. Join-Ziel — **entschieden: Fallback-Gruppen mit Priorität**

`server_groups.fallback` und `join_priority` bestimmen das Einstiegsziel; innerhalb der Gruppe
wird der vollste noch nicht ausgelastete Server gewählt (auffüllen statt verteilen). Kein Ziel
verfügbar → Join wird mit Meldung abgelehnt. Siehe **Abschnitt 11**.

### 7. Sprachsystem — **entschieden: mehrsprachig ab Start, Standard Deutsch**

Alle Texte in `messages/<kürzel>.yml`, kein spielersichtbarer String im Code. `/language
<kürzel>` wechselt und speichert in `players.locale`, beim ersten Join wird die Client-Sprache
übernommen. Module bringen eigene Dateien unter ihrer Modul-ID mit. Siehe **Abschnitt 11a**.

### 8. Wartungsmodus — **entschieden: global und pro Gruppe**

`/maintenance on [gruppe]`, Bypass über `vibecloud.maintenance.bypass`. Global in
`cloud_settings`, pro Gruppe in `server_groups.maintenance`. Siehe **Abschnitt 11**.

### 9. Event-System — **ausgearbeitet: Pre-/Post-Events mit Katalog**

Ein `EventBus` für Master, Module und Plugins. Abbrechbare Pre-Events (synchron im Master, mit
Timeout und Handler-eigenem Rückfall auf `DENY`/`ALLOW`), nicht abbrechbare Post-Events
(asynchron, clusterweit über Redis). Module dürfen eigene Events werfen und exportieren dafür
Pakete in `module.json`. Vollständiger Katalog: **Abschnitt 10a**.

### 10. Sicherheit der Master-Verbindung — **ausgearbeitet in vier Schichten**

Firewall plus `nodes.allowed_ips`, Fingerprint-Pinning des Master-Zertifikats in der
`wrapper.json`, Token pro Node (Argon2id-Hash, an den Node-Namen gebunden, rotierbar) und —
der wichtigste Teil — Befugnis-Prüfung pro Nachricht gegen die Verbindungsidentität. Der Master
prüft jede Berechtigung selbst neu, Plugins übertragen nur, *wer* etwas angefordert hat.
Siehe **Abschnitt 13**.

### 11. Modul-Kommunikation — **entschieden: ein generischer Kanal, keine Protos pro Modul**

`context.channel().request(...)` / `.respond(...)` über die bestehende Verbindung, Records als
JSON, Schlüssel automatisch unter der Modul-ID, Server-zu-Server nur über den Master. Fragen
laufen über den Kanal, Benachrichtigungen über die Events. Siehe **Abschnitt 10**.

### 12. Weitere Festlegungen aus dem letzten Durchgang

| Thema | Festlegung |
|---|---|
| Erst-Einrichtung | `cloud setup` legt `proxy`- und `lobby`-Gruppe an — frische Installation in drei Befehlen spielbar (Abschnitt 9) |
| Minestom- und eigene Jars | `jar_source: template` — das Jar liegt im Gruppen-Template, kein Download (Abschnitt 6) |
| Gruppen-Änderungen | Wirken erst auf neu gestartete Server, laufende bleiben unangetastet |
| Update-Reihenfolge | Erst Master, dann Wrapper. Master akzeptiert eine Version zurück (Abschnitt 5) |
| Zeitangaben | Überall `timestamptz` in UTC, Umrechnung erst bei der Anzeige (Abschnitt 8) |
| Modul-Ladereihenfolge | Topologisch nach `depends`, Zyklus wird mit Nennung der Module abgelehnt |

### 13. Dashboard und Module — **entschieden: Seite im Dashboard, Logik im Modul**

Modul-Seiten sprechen nur gegen `/api/modules/<id>/…` und werden ausgeblendet, wenn das Modul
nicht geladen ist. Keine von Modulen mitgelieferten JavaScript-Bundles. Siehe **Abschnitt 12**.

### 14. Zwei weitere Festlegungen

| Thema | Festlegung |
|---|---|
| Stopp mit Spielern drauf | Spieler werden zuerst auf ein Fallback-Ziel verschoben, erst dann folgt der Stopp-Befehl (Abschnitt 7) |
| Doppelter Master-Start | `pg_try_advisory_lock` — der zweite Master beendet sich mit klarer Meldung (Abschnitt 6) |

---

## 18. Stand

Der Plan ist aus meiner Sicht umsetzungsreif. Alles, was noch mit 🔧 markiert ist, sind
Konfigurationswerte mit gesetzter Vorgabe — Port-Bereiche, Zeitlimits, Aufbewahrungsfristen —
keine offenen Entscheidungen. Was darüber hinaus auftaucht, sind Detailfragen, die man beim
Schreiben des Codes besser beantwortet als vorher auf dem Papier.

Nächster Schritt: **M0** — Gradle-Gerüst, Version Catalog, Convention-Plugins,
`docker-compose.yml`, `.gitignore`, `CLAUDE.md`.
