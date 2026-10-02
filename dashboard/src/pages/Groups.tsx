import { useEffect, useState } from "react";
import { ApiError, request, type Field as GroupField, type Group } from "../api";
import { Button } from "../ui/Button";
import { changedFields, FieldGrid } from "../ui/FieldsForm";
import { Field, Select, TextInput } from "../ui/Form";
import { Badge, Card, Cell, Empty, PageHeader, Row, Table } from "../ui/Layout";
import { ConfirmModal, Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/**
 * Servergruppen anlegen und aendern.
 *
 * Die aenderbaren Felder kommen vom Master ({@code /api/v1/groups/fields}), nicht aus
 * einer Liste hier: Was vorgeschlagen wird, muss auch gesetzt werden koennen, und beides
 * waere sonst beim naechsten neuen Feld auseinandergelaufen.
 */
export function Groups() {
  const toast = useToast();
  const [groups, setGroups] = useState<Group[]>([]);
  const [fields, setFields] = useState<GroupField[]>([]);
  const [busy, setBusy] = useState(false);

  // Nur der Name, nicht die Gruppe selbst: Nach dem Speichern laedt die Seite neu, und
  // das Formular soll die neuen Werte zeigen, nicht die von vorhin.
  const [editingName, setEditingName] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<Group | null>(null);

  async function load() {
    try {
      const [loadedGroups, loadedFields] = await Promise.all([
        request<Group[]>("/api/v1/groups"),
        request<GroupField[]>("/api/v1/groups/fields"),
      ]);
      setGroups(loadedGroups);
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
      await request(`/api/v1/groups/${deleting.name}`, { method: "DELETE" });
      toast.success(`Gruppe ${deleting.name} gelöscht.`);
      setDeleting(null);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  const editing = groups.find((group) => group.name === editingName) ?? null;

  return (
    <div>
      <PageHeader
        title="Gruppen"
        subtitle="Eine Gruppe beschreibt, wie viele Server welcher Art laufen sollen."
        actions={
          <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
            Neue Gruppe
          </Button>
        }
      />

      <Card padded={false}>
        {groups.length === 0 ? (
          <Empty
            icon="group"
            title="Noch keine Gruppe"
            hint="Ohne Gruppe startet die Cloud nichts - eine Gruppe ist die Vorlage für ihre Server."
            action={
              <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
                Neue Gruppe
              </Button>
            }
          />
        ) : (
          <Table
            columns={["Name", "Plattform", "Online", "Min / Max", "Speicher", ""]}
          >
            {groups.map((group) => (
              <Row key={group.name} highlighted={group.name === editingName}>
                <Cell>
                  <span className="inline-flex items-center gap-2 font-medium text-text">
                    {group.name}
                    {group.static && <Badge>statisch</Badge>}
                  </span>
                </Cell>
                <Cell>{group.platform}</Cell>
                <Cell className="tabular-nums">{group.online}</Cell>
                <Cell className="tabular-nums">
                  {group.minOnline} / {group.maxOnline}
                </Cell>
                <Cell className="tabular-nums">{group.memoryMb} MB</Cell>
                <Cell align="right">
                  <Button size="sm" icon="edit" onClick={() => setEditingName(group.name)}>
                    Bearbeiten
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    icon="trash"
                    className="ml-2"
                    onClick={() => setDeleting(group)}
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
        <EditGroup
          group={editing}
          fields={fields}
          onClose={() => setEditingName(null)}
          onSaved={load}
        />
      )}

      <CreateGroup
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={load}
      />

      <ConfirmModal
        open={deleting !== null}
        title={`Gruppe ${deleting?.name} löschen?`}
        description={
          "Laufende Server der Gruppe bleiben zunächst stehen, werden aber nicht ersetzt. " +
          "Bei einer statischen Gruppe bleiben die Weltverzeichnisse auf dem Node liegen."
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
 * Alle Felder einer Gruppe in einem Formular.
 *
 * Nicht "Feld waehlen, Wert eintragen": In jedem Feld steht, was gerade gilt, und man
 * aendert so viele auf einmal, wie man will. Vorher musste man wissen, was drinsteht,
 * und jedes Feld einzeln speichern.
 *
 * Geschickt wird trotzdem ein PATCH je Feld - das ist die Schnittstelle, und sie prueft
 * jeden Wert einzeln. Dafuer sagt die Meldung hinterher genau, welches Feld nicht ging.
 */
function EditGroup({
  group,
  fields,
  onClose,
  onSaved,
}: {
  group: Group;
  fields: GroupField[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<Record<string, string>>(group.fields);
  const [busy, setBusy] = useState(false);

  // Nach dem Speichern laedt die Seite neu - dann gilt der neue Stand, nicht der Entwurf.
  useEffect(() => setDraft(group.fields), [group]);

  const changed = changedFields(draft, group.fields);

  async function save() {
    setBusy(true);
    const failed: string[] = [];
    let saved = 0;

    for (const [field, value] of changed) {
      try {
        await request(`/api/v1/groups/${group.name}`, {
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

    // Jedes Feld einzeln melden - eine Sammelmeldung "teilweise gespeichert" waere
    // genau die Auskunft, die man nicht brauchen kann.
    if (saved > 0) {
      toast.success(`${group.name}: ${saved} Feld${saved === 1 ? "" : "er"} gespeichert.`);
    }
    for (const message of failed) {
      toast.error(message);
    }
    await onSaved();
    setBusy(false);
  }

  return (
    <Modal
      open
      wide
      title={`${group.name} bearbeiten`}
      description="Änderungen wirken auf neu gestartete Server - laufende behalten ihre Einstellungen."
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
      <FieldGrid
        fields={fields}
        draft={draft}
        current={group.fields}
        onChange={(name, value) => setDraft({ ...draft, [name]: value })}
      />
    </Modal>
  );
}

/** Eine neue Gruppe mit Vorgaben anlegen. */
function CreateGroup({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => Promise<void>;
}) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [platform, setPlatform] = useState("PAPER");
  const [busy, setBusy] = useState(false);

  async function create() {
    setBusy(true);
    try {
      await request("/api/v1/groups", { method: "POST", body: { name, platform } });
      toast.success(`Gruppe ${name} angelegt.`);
      setName("");
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
      title="Neue Gruppe"
      description="Startet mit Vorgaben: dynamisch, 1-3 Server, 2 GB. Das Template heißt wie die Gruppe."
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
        <Field label="Name" hint="Kleinbuchstaben, so heißen später die Server.">
          {(props) => (
            <TextInput
              {...props}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="bedwars"
            />
          )}
        </Field>

        <Field label="Plattform">
          {(props) => (
            <Select
              {...props}
              value={platform}
              onChange={(event) => setPlatform(event.target.value)}
            >
              <option value="PAPER">PAPER</option>
              <option value="VELOCITY">VELOCITY</option>
              <option value="MINESTOM">MINESTOM</option>
            </Select>
          )}
        </Field>
      </div>
    </Modal>
  );
}
