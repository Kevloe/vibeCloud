import { useEffect, useState } from "react";
import {
  ApiError,
  request,
  type Player,
  type PlayerSummary,
  type Server,
} from "../api";
import { Button } from "../ui/Button";
import { Field, Select, TextInput } from "../ui/Form";
import { Icon } from "../ui/Icon";
import { Badge, Card, Detail, Empty, PageHeader } from "../ui/Layout";
import { Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";
import { PlayerPermissionsModal } from "./Permissions";

/**
 * Spielersuche nach Namen oder UUID.
 *
 * Die Suche geht ueber den Namensanfang an den Master; sieht die Eingabe wie eine UUID
 * aus, wird direkt der Datensatz geholt. Ohne Eingabe stehen die zuletzt gesehenen da -
 * das ist die Liste "alle Spieler", sinnvoll sortiert.
 */
export function Players() {
  const toast = useToast();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<PlayerSummary[]>([]);
  const [player, setPlayer] = useState<Player | null>(null);
  const [servers, setServers] = useState<Server[]>([]);
  const [sending, setSending] = useState(false);
  const [editingPermissions, setEditingPermissions] = useState(false);

  async function search(text: string) {
    try {
      setResults(
        await request<PlayerSummary[]>(
          `/api/v1/players?q=${encodeURIComponent(text)}&limit=50`,
        ),
      );
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Suche fehlgeschlagen",
      );
    }
  }

  // Beim Oeffnen die zuletzt gesehenen zeigen, danach bei jeder Eingabe suchen - mit
  // kurzer Verzoegerung, damit nicht jeder Tastendruck eine Abfrage ausloest.
  useEffect(() => {
    const timer = setTimeout(() => void search(query), query ? 250 : 0);
    return () => clearTimeout(timer);
  }, [query]);

  async function open(uuid: string) {
    try {
      setPlayer(await request<Player>(`/api/v1/players/${uuid}`));
      setServers(await request<Server[]>("/api/v1/servers"));
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
      );
    }
  }

  /** Eine UUID direkt oeffnen - sie taucht in Logs und Ban-Meldungen auf. */
  function submit(event: React.FormEvent) {
    event.preventDefault();
    const text = query.trim();
    if (/^[0-9a-fA-F-]{32,36}$/.test(text)) {
      void open(text);
    }
  }

  const targets = servers.filter((server) => server.platform !== "VELOCITY");

  return (
    <div>
      <PageHeader
        title="Spieler"
        subtitle="Nach Namensanfang suchen - oder eine UUID eingeben und Enter drücken."
      />

      <div className="grid gap-6 lg:grid-cols-[20rem_1fr]">
        <Card padded={false} className="self-start">
          <form onSubmit={submit} className="border-b border-line p-3">
            <div className="relative">
              <Icon
                name="search"
                className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-text-faint"
              />
              <TextInput
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Name oder UUID"
                aria-label="Spieler suchen"
                autoFocus
                className="pl-9"
              />
            </div>
            <p className="mt-2 text-xs uppercase tracking-wide text-text-faint">
              {query ? `${results.length} Treffer` : "Zuletzt gesehen"}
            </p>
          </form>

          <ul className="max-h-[32rem] overflow-y-auto p-1">
            {results.map((entry) => (
              <li key={entry.uuid}>
                <button
                  onClick={() => open(entry.uuid)}
                  aria-current={player?.uuid === entry.uuid ? "true" : undefined}
                  className={
                    "flex w-full items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-sm transition-colors " +
                    (player?.uuid === entry.uuid
                      ? "bg-surface-2 text-text"
                      : "text-text-muted hover:bg-surface-2/60 hover:text-text")
                  }
                >
                  <span className="inline-flex min-w-0 items-center gap-2">
                    <span className="truncate font-medium">{entry.name}</span>
                    {entry.online && <Badge tone="ok">online</Badge>}
                  </span>
                  <span className="shrink-0 text-xs text-text-faint">{entry.rank}</span>
                </button>
              </li>
            ))}
            {results.length === 0 && (
              <li className="px-3 py-6 text-center text-sm text-text-muted">
                Niemand gefunden.
              </li>
            )}
          </ul>
        </Card>

        {player ? (
          <Card
            title={player.name}
            action={
              <div className="flex items-center gap-2">
                {player.online && <Badge tone="ok">online</Badge>}
                {/*
                  Auch bei einem Spieler, der nicht als online gilt, bleibt der Knopf
                  benutzbar: Online ist der letzte gemeldete Stand, und der kann eine
                  Sekunde alt sein. Der Master nimmt den Auftrag an, der Proxy entscheidet.
                */}
                <Button
                  size="sm"
                  icon="key"
                  onClick={() => setEditingPermissions(true)}
                >
                  Rechte
                </Button>
                <Button size="sm" icon="send" onClick={() => setSending(true)}>
                  Verschieben
                </Button>
              </div>
            }
          >
            <dl className="grid gap-x-8 sm:grid-cols-2">
              <Detail
                label="UUID"
                value={<span className="console text-xs">{player.uuid}</span>}
              />
              <Detail label="Plattform" value={player.platform} />
              <Detail
                label="Rang"
                value={
                  player.rank +
                  (player.rankExpiresAt ? ` (bis ${player.rankExpiresAt})` : "")
                }
              />
              <Detail label="Sprache" value={player.locale || "-"} />
              <Detail label="Erster Join" value={player.firstLogin} />
              <Detail label="Letzter Join" value={player.lastLogin} />
              <Detail label="Letzter Server" value={player.lastServer || "-"} />
              <Detail
                label="Spielzeit"
                value={`${Math.floor(player.playtimeSeconds / 3600)} h`}
              />
            </dl>
          </Card>
        ) : (
          <Card padded={false}>
            <Empty
              icon="player"
              title="Keinen Spieler gewählt"
              hint="Einen Eintrag aus der Liste wählen - oder eine UUID eingeben und Enter drücken."
            />
          </Card>
        )}
      </div>

      {player && (
        <MovePlayer
          open={sending}
          player={player}
          targets={targets}
          onClose={() => setSending(false)}
        />
      )}

      {player && editingPermissions && (
        <PlayerPermissionsModal
          uuid={player.uuid}
          name={player.name}
          onClose={() => {
            setEditingPermissions(false);
            // Ein eigenes Recht kann den Rang nicht aendern, aber ein abgelaufenes schon:
            // Der Master setzt einen abgelaufenen Rang beim Auflosen zurueck. Neu zu laden
            // kostet einen Aufruf und erspart eine Anzeige, die nicht mehr stimmt.
            void open(player.uuid);
          }}
        />
      )}
    </div>
  );
}

/**
 * Einen Spieler auf einen anderen Server schicken.
 *
 * Ein Proxy steht nicht zur Wahl - dorthin kann niemand geschickt werden, jeder Spieler
 * ist schon ueber ihn verbunden.
 */
function MovePlayer({
  open,
  player,
  targets,
  onClose,
}: {
  open: boolean;
  player: Player;
  targets: Server[];
  onClose: () => void;
}) {
  const toast = useToast();
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState(false);

  async function move() {
    setBusy(true);
    try {
      await request(`/api/v1/players/${player.uuid}/server`, {
        method: "POST",
        body: { server: target },
      });
      // "angenommen", nicht "erledigt": Ob der Spieler online war, weiss nur der Proxy.
      toast.success(`Wechsel von ${player.name} nach ${target} angenommen.`);
      onClose();
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Wechsel fehlgeschlagen",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      title={`${player.name} verschieben`}
      description="Der Master schickt den Auftrag an alle Proxys - ausführen kann ihn nur derjenige, der den Spieler hat."
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Abbrechen
          </Button>
          <Button variant="primary" busy={busy} disabled={!target} onClick={move}>
            Verschieben
          </Button>
        </>
      }
    >
      {targets.length === 0 ? (
        <p className="text-sm text-text-muted">
          Kein Gameserver läuft - ein Proxy ist kein Ziel.
        </p>
      ) : (
        <Field label="Ziel">
          {(props) => (
            <Select
              {...props}
              value={target}
              onChange={(event) => setTarget(event.target.value)}
            >
              <option value="">wählen ...</option>
              {targets.map((server) => (
                <option key={server.name} value={server.name}>
                  {server.name} ({server.group})
                </option>
              ))}
            </Select>
          )}
        </Field>
      )}
    </Modal>
  );
}
