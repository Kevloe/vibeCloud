import { useEffect, useState } from "react";
import {
  ApiError,
  openSocket,
  request,
  type OnlinePlayer,
  type Overview as Counts,
  type PlayerSummary,
  type Snapshot,
} from "../api";
import { Button } from "../ui/Button";
import { Icon } from "../ui/Icon";
import {
  Badge,
  Card,
  Cell,
  Empty,
  PageHeader,
  Row,
  Stat,
  StateDot,
  Table,
} from "../ui/Layout";
import { useToast } from "../ui/Toast";

/**
 * Startseite: Zahlen, wer gerade online ist und wen die Cloud ueberhaupt kennt.
 *
 * Die Zahlen kommen in einem Aufruf vom Master. Fuenf einzelne Abfragen haetten aus
 * fuenf verschiedenen Momenten gestammt - und die Summen haetten nicht zueinander
 * gepasst.
 */
export function Overview({ onOpenServer }: { onOpenServer: (name: string) => void }) {
  const toast = useToast();
  const [counts, setCounts] = useState<Counts | null>(null);
  const [online, setOnline] = useState<OnlinePlayer[]>([]);
  const [known, setKnown] = useState<PlayerSummary[]>([]);
  const [live, setLive] = useState<Snapshot | null>(null);

  async function load() {
    try {
      const [loadedCounts, loadedOnline, loadedKnown] = await Promise.all([
        request<Counts>("/api/v1/overview"),
        request<OnlinePlayer[]>("/api/v1/players/online"),
        request<PlayerSummary[]>("/api/v1/players?limit=25"),
      ]);
      setCounts(loadedCounts);
      setOnline(loadedOnline);
      setKnown(loadedKnown);
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
      );
    }
  }

  useEffect(() => {
    void load();
  }, []);

  // Die Live-Verbindung haelt die Serverzustaende aktuell. Die Spielerliste laedt sie
  // nach, wenn sich die Zahl aendert - ein eigener Kanal dafuer waere Aufwand fuer
  // eine Liste, die sich selten aendert.
  useEffect(() => {
    let socket: WebSocket | null = null;
    let closed = false;
    let lastCount = -1;

    openSocket("/ws/events")
      .then((opened) => {
        if (closed) {
          opened.close();
          return;
        }
        socket = opened;
        opened.onmessage = (event) => {
          const snapshot = JSON.parse(event.data) as Snapshot;
          setLive(snapshot);
          if (snapshot.players !== lastCount) {
            lastCount = snapshot.players;
            void load();
          }
        };
      })
      .catch(() => setLive(null));

    return () => {
      closed = true;
      socket?.close();
    };
  }, []);

  const servers = live?.servers ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Übersicht"
        subtitle={
          live ? "Live-Verbindung steht." : "Keine Live-Verbindung - Zahlen vom Laden."
        }
        actions={
          <>
            <span
              className={
                "inline-flex items-center gap-2 text-xs " +
                (live ? "text-ok" : "text-text-faint")
              }
            >
              <span
                className={
                  "size-2 rounded-full " + (live ? "bg-ok" : "bg-text-faint")
                }
              />
              {live ? "live" : "statisch"}
            </span>
            <Button icon="refresh" size="sm" onClick={load}>
              Neu laden
            </Button>
          </>
        }
      />

      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Stat
          icon="player"
          label="Spieler online"
          value={live ? live.players : (counts?.playersOnline ?? 0)}
          note={`${counts?.playersKnown ?? 0} insgesamt bekannt`}
          tone={(live?.players ?? counts?.playersOnline ?? 0) > 0 ? "ok" : "neutral"}
        />
        <Stat
          icon="server"
          label="Server"
          value={`${counts?.serversRunning ?? 0} / ${counts?.servers ?? 0}`}
          note="laufend / insgesamt"
        />
        <Stat
          icon="node"
          label="Nodes"
          value={`${counts?.nodesConnected ?? 0} / ${counts?.nodes ?? 0}`}
          note="verbunden / angelegt"
          tone={
            counts && counts.nodes > 0 && counts.nodesConnected === 0 ? "warn" : "neutral"
          }
        />
        <Stat
          icon="module"
          label="Module"
          value={counts?.modules ?? 0}
          note={`${counts?.groups ?? 0} Gruppen`}
        />
      </section>

      <Card title="Server" padded={false}>
        {servers.length === 0 ? (
          <Empty
            icon="server"
            title="Kein Server läuft"
            hint="Die Cloud startet sie, sobald ein Node verbunden ist."
          />
        ) : (
          <div className="grid gap-3 p-4 sm:grid-cols-2 xl:grid-cols-3">
            {servers.map((server) => (
              <button
                key={server.name}
                onClick={() => onOpenServer(server.name)}
                className="vc-fade-in rounded-card border border-line bg-surface-2/50 p-4 text-left transition-colors hover:border-brand/60 hover:bg-surface-2"
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate font-medium text-text">{server.name}</span>
                  <StateDot state={server.state} />
                </div>
                <p className="mt-2 flex items-center gap-2 text-sm text-text-muted">
                  <Icon name="group" className="size-4 text-text-faint" />
                  {server.group}
                  <span className="text-text-faint">auf</span>
                  {server.node}
                </p>
                <p className="mt-1 flex items-center gap-2 text-sm text-text-muted">
                  <Icon name="player" className="size-4 text-text-faint" />
                  {server.players} / {server.maxPlayers}
                </p>
              </button>
            ))}
          </div>
        )}
      </Card>

      <section className="grid gap-6 xl:grid-cols-2">
        <Card title={`Gerade online (${online.length})`} padded={false}>
          {online.length === 0 ? (
            <Empty
              icon="player"
              title="Niemand verbunden"
              hint="Sobald jemand den Proxy betritt, steht er hier."
            />
          ) : (
            <Table narrow columns={["Name", "Server", "Proxy", "Seit"]}>
              {online.map((player) => (
                <Row key={player.uuid}>
                  <Cell className="font-medium text-text">{player.name}</Cell>
                  <Cell>{player.server}</Cell>
                  <Cell>{player.proxy}</Cell>
                  <Cell align="right">{player.since}</Cell>
                </Row>
              ))}
            </Table>
          )}
        </Card>

        <Card title="Zuletzt gesehen" padded={false}>
          {known.length === 0 ? (
            <Empty
              icon="player"
              title="Noch kein Spieler war verbunden"
              hint="Die Cloud legt einen Datensatz beim ersten Login an."
            />
          ) : (
            <Table narrow columns={["Name", "Rang", "Letzter Server", "Letzter Join"]}>
              {known.map((player) => (
                <Row key={player.uuid}>
                  <Cell className="font-medium text-text">
                    <span className="inline-flex items-center gap-2">
                      {player.name}
                      {player.online && <Badge tone="ok">online</Badge>}
                    </span>
                  </Cell>
                  <Cell>{player.rank}</Cell>
                  <Cell>{player.lastServer || "-"}</Cell>
                  <Cell align="right">{player.lastLogin}</Cell>
                </Row>
              ))}
            </Table>
          )}
        </Card>
      </section>
    </div>
  );
}
