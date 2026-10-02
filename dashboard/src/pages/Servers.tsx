import { useEffect, useState } from "react";
import { ApiError, openSocket, request, type Server, type Snapshot } from "../api";
import { Button } from "../ui/Button";
import { Badge, Card, Cell, Empty, PageHeader, Row, StateDot, Table } from "../ui/Layout";
import { ConfirmModal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/**
 * Server-Uebersicht mit Live-Zustand.
 *
 * Die Liste kommt einmal per REST (dort stehen Port, Plattform und Startzeit), die
 * Zustaende und Spielerzahlen danach ueber /ws/events. Ohne den WebSocket muesste die
 * Seite im Sekundentakt abfragen, und der Master haette die Arbeit.
 */
export function Servers({ onOpenServer }: { onOpenServer: (name: string) => void }) {
  const toast = useToast();
  const [servers, setServers] = useState<Server[]>([]);
  const [live, setLive] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState(false);
  const [ask, setAsk] = useState<{ server: string; action: "stop" | "kill" } | null>(
    null,
  );

  async function load() {
    try {
      setServers(await request<Server[]>("/api/v1/servers"));
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
      );
    }
  }

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    let socket: WebSocket | null = null;
    let closed = false;

    openSocket("/ws/events")
      .then((opened) => {
        if (closed) {
          opened.close();
          return;
        }
        socket = opened;
        opened.onmessage = (event) => setLive(JSON.parse(event.data) as Snapshot);
      })
      .catch(() => {
        // Ohne Live-Daten bleibt die Seite benutzbar - nur eben ohne Aktualisierung.
        setLive(null);
      });

    return () => {
      closed = true;
      socket?.close();
    };
  }, []);

  /** Der Zustand aus der Aufnahme, falls es eine gibt - sonst der vom Laden. */
  function stateOf(server: Server): { state: string; players: number } {
    const fromLive = live?.servers.find((entry) => entry.name === server.name);
    return fromLive ?? { state: server.state, players: server.players };
  }

  async function act() {
    if (!ask) {
      return;
    }
    const { server, action } = ask;
    setBusy(true);
    try {
      await request(`/api/v1/servers/${server}/${action}`, { method: "POST" });
      toast.success(
        action === "stop"
          ? `${server} wird heruntergefahren.`
          : `${server} wurde hart beendet.`,
      );
      setAsk(null);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  // Server, die die Aufnahme kennt, die Liste aber noch nicht: gerade gestartet.
  const neu = (live?.servers ?? []).filter(
    (entry) => !servers.some((server) => server.name === entry.name),
  );

  return (
    <div>
      <PageHeader
        title="Server"
        subtitle={
          live
            ? `${live.players} Spieler im Netzwerk` +
              (neu.length > 0
                ? ` · ${neu.length} Server neu gestartet, "Neu laden" zeigt die Details`
                : "")
            : "Keine Live-Verbindung - die Zustände stammen vom Laden."
        }
        actions={
          <>
            <span
              className={
                "inline-flex items-center gap-2 text-xs " +
                (live ? "text-ok" : "text-text-faint")
              }
              title={live ? "Live-Verbindung steht" : "Keine Live-Verbindung"}
            >
              <span
                className={"size-2 rounded-full " + (live ? "bg-ok" : "bg-text-faint")}
              />
              {live ? "live" : "statisch"}
            </span>
            <Button icon="refresh" size="sm" onClick={load}>
              Neu laden
            </Button>
          </>
        }
      />

      <Card padded={false}>
        {servers.length === 0 ? (
          <Empty
            icon="server"
            title="Kein Server läuft"
            hint="Die Cloud startet sie, sobald ein Node verbunden ist und eine Gruppe ein Minimum verlangt."
          />
        ) : (
          <Table
            columns={[
              "Name",
              "Gruppe",
              "Node",
              "Port",
              "Zustand",
              "Spieler",
              "Seit",
              "",
            ]}
          >
            {servers.map((server) => {
              const current = stateOf(server);
              return (
                <Row key={server.name}>
                  <Cell>
                    <span className="inline-flex items-center gap-2">
                      <button
                        onClick={() => onOpenServer(server.name)}
                        className="font-medium text-text transition-colors hover:text-brand"
                        title="Details und Live-Log"
                      >
                        {server.name}
                      </button>
                      {server.static && <Badge>statisch</Badge>}
                    </span>
                  </Cell>
                  <Cell>{server.group}</Cell>
                  <Cell>{server.node}</Cell>
                  <Cell className="tabular-nums">{server.port}</Cell>
                  <Cell>
                    <StateDot state={current.state} />
                  </Cell>
                  <Cell className="tabular-nums">
                    {current.players} / {server.maxPlayers}
                  </Cell>
                  <Cell className="text-text-faint">{server.startedAt}</Cell>
                  <Cell align="right">
                    <Button
                      size="sm"
                      icon="terminal"
                      onClick={() => onOpenServer(server.name)}
                    >
                      Konsole
                    </Button>
                    <Button
                      size="sm"
                      className="ml-2"
                      onClick={() => setAsk({ server: server.name, action: "stop" })}
                    >
                      Stoppen
                    </Button>
                    <Button
                      size="sm"
                      variant="danger"
                      icon="stop"
                      className="ml-2"
                      title="Beendet den Prozess sofort, ohne die Welt zu speichern"
                      onClick={() => setAsk({ server: server.name, action: "kill" })}
                    >
                      Hart
                    </Button>
                  </Cell>
                </Row>
              );
            })}
          </Table>
        )}
      </Card>

      <ConfirmModal
        open={ask !== null}
        title={
          ask?.action === "kill"
            ? `${ask?.server} hart beenden?`
            : `${ask?.server} stoppen?`
        }
        description={
          ask?.action === "kill"
            ? "Der Prozess wird sofort beendet. Die Welt wird nicht gespeichert - alles seit dem letzten Speichern ist weg."
            : "Der Server fährt geordnet herunter und speichert seine Welt. Spieler darauf werden getrennt."
        }
        confirmLabel={ask?.action === "kill" ? "Hart beenden" : "Stoppen"}
        busy={busy}
        onConfirm={act}
        onClose={() => setAsk(null)}
      />
    </div>
  );
}
