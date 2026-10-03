# vibeCloud

Minecraft-Cloud mit Master/Wrapper-Architektur (Multi-Root). **`PLAN.md` ist die
verbindliche Quelle** fuer Architektur und Entscheidungen — bei Widerspruch gilt PLAN.md.

# Repo, Lizenz und .gitignore
- **GPL-3.0** (`LICENSE`, Wortlaut der FSF, unveraendert). Grund: `paper-api` und
  `velocity-api` stehen selbst unter GPL-3.0, und `platform/vibecloud-paper` und
  `platform/vibecloud-velocity` kompilieren dagegen. Unter einer permissiven Lizenz waere
  offen, ob die ausgelieferten Plugin-Jars damit vereinbar sind. Dieselbe Lizenz wie die
  Abhaengigkeiten laesst die Frage gar nicht erst entstehen.
- **Jedes Laufzeit-Muster in der `.gitignore` ist mit `/` verankert.** Ohne das trifft ein
  Muster jeden gleichnamigen Ordner im Baum. Genau so war `outbox/` einmal gemeint als
  Wrapper-Verzeichnis und schluckte das Java-Paket
  `de.kevloe.vibecloud.api.outbox` - drei Quelldateien, die nie im Repo gelandet waeren.
  Aufgefallen waere es erst beim naechsten Klon. Dieselbe Falle liegt bei `messages/`:
  Dort stehen die `de.yml` von Master und Punishment-Modul als echte Ressourcen.
  Neue Muster deshalb immer mit `git check-ignore -v <pfad>` gegenpruefen, in **beide**
  Richtungen.
- **`tls/` und `secrets/` muessen draussen bleiben** - dort liegen der private
  TLS-Schluessel, der JWT-Schluessel und das Forwarding-Secret. `gradlew run` startet im
  Projektordner des Moduls, also entstehen sie innerhalb des Repos.
- **`.gitattributes` nagelt die Zeilenenden fest.** Entwickelt wird unter Windows
  (`core.autocrlf=true`), die Roots laufen unter Linux; ein `gradlew` mit CRLF scheitert
  dort mit "bad interpreter".

# Stand
- M0 fertig: Gradle-Geruest, Version Catalog, Convention-Plugin, docker-compose.
- M1 fertig: node.proto, gRPC-Server mit TLS, AuthInterceptor (Token + allowed_ips + Drosselung),
  Fingerprint-Pinning im Wrapper, Register/Heartbeat/Control-Stream mit Reconnect, FileOutbox,
  EventCursorStore, LocalEventBus, DB-Fundament (HikariCP, Flyway, Advisory-Lock), JLine-Konsole
  mit `node`-Befehlen. 26 Tests gruen.
- M2 fertig: ServerRuntime + ProcessServerRuntime, inhaltsadressierter Template-Cache mit
  Blob-Sync und Hardlinks, JarStore mit Pruefsummen-Pruefung, Servergruppen, Namensmuster,
  PlacementScheduler, static_server_bindings, FullState-Adoption, Konsolen-Streaming mit
  `screen`, Log-Upload. 43 Tests gruen. Ein echter Paper-26.2-Server laeuft nachweislich.
- M3 fertig: PluginService (Einmal-Secret -> Sitzungs-Token), Velocity-Plugin (Login-Gate,
  Backend-Registrierung, Fallback), Paper-Plugin, Minestom-Bibliothek, MessageService mit
  de.yml, ServerConfigurator (Forwarding-Secret, Ports, online-mode), nftables-Verwaltung,
  Geyser-Doku. 62 Tests gruen. Velocity 4.2 und Paper 26.2 laufen unter Cloud-Kontrolle,
  beide Plugins verbunden, Proxy kennt die Backend-Server.
- M4 fertig: Spieler- und Rang-Schema (V3), PermissionResolver mit DAG-Vererbung,
  Negationen, Kontexten und Ablauf, PermissionService mit Caffeine-Cache und
  Invalidierung ueber die gRPC-Streams, PlayerService mit Pre-Login-Gate,
  `rank`/`perm`/`maintenance`-Befehle, RankDisplay (Chat/Tab/Nametags), /language,
  Velocity-PermissionProvider. 86 Tests gruen.
- M5 fertig: module-api (CloudModule, ModuleContext, Kanal, Commands, Datenbank, Config),
  ModuleClassLoader mit child-first und Export-Sichtbarkeit, ModuleLoadOrder (topologisch,
  Zyklus-Erkennung), ModuleManager mit load/unload/reload zur Laufzeit, Modul-Kanal ueber
  die bestehenden gRPC-Streams, Bundle-Injektion in die Templates, Beispielmodul.
  125 Tests gruen.
- M6 fertig: module-punishment (Ban/TempBan/BanIP/Mute/TempMute/Kick/Warn, History,
  Einspruchs-Kennung, Begruendungs-Templates), Login-Gate ueber PlayerPreLoginEvent,
  Mute-Filter als Paper-Bundle im Modul-JAR, Modul-Kanal auf der Plugin-Seite
  (PluginModuleChannel), SendMessage/BroadcastMessage im Protokoll, Modul-Texte
  (messages/<sprache>.yml je Modul). 164 Tests gruen.
- Serverwechsel: `switch`/`send`, `MovePlayer` fuer Plugins, `switchServer(...)` in allen
  drei Plattform-Plugins, REST-Endpunkt.
- M7 gebaut: REST-Endpunkte (Server, Gruppen, Nodes, Spieler, Raenge, Module),
  API-Tokens (V4), Dashboard-Zugaenge (V5) mit Argon2id, Zwangswechsel des
  Start-Passworts, JWT samt Sitzungs-Version, automatische Loeschung bei Rechte-Entzug,
  `acp`- und `player`-Befehle, WebSocket fuer Zustaende und Server-Konsole,
  Dashboard (Vite + React + Tailwind), von Javalin mit ausgeliefert. 239 Tests gruen.
- Dashboard erweitert: Uebersichtsseite (Zahlen, Online-Liste, zuletzt gesehen),
  Server-Detailseite mit Live-Log, Gruppen/Nodes/Raenge anlegen und aendern,
  Spielersuche nach Namen, Editor fuer die Modul-Konfiguration. Dazu im Master:
  `OnlinePlayers`, Such- und Uebersichts-Endpunkte, `AdminRoutes` zum Schreiben.
- Sprachen im Dashboard: anlegen, uebersetzen, loeschen und neu einlesen
  (`MessageRoutes`, Seite "Sprachen"). 250 Tests gruen.
- Rechte im Dashboard: einzelne Regeln von Raengen und Spielern sehen, anlegen und
  entfernen, dazu `perm check` als Formular (`PermissionRoutes`, `pages/Permissions.tsx`).
  Knoten-Katalog aus `CommandRegistry.permissionNodes(...)` - dieselbe Liste wie die
  Tab-Vervollstaendigung. 259 Tests gruen. Begruendungen unter "Rechte im Dashboard".
- Gruppen und Raenge werden als ganzes Formular bearbeitet: alle aenderbaren Felder mit
  ihrem aktuellen Wert, der Master liefert sie in `fields` mit (`valueOf` neben
  `updateField`). 243 Tests gruen.
- Dashboard neu gestaltet: nur noch eine Seitenleiste (Seiten oben, Zugang unten),
  Bearbeiten in Modalen, Rueckmeldungen als Toasts, eigene Bausteine unter `src/ui/`
  (Icon, Button, Form, Modal, Toast, Layout, Sidebar), Farben als `@theme`-Tokens,
  Schriften im Bundle. Begruendungen unter "Oberflaeche des Dashboards".
- Rang eines Spielers im Dashboard setzen und zuruecksetzen (`AdminRoutes`, Dialog in
  `pages/Players.tsx`). Uebersetzen auf einer eigenen Seite statt im Dialog, dazu die
  Texte der Module (`modules/<id>/messages/<sprache>.yml`). Seite "Einstellungen":
  Wartungsmodus und die aenderbaren Felder der `config.json` (`SettingsRoutes`,
  `MasterConfigFile`, `MaintenanceSwitch`), in der Konsole als `cloud config`.
  274 Tests gruen. Begruendungen unter "Einstellungen im Dashboard" und "Sprachen im
  Dashboard".
- SFTP fuer statische Server: `SftpGateway` im Wrapper (Apache MINA SSHD), entschieden
  wird im Master (`SftpAccountService`, RPC `AuthenticateSftp`, Tabelle V6), Zugaenge mit
  `sftp create <spieler>`, Recht je Server `vibecloud.sftp.<server>`. 293 Tests gruen.
  Durchgespielt mit eigenem Master, eigenem Wrapper und dem OpenSSH-Client in einer
  Wegwerf-Datenbank: hochladen, holen, loeschen, falsches Passwort, Server eines anderen
  Nodes, entzogenes Recht. **Nicht** geprueft: unter Linux, von aussen ueber eine echte
  Firewall, mit FileZilla oder WinSCP. Begruendungen unter "SFTP fuer statische Server".
- Seite "Dateien" im Dashboard: eigene SFTP-Verbindungsdaten je Server, Passwort selbst
  erzeugen, Knopf "In WinSCP oeffnen". 295 Tests gruen. Im Browser gesehen (Liste,
  Zustand "Node nicht verbunden", Passwort-Dialog) und mit dem dort erzeugten Passwort per
  OpenSSH verbunden. **Nicht** geklickt: der WinSCP-Knopf selbst - ob Windows die Adresse
  an WinSCP uebergibt, haengt an dessen Installation.
- SFTP auch fuer Templates: Der Master bietet es selbst an (`TemplateSftp`), der
  SFTP-Code liegt jetzt im Modul `common/vibecloud-sftp`, die Seite "Dateien" hat einen
  zweiten Abschnitt. 305 Tests gruen. Durchgespielt in der Wegwerf-Umgebung: Template per
  OpenSSH gefuellt, Ablehnung ohne Recht, fuer eine unbekannte Gruppe und fuer `global`,
  Seite im Browser gesehen. **Nicht** geprueft: dass ein danach gestarteter Server die
  Datei wirklich bekommt - dafuer haette ein echter Paper-Server starten muessen.
- Wartungsmodus: Schalter in der Seitenleiste, Wartung je Gruppe auf der Seite "Gruppen",
  beides ueber `MaintenanceSwitch`. Dabei gefunden und behoben: Der globale Schalter
  ueberlebte keinen Master-Neustart. 308 Tests gruen, im Browser durchgeklickt.
  **Nicht** geprueft: was ein Proxy daraus macht - es war keiner verbunden.
- Befehle an die Konsole eines Servers aus dem Dashboard (Server-Detailseite,
  `POST /servers/{name}/command`, Recht `vibecloud.command.screen.send`). Dabei gefunden
  und behoben: `group edit ... memory 64` wurde gespeichert und legte den Scheduler fuer
  alle Gruppen still. 312 Tests gruen. Durchgespielt mit einem Stellvertreter-Server, den
  die Cloud aus einem Template gestartet hat - der Befehl stand danach in seinem Log.
  **Nicht** gesehen: das Eingabefeld im Browser (die Erweiterung war nicht verbunden) und
  ein echter Paper-Server.
- Mehrere Server-Versionen: Paper ab 1.16 je Gruppe waehlbar, in der Konsole
  (`group create <name> <plattform> [version] [template]`, `group versions`,
  `group edit ... mc_version`) und im Dashboard (Auswahl beim Anlegen, Vorschlaege beim
  Bearbeiten, Java-Spalte bei den Nodes). Liste und Mindest-Java aus der PaperMC-API
  (`VersionCatalog`), das Jar wird nach dem Speichern geladen. Unter 26.2 das neue
  Legacy-Plugin (`platform/vibecloud-paper-legacy`, Java 17), Java je Node ueber
  `javaRuntimes` in der wrapper.json. Dabei gefunden und behoben: Plugins kamen unter
  Java 17/21 nicht durch TLS (`FingerprintTrustManager`), und `CloudConnection` liess bei
  jedem Wiederverbinden einen gRPC-Kanal offen. 355 Tests gruen. Durchgespielt in einer
  Wegwerf-Umgebung: 1.16.1, 1.16.5, 1.20.4, 1.21.4 und 26.2 von der Cloud gestartet, jedes
  Plugin am Master angemeldet, eine Gruppe im Browser angelegt. Begruendungen unter
  "Mehrere Server-Versionen".
- Nur zwei Jars: Master-Jar mit Plattform-Plugins und Dashboard, Einrichtungs-Assistent,
  `CloudWrapper.jar join ...`, `./gradlew releaseJars`. Durchgespielt in einem leeren Ordner
  mit Wegwerf-DB: Master ohne Konsole (Vorlage), dann mit `cloud setup` und `node add`,
  Dashboard aus der Jar, Wrapper per `join` -> proxy-1 und lobby-1 laufen, beide Plugins
  angemeldet. **Nicht** geprueft: der Assistent an einer echten Konsole (nur per Test mit
  Antwortliste) und das Ganze unter Linux. Begruendungen unter "Nur zwei Jars".
- Offen in M7: `/acp` ist noch nicht im Spiel getestet, und die Online-Liste ist ohne
  Spieler nie gefuellt gesehen worden. Das Dashboard ist am Breitbild geprueft,
  **nicht am Telefon** - die Schublade der Seitenleiste unter 1024 px hat nie jemand
  gesehen.
- **Die drei Neuen hat noch niemand im Browser gesehen.** Geprueft ist: Tests gruen,
  `npm run build` ohne Typfehler, der Master startet und jeder neue Endpunkt antwortet
  ohne Anmeldung mit 401 statt 404. Angemeldet durchgeklickt - Rang-Dialog,
  Uebersetzungs-Seite, Einstellungen - ist nichts davon.

# NICHT verifiziert oder bewusst offen (M4)
- **Die Rang-Anzeige im Spiel.** `RankDisplay` kompiliert und wird aufgerufen, aber ohne
  Minecraft-Client konnte niemand Prefix, Tab-Sortierung oder Nametag wirklich sehen.
  Geprueft ist: Der Master liefert Rang samt Prefix, die Plugins laden ihn, die
  Invalidierung kommt an.
- **Die Paper-Bridge greift tief in Interna.** `PermissibleInjector` tauscht per Reflection
  das `PermissibleBase`-Feld von `CraftHumanEntity` aus. Gesucht wird ueber den **Typ**, nicht
  den Namen, damit ein Umbenennen in Paper sie nicht bricht. Dass das Feld existiert, ist mit
  `javap` am entpackten Server-Jar geprueft; dass final-Instanzfelder unter Java 25
  beschreibbar sind, deckt ein Test ab. **Die Injektion in einen echten Spieler ist ohne
  Client nicht geprueft.** Scheitert sie, laeuft der Server weiter und warnt einmal.
- **Der Snapshot ist ein warmer Start, kein Rettungsnetz.** Mit `offline-logins=false`
  (Standard, PLAN.md 17.4) sorgt er nur dafuer, dass Rang und Rechte nach einem
  Proxy-Neustart sofort da sind statt erst nach der ersten Master-Antwort. Seinen
  eigentlichen Zweck bekommt er erst mit `offline-logins=true` in
  `plugins/vibecloud/config.properties` - dann entscheidet der Proxy bei einem
  Master-Ausfall aus dem Snapshot. Aelter als 10 Minuten wird er verworfen.
- **Redis wird nicht benutzt.** Begruendung siehe unten - es hat in M4 keinen Abnehmer.

# NICHT verifiziert oder bewusst offen (M6)
- **Der Chat-Filter wurde nie von einem Spieler ausgeloest.** Geprueft ist: Die Cloud
  verteilt `bundles/paper.jar` in die Gameserver, Paper 26.2 laedt es und meldet
  "Mute-Filter aktiv", und der Master beantwortet die Mute-Abfrage korrekt
  (`PunishmentLoginGateTest`). Was fehlt, ist eine echte Chatnachricht - dafuer braucht
  es einen Minecraft-Client.
- **Das Login-Gate ist gegen eine echte Datenbank geprueft, aber nicht gegen einen echten
  Login.** `PunishmentLoginGateTest` loest `PlayerPreLoginEvent` genauso aus wie der
  Master beim Login; der Weg vom Proxy bis dorthin stammt aus M4 und ist unveraendert.
- **`SendMessage` und `BroadcastMessage` sind ungetestet im Betrieb.** Velocity behandelt
  beide, aber ohne Spieler auf dem Proxy hat nie jemand eine Meldung gesehen. Betroffen
  sind Verwarnungen und die Strafmeldungen ans Team - nicht Bann oder Mute selbst.
- **Es gibt nur ein Modul.** Zusammenspiel mehrerer Module (Exporte, Abhaengigkeiten,
  Ladereihenfolge) ist nur durch Tests abgedeckt, nicht im Betrieb.

# Offener Befund: Plugins nach einem Master-Neustart
Beim Testen des Dashboards sichtbar geworden, **nicht** von M7 verursacht:

Ein Gameserver, der einen Master-Neustart ueberlebt, bekommt sein Plugin nicht mehr
verbunden. Das Einmal-Secret ist verbraucht, und `ServerSessionStore` haelt `pending`
und `sessions` nur im Speicher - nach dem Neustart kennt der Master weder das eine noch
das andere. Im Log des Servers steht dann endlos:

```
Keine Verbindung zum Master (UNAUTHENTICATED: Secret ungueltig, verbraucht oder abgelaufen)
```

Der Wrapper meldet den Server weiter als `RUNNING` und der Master adoptiert ihn - aber
ohne Plugin gibt es keine Spielerzahlen, keine Rang-Daten und keine Befehle dorthin.
Die Zusage aus M2 ("ein Master-Neustart stoppt keine Gameserver") stimmt damit nur zur
Haelfte.

**Vorschlag:** Der Wrapper hat das Secret jedes Servers noch in dessen
`vibecloud-connection.json`. Schickt er es im `FullState` mit, kann der Master die
Pending-Eintraege wieder anlegen, und das Plugin kommt beim naechsten Versuch herein.
Der Weg ist sicher, weil der Node-Kanal authentifiziert und fingerprint-gepinnt ist.

# NICHT verifiziert (ehrlich halten)
- **Ein echter Spieler-Join.** Hier gibt es keinen Minecraft-Client. Geprueft ist: Proxy
  lauscht auf 25565, Backend ist registriert, Forwarding-Secret stimmt auf beiden Seiten
  ueberein. Der Join selbst muss mit einem Client getestet werden.
- **Die Firewall-Verwaltung** (`NftablesFirewall`). Laeuft nur unter Linux mit nftables;
  hier ist nur der erzeugte Regelsatz getestet, nicht seine Wirkung. Vor dem
  Produktivbetrieb: von aussen auf einen Port 30000-30999 verbinden, muss abgelehnt werden.
- **Geyser/Floodgate.** Siehe `docs/GEYSER.md` - reine Template-Dateien, kein Code.

# Was bewusst noch fehlt
- `allowed_ips` vergleicht nur Einzeladressen; CIDR kommt mit der Firewall-Verwaltung in M3.
- `connect_secret` wird erzeugt und in `vibecloud-connection.json` abgelegt, aber noch von
  niemandem gelesen - das machen die Plugins in M3.
- Spielerzahlen sind immer 0, deshalb greifen `start_percent` und `idle_timeout` noch nicht
  wirklich. Die Zahlen liefert der Proxy ab M3.
- Modul-Bundles sind im Manifest vorgesehen, aber es gibt noch keine Module (M5).
- Spielerzahlen kommen jetzt vom Proxy, aber `players`/`ranks` gibt es noch nicht (M4).
- Das Login-Gate prueft Wartungsmodus und Fallback-Ziel. Bans haengen sich ab M6 ueber
  `PlayerPreLoginEvent` ein - der Core kennt keine Bans.

# Dashboard und Zugaenge
- **Keine Registrierung.** Ein Zugang entsteht nur mit `acp create <spieler>` und haengt
  an der Minecraft-UUID. Damit gelten im Dashboard **dieselben Rechte wie im Spiel** -
  `vibecloud.command.server.stop` entscheidet an beiden Stellen. Kein zweites
  Rechtesystem, das auseinanderlaeuft.
- **`vibecloud.dashboard.login` ist die Eintrittskarte.** Faellt sie weg - durch
  `rank set`, `perm remove`, geaenderte Vererbung oder einen abgelaufenen Rang -, loescht
  der `AccountService` den Zugang. Er haengt dafuer am `PlayerPermissionsChangedEvent`,
  das bei all diesen Ursachen ausloest. Zusaetzlich prueft der Login es noch einmal:
  Das Event kann ausfallen, der Login nicht.
- **Die Sitzungs-Version steht in der Datenbank, nicht im Speicher.** PLAN.md nennt dafuer
  Redis; das waere hier falsch: Nur der Master stellt Token aus und prueft sie, und ein
  Zaehler im Speicher waere nach einem Neustart wieder bei 1 - alte Token wuerden
  **wieder gelten**. Die Spalte `dashboard_accounts.session_version` loest das ohne
  zusaetzlichen Dienst.
- **Jeder Aufruf prueft die Version mit**, nicht nur der Login. Deshalb wirkt ein
  Rechte-Entzug oder ein Passwortwechsel binnen Sekunden statt erst beim naechsten
  Anmelden.
- **Solange das Start-Passwort gilt, geht nur "Passwort setzen"** - jeder andere Aufruf
  bekommt 403. Sonst koennte jemand dauerhaft mit dem im Chat gezeigten Passwort
  arbeiten.
- **Passwoerter: Argon2id**, Tokens: SHA-256. Das ist kein Widerspruch - ein Passwort ist
  erratbar, ein 32-Byte-Token aus `SecureRandom` nicht. Beides liegt in `Argon2Hash`
  bzw. `ApiTokenService`, mit der Begruendung daneben.
- **JWT ist selbst gebaut** (HS256, `security/Jwt`). Der Master stellt die Token aus und
  prueft sie selbst - es gibt keinen dritten Beteiligten, mit dem ein Format abzustimmen
  waere. Drei Felder und eine Signatur rechtfertigen keine Abhaengigkeit mit eigenem
  Versionszyklus.
- **WebSockets melden sich mit einer Einmal-Karte an** (`WsTickets`), nicht mit dem
  Zugangstoken. Ein Browser kann beim WebSocket-Aufbau keine Kopfzeilen setzen; das Token
  muesste in die Adresse und landete damit in Verlauf und Protokollen. Die Karte gilt
  30 Sekunden und genau einmal.
- **`/ws/events` schickt Aufnahmen, keine Einzelmeldungen** - alle zwei Sekunden und nur
  bei Aenderung. Ein Event je Zustandswechsel haette die Kernpfade angefasst, auf denen
  Server starten und stoppen; zwei Sekunden sind in einer Oberflaeche nicht von "sofort"
  zu unterscheiden.
- **Spielerzahlen zaehlen nur Proxys.** Ein Spieler ist gleichzeitig auf einem Proxy und
  einem Gameserver - zusammengezaehlt waere er doppelt da.
- **Javalin liefert das Dashboard selbst aus** (`master/dashboard/`). Der Pfad muss
  `normalize()` sein: Mit einem `.` mitten im Pfad findet Jetty die Dateien nicht, jede
  Anfrage faellt auf die `index.html` zurueck, und der Browser bekommt HTML statt
  JavaScript - eine weisse Seite ohne Fehlermeldung.
- **Einzelne Rechte gibt es jetzt auch im Dashboard** - aber keine zweite Auswertung:
  Die Oberflaeche zeigt Regeln an und schickt jede Aenderung an `PermissionService`,
  denselben Dienst, den `perm` in der Konsole ruft. Begruendungen unter "Rechte im
  Dashboard".
- Abweichungen von PLAN.md Abschnitt 2: **kein TanStack Query** (fuer diese Groesse
  genuegen `fetch` und `useEffect`) und **kein shadcn/ui** (bringt eine Generator-CLI und
  ein Dutzend Radix-Abhaengigkeiten mit, fuer sieben Seiten unverhaeltnismaessig).
  Tailwind ist drin.

# Oberflaeche des Dashboards
- **Nur eine Seitenleiste, keine Kopfzeile.** Oben die Seiten, unten der eigene Zugang.
  Ein Kopfbereich waere ein zweiter Ort zum Orientieren und haette die Breite gekostet,
  die Tabellen und Logs brauchen. Den Titel bringt jede Seite selbst mit (`PageHeader`).
- **Der aktive Punkt wird nicht nur farbig markiert**, sondern mit einem Balken links und
  `aria-current="page"`. Farbe allein ist fuer Farbenblinde und fuer eine Sprachausgabe
  keine Information. Dasselbe gilt fuer Serverzustaende: Punkt **und** Wort (`StateDot`).
- **Geaendert wird in Modalen, gemeldet wird mit Toasts.** Ein Toast ist fuer etwas, das
  man zur Kenntnis nimmt ("gespeichert"); alles, was gelesen und entschieden werden muss,
  gehoert in ein Modal oder an das Formular. Deshalb steht ein Anmeldefehler am Formular
  und das Node-Token in einem Dialog, der weggeklickt werden muss - ein Toast waere nach
  vier Sekunden weg, und das Token gibt es nur einmal.
- **Zwei Ausnahmen vom Modal, beide mit Grund.** Uebersetzt wird auf einer eigenen Seite:
  Es sind ueber hundert Zeilen, und im Dialog sah man davon einen Ausschnitt, der in sich
  selbst scrollt. Suche und Speichern bleiben dort beim Scrollen oben stehen, und "Zurueck"
  fragt nach, wenn etwas ungespeichert ist. Die Einstellungen sind selbst das Formular -
  ein Dialog ueber einer sonst leeren Seite waere ein Klick ohne Zweck.
- **Das Modal faengt den Fokus.** Beim Oeffnen springt er auf das erste Feld, Tab bleibt
  darin, Escape schliesst, und danach steht er wieder auf dem Knopf, der es geoeffnet hat.
  Ohne das waere die Oberflaeche mit der Tastatur unbedienbar. Geschlossen wird nur bei
  einem Klick, der auf dem Hintergrund **beginnt** - sonst wirft ein Markieren von Text,
  das aus dem Dialog herauszieht, die Eingaben weg.
- **Zwei Toast-Bereiche:** Erfolg und Hinweis hoeflich (`aria-live="polite"`), Fehler
  unterbrechend (`assertive`). Mit einem gemeinsamen Bereich muesste man sich fuer eines
  von beidem entscheiden.
- **Vorschlaege sind Vorschlaege.** Die Werte aus `/groups/fields` stehen als `datalist`
  an einem freien Feld, nicht als `select`: `min_online` schlaegt 0-3 vor, aber 5 ist
  genauso gueltig - als Auswahlliste waere das Dashboard enger als die Konsole. Nur
  `true`/`false` ist wirklich eine Auswahl und darf ein `select` sein.
- **Bearbeiten heisst: alle Felder auf einmal, mit dem aktuellen Wert darin** (`FieldGrid`).
  Vorher waehlte man ein Feld aus einer Liste und tippte einen Wert ins Leere - man musste
  wissen, was drinsteht, und jedes Feld einzeln speichern. Geaenderte Felder bekommen einen
  Rahmen und darunter "Vorher: ...", der Fuss zaehlt auf, was gleich hinausgeht.
- **Die Beschriftung ist der Feldname aus dem Master** (`min_online`, nicht "Minimum
  online"), die Erklaerung steht als Hinweis darunter. Eigene Beschriftungen waeren eine
  zweite Liste neben der des Masters - ein neues Feld hiesse im Dashboard dann gar nichts.
  Ein fehlender Hinweis ist dagegen harmlos.
- **Das Log scrollt in seinem Kasten, nicht die Seite.** Dafuer wird `scrollTop` direkt
  gesetzt; `scrollIntoView` nimmt alle Eltern mit und schiebt Name und Knoepfe des Servers
  aus dem Bild. Der Kasten braucht dazu `Card fill` - sonst ist er so hoch wie sein
  Inhalt.
- **Symbole sind SVG-Pfade im Bundle** (`ui/Icon.tsx`), keine Emoji und kein Icon-Paket.
  Emoji sehen auf jedem System anders aus und werden vorgelesen; ein Paket mit eigenem
  Versionszyklus lohnt fuer zwei Dutzend Pfade nicht. Standardmaessig `aria-hidden` -
  ein Symbol neben Text braucht keine zweite Beschriftung.
- **Schriften liegen im Bundle** (`@fontsource`, Fira Sans und Fira Code). Ein Panel fuer
  eine Cloud muss im LAN ohne Internet funktionieren; eine Schrift von einem fremden
  Server waere genau dann weg, wenn man sie braucht.
- **Farben nur als Tokens** (`@theme` in `index.css`, Tailwind v4): `bg`, `surface`,
  `line`, `text`, `ok`/`warn`/`bad`, `brand`. Ein roher Hex-Wert in einer Komponente ist
  eine Farbe, die beim naechsten Mal nicht mehr passt.
- **Wer Bewegung reduziert haben will, bekommt keine** (`prefers-reduced-motion`). Die
  Oberflaeche bleibt vollstaendig bedienbar - es faellt nur das Ein- und Ausblenden weg.

# Schreiben ueber das Dashboard
- **`AdminRoutes` ist von `CloudRoutes` getrennt.** Lesen und Schreiben brauchen
  verschiedene Rechte und verschiedene Sorgfalt: Ein Tippfehler beim Lesen zeigt nichts
  an, beim Schreiben loescht er eine Gruppe.
- **Vorgaben fuer neue Gruppen stehen in `ServerGroup.defaults`**, nicht zweimal.
  Es gibt zwei Wege, eine Gruppe anzulegen (Konsole und REST); zwei Saetze Vorgaben waeren
  zwei Verhaltensweisen, und der Unterschied faellt erst auf, wenn ein Server mit falschem
  Speicher startet. Genau das ist hier einmal passiert - das Namensmuster der
  REST-Variante war ohne `%id%` und wurde abgelehnt.
- **Die aenderbaren Felder liefert der Master** (`/api/v1/groups/fields`,
  `/api/v1/ranks/fields`), die Oberflaeche pflegt keine eigene Liste. Was vorgeschlagen
  wird, muss auch gesetzt werden koennen.
- **Die aktuellen Werte kommen in derselben Antwort mit** (`fields` an jeder Gruppe und
  jedem Rang). Das Formular zeigt damit, was drinsteht, ohne je Datensatz nachzuladen -
  und man ueberschreibt nichts, was man vorher nicht gesehen hat.
- **`valueOf(...)` steht neben `updateField(...)`** (`ServerGroupRepository`,
  `RankRepository`). Lesen und Schreiben muessen dieselbe Feldliste kennen; stuende das
  Lesen woanders, zeigte das Formular irgendwann ein Feld, das beim Speichern abgelehnt
  wird. `EditableFieldsTest` zieht genau das nach vorn.
- **Gespeichert wird ein PATCH je geaendertem Feld**, auch wenn das Formular alle zeigt.
  Die Schnittstelle prueft jeden Wert einzeln - dafuer nennt die Meldung hinterher
  genau das Feld, das nicht ging, statt "teilweise gespeichert".
- **Loeschen wird verweigert, solange etwas laeuft**: eine Gruppe mit aktiven Servern,
  ein Node mit Servern darauf. Sonst liefen Server weiter, zu denen es keine Gruppe mehr
  gibt, und der Scheduler wuesste nicht, was er mit ihnen tun soll.
- **Eine Modul-Konfiguration wird vor dem Schreiben geparst.** Eine kaputte Datei faellt
  sonst erst beim naechsten Start des Moduls auf - und dann startet es nicht mehr.
  Wirksam wird sie mit `module reload <id>`; das Dashboard sagt das auch.
- **Rechte je Aktion sind die der Befehle**: `vibecloud.command.group.edit`,
  `vibecloud.command.node.add`, `vibecloud.command.rank.delete` und so weiter. Kein
  eigenes Rechteschema fuer die Schnittstelle.
- **Einzelne Rechte stehen in `PermissionRoutes`**, nicht hier: Dort geht es um Felder mit
  je einem Wert, bei Rechten um Regeln mit Knoten, Kontext und Ablauf.
- **Der Rang eines Spielers geht ueber `PermissionService.setRank`/`resetRank`** -
  dieselben Aufrufe wie `rank set` und `rank reset`, mit deren Rechten
  (`vibecloud.command.rank.set`, `.reset`). Der Dienst schreibt Verlauf und Protokoll
  selbst; die Route tut es nicht noch einmal. Die Dauer versteht `Times.parseDuration`,
  und nach Ablauf gilt wieder der **bisherige** Rang, nicht der Standardrang.
- **Der eigene Rang ist der eigene Zugang.** Wer sich im Dashboard einen Rang ohne
  `vibecloud.dashboard.login` gibt, ist danach abgemeldet - das ist dasselbe Verhalten wie
  in der Konsole und kein Fehler. Der Dialog warnt davor am Formular, nicht per Toast:
  Das muss man vor dem Klick lesen.

# Einstellungen im Dashboard
- **Auf der Seite steht nur noch die `config.json`.** Der Wartungsmodus gilt sofort, die
  Datei erst nach einem Neustart des Masters - zwei Wirkungen, die nicht in ein Formular
  gehoeren. Der Schalter sitzt deshalb in der Seitenleiste (siehe "Wartungsmodus im
  Dashboard").
- **Nicht jedes Feld der `config.json` ist aenderbar.** Draussen bleibt, was entscheidet,
  ob Master und Dashboard ueberhaupt hochkommen: `database.*`, `grpc.bindAddress`,
  `grpc.port`, `http.enabled`, `http.port`, `http.secureCookies`. Ein Tippfehler dort
  liesse sich ueber genau den Weg nicht zuruecknehmen, ueber den er hineinkam. Die
  Datenbank wird nicht einmal angezeigt - das Passwort gehoert nicht in einen Browser.
- **Geschrieben wird die Datei, nicht der laufende Master** (`MasterConfigFile`). Ein Teil
  der Werte steckt nach dem Start in Diensten, die sich nicht umstellen lassen (Takt des
  Schedulers, Heartbeat). Die Haelfte sofort und die andere nach dem Neustart waere eine
  Regel, die sich niemand merkt. Die Seite zeigt deshalb beides: was in der Datei steht
  und womit der Master noch laeuft.
- **Mehrere Felder in einem Aufruf** - anders als bei Gruppen und Raengen. Die Felder
  haengen voneinander ab: Wer den Portbereich von 30000-30999 auf 40000-40999 verschiebt,
  haette mit einem PATCH je Feld zwischendurch 40000-30999, und schon der erste Schritt
  wuerde abgelehnt. Geprueft wird das Ganze, gespeichert wird alles oder nichts.
- **In der Datei aendern sich nur die genannten Felder.** Geschrieben wird ueber den
  JSON-Baum, nicht ueber `MasterConfig`: Sonst verschwaenden Eintraege, die diese Version
  nicht kennt, und fehlende Felder bekaemen nebenbei ihre Vorgabe eingetragen.
- **Lesen und Schreiben stehen in einem Eintrag** (`Field` in `MasterConfigFile`), nicht
  in zwei Listen wie bei `valueOf`/`updateField`. Hier gab es noch keine zweite Liste -
  dann muss auch keine entstehen.
- **`cloud config` gibt es, damit das Dashboard kein eigenes Recht braucht.** Die Seite
  haengt an `vibecloud.command.cloud.config`, der Schalter an
  `vibecloud.command.maintenance.on`/`.off`/`.list`. Wer nur `.off` hat, darf aufheben,
  aber nicht einschalten - wie in der Konsole.

# Serverkonsole im Dashboard
- **Mitlesen und Tippen sind zwei Rechte.** Das Log haengt an `vibecloud.command.screen`,
  das Absenden an `vibecloud.command.screen.send` - und das erste deckt das zweite nicht
  ab ("a.b" deckt "a.b.c" nicht ab). In der Konsole eines Gameservers gibt es `op`; wer
  dort tippen darf, darf auf diesem Server alles. In der Master-Konsole faellt der
  Unterschied nicht auf, weil dort ohnehin jeder alles darf.
- **Das ist ein Knoten, den die Konsole nicht kennt** - eine bewusste Ausnahme von "Rechte
  je Aktion sind die der Befehle". `screen` gibt es im Spiel nicht, also schlaegt der
  Katalog seine Rechte nicht von selbst vor; beide werden beim Start angemeldet
  (`HttpApi.CONSOLE_PERMISSION`, `CONSOLE_SEND_PERMISSION`).
- **Derselbe Weg wie `screen`**: `ServerService.execute` schreibt die Zeile in die
  Standardeingabe des Servers und den Befehl samt Absender ins Protokoll. Im Dashboard
  wird nichts nachgebaut.
- **Genau eine Zeile je Aufruf**, hoechstens 1000 Zeichen. Ein Zeilenumbruch waere ein
  zweiter Befehl, der im Protokoll nicht als solcher auftaucht.
- **"angenommen", nicht "ausgefuehrt".** Eine Antwort auf einen Befehl gibt es in dieser
  Richtung nicht; was der Server daraus macht, steht in seinem Log. Die Oberflaeche reiht
  die eigene Zeile (`> ...`) deshalb selbst ins Log ein - sonst saehe man die Antwort ohne
  die Frage. Andere, die mitlesen, sehen die Zeile nicht; wer was geschickt hat, steht im
  Protokoll.
- **Ein fehlgeschlagener Befehl bleibt im Feld stehen**, und Pfeil hoch/runter holt die
  letzten fuenfzig zurueck - nur fuer diese Seite, nach dem Verlassen ist es weg.

# Gruppen-Felder
- **Gespeichert wird nur, was sich danach noch lesen laesst** (`ServerGroupRepository.
  updateField`). Die Regeln einer Gruppe stehen in `ServerGroup` (mindestens 128 MB,
  `max_online` nicht unter `min_online`, `%id%` im Namensmuster), die Datenbank kennt sie
  nicht. Ohne die Gegenprobe nahm sie `memory 64` an, danach warf jedes `findAll()`, und
  der Scheduler startete fuer **keine** Gruppe mehr einen Server - ein Tippfehler in einem
  Feld, in Konsole oder Dashboard, legte die Cloud still. Jetzt laeuft die Aenderung in
  einer Transaktion und wird zurueckgenommen, wenn der Konstruktor wirft. Abgedeckt von
  `ServerGroupRepositoryTest`.

# Wartungsmodus im Dashboard
- **Der globale Schalter sitzt unten in der Seitenleiste**, ueber dem eigenen Zugang
  (`MaintenanceToggle.tsx`, `footer` der `Sidebar`), nicht mehr auf der Einstellungsseite.
  Er gilt sofort und fuer alle - so etwas soll von jeder Seite aus zu sehen und zu
  schalten sein. Ein Netzwerk, in das niemand hereinkommt, entdeckt man sonst erst auf der
  Einstellungsseite.
- **Zu sehen nur mit `maintenance.list`**, geschaltet wird je Richtung: `mayEnable` und
  `mayDisable` kommen in der Antwort mit (`ApiAuth.allows`). Das ist eine Auskunft fuer die
  Oberflaeche, keine Pruefung - der Endpunkt, der schaltet, ruft weiter `require`.
- **Einschalten fragt nach, Aufheben nicht**, global wie je Gruppe: Das eine sperrt Spieler
  aus, das andere laesst sie wieder herein.
- **Der Dialog haengt per Portal an `document.body`.** Die Seitenleiste wird mit
  `transform` verschoben, und darin waere ein `fixed`-Dialog an ihr festgemacht statt am
  Fenster.
- **Die Anzeige holt sich den Stand alle 30 Sekunden neu** und sofort nach einer Aenderung
  an einer Gruppe (Ereignis `vibecloud:maintenance`). Die Wartung laesst sich auch in der
  Konsole und von anderen umschalten.
- **Global und je Gruppe laufen ueber eine Stelle** (`MaintenanceSwitch`): speichern,
  protokollieren, den Proxys mitteilen. Das Letzte vergisst man am leichtesten - genau so
  war es: Das Feld `maintenance` einer Gruppe liess sich im Dashboard setzen, und kein
  Proxy erfuhr davon. Jetzt geht auch `PATCH /groups/{name}` mit diesem Feld dort durch.
- **Die Gruppen-Wartung hat einen eigenen Endpunkt und Knopf** (Seite "Gruppen",
  `PUT /settings/maintenance/groups/{name}`). Er haengt an `maintenance.on`/`.off` und nicht
  an `group.edit`: Wer ein Minigame kurz sperren darf, soll dafuer nicht die ganze Gruppe
  umbauen duerfen.
- **Einen Wartungsmodus fuer einen einzelnen Server gibt es nicht.** Die Server einer
  Gruppe sind austauschbar; einen davon zu sperren hiesse nur, dass der Proxy den naechsten
  nimmt. Wer einen einzelnen Server sperren will, hat eine Gruppe mit einem Server.
- **Der globale Schalter ueberlebte keinen Master-Neustart.** Gespeichert wird als JSON
  (`"true"`), gelesen wurde der Text samt Anfuehrungszeichen, und `parseBoolean` machte
  daraus `false` - das Netzwerk war nach jedem Neustart stillschweigend wieder offen.
  `CloudSettings` liest jetzt mit `value #>> '{}'`. Abgedeckt von `MaintenanceSwitchTest`.

# Rechte im Dashboard
- **Keine zweite Auswertung.** `PermissionRoutes` liest ueber `PermissionService` und
  `PermissionResolver` und schreibt ueber `addRankPermission`/`addPlayerPermission` -
  dieselben Methoden, die `perm` in der Konsole ruft. Die Rechte-Auswertung ist die Stelle,
  an der zwei Umsetzungen am teuersten waeren: Ein Unterschied faellt nicht als Fehler auf,
  sondern als ein Spieler, der etwas darf.
- **Die Rechte eines Rangs liefert der Resolver**, nicht ein zweiter Durchlauf durch die
  Vererbung. Aufgerufen wird `PermissionResolver.resolve(rangId, ..., List.of())` - ohne
  Spieler-Regeln, weil es um den Rang geht und nicht um eine Person. Damit stimmt die
  Anzeige per Konstruktion mit dem ueberein, was beim Login passiert.
- **Geerbte Regeln stehen mit dabei, aber ohne Kreuz.** Ohne sie kann niemand erklaeren,
  warum ein Rang etwas darf, das nicht in seiner eigenen Liste steht. Loeschen laesst sich
  dort nichts: Die Regel gehoert dem Elternrang, und ein Kreuz wuerde etwas aendern, das
  man gerade nicht ansieht.
- **`perm check` ist ein Formular, und es sagt warum.** Ja oder nein allein hilft nicht -
  die entscheidende Regel und die **ueberstimmten** sind der halbe Zweck. Dort steht oft
  der Denkfehler: ein Verbot in einem geerbten Rang, das niemand vermutet hat.
- **Ein Knoten wird geprueft, bevor er gespeichert wird.** Eine Regel, die nie trifft,
  faellt sonst nirgends auf: Sie steht in der Datenbank, wird bei jeder Abfrage geladen und
  entscheidet nie etwas - im Dashboard sieht sie aus, als waere das Recht vergeben.
  `vibecloud.*.stop` ist genau so ein Fall; ein Stern mitten im Knoten ist kein Wildcard,
  sondern ein gewoehnliches Zeichen. Erlaubt sind deshalb nur Kleinbuchstaben, Ziffern,
  `_`, `-` und Punkte, ein Stern allein oder als letztes Segment
  (`PermissionInputTest`).
- **Nur Spieler-Rechte koennen ablaufen.** Eine Rang-Regel mit Ablauf aenderte um drei Uhr
  nachts still die Rechte aller Spieler mit diesem Rang - dafuer gibt es auch in der
  Konsole keinen Weg, und die Schnittstelle lehnt `duration` an einer Rang-Regel ab.
- **Erlaubt oder verboten ist eine Auswahl, kein Minus.** In einer Oberflaeche kann man ein
  Zeichen vergessen; ein `select` nicht. Ein fuehrendes `-` versteht die Schnittstelle
  trotzdem - so laesst sich eine Zeile aus der Konsole eins zu eins einfuegen.
- **Der Kontext steht einzeln in der Antwort** (`group`, `server`) und nicht nur als Text
  (`"group=lobby"`). Beim Entfernen muss er genauso wieder mitgehen, und die Oberflaeche
  muesste ihn sonst erst zerlegen. Entfernt wird immer genau eine Regel - dieselbe kann
  global und je Gruppe existieren.
- **Knoten sind Vorschlaege, keine Auswahl** (`datalist`). Der Katalog kommt aus
  `CommandRegistry.permissionNodes(...)`: die Rechte der Befehle und die von Modulen
  angemeldeten. Ein Plugin bringt eigene mit, ohne sie anzumelden - als Auswahlliste waere
  das Dashboard enger als die Konsole. Der Kontext dagegen **ist** eine Auswahl: ueberall,
  eine Gruppe oder ein Server sind wirklich drei Faelle.
- **Der Katalog steht in `CommandRegistry`**, nicht in der Konsole. Zwei Oberflaechen leben
  davon - die Tab-Vervollstaendigung von `perm` und der Editor im Dashboard. Zwei Listen
  waeren zwei Vorstellungen davon, welche Rechte es gibt, und die im Dashboard waere die,
  die niemand pflegt.
- **Rechte je Aufruf sind die Unterbefehle von `perm`**: `perm.rank`, `perm.player`,
  `perm.check`, `perm.list`. Auch das **Lesen** der Rang-Regeln haengt an `perm.rank`,
  obwohl es nur liest - es gibt keinen lesenden Unterbefehl dafuer, und einen hier zu
  erfinden hiesse, einen Rechte-Knoten zu haben, den die Konsole nicht kennt.
- **Eine Datei fuer Raenge und Spieler** (`pages/Permissions.tsx`). Es ist dieselbe Sache:
  eine Liste von Regeln, ein Formular, ein Kreuz. Zweimal geschrieben waere der Unterschied
  zwischen Rang und Spieler irgendwann ein Unterschied im Verhalten.
- **Rechte sind ein eigener Dialog, nicht Teil von "Bearbeiten".** Dort geht es um Felder
  mit je einem Wert, die beim Speichern hinausgehen; eine Regel wirkt sofort. Ein Dialog
  mit beidem haette einen Speichern-Knopf, der fuer die eine Haelfte gilt und fuer die
  andere nicht.
- **Dauern versteht `Times.parseDuration`**, nicht jeder Befehl selbst. `30d` muss in
  `/tempban`, in `perm player add` und im Dashboard dasselbe heissen - ein Unterschied
  waere erst nach dreissig Tagen zu sehen.

# Sprachen im Dashboard
- **Bearbeitet werden die Dateien in `messages/`, nicht das geladene Bundle.** Im Bundle
  haengen auch die Texte der Module (`punishment.ban.screen`); die stehen im JAR des
  Moduls. Wer sie mitspeicherte, fror sie in der Datei des Betreibers ein - und ein
  Modul-Update aenderte seine eigenen Texte nicht mehr.
- **Nach jeder Aenderung wird neu eingelesen und verteilt**, ueber `MessageDistributor` -
  dieselbe Stelle, die `cloud messages reload` benutzt. Zweimal geschrieben waere es
  zweimal fast richtig, und der Unterschied faellt erst auf, wenn ein Server als einziger
  den alten Satz zeigt.
- **Schlaegt das Einlesen fehl, bleiben die alten Texte aktiv** - die Datei ist dann
  geschrieben, im Spiel gilt aber noch der alte Stand. Die Antwort sagt das auch. Alles
  andere waere ein Netzwerk voller Schluesselnamen.
- **Eine Sprachkennung ist kein Dateiname.** Sie wird zu `messages/<kennung>.yml`, deshalb
  die Pruefung auf `[a-z]{2,3}(_[A-Z]{2})?`: Ohne sie waere `../../config` eine gueltige
  "Sprache". Abgedeckt von `MessageServiceTest`.
- **Eine neue Sprache startet mit den Texten der Standardsprache**, nicht leer. Leer saehe
  man nur Schluesselnamen und wuesste nicht, was zu uebersetzen ist; so ersetzt man Zeile
  fuer Zeile, und bis dahin greift ohnehin Deutsch.
- **Leere Felder werden nicht gespeichert.** Ein leerer Text waere im Spiel eine leere
  Zeile - ein fehlender Schluessel faellt dagegen auf die Standardsprache zurueck.
- **Ein echter Zeilenumbruch wird abgelehnt** (`<newline>` ist das Tag dafuer). In einer
  einfach quotierten YAML-Zeile wuerde er die Datei zerreissen.
- **Die Datei wird flach neu geschrieben** (Punkt-Schreibweise, alphabetisch) - eigene
  Kommentare und Verschachtelung gehen dabei verloren. Die Oberflaeche sagt es vorher;
  wer das nicht will, bearbeitet weiter von Hand und drueckt "Neu einlesen".
- **Geschrieben wird ueber eine Datei daneben und ein Umbenennen.** Faellt der Master
  mitten im Schreiben aus, ist die alte Datei noch heil statt halb ueberschrieben.
- **Schluessel sind auf `[A-Za-z0-9_.-]` begrenzt.** Sie stehen unquotiert vor dem
  Doppelpunkt; einer mit Doppelpunkt oder Raute ergaebe YAML, das sich nicht mehr lesen
  laesst.
- **Die Standardsprache laesst sich nicht loeschen** - ohne sie wirft `load()`, und es
  gaebe keinen Rueckfall mehr.
- **Recht ist `vibecloud.command.cloud.messages`** - dasselbe wie fuer
  `cloud messages reload`. Kein eigenes Schema fuer die Oberflaeche.
- **Die Texte eines Moduls liegen in `modules/<id>/messages/<sprache>.yml`** - neben
  dessen `config.json`, nicht in `messages/`. Dort steht **nur, was vom JAR abweicht**
  (`MessageService.saveModuleEntries`). Der ganze Satz froere die Texte ein, und genau
  deshalb wurden sie bisher gar nicht bearbeitet; so folgt jeder Text, den niemand
  angefasst hat, weiter den Updates des Moduls. Steht nichts Eigenes mehr drin, wird die
  Datei geloescht.
- **Beim Laden liegt der eigene Text ueber dem aus dem JAR** (`overlayModuleOverrides`),
  mit demselben Praefix (`punishment.ban.screen`). Ein Modul kann damit weiterhin keine
  Texte der Cloud ueberschreiben, und der Betreiber keine eines anderen Moduls.
- **Eine Sprache gibt es, wenn die Cloud ihre Datei hat.** Die Module haengen sich daran:
  Angelegt und geloescht wird eine Sprache nur bei den Texten der Cloud. Eigene Modul-Texte
  zu einer geloeschten Sprache bleiben liegen, bringen sie aber nicht von selbst zurueck -
  und gespeichert wird fuer eine Sprache ohne Datei nichts.
- **Ein Modul laesst sich in eine Sprache uebersetzen, die es nicht mitbringt.** Dann ist
  jeder Text ein eigener. Zu sehen ist das Modul nur, solange es geladen ist und Texte
  eingehaengt hat - das ist zugleich der Schutz vor einer erfundenen Kennung im Pfad.
- **Die Antwort fuer ein Modul hat drei Saetze**: was gilt (`entries`), die Standardsprache
  als Vorlage (`defaults`) und was das JAR mitbringt (`bundled`). Ohne das Dritte saehe
  niemand, welcher Text ein eigener ist, und koennte ihn nicht zuruecksetzen.

# Mehrere Server-Versionen
- **Die Version gehoert zur Gruppe** (`mc_version`), wie bisher. Neu ist, dass die Cloud
  weiss, welche es gibt und was jede braucht: `VersionCatalog` fragt
  `fill.papermc.io/v3/projects/<projekt>/versions` - ein Aufruf liefert alle Versionen samt
  Mindest-Java. Eine eigene Liste im Code waere mit der naechsten Paper-Version veraltet.
  Zwischengespeichert eine Stunde; die Tab-Vervollstaendigung liest nur den Zwischenspeicher,
  ein Tastendruck wartet nicht auf PaperMC.
- **Waehlbar: Paper ab 1.16, Velocity ab 4.2, nur Releases.** Darunter gibt es kein
  Cloud-Plugin; ein Server ohne Plugin waere nur scheinbar Teil der Cloud. `-rc`, `-pre` und
  `SNAPSHOT` sind nicht waehlbar - aus demselben Grund, aus dem `latest` nur stabile Builds
  nimmt.
- **Geprueft wird an einer Stelle**: `ServerGroupRepository.VersionCheck`, aufgerufen von
  `create` und von `updateField` bei `mc_version`/`jar_source`. Konsole und REST laufen beide
  dort durch. Geprueft wird die fertige Gruppe, nicht das Feld - ob 1.16.5 geht, haengt an
  Plattform und `jar_source`. Ein eigenes Jar (`template`, `custom:`) wird nicht geprueft.
- **Ohne Internet wird nur die Form geprueft**, die Gruppe aber gespeichert. Sonst liesse
  sich ohne Verbindung zu PaperMC keine Gruppe mehr anlegen; der Fehler kaeme beim Download
  ohnehin.
- **Das Jar wird nach dem Speichern geladen** (`JarStore.prefetch`), nicht erst beim ersten
  Start - sonst wartet der auf 50 MB. Ein Fehler dabei ist kein Fehler der Aenderung, der
  Start versucht es noch einmal.
- **`group create <name> <plattform> [version] [template]`** - die Version steht vor dem
  Template. Bisher stand dort das Template; wer die alte Reihenfolge tippt, bekommt
  "keine Release-Version" und den Hinweis auf die neue. Template-Namen enthalten keinen
  Punkt, Versionen schon - daran erkennt der Hinweis den Fall.

## Java
- **Gebraucht wird das Hoehere aus API und Plugin** (`VersionCatalog.requiredJava`):
  1.16.5 nennt Java 8, das Legacy-Plugin braucht 17 - also 17. 26.x braucht 25.
- **Der Betreiber installiert die Java selbst** und traegt sie in die wrapper.json ein
  (`javaRuntimes`, Pfad zum Programm oder zum Java-Verzeichnis). Der Wrapper fragt jede
  Installation nach ihrer Version (`-XshowSettings:properties`); eine Zuordnung "17 = Pfad"
  von Hand koennte falsch sein. Die eigene Java des Wrappers ist immer dabei.
- **Gewaehlt wird die kleinste passende**, nicht die neueste. Alte Server brechen eher an
  einer zu neuen Java, als dass sie von ihr profitieren.
- **Der Master startet nur auf einem Node mit passender Java** (`NodeRegistry.hasJava`,
  `RegisterRequest.java_versions`). Sonst startete der Scheduler dort jede Runde einen
  Server, der sofort wieder ausgeht. Ein Wrapper, der nichts meldet, ist aelter und startet
  alles mit seiner Java - der wird nicht ausgeschlossen. Ein statischer Server weicht auch
  hier nicht aus; die Meldung nennt den Node.
- **Paper 1.16 und 1.17 weigern sich ueber Java 16** ("Only up to Java 16 is supported").
  `-DPaper.IgnoreJavaVersion=true` kommt fuer Legacy-Versionen unter 1.18 automatisch dazu.
  Mit 1.16.1 und 1.16.5 unter Temurin 17 geprueft.
- **`--enable-native-access` nur ab Java 22.** Java 8 und 16 starten mit dem Flag gar nicht.

## Das Legacy-Plugin
- **Unter 26.2 bekommt ein Server `vibecloud-paper-legacy.jar`** statt `vibecloud-paper.jar`
  (`TemplateStore`): Das aktuelle ist fuer Java 25 und `api-version: 26.2` gebaut, schon
  26.1 lehnt es ab. Es liegt unter `templates/global/server-legacy/plugins/`; aus
  `global/server` faellt `plugins/vibecloud-paper.jar` dafuer heraus.
- **Keine zweite Verbindung und keine zweite Rechte-Auswertung.** `CloudConnection`,
  `CloudPermissions`, die Rechte-Auswertung, `CloudPermissible`, `PermissibleInjector` und
  `RankTeams` sind **dieselben Dateien**; der Build kopiert sie und kompiliert sie mit
  `--release 17` noch einmal. **Diese Dateien muessen deshalb mit Java 17 kompilieren** -
  keine virtuellen Threads, kein `getFirst()`/`removeLast()`, kein Pattern-Matching-switch.
  Wer es doch tut, bricht den Build des Legacy-Plugins, nicht erst einen 1.16-Server.
  Anders ist nur, was auf alten Servern anders gehen muss: Texte als Strings statt
  Components (`LegacyText`), Chat ueber `AsyncPlayerChatEvent`, `sendOpLevel` per
  Reflection (fehlt die Methode, bleibt F3+F4 fuer Nicht-Operatoren aus).
- **Der Kern laeuft in einem eigenen Klassenlader.** Paper 1.16 schreibt jede Plugin-Klasse
  mit einem alten ASM um, das Java-17-Klassen nicht lesen kann - jede schrieb einen ERROR
  mit Stacktrace ins Log, auch spaeter im Betrieb. Deshalb besteht das Plugin aus einem
  Lader (`src/bootstrap`, Java 8, zwei Klassen) und dem Kern als
  `META-INF/vibecloud/core.jar`, den der Lader nach `plugins/vibeCloud/core.jar` legt und
  selbst laedt. Was Bukkit nicht laedt, schreibt es nicht um. Plugins, die das Legacy-Plugin
  ansprechen, sehen nur `hasPermission` und `switchServer` - Signaturen mit Klassen des
  Kerns wuerden ihn ein zweites Mal laden lassen.
- **Alles Eingepackte ist verlagert** (gson, snakeyaml, guava, protobuf, Adventure 4,
  SLF4J): Paper 1.16 bringt eigene, aeltere Fassungen mit. Adventure 5 ist fuer Java 21
  gebaut, deshalb 4.26. SLF4J schreibt ueber `slf4j-jdk14` in die Serverkonsole - Paper 1.16
  hat keinen Anbieter, und ohne ihn liefen Verbindungsfehler ins Leere.
- **Legacy-Server bekommen keine Modul-Bundles.** Sie sind fuer das aktuelle Paper und
  Java 25 gebaut. Damit gibt es auf Legacy-Servern **keinen Mute-Filter** - Bann und Kick
  laufen weiter ueber Proxy und Master.
- **Paper bis 1.18 liest das Velocity-Secret aus `paper.yml`** (`settings.velocity-support`),
  ab 1.19 aus `config/paper-global.yml`. `ServerConfigurator` entscheidet an
  `StartServer.mc_version`; landet das Secret in der falschen Datei, weist der Server jeden
  Spieler vom Proxy ab.

## TLS zum Master
- **`FingerprintTrustManager` ist ein `X509ExtendedTrustManager`.** Als einfacher
  `X509TrustManager` wurde er von der TLS-Engine eingepackt, und die Verpackung pruefte
  zusaetzlich den Hostnamen: Das Zertifikat lautet auf `vibecloud-master`, verbunden wird
  mit einer IP. Unter Java 17 und 21 (Netty mit OpenSSL) scheiterte daran jede Verbindung;
  unter Java 25 nahm Netty einen anderen Weg, deshalb fiel es erst mit dem Legacy-Plugin
  auf. Der Hostname beweist nichts mehr, wenn der Fingerprint genau ein Zertifikat festlegt.
  Ein falscher Fingerprint wird weiter abgelehnt (`FingerprintTrustManagerTest`, und im
  Durchlauf unter 17 und 25 geprueft).

## NICHT verifiziert (Mehrere Server-Versionen)
- **Kein Spieler auf einem Legacy-Server.** Plugin verbunden - Rechte-Bridge und Chat-Format
  sind ohne Client nicht gesehen. Dasselbe gilt fuer das Forwarding: `paper.yml` ist richtig
  geschrieben, aber kein Proxy hat einen Spieler auf einen 1.16-Server geschickt.
- **Nicht jede Version ist gestartet worden** - 1.16.1, 1.16.5, 1.20.4, 1.21.4 und 26.2. Was
  dazwischen liegt (1.17, 1.18, 1.19, 1.20.5/6), ist nur durch die Regeln abgedeckt.
- **Velocity-Versionen** sind waehlbar, aber nur 4.2 lief je unter der Cloud.
- **Nur unter Windows.** Die Java-Erkennung unter Linux (`/usr/lib/jvm/...`) ist nicht
  durchgespielt.
- **Ein Node ohne passende Java** ist nur in Tests durchgespielt, nicht im Betrieb.

# SFTP fuer Templates (dynamische Gruppen)
- **Einen dynamischen Server bearbeitet man nicht - man bearbeitet sein Template.** Sein
  Verzeichnis entsteht bei jedem Start neu und ist nach dem Stopp weg. Was bleiben soll,
  gehoert nach `templates/<template>/` auf dem Master.
- **Der Master bietet dafuer selbst SFTP an** (`TemplateSftp`, `sftp` in der config.json,
  standardmaessig aus, Port **2223**). Nicht 2222 wie der Wrapper: Laufen beide auf
  demselben Rechner, stritten sie sich sonst um den Port.
- **Derselbe Code wie im Wrapper**, in einem eigenen Modul `common/vibecloud-sftp`
  (`SftpGateway`). Nicht in `vibecloud-api`: Dort haengen die Plattform-Plugins dran, und
  ein SSH-Server hat in einem Paper-Plugin nichts verloren. Nur Master und Wrapper
  haengen davon ab.
- **Benutzername `<zugang>.<gruppe>`, Recht `vibecloud.sftp.template.<gruppe>`.** Eine
  eigene Ebene und nicht `vibecloud.sftp.<gruppe>`: Ein Template wirkt auf jeden Server
  der Gruppe auf jedem Node, ein Serververzeichnis auf einen. `vibecloud.sftp.*` deckt
  **beides** ab - wer es vorher fuer statische Server bekommen hat, darf jetzt auch an
  alle Templates.
- **Zugang und Passwort sind dieselben** wie fuer statische Server; nur die Adresse ist
  eine andere (Master statt Node). Im Dashboard nimmt die Seite dafuer
  `location.hostname` - der Master weiss nicht, unter welchem Namen man ihn erreicht.
- **Eine Aenderung wirkt auf neu gestartete Server**, ohne dass ein Cache geleert wird:
  `TemplateStore.buildManifest` liest das Verzeichnis bei jedem Start. Laufende Server
  behalten ihre Dateien. Ein Server, der mitten in einem Upload startet, bekommt die
  halbe Datei - dagegen gibt es nichts.
- **Auch statische Gruppen haben ein Template** und stehen in der Liste. Es ist die
  Antwort auf "meine per SFTP geaenderte `server.properties` ist nach dem Start wieder
  die alte": Kommt die Datei aus dem Template, aendert man sie dort.
- **`templates/global/` ist nicht erreichbar**, und der Template-Name muss ein einfacher
  Name sein (`TemplateStore.editableDirectory`). Er ist ein freies Feld der Gruppe -
  ungeprueft fuehrte `../secrets` zum JWT-Schluessel. Abgedeckt von
  `TemplateDirectoryTest`.
- **Ein fehlendes Template-Verzeichnis entsteht erst nach der Anmeldung** - gefragt wird
  nach dem Verzeichnis schon vorher, und sonst legte jeder geratene Name einen Ordner an.

# SFTP fuer statische Server
- **Der SFTP-Server laeuft im Wrapper**, nicht im Master: Die Dateien liegen auf dem Node.
  Ein Umweg ueber den Master hiesse, jede Welt durch den gRPC-Kanal zu schieben. Damit ist
  es der **einzige Port, den ein Root fuer die Cloud nach aussen oeffnet** - deshalb
  standardmaessig aus (`sftp.enabled` in der wrapper.json, Port 2222).
- **Ein Lauscher je Node, nicht einer je Server.** Der Benutzername sagt, wohin es geht:
  `<zugang>.<server>`, getrennt am **letzten** Punkt (ein Bedrock-Name beginnt mit einem).
  Jede Sitzung ist in `servers/<server>` eingesperrt. Ein Port je Server waere ein
  Portbereich mehr in der Firewall.
- **Nur statische Server.** Das Verzeichnis eines dynamischen ist nach dem Stopp weg, und
  alles Hochgeladene mit ihm. Erkannt wird ein statischer an seiner Spurdatei, nicht an der
  Liste der laufenden - Dateien aendert man gerade dann, wenn er steht.
- **Der Wrapper entscheidet nichts.** Er prueft, was er selbst weiss (Form des Namens, den
  Server gibt es hier, er ist statisch), und fragt dann den Master. Ohne Verbindung kommt
  niemand herein, und gemerkt wird sich nichts: "hat vorhin gestimmt" liesse jemanden
  herein, dem das Recht inzwischen entzogen wurde.
- **Was der Wrapper selbst ablehnen kann, erreicht den Master nicht.** Jeder offene
  SSH-Port bekommt Dauerbeschuss mit `root` und `admin`. Dazu fuenf Fehlversuche je
  Adresse, dann zehn Minuten Ruhe - nur im Speicher.
- **Der Master prueft den Node mit**: Der Server muss an genau den Node gebunden sein, der
  fragt (`static_server_bindings`). Sonst koennte ein uebernommener Node Passwoerter gegen
  Server durchprobieren, die woanders liegen.
- **Rechte kommen aus dem Spiel**: `vibecloud.sftp.<server>`, `vibecloud.sftp.*` fuer alle.
  Kein zweites Rechtesystem. Geprueft wird bei **jeder Anmeldung** - eine offene Sitzung
  bleibt aber offen, bis sie getrennt wird. Das ist weniger als beim Dashboard, wo ein
  Entzug binnen Sekunden wirkt, und bewusst offen gelassen.
- **Das Recht ist eines fuer Administratoren.** Wer Dateien hochladen darf, kann ein Plugin
  hochladen, und das laeuft beim naechsten Start als Code auf dem Node - mit den Rechten
  des Wrappers, also mit Blick auf die `wrapper.json`. In jedem Serververzeichnis steht
  ausserdem das Forwarding-Secret. Die Einsperrung haelt Versehen ab, keinen Angreifer mit
  diesem Recht.
- **Ein eigenes Passwort, nicht das des Dashboards.** Es geht im Klartext durch den Wrapper.
  Waere es dasselbe, haette ein uebernommener Node danach die Dashboard-Zugaenge aller, die
  sich bei ihm angemeldet haben.
- **Passwoerter erzeugt der Master, niemand waehlt eines** - 24 Zeichen ohne 0, O, 1, I, l.
  Deshalb SHA-256 wie bei den API-Tokens und kein Argon2. Mit selbst gewaehlten Passwoertern
  waere das falsch; wer sie einfuehrt, muss das Verfahren mit aendern.
- **`sftp` gibt es nur in der Konsole**, wie `api token`: Das Passwort erscheint einmal, und
  anders als bei `acp` gibt es danach keinen erzwungenen Wechsel.
- **Vor dem Schreiben wird die Datei vom Blob-Cache getrennt** (`UnsharingAccessor`).
  Template-Dateien sind harte Links auf die Blobs. Wer eine an Ort und Stelle aendert,
  aendert den Blob - und damit dieselbe Datei in jedem Server dieses Nodes, dessen Template
  sie enthaelt. Unter Windows kennt Java die Link-Anzahl nicht; dort wird immer getrennt.
  **Dasselbe Problem hat ein Gameserver, der seine eigene Konfiguration umschreibt** - das
  ist nicht behoben.
- **Template-Dateien werden bei jedem Start neu ausgerollt** (`TemplateCache.materialize`),
  auch bei statischen Servern. Eine per SFTP geaenderte `server.properties` ist nach dem
  naechsten Start wieder die aus dem Template, wenn das Template eine hat. Bestand hat, was
  das Template nicht kennt: Welten, Plugin-Daten, eigene Plugins. Ob statische Server
  vorhandene Dateien behalten sollen, ist eine offene Entscheidung.
- **Nur SFTP.** Keine Shell, keine Befehle, keine Port-Weiterleitung, nur Passwort-Anmeldung.
  Die Begruessung nennt weder Bibliothek noch Version.
- **Der Host-Schluessel liegt in `secrets/sftp-host.key`** und bleibt ueber Neustarts
  derselbe; sein Fingerprint steht beim Start im Log. Ein neuer bei jedem Start saehe fuer
  jeden Client aus wie ein Angriff. `/wrapper/*/secrets/` steht in der `.gitignore`.
- **`sftp servers` zeigt die Adresse des Nodes**, nicht die des Masters. Port und
  Fingerprint des Host-Schluessels meldet der Wrapper bei der Anmeldung mit
  (`RegisterRequest.sftp_port`, `sftp_host_key`) - der Master verbindet sich nie dorthin.
- **Im Dashboard sieht jeder nur sich selbst** (Seite "Dateien", `SftpRoutes`,
  `pages/Sftp.tsx`): die statischen Server, fuer die er `vibecloud.sftp.<server>` hat, mit
  Adresse, Port, Benutzername und Host-Schluessel. Die Liste fragt
  `SftpAccountService.mayAccess` - dasselbe Recht wie die Anmeldung, damit nichts
  angezeigt wird, das dann nicht aufgeht. Ein eigenes Recht fuer die Seite gibt es nicht.
- **Das eigene Passwort erzeugt man im Dashboard selbst**, und es steht einmal in einem
  Dialog, der weggeklickt werden muss - wie das Node-Token. Das widerspricht "`sftp` nur in
  der Konsole" nicht: Dort ging es um den Chat. Der Zugang allein oeffnet nichts, und mit
  dem Start-Passwort des Dashboards geht es nicht (`SftpRoutes.user`). Fremde Zugaenge
  verwaltet weiter die Konsole.
- **"In WinSCP oeffnen" ist eine `sftp://`-Adresse ohne Passwort.** Sie oeffnet das
  Programm, das dafuer eingetragen ist; ein Passwort darin stuende in Verlaeufen. Der
  Host-Schluessel steht daneben, weil das Programm beim ersten Verbinden danach fragt -
  ohne den Wert koennte man nur "ja" sagen und hoffen.
- **Die angezeigte Adresse ist die, unter der der Master den Node kennt** (`addressOf`,
  also `serverAddress` oder die Quell-IP). Hinter NAT oder in einem privaten Netz ist das
  nicht die, die man von aussen braucht.

# Wer online ist
- **`OnlinePlayers` haelt das nur im Speicher.** Wer verbunden ist, ist kein dauerhafter
  Zustand: Nach einem Master-Neustart melden die Proxys ihre Spieler ohnehin neu. Eine
  Tabelle dafuer waere eine zweite Wahrheit, die nach jedem Absturz falsch waere.
- **Die Quelle ist immer der Proxy** - Betreten, Verlassen und jeder Serverwechsel.
  Verliert ein Proxy die Verbindung, verschwinden seine Spieler aus der Liste: Das
  Verlassen haette er gemeldet, und er ist gerade nicht mehr da.
- **`lastServer` in der Datenbank ist etwas anderes.** Das ist der letzte bekannte Ort und
  steht auch dann noch da, wenn jemand laengst offline ist.
- **`/api/v1/players/online` muss vor `/api/v1/players/{uuid}` registriert sein** - sonst
  faengt der Platzhalter es ab, und die Antwort ist "Keine gueltige UUID".

# Serverwechsel
- **Eine Stelle fuer alle Wege**: `PlayerTransferService`. Konsole, Befehl im Spiel,
  Plugin ueber gRPC und REST laufen alle dort zusammen - sonst waere derselbe Ablauf
  viermal geschrieben und dreimal halb richtig.
- **Der Master verschiebt nicht selbst.** Er schickt `TransferPlayer` an alle Proxys;
  derjenige, der den Spieler hat, fuehrt es aus. Nur der Proxy weiss, wer wo ist - der
  Master kennt den letzten gemeldeten Stand, und der kann eine Sekunde alt sein.
- **Ein Proxy ist kein Ziel.** Dorthin kann niemand geschickt werden, jeder Spieler ist
  schon ueber ihn verbunden. Stand vorher trotzdem in der Vorschlagsliste.
- **`switch` fuer sich selbst, `send` fuer andere** - zwei Rechte, weil es zwei Dinge sind.
  Dafuer hat die Befehls-Schnittstelle jetzt einen `CommandActor`; wer ihn nicht braucht,
  sieht ihn nicht (`execute(out, args)` bleibt).
- **Ein Gameserver darf nur eigene Spieler verschieben** (`MovePlayer`). Sonst koennte ein
  einzelner kompromittierter Server beliebige Spieler im Netzwerk herumschieben. Ein Proxy
  darf jeden - er verwaltet sie alle.
- **Kein Erfolg wird behauptet.** Die Antwort sagt "angenommen" und wie viele Proxys
  erreicht wurden. Ob der Spieler online war, weiss der Master nicht.

# REST-Schnittstelle
- **Standardmaessig aus** (`http.enabled` in der config.json). Ein offener Port mit
  Schreibzugriff auf das Netzwerk soll eine Entscheidung sein, kein Nebeneffekt.
- **Tokens: `<id>.<geheimnis>`**, gespeichert wird nur SHA-256 des Geheimnisses. Fuer
  Passwoerter waere das falsch, hier richtig: 32 Byte aus `SecureRandom`, da hilft kein
  Durchprobieren. Argon2 wuerde nur jede Anfrage bremsen. Die Id steht vorn, damit die
  Pruefung eine Zeile liest statt alle Tokens zu vergleichen.
- **Rechte je Token** (`servers.read`, `players.write`, `*`) und 120 Anfragen pro Minute.
  Ein unbekanntes Recht beim Anlegen wird abgelehnt - ein vertipptes waere ein Token, das
  stillschweigend nichts darf.
- **Kein Unterschied zwischen "kein Token" und "falsches Token"** (beides 401). Sonst
  waere die Antwort eine Auskunft darueber, welche Ids es gibt.
- **`api token` gibt es nur in der Konsole.** Das Token wird einmal angezeigt und gehoert
  nicht in einen Chatverlauf.
- **Javalin 7 nimmt Routen in der Konfiguration**, nicht fluent am Server, und braucht
  einen JSON-Mapper. Eingesetzt ist Gson - eine zweite JSON-Bibliothek nur fuer die
  Schnittstelle waere Ballast.

# Lebenszeit der Gameserver
- **Geht der Wrapper, gehen seine Server mit.** Er besitzt die Prozesse auf seinem Node.
  Der Shutdown-Hook faehrt sie mit `stopGraceSeconds` (wrapper.json, Standard 30) herunter,
  beendet Haengende hart und loescht danach die Verzeichnisse dynamischer Server. Logs
  werden nach `logs/pending` gesichert und beim naechsten Start hochgeladen.
- **Das ist das Gegenteil des Master-Neustarts** - und beides ist richtig. Faellt der
  Master aus, laufen die Server weiter und werden adoptiert; faellt der Wrapper aus, ist
  niemand mehr da, der sie steuert, und sie wuerden nur ihre Ports halten.
- **Nach einem harten Abbruch raeumt der naechste Start auf** (`cleanupLeftovers`).
  Erkannt wird das an `.vibecloud-node.json` im Serververzeichnis: Name, Gruppe, Port,
  ob statisch, Prozesskennung und Prozess-Startzeit.
- **Ohne Spurdatei wird nichts geloescht.** Ein Verzeichnis ohne Spur koennte die Welt
  eines statischen Servers sein, und die ist nicht wiederherstellbar - dann lieber eine
  Warnung und von Hand aufraeumen.
- **Die Prozesskennung allein darf niemanden beenden.** Das Betriebssystem gibt sie wieder
  aus; deshalb muss auch die Startzeit passen (Toleranz 2 s), sonst traefe das Aufraeumen
  einen fremden Prozess.
- **Statische Server behalten ihr Verzeichnis**, beim Abschalten wie beim Aufraeumen. Dort
  liegt die Welt, und der Master bindet sie wieder an genau diesen Node.

# Anzeige im Spiel
- **Kopf und Fuss der Tab-Liste gehen erst ab `ServerPostConnectEvent`.** Im
  `PostLoginEvent` steckt der Client noch im Login-Zustand und verwirft die Pakete
  **still** - es sieht aus, als wuerde die Anzeige fehlen. Genau das war der Fehler:
  Sie erschien erst nach einer Rang-Aenderung, weil die spaeter kommt.
  `ServerConnectedEvent` ist ebenfalls zu frueh - es feuert, bevor der Wechsel beim
  Client angekommen ist.
- **Die Spielerzahl im Fuss gilt fuer alle.** Bei Join und Quit wird die Anzeige bei
  jedem Spieler neu gesetzt, sonst steht dort die Zahl vom eigenen Join. Beim Quit eine
  weniger - Velocity fuehrt den Gehenden da noch in der Liste.
- **Nichts davon haengt am Rang.** Kopf und Fuss sind fuer alle gleich; die Abfrage des
  Rangs war der Grund, warum die Anzeige bei einem Spieler ohne Rang ganz ausblieb.
- **Fehlende Schluessel werden in `messages/de.yml` ergaenzt**, die Datei aber nie
  ueberschrieben. Eigene Texte ueberleben ein Update, und neue Schluessel erscheinen
  nicht als Schluesselname im Spiel. Angehaengt wird flach in Punkt-Schreibweise
  (`tab.header: '...'`) - der Lader versteht beide Formen.
- **Nach jedem Laden der Rechte geht die Befehlsliste neu an den Client**
  (`player.updateCommands()` in `VibeCloudPaper.loadPlayer`). Paper schickt sie beim Join,
  und da hat die Cloud noch nicht geantwortet - berechnet wird sie also mit den
  Bukkit-Rechten. Ohne das neue Senden funktionierte `/gamemode` mit `*` zwar, wurde aber
  weder vorgeschlagen noch als bekannt angezeigt; nach einem Rechte-Entzug blieb es
  umgekehrt stehen. **Nicht im Spiel gesehen** - dafuer braucht es einen Client.
- **F3+F4 haengt an der OP-Stufe, die der Client gemeldet bekommt**, nicht an einem Recht.
  Der Server laesst den Wechsel mit `minecraft.command.gamemode` zu (am Server-Jar mit
  `javap` geprueft: `handleChangeGameMode`), der Client oeffnet den Umschalter aber erst ab
  Stufe 2. Wer das Recht hat, bekommt deshalb `sendOpLevel(2)` - eine Auskunft, kein
  Operator-Status; geprueft wird weiter auf dem Server. **Stufe 4 gibt es nur beim vollen
  Wildcard `*`** (`opLevelFor`, gefragt wird `CloudPermissions.has(uuid, "*")`): Wer allein
  den Spielmodus wechseln darf, soll dem Client nicht als Voll-Operator erscheinen. Ein
  einzelnes Verbot neben dem `*` aendert die Stufe nicht. Nach Weltwechsel und Respawn
  meldet der Server die Stufe von sich aus neu, deshalb geht sie dort einen Tick spaeter
  noch einmal hinaus. Echte Operatoren bleiben unberuehrt. **Nicht im Spiel gesehen.**

# Befehle im Spiel
- **Ein Weg, nicht zwei.** Der Proxy schickt die Zeile samt Spieler-UUID an den Master
  (`RunCommand`), der prueft das Recht **selbst neu** und fuehrt denselben Befehl aus wie
  in der Konsole. Im Plugin wird kein Befehl nachgebaut und nichts entschieden - das
  `hasPermission` dort verbirgt den Befehl nur vor Spielern, die ihn nicht brauchen.
- **Rechte gelten pro Unterbefehl**: `vibecloud.command.server.kill` ist etwas anderes als
  `vibecloud.command.server.list`. Alles unter einem Befehl deckt
  `vibecloud.command.server.*` ab - **nicht** `vibecloud.command.server`: "a.b" deckt
  "a.b.c" nicht ab (`PermissionNodes`). Ohne Argumente aufgerufen zeigt ein Befehl nur
  seine Syntax, dafuer genuegt irgendein Recht darunter.
- **Modul-Befehle behalten ihr eigenes Recht.** `/ban` haengt an
  `vibecloud.punishment.ban`, nicht am abgeleiteten `vibecloud.command.ban` - das setzt
  `ModuleChannelRouter.registerCommand` ueber `permission()`.
- **`stop`, `module`, `screen` und `help` gibt es im Spiel nicht** (`availableInGame()`).
  Die ersten drei sind zu heikel oder technisch unmoeglich, `help` wuerde das `/help` des
  Gameservers verdecken. Im Spiel listet `/cloudhelp` am Proxy, was der Spieler darf.
- **Unbekannter Befehl und fehlendes Recht sehen gleich aus.** Sonst waere die
  Fehlermeldung eine Auskunft darueber, welche Befehle es gibt.
- **Nur ein Proxy darf Befehle melden.** Ein Gameserver koennte sonst Befehle mit der UUID
  eines Administrators ausloesen - geprueft wird gegen die Plattform aus der
  Server-Registry, nicht gegen die Angabe im Aufruf.
- **Vorschlaege kommen vom Master** (`SuggestCommand`) und sind nach Rechten gefiltert -
  wer `server kill` nicht darf, bekommt es beim Tippen nicht angeboten. Es ist dieselbe
  `complete()`-Methode wie in der Konsole, damit beides nie auseinanderlaeuft.
- **Die Argumentliste endet immer mit dem angefangenen Text** (bei einem Leerzeichen am
  Ende ein leerer Eintrag). Deshalb nutzt das Velocity-Plugin `RawCommand` und nicht
  `SimpleCommand`: Nur dort ist das Leerzeichen am Ende sichtbar.
- **Die Befehlsliste wird vollstaendig verteilt**, nicht als Aenderung (`SetCommands`).
  Nach `module load`/`unload` schickt der Master die neue Liste; der Proxy ersetzt damit
  seine alte. Eine verpasste Meldung heilt so von selbst.

# Wichtige Regeln fuer die Konsole
- **Log-Meldungen gehen durch den LineReader**, nicht nach `System.out`. Dafuer gibt es
  `TerminalAppender` in `logback.xml`. Ein gewoehnlicher `ConsoleAppender` ueberschreibt
  die Eingabezeile: Prompt und halb getippter Befehl sind nach jeder Meldung weg.
  Das ist die einzige Stelle, die Logback direkt kennt - sonst nur SLF4J.
- **Das Terminal wird als System-Terminal geoeffnet**, mit Rueckfall auf ein einfaches.
  Nur ein System-Terminal kann den Cursor bewegen; ohne das gibt es weder ein neu
  gezeichnetes Prompt noch Tab-Vervollstaendigung. Ohne Konsole (Dienst, umgeleitete
  Eingabe) greift der Rueckfall.
- **Log-Format ist `<Datum Zeit> <LEVEL> - <Meldung>`**, ohne Logger-Namen.
  `d.k.v.m.g.NodeServiceImpl` sagt einem Betreiber nichts.
- **Zeitangaben fuer die Anzeige laufen ueber `Times`** (`common`). `Instant.toString()`
  ist UTC und liegt in Deutschland im Sommer zwei Stunden hinter der Wanduhr - das sieht
  aus wie ein Fehler. Gespeichert wird weiter UTC.
- **Ein unbekannter Befehl schlaegt passende vor** (`CommandRegistry.completeNames`:
  erst Praefix, dann Teiltreffer). Der einzige Treffer wird aber **nicht** einfach
  ausgefuehrt - unter den Befehlen stehen `ban` und `stop`.
- **`MessageBundle.raw(null, ...)` ist erlaubt** und bedeutet "keine Sprache bekannt",
  etwa bei der MOTD. Vorher warf die unveraenderliche Map dabei, und der Proxy
  beantwortete keinen einzigen Server-List-Ping mehr.

# Wichtige Regeln aus M6
- **Der Core kennt keine Bans.** Das Modul haengt einen Handler an `PlayerPreLoginEvent`
  und bricht ab. Wer Whitelist oder Laendersperre will, macht es genauso - ohne eine
  Zeile im Core.
- **Bei einem Datenbankfehler wird der Login abgelehnt, der Chat aber durchgelassen.**
  Ein Ausfall darf kein Weg sein, einen Bann zu umgehen; ein stummer Chat waere dagegen
  eine Strafe fuer alle. Die Richtung ist pro Fall bewusst gewaehlt, nicht einheitlich.
- **`ban` speichert keine IP, nur `banip` tut das.** Sonst traefe jeder Bann die
  Mitbewohner des Gebannten mit. `enforceIpBans` in der Modul-Config steuert nur, ob
  beim Login ueberhaupt gegen IPs geprueft wird.
- **Strafen werden nie geloescht, nur als aufgehoben markiert** (`revoked_at`). Die
  Historie muss vollstaendig bleiben, auch nach einem Entbannen.
- **Einspruchs-Kennungen enthalten kein 0, O, 1, I oder L.** Ein Spieler tippt sie vom
  Ban-Bildschirm ab.
- **Der Modul-Klassenlader ist auch bei Ressourcen child-first.** `URLClassLoader` fragt
  sonst den Parent zuerst - und ein Modul bekaeme `messages/de.yml` des Masters statt
  seiner eigenen Datei. Das faellt nirgends als Fehler auf; im Spiel stuende statt des
  Ban-Textes nur der Schluessel. Abgedeckt von `ModuleClassLoaderTest`.
- **Spielersichtbare Texte schickt der Master als Schluessel**, auch aus einem Modul:
  `kick`, `message` und `broadcast` in `ModulePlayers` nehmen nur Schluessel und
  Platzhalter. Den Satz baut das Plugin in der Sprache des Spielers.
- Ein Bundle (`bundles/paper.jar`) braucht das Cloud-Plugin auf dem Server. Es gehoert
  nach `templates/global/server/plugins/` - die Cloud verteilt nur Modul-Bundles, nicht
  sich selbst.

# Wichtige Regeln aus M5
- **Modul-Migrations liegen unter `db/migration/<modul-id>/`**, nicht direkt unter
  `db/migration`. Der ClassLoader eines Moduls sieht auch die Ressourcen des Masters -
  Flyway faende sonst zwei Dateien mit Version 1 und bricht ab.
- **`baselineVersion("0")` ist Pflicht** bei Modul-Migrations. Mit dem Standardwert 1
  baselint Flyway auf Version 1 und ueberspringt die V1 des Moduls - die Tabellen
  entstehen nie, und es sieht aus wie ein Erfolg.
- **Module nutzen `compileOnly(project(":modules:module-api"))`**, nie `implementation`.
  Mitgepackt haette das Modul eigene Kopien von `CloudModule` und `EventBus`, und der
  Master koennte es nicht als `CloudModule` ansprechen - ein ClassCastException mit
  identischen Klassennamen.
- **Ein Modul sieht nur `vibecloud-api`, `common`, `protocol` und `module-api`.**
  `de.kevloe.vibecloud.master` fehlt absichtlich in den geteilten Paketen.
- **Eigene Events brauchen einen `exports`-Eintrag** in `module.json`, sonst versteckt die
  Isolation sie vor anderen Modulen.
- Ein Zyklus zwischen Modulen laedt **kein** Modul - halb geladene Module wuerden den
  Fehler verschleiern.

# Wichtige Regeln aus M4
- **Ein Spieler hat genau einen Rang** (`players.rank_id`). Rechte-Sets kombiniert man ueber
  Vererbung (`rank inherit`), nicht ueber mehrere Raenge.
- **Vorrang der Regeln** (erster Unterschied entscheidet): Knoten-Genauigkeit, dann
  Kontext-Genauigkeit, dann Ebene (Spieler > eigener Rang > geerbte nach weight), dann
  gewinnt das Verbot. Das ist eine **Praezisierung** gegenueber PLAN.md Abschnitt 9, wo
  "Negationen gewinnen immer" und "Spieler schlaegt Rang immer" sich widersprechen.
  Dokumentiert in `ResolvedPermissions`.
- **Zyklen werden beim Anlegen abgelehnt**, nicht zur Laufzeit entdeckt.
- **Invalidierung laeuft ueber die gRPC-Streams, nicht ueber Redis.** Ueber Redis braeuchte
  jedes Plugin Redis-Zugangsdaten, und Abschnitt 3 legt fest, dass Plugins keinen Zugang zu
  Datenspeichern haben. Redis bleibt damit vorerst ohne Abnehmer - es wird interessant bei
  einem zweiten Master (M8) oder gemeinsamem Modul-Zustand (M5/M6).
- **Unbekannter Spieler = keine Rechte.** Ein Ausfall darf nie Rechte erweitern.
- Abgelaufene Raenge fallen auf `rank_fallback_id`, sonst auf den Default - geprueft
  minuetlich UND beim Login.

# Wichtige Regeln aus M3
- **Plugin-Jars muessen protobuf und guava verlagern** (`relocate`). Paper liefert
  protobuf-java 4.29.0 mit und gewinnt auf dem Klassenpfad; generierter Code von 4.36.2
  wird dann abgelehnt. Ohne Relocation scheitert das Plugin im Static-Initializer.
- **Proxys lauschen auf 25565**, Gameserver auf 30000-30999. Niemals vermischen: Der
  interne Bereich ist per Firewall auf die Proxy-IPs begrenzt und fuer Spieler unerreichbar.
- **Pro Node hoechstens ein Proxy** - Port 25565 kann nur einmal belegt werden.
- **Backend-Adresse ist die IP des Nodes, nie sein Name.** `node-a` ist nicht auflösbar.
  Der Master nimmt `serverAddress` aus der wrapper.json, sonst die Quell-IP der Verbindung.
- **`velocity.toml` braucht einen leeren `[forced-hosts]`-Abschnitt.** Fehlt er, nimmt
  Velocity seine Beispiel-Hosts, findet die Server nicht und startet nicht.
- Das Einmal-Secret gilt nur fuer `RegisterServer`; danach gilt das Sitzungs-Token.
- **Das Login-Gate am Proxy haengt am `LoginEvent`, nicht am `PreLoginEvent`.** Vorher kennt
  Velocity nur die UUID, die der Client selbst schickt - ungeprueft, und vor 1.19.1 gar
  keine. So war es: Ein 1.16.5-Client wurde unter einer ausgerechneten Offline-UUID
  geprueft, seine Entscheidung danach unter der echten nicht gefunden, und Velocity wies ihn
  mit "keine verfuegbaren Server" ab. Und die Bann-Pruefung lief gegen eine UUID, die ein
  veraenderter Client frei waehlen konnte. Im `LoginEvent` ist die Anmeldung bei Mojang
  durch. **Nicht im Spiel gesehen**, dass ein Bann jetzt bei einem 1.16.5-Client greift.

# Wichtige Regeln aus M2
- **`mc_version: latest` heisst "neueste Version mit STABILEN Builds"**, nicht die neueste
  ueberhaupt. Sonst laeuft das Netzwerk unbemerkt auf einer Beta (26.3 ist aktuell Beta).
- **Statische Server weichen nie auf einen anderen Node aus.** Faellt ihr Node aus, bleiben
  sie aus (`WAITING_FOR_NODE`) - auf einem anderen Node kaemen sie mit leerer Welt hoch.
- **Ein Master-Neustart stoppt keine Gameserver.** Der Wrapper meldet beim Reconnect seinen
  `FullState` und der Master adoptiert sie. **Ein Wrapper-Neustart stoppt sie dagegen
  schon** - siehe unten, der Wrapper besitzt die Prozesse auf seinem Node.
- Gruppen-Aenderungen wirken erst auf **neu gestartete** Server.
- Logs beendeter DYNAMIC-Server gehen zum Master, statische behalten sie auf dem Node.

# Nach jeder Aenderung (verbindlich)
**Jede** Aenderung am Code wird gebaut und sofort in die Testumgebung uebertragen - nicht
erst am Ende eines Meilensteins:

```
./gradlew updateTestEnv
```

Das baut alles inklusive Tests und kopiert danach die sechs Dateien, die die Testumgebung
braucht:

| Datei | Ziel in der Testumgebung |
|---|---|
| `CloudMaster.jar` | `master/` |
| `CloudWrapper.jar` | `wrapper/` |
| `module-punishment.jar` | `master/modules/` |
| `vibecloud-paper.jar` | `master/templates/global/server/plugins/` |
| `vibecloud-velocity.jar` | `master/templates/global/proxy/plugins/` |
| `vibecloud-paper-legacy.jar` | `master/templates/global/server-legacy/plugins/` |
| `dashboard/dist/` | `master/dashboard/` (nur wenn gebaut) |

- **Schlaegt ein Test fehl, wird nichts kopiert.** Der Task haengt am vollstaendigen Build,
  damit in der Testumgebung nie ein Jar landet, das an einem roten Test vorbeigekommen ist.
- **Der Pfad steht in `gradle.properties`** (`vibecloud.testEnv`, standardmaessig
  `../vibeCloud-Test`). Fehlt der Ordner, meldet der Task das und tut nichts - der Build
  bleibt gruen.
- **Laufende Prozesse merken die neuen Jars nicht.** Master, Wrapper und Gameserver laden
  ihre Klassen beim Start; nach einem Update muessen sie neu gestartet werden. Dateien, die
  sich nicht ersetzen liessen, nennt der Task namentlich.
- Die Testumgebung selbst (`START-HIER.md`, Startskripte, Node-Token, Zertifikat,
  Templates) wird **nicht** ueberschrieben - nur die Jars.
- **Das Dashboard baut npm, nicht Gradle** (PLAN.md Abschnitt 4): `cd dashboard &&
  npm run build`. `updateTestEnv` kopiert danach nur noch `dist/` mit.

# Build
- `./gradlew updateTestEnv` — der normale Weg: baut alles und aktualisiert die
  Testumgebung (siehe oben)
- `./gradlew build` — alles inkl. Fat-Jars (`CloudMaster.jar`, `CloudWrapper.jar`),
  ohne die Testumgebung anzufassen
- `./gradlew :master:vibecloud-master:shadowJar` — nur ein Modul
- `./gradlew test`
- `docker compose up -d` — PostgreSQL + Redis fuer die Entwicklung
- Java-Version zentral in `gradle.properties` (`vibecloud.javaVersion`), Toolchain laedt das JDK nach
- Alle Bibliotheksversionen in `gradle/libs.versions.toml` — nie direkt im Build-Skript

# Starten
Die Jars sind fuer Java 25 kompiliert. Ohne lokal installiertes JDK 25 laufen sie nicht mit
`java -jar`, obwohl der Build gruen ist — Gradle nutzt die eigene Toolchain, die Kommandozeile
nicht. Zwei Wege:
- `./gradlew :master:vibecloud-master:run` / `:wrapper:vibecloud-wrapper:run` — nutzt die Toolchain
- `java -jar master/vibecloud-master/build/libs/CloudMaster.jar` — braucht Java 25 auf dem PATH

**Auf den Roots muss Java 25 installiert sein**, nicht nur die JRE fuer die Gameserver.
Beim Start immer `--enable-native-access=ALL-UNNAMED` mitgeben, sonst warnt Java 25 wegen der
nativen Netty-Bibliotheken bei jedem Start (die Start-Skripte und die systemd-Unit tun das schon).

# Erste Inbetriebnahme
Ein Root braucht nur `CloudMaster.jar` bzw. `CloudWrapper.jar` (gebaut mit
`./gradlew releaseJars` nach `build/release/`), Java 25 und eine erreichbare PostgreSQL.
1. PostgreSQL bereitstellen (Entwicklung: `docker compose up -d`).
2. `java -jar CloudMaster.jar` in einem leeren Ordner. Mit Konsole fragt ein Assistent
   Datenbank, gRPC-Port, Dashboard und Standard-Gruppen ab, prueft die Verbindung und
   startet durch. Ohne Konsole (Dienst) wie frueher: `config.json` schreiben, beenden.
3. In der Konsole `node add <name> [maxMemoryMb] [ip,ip]` -> gibt eine fertige Zeile
   `java -jar CloudWrapper.jar join <master>:<port> <node> <token> <fingerprint>` aus.
   **Das Token wird nur einmal angezeigt.**
4. Diese Zeile auf dem Root neben der `CloudWrapper.jar` ausfuehren. Danach genuegt
   `java -jar CloudWrapper.jar`. `node list` zeigt ihn online.
5. Module (`module-punishment.jar` usw.) **von Hand** nach `modules/` legen.

# Nur zwei Jars
- **Die Master-Jar bringt die drei Plattform-Plugins und das Dashboard mit**
  (`bundledResources` im Master-Build, ~100 MB statt ~45). Die Plugins packt
  `BundledFiles` beim Start nach `templates/global/.../plugins/`.
- **Module kommen nicht mit** - auch `module-punishment` nicht. Welche Module laufen,
  entscheidet der Betreiber; der Master legt nur den leeren Ordner `modules/` an.
- **Auspack-Regel** (`BundledFiles`, Stand in `.vibecloud-bundled.json`): fehlt -> auspacken;
  unveraendert seit dem letzten Auspacken -> durch die neue Fassung ersetzen (so kommt ein
  Plugin-Update mit dem Master-Update); sonst hat jemand eine eigene Fassung hingelegt ->
  liegen lassen und warnen. Ohne Stand gilt eine abweichende Datei als eigene. Ein
  geloeschtes Plugin kommt zurueck - ohne ist kein Server Teil der Cloud.
  **Folge fuer die Testumgebung:** Dort legt `updateTestEnv` die Plugins hin, ohne Stand -
  der Master laesst sie liegen und warnt einmal je Datei, wenn sie abweichen.
- **Das Dashboard kommt aus der Jar, ein Ordner `dashboard/` daneben geht vor.** So laesst
  es sich ohne neuen Master tauschen, und `updateTestEnv` bleibt, wie es ist.
- **`releaseJars` bricht ab, wenn `dashboard/dist` fehlt.** Gebaut wird es mit npm, nicht
  Gradle (PLAN.md Abschnitt 4); `build` allein laeuft ohne weiter, damit Tests nicht an npm
  haengen.
- **Der Assistent laeuft nur an einer echten Konsole** (`System.console().isTerminal()`),
  schreibt die `config.json` erst nach erfolgreicher DB-Verbindung und verlangt ein
  Passwort. Die Standard-Gruppen legt er ueber `cloud setup` an - derselbe Weg wie
  getippt, erst wenn die Datenbank steht.
- **`join` ueberschreibt nie eine vorhandene `wrapper.json`** - darin steht ein Token, das
  sich nicht wieder anzeigen laesst. Unter Linux wird sie mit 600 angelegt. Das Token
  landet dabei in der Shell-History; der Wrapper sagt das.
- **Was die Jars nicht mitbringen:** Java 25 (brauchen sie selbst), PostgreSQL, Java
  17/21 fuer Paper unter 1.20.5 (`javaRuntimes`, bewusst kein Download), offene Ports
  (gRPC zum Master, 25565 am Proxy-Node), `nft` fuer die Firewall-Verwaltung.

# Abhaengigkeitsrichtung (strikt)
`protocol` + `common` -> `api` -> {`master`, `wrapper`, `platform/*`, `module-api`} -> `modules/*`

`common/vibecloud-sftp` steht daneben: Es haengt von nichts im Projekt ab, und nur `master`
und `wrapper` haengen davon ab.

Ein Modul sieht **nur** `module-api` und `vibecloud-api`, niemals Master-Interna.

# API-Trennung (KRITISCH - nicht vermischen)
- `platform/vibecloud-velocity`: nur Velocity-API (4.x), `@Plugin`, `@Subscribe`, Adventure
- `platform/vibecloud-paper`: nur Paper-API, `JavaPlugin`, `plugin.yml`
- `platform/vibecloud-minestom`: nur Minestom, eigener `main()`, KEIN Bukkit, KEIN `plugin.yml`
- Plattform-Abhaengigkeiten immer `compileOnly` — der Server bringt sie selbst mit

# Minecraft-Versionen
Minecraft nutzt ein neues Schema: nach `1.21.11` kam `26.1` -> `26.2` -> `26.3`.
**`26.2` ist die aktuelle stabile Reihe**, `26.3` bisher nur Beta. Die Version gehoert zur
Servergruppe (`mc_version`), nicht in den Core.

# Stil
- Java 25: Records, sealed interfaces, Pattern-Matching-switch, Virtual Threads
- Logging ueber SLF4J. **Nie** `System.out`, `printStackTrace()` oder `§`-Farbcodes
- Spielersichtbare Texte **immer** als Nachrichten-Schluessel, nie als String im Code
- Zeitangaben in UTC (`timestamptz`), Umrechnung erst bei der Anzeige
- Keine Credentials im Code — `config.json` / `wrapper.json` sind in `.gitignore`

# Sicherheit (nicht aufweichen)
- Der Master prueft **jede** Berechtigung selbst neu. Plugins uebertragen nur, *wer* etwas
  angefordert hat — niemals ein "darf das"-Flag
- Jede eingehende Nachricht wird gegen die Verbindungsidentitaet geprueft
  (`NODE:<name>` / `SERVER:<id>`): ein Node darf nur ueber seine eigenen Server sprechen
- Nur der Master greift auf die Datenbank zu. Wrapper und Plugins nie
