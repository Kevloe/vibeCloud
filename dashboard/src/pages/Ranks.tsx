import { useEffect, useState } from "react";
import { ApiError, request, type Rank } from "../api";
import { Button } from "../ui/Button";
import { changedFields, FieldGrid } from "../ui/FieldsForm";
import { Field, Select, TextInput } from "../ui/Form";
import { Badge, Card, Cell, Empty, PageHeader, Row, Table } from "../ui/Layout";
import { ConfirmModal, Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";
import { RankPermissionsModal } from "./Permissions";

/**
 * Raenge anlegen, aendern, ihre Vererbung pflegen - und ihre Rechte.
 *
 * Die Rechte stehen in einem eigenen Dialog und nicht im Bearbeiten-Formular: Dort geht es
 * um Felder mit je einem Wert, hier um eine Liste von Regeln mit Knoten, Kontext und
 * Ablauf. Zusammen in einem Dialog waere das Formular zwei Formulare mit einem
 * Speichern-Knopf, der fuer das eine gilt und fuer das andere nicht - eine Regel wirkt
 * sofort, ein Feld erst beim Speichern.
 */
export function Ranks() {
  const toast = useToast();
  const [ranks, setRanks] = useState<Rank[]>([]);
  const [fields, setFields] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  // Nur die Id, nicht der Rang selbst: Nach dem Speichern laedt die Seite neu, und der
  // Dialog soll die neuen Werte zeigen, nicht die von vorhin.
  const [editingId, setEditingId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<Rank | null>(null);
  const [permissionsOf, setPermissionsOf] = useState<string | null>(null);

  async function load() {
    try {
      const [loadedRanks, loadedFields] = await Promise.all([
        request<Rank[]>("/api/v1/ranks"),
        request<string[]>("/api/v1/ranks/fields"),
      ]);
      setRanks(loadedRanks);
      setFields(loadedFields);
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
      );
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function remove() {
    if (!deleting) {
      return;
    }
    setBusy(true);
    try {
      await request(`/api/v1/ranks/${deleting.id}`, { method: "DELETE" });
      toast.success(`Rang ${deleting.id} gelöscht.`);
      setDeleting(null);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  const editing = ranks.find((rank) => rank.id === editingId) ?? null;

  return (
    <div>
      <PageHeader
        title="Ränge"
        subtitle="Ein Spieler hat genau einen Rang - Rechte-Sets kombiniert man über Vererbung."
        actions={
          <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
            Neuer Rang
          </Button>
        }
      />

      <Card padded={false}>
        {ranks.length === 0 ? (
          <Empty
            icon="rank"
            title="Noch kein Rang"
            hint="Der Standardrang entsteht beim ersten Start der Cloud - fehlt er, hat niemand Rechte."
          />
        ) : (
          <Table
            columns={["Id", "Anzeigename", "Prefix", "Gewicht", "Erbt von", ""]}
          >
            {ranks.map((rank) => (
              <Row key={rank.id} highlighted={rank.id === editingId}>
                <Cell>
                  <span className="inline-flex items-center gap-2 font-medium text-text">
                    {rank.id}
                    {rank.default && <Badge tone="brand">Standard</Badge>}
                  </span>
                </Cell>
                <Cell>{rank.displayName}</Cell>
                <Cell className="console text-xs">{rank.prefix || "-"}</Cell>
                <Cell className="tabular-nums">{rank.weight}</Cell>
                <Cell>
                  {rank.inherits.length > 0 ? rank.inherits.join(", ") : "-"}
                </Cell>
                <Cell align="right">
                  <Button
                    size="sm"
                    icon="key"
                    onClick={() => setPermissionsOf(rank.id)}
                  >
                    Rechte
                  </Button>
                  <Button
                    size="sm"
                    icon="edit"
                    className="ml-2"
                    onClick={() => setEditingId(rank.id)}
                  >
                    Bearbeiten
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    icon="trash"
                    className="ml-2"
                    disabled={rank.default}
                    title={
                      rank.default
                        ? "Der Standardrang lässt sich nicht löschen"
                        : undefined
                    }
                    onClick={() => setDeleting(rank)}
                  >
                    Löschen
                  </Button>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </Card>

      {editing && (
        <EditRank
          rank={editing}
          ranks={ranks}
          fields={fields}
          onClose={() => setEditingId(null)}
          onSaved={load}
        />
      )}

      {permissionsOf && (
        <RankPermissionsModal
          rankId={permissionsOf}
          onClose={() => setPermissionsOf(null)}
        />
      )}

      <CreateRank open={creating} onClose={() => setCreating(false)} onCreated={load} />

      <ConfirmModal
        open={deleting !== null}
        title={`Rang ${deleting?.id} löschen?`}
        description={
          "Spieler mit diesem Rang fallen auf ihren Ersatzrang zurück, sonst auf den " +
          "Standardrang. Die Rechte des Rangs selbst sind danach weg."
        }
        confirmLabel="Löschen"
        busy={busy}
        onConfirm={remove}
        onClose={() => setDeleting(null)}
      />
    </div>
  );
}

/**
 * Alle Felder eines Rangs in einem Formular, darunter die Vererbung.
 *
 * Wie bei den Gruppen: In jedem Feld steht, was gerade gilt. Die Vererbung bleibt davon
 * getrennt - sie wirkt sofort und einzeln, weil ein Elternrang kein Textfeld ist.
 */
function EditRank({
  rank,
  ranks,
  fields,
  onClose,
  onSaved,
}: {
  rank: Rank;
  ranks: Rank[];
  fields: string[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<Record<string, string>>(rank.fields);
  const [parent, setParent] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => setDraft(rank.fields), [rank]);

  const changed = changedFields(draft, rank.fields);

  async function save() {
    setBusy(true);
    const failed: string[] = [];
    let saved = 0;

    for (const [field, value] of changed) {
      try {
        await request(`/api/v1/ranks/${rank.id}`, {
          method: "PATCH",
          body: { field, value },
        });
        saved++;
      } catch (exception) {
        failed.push(
          `${field}: ${exception instanceof ApiError ? exception.message : "fehlgeschlagen"}`,
        );
      }
    }

    if (saved > 0) {
      toast.success(`${rank.id}: ${saved} Feld${saved === 1 ? "" : "er"} gespeichert.`);
    }
    for (const message of failed) {
      toast.error(message);
    }
    await onSaved();
    setBusy(false);
  }

  /** Vererbung wirkt sofort - sie haengt nicht am Speichern-Knopf des Formulars. */
  async function inherit(what: () => Promise<unknown>, success: string) {
    setBusy(true);
    try {
      await what();
      toast.success(success);
      await onSaved();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      wide
      title={`${rank.id} bearbeiten`}
      description="Die Änderung wirkt sofort - die Plugins laden die Rechte neu."
      onClose={onClose}
      footer={
        <>
          <span className="mr-auto text-xs text-text-faint">
            {changed.length === 0
              ? "Nichts geändert."
              : `${changed.length} geändert: ${changed.map(([name]) => name).join(", ")}`}
          </span>
          <Button variant="ghost" onClick={onClose}>
            Abbrechen
          </Button>
          <Button
            variant="primary"
            busy={busy}
            disabled={changed.length === 0}
            onClick={save}
          >
            Speichern
          </Button>
        </>
      }
    >
      <div className="space-y-6">
        <FieldGrid
          fields={fields.map((name) => ({ name, values: [] }))}
          draft={draft}
          current={rank.fields}
          onChange={(name, value) => setDraft({ ...draft, [name]: value })}
        />

        <div className="border-t border-line pt-4">
          <p className="mb-2 text-xs font-medium uppercase tracking-wide text-text-faint">
            Vererbung
          </p>

          <div className="mb-3 flex flex-wrap gap-2">
            {rank.inherits.map((entry) => (
              <span
                key={entry}
                className="inline-flex items-center gap-1.5 rounded border border-line px-2 py-1 text-xs text-text"
              >
                {entry}
                <button
                  aria-label={`${entry} entfernen`}
                  onClick={() =>
                    inherit(
                      () =>
                        request(`/api/v1/ranks/${rank.id}/inherit/${entry}`, {
                          method: "DELETE",
                        }),
                      `${rank.id} erbt nicht mehr von ${entry}.`,
                    )
                  }
                  className="text-text-faint transition-colors hover:text-bad"
                >
                  &times;
                </button>
              </span>
            ))}
            {rank.inherits.length === 0 && (
              <span className="text-xs text-text-faint">Erbt von niemandem.</span>
            )}
          </div>

          <div className="flex flex-wrap items-start gap-3">
            <div className="min-w-48 flex-1">
              <Field label="Erbt zusätzlich von">
                {(props) => (
                  <Select
                    {...props}
                    value={parent}
                    onChange={(event) => setParent(event.target.value)}
                  >
                    <option value="">wählen ...</option>
                    {ranks
                      .filter(
                        (entry) =>
                          entry.id !== rank.id && !rank.inherits.includes(entry.id),
                      )
                      .map((entry) => (
                        <option key={entry.id} value={entry.id}>
                          {entry.id}
                        </option>
                      ))}
                  </Select>
                )}
              </Field>
            </div>
            <Button
              icon="plus"
              busy={busy}
              disabled={!parent}
              className="mt-[1.3rem]"
              onClick={() =>
                inherit(
                  () =>
                    request(`/api/v1/ranks/${rank.id}/inherit`, {
                      method: "POST",
                      body: { parent },
                    }),
                  `${rank.id} erbt jetzt von ${parent}.`,
                ).then(() => setParent(""))
              }
            >
              Hinzufügen
            </Button>
          </div>

          <p className="mt-2 text-xs text-text-faint">
            Ein Kreis in der Vererbung wird beim Anlegen abgelehnt, nicht erst beim
            Auswerten.
          </p>
        </div>
      </div>
    </Modal>
  );
}

function CreateRank({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => Promise<void>;
}) {
  const toast = useToast();
  const [id, setId] = useState("");
  const [weight, setWeight] = useState("10");
  const [busy, setBusy] = useState(false);

  async function create() {
    setBusy(true);
    try {
      await request("/api/v1/ranks", {
        method: "POST",
        body: { id, weight: Number(weight) || 0 },
      });
      toast.success(`Rang ${id} angelegt.`);
      setId("");
      onClose();
      await onCreated();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      title="Neuer Rang"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Abbrechen
          </Button>
          <Button variant="primary" busy={busy} disabled={!id} onClick={create}>
            Anlegen
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Id" hint="Kleinbuchstaben - so heißt der Rang in rank set.">
          {(props) => (
            <TextInput
              {...props}
              value={id}
              onChange={(event) => setId(event.target.value)}
              placeholder="moderator"
            />
          )}
        </Field>

        <Field
          label="Gewicht"
          hint="Entscheidet bei geerbten Rechten, wer gewinnt - höher schlägt niedriger."
        >
          {(props) => (
            <TextInput
              {...props}
              inputMode="numeric"
              value={weight}
              onChange={(event) => setWeight(event.target.value)}
            />
          )}
        </Field>

        <p className="text-xs text-text-faint">
          Rechte vergibst du danach über „Rechte" in der Liste - oder mit{" "}
          <code className="console">perm rank add</code>.
        </p>
      </div>
    </Modal>
  );
}
