import { useEffect, useState } from "react";
import {
  ApiError,
  request,
  type Field as GroupField,
  type Group,
  type VersionList,
} from "../api";
import { Button } from "../ui/Button";
import { Icon } from "../ui/Icon";
import { changedFields, FieldGrid } from "../ui/FieldsForm";
import { Field, Select, TextInput } from "../ui/Form";
import { Badge, Card, Cell, Empty, PageHeader, Row, Table } from "../ui/Layout";
import { ConfirmModal, Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";
import { MAINTENANCE_CHANGED } from "../MaintenanceToggle";

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
  const [closing, setClosing] = useState<Group | null>(null);

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

  /**
   * Die Wartung einer Gruppe - dasselbe wie {@code maintenance on|off <gruppe>}.
   *
   * Ein eigener Aufruf und nicht das Feld im Formular: Hier zaehlt das Recht fuer die
   * Wartung, dort das zum Bearbeiten der Gruppe. Wer ein Minigame kurz sperren darf,
   * muss dafuer nicht die ganze Gruppe umbauen duerfen.
   */
  async function setMaintenance(group: Group, active: boolean) {
    setBusy(true);
    try {
      const answer = await request<{ note: string; plugins: number }>(
        `/api/v1/settings/maintenance/groups/${group.name}`,
        { method: "PUT", body: { active } },
      );
      toast.success(`${answer.note} An ${answer.plugins} Server gemeldet.`);
      setClosing(null);
      await load();
      // Die Seitenleiste nennt die Gruppen in Wartung - sie soll es gleich wissen.
      window.dispatchEvent(new Event(MAINTENANCE_CHANGED));
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
            columns={["Name", "Plattform", "Version", "Online", "Min / Max", "Speicher", ""]}
          >
            {groups.map((group) => (
              <Row key={group.name} highlighted={group.name === editingName}>
                <Cell>
                  <span className="inline-flex items-center gap-2 font-medium text-text">
                    {group.name}
                    {group.static && <Badge>statisch</Badge>}
                    {inMaintenance(group) && <Badge tone="warn">Wartung</Badge>}
                  </span>
                </Cell>
                <Cell>{group.platform}</Cell>
                <Cell className="tabular-nums">{group.fields.mc_version || "–"}</Cell>
                <Cell className="tabular-nums">{group.online}</Cell>
                <Cell className="tabular-nums">
                  {group.minOnline} / {group.maxOnline}
                </Cell>
                <Cell className="tabular-nums">{group.memoryMb} MB</Cell>
                <Cell align="right">
                  {/*
                    Einschalten fragt nach, Aufheben nicht - das eine sperrt Spieler aus,
                    das andere laesst sie wieder herein.
                  */}
                  <Button
                    size="sm"
                    icon={inMaintenance(group) ? "check" : "alert"}
                    className="mr-2"
                    disabled={busy}
                    onClick={() =>
                      inMaintenance(group) ? setMaintenance(group, false) : setClosing(group)
                    }
                  >
                    {inMaintenance(group) ? "Wartung aufheben" : "Wartung"}
                  </Button>
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
        open={closing !== null}
        title={`Gruppe ${closing?.name} in Wartung setzen?`}
        description={
          "Neue Verbindungen zu Servern dieser Gruppe werden abgelehnt, und sie fällt als " +
          "Join-Ziel weg. Die laufenden Server bleiben an, und wer schon darauf spielt, " +
          "bleibt dort. Wer vibecloud.maintenance.bypass hat, kommt weiter hinein - so " +
          "lässt sich darauf testen."
        }
        confirmLabel="In Wartung setzen"
        busy={busy}
        onConfirm={() => closing && setMaintenance(closing, true)}
        onClose={() => setClosing(null)}
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

/** Das Feld kommt als Text vom Master, wie jedes andere der Gruppe. */
function inMaintenance(group: Group): boolean {
  return group.fields.maintenance === "true";
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
  const { list: versions } = useVersions(group.platform);

  // Nach dem Speichern laedt die Seite neu - dann gilt der neue Stand, nicht der Entwurf.
  useEffect(() => setDraft(group.fields), [group]);

  const changed = changedFields(draft, group.fields);

  // Der Master kennt die Versionen nur je Plattform - /groups/fields gilt fuer alle
  // Gruppen. Deshalb kommen die Vorschlaege fuer mc_version hier dazu. Es bleiben
  // Vorschlaege: Ohne Liste (PaperMC nicht erreichbar) geht trotzdem jede Eingabe.
  const versionIds = versions?.selectable
    ? ["latest", ...versions.versions.map((version) => version.id)]
    : [];
  const shownFields =
    versionIds.length > 0
      ? fields.map((field) =>
          field.name === "mc_version" ? { ...field, values: versionIds } : field,
        )
      : fields;
  const requirement = requirementOf(versions, draft.mc_version ?? "");
  const versionChanged = (draft.mc_version ?? "") !== (group.fields.mc_version ?? "");

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
        fields={shownFields}
        draft={draft}
        current={group.fields}
        notes={requirement ? { mc_version: requirement.text } : {}}
        onChange={(name, value) => setDraft({ ...draft, [name]: value })}
      />
      {/*
        Bei einer geaenderten Version steht unter dem Feld "Vorher: ...". Was die neue
        braucht, soll man trotzdem vor dem Speichern sehen.
      */}
      {versionChanged && requirement && versions && (
        <div className="mt-4 space-y-2">
          <p className="text-xs text-text-muted">
            {draft.mc_version}: {requirement.text}
          </p>
          <JavaWarning list={versions} java={requirement.java} />
        </div>
      )}
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
  const [version, setVersion] = useState("latest");
  const [busy, setBusy] = useState(false);
  const { list: versions, failed } = useVersions(open ? platform : null);

  const selectable = platform !== "MINESTOM";
  // Eine Auswahl nur, wenn die Liste da ist. Sonst ein freies Feld - die Versionsliste
  // kommt von PaperMC, und ohne Internet soll man trotzdem eine Gruppe anlegen koennen.
  const asSelect = versions !== null && versions.reachable && versions.versions.length > 0;
  const requirement = requirementOf(versions, version);

  async function create() {
    setBusy(true);
    try {
      await request("/api/v1/groups", {
        method: "POST",
        body: selectable ? { name, platform, version } : { name, platform },
      });
      toast.success(
        selectable ? `Gruppe ${name} angelegt (${version}).` : `Gruppe ${name} angelegt.`,
      );
      setName("");
      setVersion("latest");
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
              onChange={(event) => {
                setPlatform(event.target.value);
                // Paper- und Velocity-Versionen haben nichts gemeinsam.
                setVersion("latest");
              }}
            >
              <option value="PAPER">PAPER</option>
              <option value="VELOCITY">VELOCITY</option>
              <option value="MINESTOM">MINESTOM</option>
            </Select>
          )}
        </Field>

        {selectable ? (
          <Field
            label="Version"
            hint={
              requirement?.text ??
              (failed || (versions && !versions.reachable)
                ? "Versionsliste nicht abrufbar - Version von Hand eintragen, geprüft wird sie beim Speichern."
                : "Fehlt das Jar, lädt der Master es nach dem Anlegen herunter.")
            }
          >
            {(props) =>
              asSelect ? (
                <Select
                  {...props}
                  value={version}
                  onChange={(event) => setVersion(event.target.value)}
                >
                  <option value="latest">latest (neueste stabile)</option>
                  {versions.versions.map((entry) => (
                    <option key={entry.id} value={entry.id}>
                      {entry.id}
                      {entry.legacy ? " · Legacy" : ""}
                      {entry.downloaded ? " · geladen" : ""}
                    </option>
                  ))}
                </Select>
              ) : (
                <TextInput
                  {...props}
                  value={version}
                  onChange={(event) => setVersion(event.target.value.trim())}
                  placeholder="latest"
                />
              )
            }
          </Field>
        ) : (
          <p className="text-xs text-text-muted">
            Minestom bringt sein Jar selbst mit: templates/{name || "<gruppe>"}/server.jar.
          </p>
        )}

        {/* Am Formular und nicht als Toast: Das muss man vor dem Klick lesen. */}
        {selectable && requirement && versions && (
          <JavaWarning list={versions} java={requirement.java} />
        )}
      </div>
    </Modal>
  );
}

/**
 * Die waehlbaren Versionen einer Plattform, wie {@code group versions} sie zeigt.
 *
 * {@code failed} heisst: kein Recht ({@code vibecloud.command.group.versions}) oder der
 * Master nicht erreichbar. Dann gibt es ein freies Feld statt einer Auswahl.
 */
function useVersions(platform: string | null) {
  const [list, setList] = useState<VersionList | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!platform) {
      return;
    }
    let active = true;
    setList(null);
    setFailed(false);
    request<VersionList>(`/api/v1/groups/versions?platform=${platform}`)
      .then((answer) => active && setList(answer))
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, [platform]);

  return { list, failed };
}

/** Was ein Server dieser Version braucht - eine Zeile fuer den Hinweis am Feld. */
function requirementOf(
  list: VersionList | null,
  version: string,
): { text: string; java: number } | null {
  if (!list || !list.selectable) {
    return null;
  }
  if (version === "latest") {
    return {
      text: `Neueste stabile Version · braucht Java ${list.latestJava}+`,
      java: list.latestJava,
    };
  }
  const found = list.versions.find((entry) => entry.id === version);
  if (!found) {
    return null;
  }
  const parts = [`Braucht Java ${found.java}+`];
  if (found.legacy) {
    parts.push("bekommt das Legacy-Plugin");
  }
  parts.push(found.downloaded ? "Jar ist geladen" : "Jar wird nach dem Speichern geladen");
  if (!found.supported) {
    parts.push("von PaperMC nicht mehr gepflegt");
  }
  return { text: parts.join(" · "), java: found.java };
}

/**
 * Warnt, wenn kein verbundener Node die noetige Java hat.
 *
 * Gespeichert werden darf trotzdem: Die Java laesst sich nachtragen, und eine Gruppe
 * vorzubereiten, bevor der Root eingerichtet ist, ist ein normaler Ablauf. Ein Node, der
 * nichts meldet, ist ein alter Wrapper - der startet alles mit seiner eigenen Java, so wie
 * der Master es auch sieht.
 */
function JavaWarning({ list, java }: { list: VersionList; java: number }) {
  const ready = list.nodes.some(
    (node) => node.java.length === 0 || node.java.some((version) => version >= java),
  );
  if (ready) {
    return null;
  }
  return (
    <p className="flex items-start gap-2 rounded-md border border-warn/40 p-3 text-xs text-warn">
      <Icon name="alert" className="mt-px size-4 shrink-0" />
      {list.nodes.length === 0
        ? `Kein Node ist verbunden. Server dieser Version brauchen Java ${java} oder neuer auf dem Node.`
        : `Kein verbundener Node hat Java ${java} oder neuer. Die Server starten erst, wenn ` +
          `ein Node sie installiert und in seiner wrapper.json unter javaRuntimes einträgt.`}
    </p>
  );
}
