import { useEffect, useState, type ReactNode } from "react";
import {
  ApiError,
  request,
  type SftpOverview,
  type SftpServer,
  type SftpTemplate,
} from "../api";
import { Button } from "../ui/Button";
import { Icon } from "../ui/Icon";
import { Badge, Card, Empty, PageHeader } from "../ui/Layout";
import { ConfirmModal, Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/**
 * Der eigene SFTP-Zugang: zu den Verzeichnissen statischer Server und zu den Templates
 * der Gruppen.
 *
 * Die Seite zeigt nur, was der Angemeldete selbst darf, mit allem, was ein Programm wie
 * WinSCP zum Verbinden braucht. Verbunden wird dort, wo die Dateien liegen: Ein statischer
 * Server liegt auf seinem <b>Node</b>, ein Template auf dem <b>Master</b>. Deshalb zwei
 * Abschnitte mit zwei Adressen - und fuer einen dynamischen Server gibt es nur das
 * Template, weil sein eigenes Verzeichnis nach dem Stopp weg ist.
 *
 * Das Passwort ist ein eigenes und nicht das des Dashboards. Es wird hier erzeugt und
 * genau einmal gezeigt; der Master kennt danach nur noch seinen Hash.
 */
export function Sftp() {
  const toast = useToast();
  const [overview, setOverview] = useState<SftpOverview | null>(null);
  const [password, setPassword] = useState<{ username: string; value: string } | null>(
    null,
  );
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      setOverview(await request<SftpOverview>("/api/v1/sftp"));
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
      );
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function newPassword() {
    setBusy(true);
    try {
      const answer = await request<{ username: string; password: string }>(
        "/api/v1/sftp/password",
        { method: "POST" },
      );
      setAsking(false);
      setPassword({ username: answer.username, value: answer.password });
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  if (!overview) {
    return (
      <div>
        <PageHeader title="Dateien" />
        <p className="text-sm text-text-muted">Lade ...</p>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Dateien"
        subtitle="Per SFTP an die Dateien der Cloud - mit WinSCP, FileZilla oder jedem anderen SFTP-Programm."
        actions={
          <Button
            variant={overview.hasAccount ? "secondary" : "primary"}
            icon="key"
            busy={busy && !asking}
            // Ein erstes Passwort macht nichts ungueltig - ein neues schon. Nur das
            // zweite ist eine Entscheidung, nach der gefragt werden muss.
            onClick={() => (overview.hasAccount ? setAsking(true) : newPassword())}
          >
            {overview.hasAccount ? "Neues Passwort" : "Passwort erzeugen"}
          </Button>
        }
      />

      <div className="space-y-6">
        <Card>
          <div className="flex flex-wrap items-center gap-x-8 gap-y-2 text-sm">
            <span className="inline-flex items-center gap-2 text-text">
              <Icon name="key" className="size-4 text-text-faint" />
              {overview.hasAccount ? (
                <>
                  SFTP-Zugang <span className="font-medium">{overview.username}</span>
                  <Badge tone="ok">eingerichtet</Badge>
                </>
              ) : (
                <>
                  Noch kein SFTP-Passwort
                  <Badge tone="warn">fehlt</Badge>
                </>
              )}
            </span>
            {overview.hasAccount && (
              <span className="text-text-muted">
                {overview.lastLogin
                  ? `Zuletzt angemeldet ${overview.lastLogin} auf ${lastTarget(overview.lastServer)}`
                  : "Noch nie angemeldet"}
              </span>
            )}
          </div>
          <p className="mt-3 max-w-3xl text-xs text-text-faint">
            Das SFTP-Passwort ist ein eigenes, nicht das dieses Dashboards - es geht bei
            der Anmeldung über den Node. Es wird einmal angezeigt und lässt sich danach
            nur ersetzen, nicht wieder ansehen.
          </p>
        </Card>

        <Section
          title="Statische Server"
          hint="Das Verzeichnis des Servers selbst, mit Welt und Plugin-Daten. Es liegt auf seinem Node - dorthin wird verbunden."
        >
          {overview.servers.length === 0 ? (
            <Card padded={false}>
              <Empty
                icon="folder"
                title="Kein Server freigegeben"
                hint={
                  <>
                    Zu sehen sind statische Server, für die du das Recht{" "}
                    <code className="console">vibecloud.sftp.&lt;server&gt;</code> hast.
                    Ein dynamischer Server hat kein eigenes Verzeichnis, das bleibt -
                    dafür gibt es unten das Template seiner Gruppe.
                  </>
                }
              />
            </Card>
          ) : (
            <div className="grid gap-4 xl:grid-cols-2">
              {overview.servers.map((server) => (
                <ServerCard
                  key={server.server}
                  server={server}
                  hasAccount={overview.hasAccount}
                />
              ))}
            </div>
          )}
        </Section>

        <Section
          title="Templates der Gruppen"
          hint="Die Vorlage, aus der jeder Server der Gruppe entsteht. Sie liegt auf dem Master. Eine Änderung wirkt auf neu gestartete Server - laufende behalten ihre Dateien."
        >
          {overview.templates.length === 0 ? (
            <Card padded={false}>
              <Empty
                icon="folder"
                title="Kein Template freigegeben"
                hint={
                  <>
                    Zu sehen sind Gruppen, für die du das Recht{" "}
                    <code className="console">
                      vibecloud.sftp.template.&lt;gruppe&gt;
                    </code>{" "}
                    hast.
                  </>
                }
              />
            </Card>
          ) : (
            <div className="grid gap-4 xl:grid-cols-2">
              {overview.templates.map((template) => (
                <TemplateCard
                  key={template.group}
                  template={template}
                  port={overview.templatePort}
                  hostKey={overview.templateHostKey}
                  hasAccount={overview.hasAccount}
                />
              ))}
            </div>
          )}
        </Section>
      </div>

      {password && (
        <PasswordDialog password={password} onClose={() => setPassword(null)} />
      )}

      <ConfirmModal
        open={asking}
        title="Neues SFTP-Passwort erzeugen?"
        description={
          "Das bisherige Passwort gilt sofort nicht mehr - auch in Programmen, in denen " +
          "es gespeichert ist. Verbindungen, die gerade offen sind, bleiben es, bis sie " +
          "getrennt werden."
        }
        confirmLabel="Neues Passwort"
        busy={busy}
        onConfirm={newPassword}
        onClose={() => setAsking(false)}
      />
    </div>
  );
}

/** "template:lobby" ist die Schreibweise des Masters - hier steht ein Satzteil. */
function lastTarget(target: string): string {
  if (!target) {
    return "?";
  }
  return target.startsWith("template:")
    ? `dem Template von ${target.slice("template:".length)}`
    : target;
}

/** Ein Abschnitt der Seite: Ueberschrift, ein Satz dazu, dann die Kaesten. */
function Section({
  title,
  hint,
  children,
}: {
  title: string;
  hint: string;
  children: ReactNode;
}) {
  return (
    <section>
      <h2 className="text-sm font-semibold tracking-wide text-text">{title}</h2>
      <p className="mb-3 mt-1 max-w-3xl text-xs text-text-faint">{hint}</p>
      {children}
    </section>
  );
}

/** Der Server eines Nodes - verbunden wird mit dem Node. */
function ServerCard({ server, hasAccount }: { server: SftpServer; hasAccount: boolean }) {
  return (
    <ConnectionCard
      title={server.server}
      hasAccount={hasAccount}
      badge={
        !server.nodeConnected ? (
          <Badge tone="warn">Node {server.node} nicht verbunden</Badge>
        ) : server.port === 0 ? (
          <Badge tone="warn">SFTP auf {server.node} aus</Badge>
        ) : (
          <Badge tone="ok">bereit</Badge>
        )
      }
      connection={
        server.url !== undefined
          ? {
              host: server.host ?? "",
              port: server.port,
              username: server.username,
              hostKey: server.hostKey ?? "",
              url: server.url,
            }
          : null
      }
      unavailable={
        !server.nodeConnected
          ? `Der Server liegt auf ${server.node}, und dieser Node ist gerade nicht verbunden. Die Adresse steht hier, sobald er wieder da ist.`
          : // Dieselbe Meldung fuer drei Ursachen - der Master sieht nur, dass der Wrapper
            // keinen SFTP-Port gemeldet hat, nicht warum.
            `Der Wrapper auf ${server.node} bietet gerade kein SFTP an. Eingeschaltet wird es in seiner wrapper.json mit "sftp": { "enabled": true }, danach braucht er einen Neustart. Steht das schon drin, läuft er noch mit einer älteren Version oder der Port war belegt - dann steht der Grund in seinem Log.`
      }
    />
  );
}

/**
 * Das Template einer Gruppe - verbunden wird mit dem Master.
 *
 * Die Adresse steht nicht in der Antwort des Masters: Er weiss nicht, unter welchem Namen
 * man ihn erreicht. Die Seite weiss es - es ist der, unter dem sie geladen wurde.
 */
function TemplateCard({
  template,
  port,
  hostKey,
  hasAccount,
}: {
  template: SftpTemplate;
  port: number;
  hostKey: string;
  hasAccount: boolean;
}) {
  const host = window.location.hostname;
  // Eine IPv6-Adresse steht in einer Adresse in eckigen Klammern.
  const urlHost = host.includes(":") && !host.startsWith("[") ? `[${host}]` : host;

  return (
    <ConnectionCard
      title={template.group}
      hasAccount={hasAccount}
      badge={
        port === 0 ? (
          <Badge tone="warn">SFTP am Master aus</Badge>
        ) : (
          <span className="inline-flex items-center gap-2">
            <Badge>{template.static ? "statisch" : "dynamisch"}</Badge>
            <Badge tone="ok">bereit</Badge>
          </span>
        )
      }
      connection={
        port > 0
          ? {
              host,
              port,
              username: template.username,
              hostKey,
              url: `sftp://${encodeURIComponent(template.username)}@${urlHost}:${port}/`,
            }
          : null
      }
      unavailable={
        'Der Master bietet gerade kein SFTP an. Eingeschaltet wird es in seiner config.json mit "sftp": { "enabled": true }, danach braucht er einen Neustart.'
      }
      note={
        template.static
          ? // Bei einer statischen Gruppe gibt es beides, und es ist nicht dasselbe.
            `Ordner templates/${template.template}. Wird bei jedem Start über das Serververzeichnis gelegt - was hier liegt, überschreibt dort die gleichnamige Datei.`
          : `Ordner templates/${template.template}. Jeder neue Server der Gruppe startet mit genau diesen Dateien.`
      }
    />
  );
}

type Connection = {
  host: string;
  port: number;
  username: string;
  hostKey: string;
  url: string;
};

/**
 * Ein Ziel mit allem, was zum Verbinden gehoert.
 *
 * Als Kasten und nicht als Tabellenzeile: Adresse, Port, Benutzername und Schluessel sind
 * vier Werte zum Kopieren, und in einer Zeile waere dafuer kein Platz.
 */
function ConnectionCard({
  title,
  badge,
  connection,
  unavailable,
  note,
  hasAccount,
}: {
  title: string;
  badge: ReactNode;
  /** {@code null}, wenn es gerade nichts zu verbinden gibt. */
  connection: Connection | null;
  /** Was dann stattdessen dasteht - warum, und was zu tun ist. */
  unavailable: string;
  note?: string;
  hasAccount: boolean;
}) {
  return (
    <Card title={title} action={badge}>
      {connection ? (
        <>
          <dl>
            <Value label="Adresse" value={connection.host} />
            <Value label="Port" value={String(connection.port)} />
            <Value label="Benutzername" value={connection.username} />
            {/*
              Beim ersten Verbinden fragt das Programm, ob dieser Schluessel stimmt. Ohne
              den Wert hier koennte man nur "ja" sagen und hoffen.
            */}
            {connection.hostKey && (
              <Value label="Host-Schlüssel" value={connection.hostKey} />
            )}
          </dl>

          {note && <p className="mt-3 text-xs text-text-faint">{note}</p>}

          <div className="mt-4 flex flex-wrap items-center gap-3">
            <Button
              variant="primary"
              icon="folder"
              disabled={!hasAccount}
              title={hasAccount ? undefined : "Erst oben ein Passwort erzeugen"}
              // Eine sftp://-Adresse oeffnet das Programm, das dafuer eingetragen ist.
              // Das Passwort steht nicht darin - eine Adresse landet in Verlaeufen.
              onClick={() => {
                window.location.href = connection.url;
              }}
            >
              In WinSCP öffnen
            </Button>
            <span className="text-xs text-text-faint">
              {hasAccount
                ? "Das Programm fragt nach dem Passwort und kann es sich merken."
                : "Dafür fehlt noch ein SFTP-Passwort."}
            </span>
          </div>
        </>
      ) : (
        <p className="text-sm text-text-muted">{unavailable}</p>
      )}
    </Card>
  );
}

/** Ein Wert zum Kopieren - der Text bleibt markierbar, falls der Knopf nichts darf. */
function Value({ label, value }: { label: string; value: string }) {
  const toast = useToast();

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      toast.success(`${label} kopiert.`);
    } catch {
      // Die Zwischenablage gibt es nur ueber HTTPS oder auf localhost. Im LAN ueber
      // HTTP bleibt der Text markierbar - nur der Knopf kann nichts tun.
      toast.error("Kopieren nicht erlaubt - bitte von Hand markieren.");
    }
  }

  return (
    <div className="flex items-center justify-between gap-3 border-b border-line/60 py-1.5">
      <dt className="shrink-0 text-xs uppercase tracking-wide text-text-faint">{label}</dt>
      <dd className="flex min-w-0 items-center gap-1">
        <code className="console select-all break-all text-right text-xs text-text">
          {value}
        </code>
        <button
          onClick={copy}
          aria-label={`${label} kopieren`}
          title="Kopieren"
          className="shrink-0 rounded p-1 text-text-faint transition-colors hover:bg-surface-2 hover:text-text"
        >
          <Icon name="copy" className="size-4" />
        </button>
      </dd>
    </div>
  );
}

/**
 * Das SFTP-Passwort - einmal und nie wieder.
 *
 * Wie das Token eines Nodes: kein Toast, sondern ein Dialog, der steht, bis man ihn
 * wegklickt. Wer es nicht kopiert hat, erzeugt ein neues.
 */
function PasswordDialog({
  password,
  onClose,
}: {
  password: { username: string; value: string };
  onClose: () => void;
}) {
  const toast = useToast();

  async function copy() {
    try {
      await navigator.clipboard.writeText(password.value);
      toast.success("Passwort kopiert.");
    } catch {
      toast.error("Kopieren nicht erlaubt - bitte von Hand markieren.");
    }
  }

  return (
    <Modal
      open
      title={`SFTP-Passwort für ${password.username}`}
      description="Es wird nur jetzt angezeigt - gespeichert ist allein sein Hash."
      onClose={onClose}
      footer={
        <>
          <Button icon="copy" onClick={copy}>
            Kopieren
          </Button>
          <Button variant="primary" onClick={onClose}>
            Verstanden
          </Button>
        </>
      }
    >
      <code className="console block select-all break-all rounded-md border border-warn/40 bg-warn/5 px-3 py-2 text-sm text-warn">
        {password.value}
      </code>
      <p className="mt-3 text-sm text-text-muted">
        Es gilt für alles auf dieser Seite. Der Benutzername ist je Ziel ein anderer:{" "}
        <code className="console">{password.username}.&lt;server&gt;</code> oder{" "}
        <code className="console">{password.username}.&lt;gruppe&gt;</code>
      </p>
    </Modal>
  );
}
