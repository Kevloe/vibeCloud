import { useEffect, useState } from "react";
import { ApiError, request, type Node } from "../api";
import { Button } from "../ui/Button";
import { Field, TextInput } from "../ui/Form";
import { Badge, Card, Cell, Empty, PageHeader, Row, Table } from "../ui/Layout";
import { ConfirmModal, Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/**
 * Nodes anlegen, sperren und ihr Token erneuern.
 *
 * Das Token erscheint genau einmal - gespeichert ist allein sein Hash. Deshalb steht es
 * in einem Dialog, der weggeklickt werden muss, und nicht in einem Toast, der nach vier
 * Sekunden verschwindet.
 */
export function Nodes() {
  const toast = useToast();
  const [nodes, setNodes] = useState<Node[]>([]);
  const [busy, setBusy] = useState(false);

  const [token, setToken] = useState<{ node: string; value: string } | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<Node | null>(null);
  const [rotating, setRotating] = useState<Node | null>(null);

  async function load() {
    try {
      setNodes(await request<Node[]>("/api/v1/nodes"));
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
      );
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function toggle(node: Node) {
    try {
      await request(`/api/v1/nodes/${node.name}`, {
        method: "PATCH",
        body: { enabled: !node.enabled },
      });
      toast.success(
        node.enabled ? `${node.name} gesperrt.` : `${node.name} freigegeben.`,
      );
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    }
  }

  async function rotate() {
    if (!rotating) {
      return;
    }
    setBusy(true);
    try {
      const answer = await request<{ token: string }>(
        `/api/v1/nodes/${rotating.name}/token`,
        { method: "POST" },
      );
      setToken({ node: rotating.name, value: answer.token });
      setRotating(null);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!deleting) {
      return;
    }
    setBusy(true);
    try {
      await request(`/api/v1/nodes/${deleting.name}`, { method: "DELETE" });
      toast.success(`${deleting.name} entfernt.`);
      setDeleting(null);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <PageHeader
        title="Nodes"
        subtitle="Jeder Node ist ein Root-Server mit einem Wrapper darauf."
        actions={
          <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
            Neuer Node
          </Button>
        }
      />

      <Card padded={false}>
        {nodes.length === 0 ? (
          <Empty
            icon="node"
            title="Noch kein Node angelegt"
            hint="Ohne Node hat die Cloud keinen Ort, an dem sie Server starten kann."
            action={
              <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
                Neuer Node
              </Button>
            }
          />
        ) : (
          <Table columns={["Name", "Zustand", "Server", "Speicher", "Zuletzt", ""]}>
            {nodes.map((node) => (
              <Row key={node.name}>
                <Cell className="font-medium text-text">{node.name}</Cell>
                <Cell>
                  {node.connected ? (
                    <Badge tone="ok">verbunden</Badge>
                  ) : node.enabled ? (
                    <Badge tone="warn">offline</Badge>
                  ) : (
                    <Badge tone="bad">gesperrt</Badge>
                  )}
                </Cell>
                <Cell className="tabular-nums">{node.servers}</Cell>
                <Cell className="tabular-nums">
                  {(node.maxMemoryMb / 1024).toFixed(0)} GB
                </Cell>
                <Cell className="text-text-faint">{node.lastSeen || "nie"}</Cell>
                <Cell align="right">
                  <Button size="sm" onClick={() => toggle(node)}>
                    {node.enabled ? "Sperren" : "Freigeben"}
                  </Button>
                  <Button
                    size="sm"
                    icon="key"
                    className="ml-2"
                    title="Das alte Token gilt danach nicht mehr"
                    onClick={() => setRotating(node)}
                  >
                    Token
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    icon="trash"
                    className="ml-2"
                    onClick={() => setDeleting(node)}
                  >
                    Entfernen
                  </Button>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </Card>

      <CreateNode
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={async (node, value) => {
          setToken({ node, value });
          await load();
        }}
      />

      {token && <TokenDialog token={token} onClose={() => setToken(null)} />}

      <ConfirmModal
        open={rotating !== null}
        title={`Token für ${rotating?.name} erneuern?`}
        description={
          "Das alte Token gilt sofort nicht mehr. Der Wrapper auf diesem Node verliert die " +
          "Verbindung, bis das neue in seiner wrapper.json steht."
        }
        confirmLabel="Erneuern"
        busy={busy}
        onConfirm={rotate}
        onClose={() => setRotating(null)}
      />

      <ConfirmModal
        open={deleting !== null}
        title={`${deleting?.name} entfernen?`}
        description={
          "Der Node verschwindet aus der Cloud und sein Token wird ungültig. Server, die " +
          "dort noch laufen, steuert niemand mehr - sie beenden sich erst, wenn der Wrapper geht."
        }
        confirmLabel="Entfernen"
        busy={busy}
        onConfirm={remove}
        onClose={() => setDeleting(null)}
      />
    </div>
  );
}

/**
 * Das Token eines Nodes - einmal und nie wieder.
 *
 * Kein Toast: Wer hier wegklickt, bevor er es kopiert hat, muss es erneuern. Deshalb
 * steht es, bis man "Verstanden" drueckt.
 */
function TokenDialog({
  token,
  onClose,
}: {
  token: { node: string; value: string };
  onClose: () => void;
}) {
  const toast = useToast();

  async function copy() {
    try {
      await navigator.clipboard.writeText(token.value);
      toast.success("Token kopiert.");
    } catch {
      // Ohne Zugriff auf die Zwischenablage bleibt der Text markierbar - nur der Knopf
      // kann nichts tun.
      toast.error("Kopieren nicht erlaubt - bitte von Hand markieren.");
    }
  }

  return (
    <Modal
      open
      title={`Token für ${token.node}`}
      description="Es wird nur jetzt angezeigt - gespeichert ist allein sein Hash."
      onClose={onClose}
      footer={
        <>
          <Button icon="key" onClick={copy}>
            Kopieren
          </Button>
          <Button variant="primary" onClick={onClose}>
            Verstanden
          </Button>
        </>
      }
    >
      <code className="console block break-all rounded-md border border-warn/40 bg-warn/5 px-3 py-2 text-sm text-warn">
        {token.value}
      </code>
      <p className="mt-3 text-sm text-text-muted">
        In die <code className="console">wrapper.json</code> auf dem Root eintragen und
        den Wrapper neu starten.
      </p>
    </Modal>
  );
}

function CreateNode({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (node: string, token: string) => Promise<void>;
}) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [memory, setMemory] = useState("8192");
  const [busy, setBusy] = useState(false);

  async function create() {
    setBusy(true);
    try {
      const answer = await request<{ token: string }>("/api/v1/nodes", {
        method: "POST",
        body: { name, maxMemoryMb: Number(memory) || 8192 },
      });
      toast.success(`Node ${name} angelegt.`);
      onClose();
      setName("");
      await onCreated(name, answer.token);
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      title="Neuer Node"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Abbrechen
          </Button>
          <Button variant="primary" busy={busy} disabled={!name} onClick={create}>
            Anlegen
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Name" hint="So heißt der Node in node list und in der wrapper.json.">
          {(props) => (
            <TextInput
              {...props}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="node-b"
            />
          )}
        </Field>

        <Field label="Speicher (MB)" hint="Wie viel die Cloud auf diesem Root vergeben darf.">
          {(props) => (
            <TextInput
              {...props}
              inputMode="numeric"
              value={memory}
              onChange={(event) => setMemory(event.target.value)}
            />
          )}
        </Field>

        <p className="text-xs text-text-faint">
          Ohne IP-Liste darf sich der Node von jeder Adresse anmelden. Für den
          Produktivbetrieb in der Konsole mit IP-Liste anlegen:{" "}
          <code className="console">node add &lt;name&gt; &lt;mb&gt; &lt;ip,ip&gt;</code>
        </p>
      </div>
    </Modal>
  );
}
