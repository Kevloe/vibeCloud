import { useEffect, useRef, useState } from "react";
import { ApiError, openSocket, request, type Server, type Snapshot } from "../api";
import { Button } from "../ui/Button";
import { Badge, Card, Detail, PageHeader, StateDot } from "../ui/Layout";
import { ConfirmModal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/**
 * Ein Server mit seinem Log.
 *
 * Zustand und Spielerzahl kommen aus derselben Live-Verbindung wie die Uebersicht, das
 * Log aus {@code /ws/console/<server>}. Die letzten Zeilen schickt der Master beim
 * Verbinden mit - sonst stuende man vor einem leeren Fenster, bis der Server das
 * naechste Mal etwas sagt.
 */
export function ServerDetail({ name, onBack }: { name: string; onBack: () => void }) {
  const toast = useToast();
  const [server, setServer] = useState<Server | null>(null);
  const [live, setLive] = useState<Snapshot["servers"][number] | null>(null);
  const [lines, setLines] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [follow, setFollow] = useState(true);
  const [ask, setAsk] = useState<"stop" | "kill" | null>(null);
  const box = useRef<HTMLDivElement>(null);

  async function load() {
    try {
      const all = await request<Server[]>("/api/v1/servers");
      setServer(all.find((entry) => entry.name === name) ?? null);
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
      );
    }
  }

  useEffect(() => {
    void load();
  }, [name]);

  // Zustand live
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
        opened.onmessage = (event) => {
          const snapshot = JSON.parse(event.data) as Snapshot;
          setLive(snapshot.servers.find((entry) => entry.name === name) ?? null);
        };
      })
      .catch(() => setLive(null));

    return () => {
      closed = true;
      socket?.close();
    };
  }, [name]);

  // Log live
  useEffect(() => {
    setLines([]);
    let socket: WebSocket | null = null;
    let closed = false;

    openSocket(`/ws/console/${name}`)
      .then((opened) => {
        if (closed) {
          opened.close();
          return;
        }
        socket = opened;
        opened.onmessage = (event) => {
          const message = JSON.parse(event.data) as { line: string };
          // Begrenzt: Ein Server, der stundenlang laeuft, wuerde den Browser sonst mit
          // Zehntausenden Zeilen beschaeftigen.
          setLines((previous) => [...previous, message.line].slice(-1000));
        };
        opened.onclose = (event) => {
          if (event.code === 4404) {
            toast.error(`${name} läuft nicht mehr.`);
          } else if (event.code === 4403) {
            toast.error("Dir fehlt das Recht für die Serverkonsole.");
          }
        };
      })
      .catch(() => toast.error("Keine Verbindung zur Konsole."));

    return () => {
      closed = true;
      socket?.close();
    };
  }, [name]);

  // Direkt am Kasten scrollen, nicht ueber scrollIntoView: Das haette die ganze Seite
  // mitgenommen und den Kopf mit Name und Knoepfen aus dem Bild geschoben.
  useEffect(() => {
    if (follow && box.current) {
      box.current.scrollTop = box.current.scrollHeight;
    }
  }, [lines, follow]);

  async function act() {
    if (!ask) {
      return;
    }
    setBusy(true);
    try {
      await request(`/api/v1/servers/${name}/${ask}`, { method: "POST" });
      toast.success(
        ask === "stop" ? `${name} wird heruntergefahren.` : `${name} wurde hart beendet.`,
      );
      setAsk(null);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  const state = live?.state ?? server?.state ?? "?";
  const players = live?.players ?? server?.players ?? 0;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <PageHeader
        title={name}
        subtitle={
          <span className="inline-flex flex-wrap items-center gap-3">
            <StateDot state={state} />
            <span>
              {players} / {server?.maxPlayers ?? "?"} Spieler
            </span>
            {server?.static && <Badge>statisch</Badge>}
          </span>
        }
        actions={
          <>
            <Button icon="back" size="sm" onClick={onBack}>
              Zurück
            </Button>
            <Button size="sm" onClick={() => setAsk("stop")}>
              Stoppen
            </Button>
            <Button
              size="sm"
              variant="danger"
              icon="stop"
              onClick={() => setAsk("kill")}
              title="Beendet den Prozess sofort, ohne die Welt zu speichern"
            >
              Hart beenden
            </Button>
          </>
        }
      />

      {server && (
        <dl className="mb-4 grid gap-x-8 sm:grid-cols-2 xl:grid-cols-3">
          <Detail label="Gruppe" value={server.group} />
          <Detail label="Node" value={server.node} />
          <Detail label="Port" value={server.port} />
          <Detail label="Plattform" value={server.platform} />
          <Detail label="Gestartet" value={server.startedAt} />
          <Detail label="Art" value={server.static ? "statisch" : "dynamisch"} />
        </dl>
      )}

      <Card
        title="Konsole"
        padded={false}
        fill
        className="flex min-h-[20rem] flex-1 flex-col overflow-hidden"
        action={
          <label className="flex cursor-pointer items-center gap-2 text-xs text-text-muted">
            <input
              type="checkbox"
              checked={follow}
              onChange={(event) => setFollow(event.target.checked)}
              className="size-4 accent-[var(--color-brand)]"
            />
            mitlaufen
          </label>
        }
      >
        <div
          ref={box}
          className="console flex-1 overflow-auto bg-bg/60 p-4 text-xs leading-relaxed text-text-muted"
        >
          {lines.map((line, index) => (
            <div key={index} className="whitespace-pre-wrap">
              {line}
            </div>
          ))}
          {lines.length === 0 && (
            <span className="text-text-faint">Warte auf Ausgabe ...</span>
          )}
        </div>
      </Card>

      <ConfirmModal
        open={ask !== null}
        title={ask === "kill" ? `${name} hart beenden?` : `${name} stoppen?`}
        description={
          ask === "kill"
            ? "Der Prozess wird sofort beendet. Die Welt wird nicht gespeichert - alles seit dem letzten Speichern ist weg."
            : "Der Server fährt geordnet herunter und speichert seine Welt. Spieler darauf werden getrennt."
        }
        confirmLabel={ask === "kill" ? "Hart beenden" : "Stoppen"}
        busy={busy}
        onConfirm={act}
        onClose={() => setAsk(null)}
      />
    </div>
  );
}
