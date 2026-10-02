import { useEffect, useMemo, useState } from "react";
import {
  ApiError,
  request,
  type Group,
  type PermissionCheck,
  type PermissionNode,
  type PermissionRule,
  type PlayerPermissions,
  type RankPermissions,
  type Server,
} from "../api";
import { Button } from "../ui/Button";
import { Field, Select, TextInput } from "../ui/Form";
import { Badge, Cell, Empty, Row, Table } from "../ui/Layout";
import { Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/**
 * Der Rechte-Editor - fuer Raenge und fuer Spieler.
 *
 * Eine Datei fuer beide, weil es dieselbe Sache ist: eine Liste von Regeln, ein Formular
 * zum Anlegen, ein Kreuz zum Entfernen. Zweimal geschrieben waere der Unterschied zwischen
 * Rang und Spieler irgendwann ein Unterschied im Verhalten - und zu sehen waere er erst
 * an einem Spieler, der etwas darf.
 *
 * Entschieden wird hier nichts. Jede Aenderung geht als ein Aufruf an den Master, und der
 * prueft Knoten, Kontext und Dauer selbst neu; die Auswertung der Regeln passiert dort
 * ohnehin ausschliesslich (`PermissionService`).
 */

/**
 * Die Id der Vorschlagsliste fuer Knoten.
 *
 * Eine Liste je Dialog, auf die beide Felder zeigen - das Formular zum Anlegen und das zum
 * Pruefen. Zwei `datalist` mit derselben Id waeren ungueltiges HTML, und der Browser
 * nimmt dann die erste; welche das ist, haengt an der Reihenfolge im Dialog.
 */
const NODE_LIST = "vc-permission-nodes";

function NodeDatalist({ nodes }: { nodes: PermissionNode[] }) {
  return (
    <datalist id={NODE_LIST}>
      {nodes.map((entry) => (
        <option key={entry.node} value={entry.node}>
          {entry.description}
        </option>
      ))}
    </datalist>
  );
}

/** Woher eine Regel kommt, in Worten. */
function describeTier(rule: PermissionRule): string {
  switch (rule.tier) {
    case "PLAYER":
      return "direkt beim Spieler";
    case "OWN_RANK":
      return `eigener Rang ${rule.source}`;
    case "INHERITED_RANK":
      return `geerbt von ${rule.source} (weight ${rule.weight})`;
    default:
      return "";
  }
}

/**
 * Erlaubt oder verboten - als Wort, nicht nur als Farbe.
 *
 * Ein gruener und ein roter Punkt sehen fuer Farbenblinde gleich aus, und das ist hier die
 * wichtigste Information der ganzen Zeile.
 */
function Value({ allowed }: { allowed: boolean }) {
  return (
    <Badge tone={allowed ? "ok" : "bad"}>{allowed ? "erlaubt" : "verboten"}</Badge>
  );
}

/**
 * Eine Tabelle mit Regeln.
 *
 * `onRemove` fehlt bei geerbten und bei Rang-Regeln in der Spieler-Ansicht: Dort gehoert
 * die Regel jemand anderem. Ein Kreuz, das die Regel eines Elternrangs loescht, waere eine
 * Aenderung an einer Stelle, die man gerade nicht ansieht.
 */
function RuleTable({
  rules,
  withSource,
  onRemove,
  busy,
}: {
  rules: PermissionRule[];
  withSource?: boolean;
  onRemove?: (rule: PermissionRule) => void;
  busy?: boolean;
}) {
  const columns = ["Knoten", "", "Kontext", "Gültig bis"];
  if (withSource) {
    columns.push("Herkunft");
  }
  if (onRemove) {
    columns.push("");
  }

  return (
    <Table columns={columns} narrow>
      {rules.map((rule) => (
        <Row key={`${rule.node}|${rule.context}|${rule.tier ?? ""}|${rule.source ?? ""}`}>
          <Cell className="console text-xs text-text">{rule.node}</Cell>
          <Cell>
            <Value allowed={rule.value} />
          </Cell>
          <Cell className="text-xs">
            {rule.context === "global" ? (
              <span className="text-text-faint">überall</span>
            ) : (
              <span className="console">{rule.context}</span>
            )}
          </Cell>
          <Cell className="text-xs">{rule.expiresAt || "permanent"}</Cell>
          {withSource && <Cell className="text-xs">{describeTier(rule)}</Cell>}
          {onRemove && (
            <Cell align="right">
              <Button
                size="sm"
                variant="ghost"
                icon="close"
                busy={busy}
                aria-label={`${rule.node} entfernen`}
                onClick={() => onRemove(rule)}
              >
                Entfernen
              </Button>
            </Cell>
          )}
        </Row>
      ))}
    </Table>
  );
}

/**
 * Das Formular zum Anlegen einer Regel.
 *
 * Der Knoten ist ein freies Feld mit `datalist`, kein `select`: Der Master kennt die
 * Knoten der Befehle und die der Module, aber ein Plugin bringt eigene mit, ohne sie
 * anzumelden. Als Auswahlliste waere das Dashboard enger als die Konsole.
 *
 * Der Kontext dagegen ist eine Auswahl - "überall", eine Gruppe oder ein Server sind
 * wirklich drei Fälle, und die Namen kennt der Master vollständig.
 */
function AddRule({
  nodes,
  groups,
  servers,
  withDuration,
  busy,
  onAdd,
}: {
  nodes: PermissionNode[];
  groups: Group[];
  servers: Server[];
  /** Nur Spieler-Rechte können ablaufen - siehe `PermissionRoutes`. */
  withDuration?: boolean;
  busy?: boolean;
  onAdd: (rule: {
    node: string;
    value: boolean;
    group?: string;
    server?: string;
    duration?: string;
  }) => Promise<void>;
}) {
  const [node, setNode] = useState("");
  const [value, setValue] = useState("true");
  const [scope, setScope] = useState<"global" | "group" | "server">("global");
  const [target, setTarget] = useState("");
  const [duration, setDuration] = useState("permanent");

  const hint = useMemo(
    () => nodes.find((entry) => entry.node === node.trim().toLowerCase())?.description,
    [node, nodes],
  );

  const incomplete = !node.trim() || (scope !== "global" && !target);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (incomplete) {
      return;
    }
    await onAdd({
      node: node.trim(),
      value: value === "true",
      group: scope === "group" ? target : undefined,
      server: scope === "server" ? target : undefined,
      duration: withDuration ? duration : undefined,
    });
    // Der Knoten bleibt stehen: Oft legt man mehrere Kontexte für denselben an.
    setTarget("");
  }

  return (
    <form onSubmit={submit} className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-[1fr_9rem]">
        <Field
          label="node"
          hint={hint || "Vorschläge aus Befehlen und Modulen - eigene Knoten gehen auch."}
        >
          {(props) => (
            <TextInput
              {...props}
              list={NODE_LIST}
              value={node}
              onChange={(event) => setNode(event.target.value)}
              placeholder="vibecloud.command.server.stop"
              className="console"
              autoComplete="off"
            />
          )}
        </Field>

        <Field label="Wirkung" hint="Ein Verbot schlägt bei gleicher Genauigkeit.">
          {(props) => (
            <Select
              {...props}
              value={value}
              onChange={(event) => setValue(event.target.value)}
            >
              <option value="true">erlaubt</option>
              <option value="false">verboten</option>
            </Select>
          )}
        </Field>
      </div>

      <div
        className={
          "grid gap-3 " +
          (withDuration
            ? "sm:grid-cols-[12rem_1fr_9rem]"
            : "sm:grid-cols-[12rem_1fr]")
        }
      >
        <Field label="Gilt" hint="Genauer schlägt allgemeiner.">
          {(props) => (
            <Select
              {...props}
              value={scope}
              onChange={(event) => {
                setScope(event.target.value as "global" | "group" | "server");
                setTarget("");
              }}
            >
              <option value="global">überall</option>
              <option value="group">in einer Gruppe</option>
              <option value="server">auf einem Server</option>
            </Select>
          )}
        </Field>

        {scope === "global" ? (
          <p className="self-center pt-4 text-xs text-text-faint">
            Ohne Einschränkung - die Regel gilt auf jedem Server.
          </p>
        ) : (
          <Field label={scope === "group" ? "group" : "server"}>
            {(props) => (
              <Select
                {...props}
                value={target}
                onChange={(event) => setTarget(event.target.value)}
              >
                <option value="">wählen ...</option>
                {scope === "group"
                  ? groups.map((group) => (
                      <option key={group.name} value={group.name}>
                        {group.name}
                      </option>
                    ))
                  : servers.map((server) => (
                      <option key={server.name} value={server.name}>
                        {server.name}
                      </option>
                    ))}
              </Select>
            )}
          </Field>
        )}

        {withDuration && (
          <Field label="Dauer" hint="30d, 12h, 90m, 45s">
            {(props) => (
              <TextInput
                {...props}
                list="vc-permission-durations"
                value={duration}
                onChange={(event) => setDuration(event.target.value)}
              />
            )}
          </Field>
        )}
      </div>
      <datalist id="vc-permission-durations">
        {["permanent", "30d", "14d", "7d", "3d", "24h", "12h", "2h", "30m"].map(
          (entry) => (
            <option key={entry} value={entry} />
          ),
        )}
      </datalist>

      <div className="flex justify-end">
        <Button
          type="submit"
          variant="primary"
          icon="plus"
          busy={busy}
          disabled={incomplete}
        >
          Regel hinzufügen
        </Button>
      </div>
    </form>
  );
}

/**
 * Knoten, Gruppen und Server für die Vorschläge.
 *
 * In einem Hook, weil beide Dialoge dasselbe brauchen. Fehlt eines davon, bleibt der
 * Dialog benutzbar - dann gibt es eben keine Vorschläge, aber das Feld nimmt ohnehin
 * freien Text.
 */
function useSuggestions() {
  const [nodes, setNodes] = useState<PermissionNode[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [servers, setServers] = useState<Server[]>([]);

  useEffect(() => {
    request<PermissionNode[]>("/api/v1/permissions/nodes")
      .then(setNodes)
      .catch(() => setNodes([]));
    request<Group[]>("/api/v1/groups")
      .then(setGroups)
      .catch(() => setGroups([]));
    request<Server[]>("/api/v1/servers")
      .then(setServers)
      .catch(() => setServers([]));
  }, []);

  return { nodes, groups, servers };
}

/** Die Abfrage zum Entfernen - Knoten und Kontext müssen genau so wieder mit. */
function removeQuery(rule: PermissionRule): string {
  const parameters = new URLSearchParams({ node: rule.node });
  if (rule.group) {
    parameters.set("group", rule.group);
  }
  if (rule.server) {
    parameters.set("server", rule.server);
  }
  return parameters.toString();
}

function message(exception: unknown, fallback: string): string {
  return exception instanceof ApiError ? exception.message : fallback;
}

// ---------------------------------------------------------------- Ränge

/**
 * Die Rechte eines Rangs.
 *
 * Die geerbten stehen darunter, weil ohne sie niemand erklären kann, warum ein Rang etwas
 * darf, das nicht in seiner eigenen Liste steht. Ändern lassen sie sich hier nicht - sie
 * gehören dem Elternrang.
 */
export function RankPermissionsModal({
  rankId,
  onClose,
}: {
  rankId: string;
  onClose: () => void;
}) {
  const toast = useToast();
  const { nodes, groups, servers } = useSuggestions();
  const [data, setData] = useState<RankPermissions | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      setData(await request<RankPermissions>(`/api/v1/ranks/${rankId}/permissions`));
    } catch (exception) {
      toast.error(message(exception, "Rechte nicht ladbar"));
    }
  }

  useEffect(() => {
    void load();
  }, [rankId]);

  async function add(rule: {
    node: string;
    value: boolean;
    group?: string;
    server?: string;
  }) {
    setBusy(true);
    try {
      await request(`/api/v1/ranks/${rankId}/permissions`, {
        method: "POST",
        body: rule,
      });
      toast.success(`${rankId}: ${rule.value ? "" : "-"}${rule.node} gesetzt.`);
      await load();
    } catch (exception) {
      toast.error(message(exception, "Regel nicht gespeichert"));
    } finally {
      setBusy(false);
    }
  }

  async function remove(rule: PermissionRule) {
    setBusy(true);
    try {
      await request(`/api/v1/ranks/${rankId}/permissions?${removeQuery(rule)}`, {
        method: "DELETE",
      });
      toast.success(`${rule.node} entfernt.`);
      await load();
    } catch (exception) {
      toast.error(message(exception, "Regel nicht entfernt"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      wide
      title={`Rechte von ${rankId}`}
      description="Wirkt sofort - für alle Spieler mit diesem Rang und für die, die ihn erben."
      onClose={onClose}
      footer={
        <Button variant="ghost" onClick={onClose}>
          Schließen
        </Button>
      }
    >
      <div className="space-y-6">
        <NodeDatalist nodes={nodes} />

        <Section title="Eigene Regeln">
          {data && data.own.length > 0 ? (
            <RuleTable rules={data.own} onRemove={remove} busy={busy} />
          ) : (
            <Empty
              icon="key"
              title="Keine eigenen Rechte"
              hint="Dieser Rang gibt selbst nichts - was seine Spieler dürfen, kommt aus der Vererbung."
            />
          )}
        </Section>

        <Section title="Neue Regel">
          <AddRule
            nodes={nodes}
            groups={groups}
            servers={servers}
            busy={busy}
            onAdd={add}
          />
        </Section>

        {data && data.inherited.length > 0 && (
          <Section
            title="Geerbt"
            hint="Gehört dem Elternrang - geändert wird es dort. Ein höheres weight schlägt ein niedrigeres."
          >
            <RuleTable rules={data.inherited} withSource />
          </Section>
        )}
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------- Spieler

/**
 * Die Rechte eines Spielers.
 *
 * Drei Teile, und die Reihenfolge ist Absicht: erst die Prüfung ("darf er das?"), dann
 * seine eigenen Regeln, dann alles Wirksame. Die Prüfung steht oben, weil sie der Grund
 * ist, aus dem man hier normalerweise landet.
 */
export function PlayerPermissionsModal({
  uuid,
  name,
  onClose,
}: {
  uuid: string;
  name: string;
  onClose: () => void;
}) {
  const toast = useToast();
  const { nodes, groups, servers } = useSuggestions();
  const [data, setData] = useState<PlayerPermissions | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      setData(await request<PlayerPermissions>(`/api/v1/players/${uuid}/permissions`));
    } catch (exception) {
      toast.error(message(exception, "Rechte nicht ladbar"));
    }
  }

  useEffect(() => {
    void load();
  }, [uuid]);

  async function add(rule: {
    node: string;
    value: boolean;
    group?: string;
    server?: string;
    duration?: string;
  }) {
    setBusy(true);
    try {
      await request(`/api/v1/players/${uuid}/permissions`, {
        method: "POST",
        body: rule,
      });
      toast.success(`${name}: ${rule.value ? "" : "-"}${rule.node} gesetzt.`);
      await load();
    } catch (exception) {
      toast.error(message(exception, "Regel nicht gespeichert"));
    } finally {
      setBusy(false);
    }
  }

  async function remove(rule: PermissionRule) {
    setBusy(true);
    try {
      await request(`/api/v1/players/${uuid}/permissions?${removeQuery(rule)}`, {
        method: "DELETE",
      });
      toast.success(`${rule.node} entfernt.`);
      await load();
    } catch (exception) {
      toast.error(message(exception, "Regel nicht entfernt"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      wide
      title={`Rechte von ${name}`}
      description={
        data
          ? `Rang ${data.rank}. Eigene Regeln wirken sofort - die Plugins laden neu.`
          : "Eigene Regeln wirken sofort - die Plugins laden neu."
      }
      onClose={onClose}
      footer={
        <Button variant="ghost" onClick={onClose}>
          Schließen
        </Button>
      }
    >
      <div className="space-y-6">
        <NodeDatalist nodes={nodes} />

        <Section
          title="Prüfen"
          hint="Sagt nicht nur ja oder nein, sondern welche Regel entschieden hat."
        >
          <CheckRule uuid={uuid} groups={groups} servers={servers} />
        </Section>

        <Section title="Eigene Regeln" hint="Nur diese gehören dem Spieler selbst.">
          {data && data.own.length > 0 ? (
            <RuleTable rules={data.own} onRemove={remove} busy={busy} />
          ) : (
            <Empty
              icon="key"
              title="Keine eigenen Rechte"
              hint="Was dieser Spieler darf, kommt vollständig aus seinem Rang."
            />
          )}
        </Section>

        <Section title="Neue Regel">
          <AddRule
            nodes={nodes}
            groups={groups}
            servers={servers}
            withDuration
            busy={busy}
            onAdd={add}
          />
        </Section>

        {data && data.effective.length > 0 && (
          <Section
            title="Alles Wirksame"
            hint="Jede Regel, die auf diesen Spieler zutrifft - mit ihrer Herkunft. Entfernt wird eine Rang-Regel beim Rang."
          >
            <RuleTable rules={data.effective} withSource />
          </Section>
        )}
      </div>
    </Modal>
  );
}

/**
 * `perm check` als Formular.
 *
 * Die überstimmten Regeln stehen mit darunter - oft steht genau dort der Denkfehler, etwa
 * ein Verbot in einem geerbten Rang, das niemand vermutet hat.
 */
function CheckRule({
  uuid,
  groups,
  servers,
}: {
  uuid: string;
  groups: Group[];
  servers: Server[];
}) {
  const toast = useToast();
  const [node, setNode] = useState("");
  const [scope, setScope] = useState<"global" | "group" | "server">("global");
  const [target, setTarget] = useState("");
  const [result, setResult] = useState<PermissionCheck | null>(null);
  const [busy, setBusy] = useState(false);

  async function check(event: React.FormEvent) {
    event.preventDefault();
    if (!node.trim()) {
      return;
    }
    setBusy(true);
    try {
      const parameters = new URLSearchParams({ node: node.trim() });
      if (scope === "group" && target) {
        parameters.set("group", target);
      }
      if (scope === "server" && target) {
        parameters.set("server", target);
      }
      setResult(
        await request<PermissionCheck>(
          `/api/v1/players/${uuid}/permissions/check?${parameters}`,
        ),
      );
    } catch (exception) {
      setResult(null);
      toast.error(message(exception, "Prüfung fehlgeschlagen"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      <form onSubmit={check} className="grid items-end gap-3 sm:grid-cols-[1fr_9rem_auto]">
        <Field label="node">
          {(props) => (
            <TextInput
              {...props}
              list={NODE_LIST}
              value={node}
              onChange={(event) => setNode(event.target.value)}
              placeholder="vibecloud.command.server.stop"
              className="console"
              autoComplete="off"
            />
          )}
        </Field>

        <Field label="Umgebung">
          {(props) => (
            <Select
              {...props}
              value={scope === "global" ? "global" : `${scope}:${target}`}
              onChange={(event) => {
                const [kind, value] = event.target.value.split(":");
                setScope(kind as "global" | "group" | "server");
                setTarget(value ?? "");
              }}
            >
              <option value="global">überall</option>
              {groups.map((group) => (
                <option key={`g-${group.name}`} value={`group:${group.name}`}>
                  Gruppe {group.name}
                </option>
              ))}
              {servers.map((server) => (
                <option key={`s-${server.name}`} value={`server:${server.name}`}>
                  Server {server.name}
                </option>
              ))}
            </Select>
          )}
        </Field>

        <Button type="submit" icon="check" busy={busy} disabled={!node.trim()}>
          Prüfen
        </Button>
      </form>

      {result && (
        <div className="rounded-md border border-line bg-bg p-3">
          <p className="flex flex-wrap items-center gap-2 text-sm text-text">
            <Value allowed={result.allowed} />
            <span className="console text-xs">{result.node}</span>
            <span className="text-xs text-text-faint">
              {result.context === "global" ? "überall" : result.context}
            </span>
          </p>

          {result.decidedBy ? (
            <p className="mt-2 text-xs text-text-muted">
              Entschieden durch{" "}
              <span className="console text-text">
                {result.decidedBy.value ? "" : "-"}
                {result.decidedBy.node}
              </span>
              {result.decidedBy.context === "global"
                ? " "
                : ` [${result.decidedBy.context}] `}
              &mdash; {describeTier(result.decidedBy)}.
            </p>
          ) : (
            <p className="mt-2 text-xs text-text-muted">
              Keine Regel trifft zu - es gilt die Vorgabe (verboten).
            </p>
          )}

          {result.overridden.length > 0 && (
            <div className="mt-3">
              <p className="mb-1 text-xs font-medium uppercase tracking-wide text-text-faint">
                Überstimmte Regeln
              </p>
              <RuleTable rules={result.overridden} withSource />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Ein Abschnitt im Dialog - Überschrift, optionaler Hinweis, Inhalt. */
function Section({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <section>
      <h3 className="text-xs font-medium uppercase tracking-wide text-text-faint">
        {title}
      </h3>
      {hint && <p className="mt-0.5 mb-2 text-xs text-text-muted">{hint}</p>}
      <div className={hint ? "" : "mt-2"}>{children}</div>
    </section>
  );
}
