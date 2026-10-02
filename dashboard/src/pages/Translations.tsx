import { useEffect, useState } from "react";
import {
  ApiError,
  request,
  type LocaleFile,
  type LocaleSummary,
  type ModuleLocales,
} from "../api";
import { Button } from "../ui/Button";
import { Field, TextInput } from "../ui/Form";
import { Icon } from "../ui/Icon";
import { Badge, Card, Cell, Empty, PageHeader, Row, Table } from "../ui/Layout";
import { ConfirmModal, Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/** Was gerade uebersetzt wird: die Texte der Cloud oder die eines Moduls. */
type Editing = {
  /** Kennung des Moduls - {@code null} fuer die Texte der Cloud. */
  module: string | null;
  locale: string;
};

/**
 * Sprachen anlegen und uebersetzen (PLAN.md Abschnitt 11a).
 *
 * Zwei Arten von Texten: die der Cloud aus {@code messages/} und die der Module. Eine
 * Sprache gibt es, wenn die Cloud ihre Datei hat - die Module haengen sich daran. Nach
 * jedem Speichern liest der Master neu ein und verteilt an alle Plugins; eine
 * Textkorrektur wirkt sofort, ohne dass ein Server neu startet.
 *
 * Uebersetzt wird auf einer eigenen Seite und nicht in einem Dialog: Es sind ueber hundert
 * Zeilen, und in einem Dialog haette man davon nur einen Ausschnitt gesehen, der in sich
 * selbst scrollt.
 */
export function Translations() {
  const toast = useToast();
  const [locales, setLocales] = useState<LocaleSummary[]>([]);
  const [modules, setModules] = useState<ModuleLocales[]>([]);
  const [busy, setBusy] = useState(false);

  const [editing, setEditing] = useState<Editing | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<LocaleSummary | null>(null);

  async function load() {
    try {
      const answer = await request<{
        default: string;
        locales: LocaleSummary[];
        modules: ModuleLocales[];
      }>("/api/v1/messages");
      setLocales(answer.locales);
      setModules(answer.modules ?? []);
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
      const answer = await request<{ note: string }>(
        `/api/v1/messages/${deleting.locale}`,
        { method: "DELETE" },
      );
      toast.success(answer.note);
      setDeleting(null);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  /** Fuer Dateien, die jemand von Hand geaendert hat. */
  async function reload() {
    try {
      const answer = await request<{ note: string }>("/api/v1/messages/reload", {
        method: "POST",
      });
      toast.success(answer.note);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    }
  }

  if (editing) {
    return (
      <LocaleEditor
        // Der Schluessel sorgt dafuer, dass ein Wechsel der Sprache mit leerem Entwurf
        // beginnt und nicht mit dem der vorigen.
        key={`${editing.module ?? ""}/${editing.locale}`}
        editing={editing}
        onBack={() => {
          setEditing(null);
          // Die Zahlen in der Uebersicht (fehlend, eigene) haben sich geaendert.
          void load();
        }}
      />
    );
  }

  return (
    <div>
      <PageHeader
        title="Sprachen"
        subtitle="Jeder Text im Spiel kommt aus diesen Dateien - der Master verteilt sie an alle Server."
        actions={
          <>
            <Button icon="refresh" size="sm" onClick={reload}>
              Neu einlesen
            </Button>
            <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
              Neue Sprache
            </Button>
          </>
        }
      />

      <div className="space-y-6">
        <Card title="Texte der Cloud" padded={false}>
          {locales.length === 0 ? (
            <Empty
              icon="language"
              title="Noch keine Sprachdatei"
              hint="Die Standardsprache legt der Master beim ersten Start selbst an."
            />
          ) : (
            <Table columns={["Sprache", "Texte", "Fehlend", ""]}>
              {locales.map((locale) => (
                <Row key={locale.locale}>
                  <Cell>
                    <LocaleName locale={locale} />
                  </Cell>
                  <Cell className="tabular-nums">{locale.keys}</Cell>
                  <Cell>
                    <Missing count={locale.missing} />
                  </Cell>
                  <Cell align="right">
                    <Button
                      size="sm"
                      icon="edit"
                      onClick={() => setEditing({ module: null, locale: locale.locale })}
                    >
                      Übersetzen
                    </Button>
                    <Button
                      size="sm"
                      variant="danger"
                      icon="trash"
                      className="ml-2"
                      disabled={locale.default}
                      title={
                        locale.default
                          ? "Ohne die Standardsprache lädt gar nichts mehr"
                          : undefined
                      }
                      onClick={() => setDeleting(locale)}
                    >
                      Löschen
                    </Button>
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </Card>

        {/*
          Je Modul ein Kasten. Die Sprachen sind dieselben wie oben - angelegt und
          geloescht wird eine Sprache nur dort, nicht je Modul.
        */}
        {modules.map((module) => (
          <Card key={module.id} title={`Modul ${module.id}`} padded={false}>
            <Table columns={["Sprache", "Texte", "Fehlend", "Eigene", ""]}>
              {module.locales.map((locale) => (
                <Row key={locale.locale}>
                  <Cell>
                    <LocaleName locale={locale} />
                  </Cell>
                  <Cell className="tabular-nums">{locale.keys}</Cell>
                  <Cell>
                    <Missing count={locale.missing} />
                  </Cell>
                  <Cell className="tabular-nums">
                    {locale.overridden === 0 ? (
                      <span className="text-text-faint">keine</span>
                    ) : (
                      locale.overridden
                    )}
                  </Cell>
                  <Cell align="right">
                    <Button
                      size="sm"
                      icon="edit"
                      onClick={() =>
                        setEditing({ module: module.id, locale: locale.locale })
                      }
                    >
                      Übersetzen
                    </Button>
                  </Cell>
                </Row>
              ))}
            </Table>
          </Card>
        ))}
      </div>

      <CreateLocale
        open={creating}
        existing={locales.map((locale) => locale.locale)}
        onClose={() => setCreating(false)}
        onCreated={load}
      />

      <ConfirmModal
        open={deleting !== null}
        title={`Sprache ${deleting?.locale} löschen?`}
        description={
          "Die Datei wird gelöscht. Spieler mit dieser Sprache bekommen danach die " +
          "Standardsprache - ihre Einstellung bleibt, es gibt nur keine Texte mehr dazu. " +
          "Eigene Texte zu Modulen in dieser Sprache bleiben liegen und gelten wieder, " +
          "wenn die Sprache neu angelegt wird."
        }
        confirmLabel="Löschen"
        busy={busy}
        onConfirm={remove}
        onClose={() => setDeleting(null)}
      />
    </div>
  );
}

function LocaleName({ locale }: { locale: LocaleSummary }) {
  return (
    <span className="inline-flex items-center gap-2 font-medium text-text">
      {locale.locale}
      {locale.default && <Badge tone="brand">Standard</Badge>}
    </span>
  );
}

function Missing({ count }: { count: number }) {
  return count === 0 ? (
    <Badge tone="ok">vollständig</Badge>
  ) : (
    <Badge tone="warn">{count} fehlen – es greift Deutsch</Badge>
  );
}

/**
 * Die Uebersetzungs-Seite.
 *
 * Links der Schluessel und der Ausgangstext, rechts das Feld - ohne den Ausgangstext
 * uebersetzt niemand etwas. Leer gelassene Felder werden <b>nicht</b> gespeichert: Dann
 * greift die Standardsprache, und im Spiel steht ein deutscher Satz statt einer leeren
 * Zeile.
 *
 * Bei einem Modul kommt ein Drittes dazu: der Text aus dem JAR. Gespeichert wird dort nur,
 * was davon abweicht - alles andere soll weiter den Updates des Moduls folgen.
 */
function LocaleEditor({ editing, onBack }: { editing: Editing; onBack: () => void }) {
  const toast = useToast();
  const { module, locale } = editing;
  const url = module
    ? `/api/v1/messages/modules/${module}/${locale}`
    : `/api/v1/messages/${locale}`;

  const [file, setFile] = useState<LocaleFile | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [filter, setFilter] = useState("");
  const [onlyMissing, setOnlyMissing] = useState(false);
  const [onlyOwn, setOnlyOwn] = useState(false);
  const [busy, setBusy] = useState(false);
  const [leaving, setLeaving] = useState(false);

  async function load() {
    try {
      const loaded = await request<LocaleFile>(url);
      setFile(loaded);
      setDraft(loaded.entries);
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
      );
    }
  }

  useEffect(() => {
    void load();
  }, [url]);

  const title = `${locale} übersetzen`;
  const source = module ? `Modul ${module}` : "Texte der Cloud";

  if (!file) {
    return (
      <div>
        <PageHeader
          title={title}
          subtitle={source}
          actions={
            <Button icon="back" size="sm" onClick={onBack}>
              Zurück
            </Button>
          }
        />
        <p className="text-sm text-text-muted">Lade ...</p>
      </div>
    );
  }

  const bundled = file.bundled ?? {};

  // Alle Schluessel der Standardsprache plus die, die es nur hier gibt - sonst fiele ein
  // eigener Schluessel beim naechsten Speichern still unter den Tisch.
  const keys = Array.from(
    new Set([
      ...Object.keys(file.defaults),
      ...Object.keys(file.entries),
      ...Object.keys(bundled),
    ]),
  ).sort();

  /** Ob hier ein eigener Text steht, wo das Modul einen anderen mitbringt. */
  function isOwn(key: string): boolean {
    const value = draft[key] ?? "";
    return module !== null && value.trim() !== "" && value !== (bundled[key] ?? "");
  }

  const shown = keys.filter((key) => {
    if (onlyMissing && (draft[key] ?? "").trim() !== "") {
      return false;
    }
    if (onlyOwn && !isOwn(key)) {
      return false;
    }
    if (!filter) {
      return true;
    }
    const needle = filter.toLowerCase();
    return (
      key.toLowerCase().includes(needle) ||
      (draft[key] ?? "").toLowerCase().includes(needle) ||
      (file.defaults[key] ?? "").toLowerCase().includes(needle)
    );
  });

  const filled = keys.filter((key) => (draft[key] ?? "").trim() !== "").length;
  const changed = keys.filter(
    (key) => (draft[key] ?? "") !== (file.entries[key] ?? ""),
  ).length;

  async function save() {
    setBusy(true);
    // Leere Felder gar nicht erst schicken: Ein leerer Text waere im Spiel eine leere
    // Zeile, ein fehlender Schluessel dagegen faellt auf die Standardsprache zurueck.
    const entries: Record<string, string> = {};
    for (const [key, value] of Object.entries(draft)) {
      if (value.trim() !== "") {
        entries[key] = value;
      }
    }

    try {
      const answer = await request<{ note: string }>(url, {
        method: "PUT",
        body: { entries },
      });
      toast.success(answer.note);
      // Neu holen statt den Entwurf zu uebernehmen: Bei einem Modul steht in einem
      // geleerten Feld danach wieder der Text aus dem JAR.
      await load();
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Speichern fehlgeschlagen",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <PageHeader
        title={title}
        subtitle={
          <>
            {source} ·{" "}
            {file.default
              ? "Das ist die Standardsprache - sie ist die Vorlage für alle anderen und sollte vollständig bleiben."
              : "Leere Felder werden nicht gespeichert; dort greift die Standardsprache."}
          </>
        }
        actions={
          <Button
            icon="back"
            size="sm"
            // Ungespeichertes geht beim Verlassen verloren - das soll eine Entscheidung
            // sein und kein Versehen.
            onClick={() => (changed > 0 ? setLeaving(true) : onBack())}
          >
            Zurück
          </Button>
        }
      />

      {/*
        Suche und Speichern bleiben beim Scrollen oben stehen: In Zeile 120 soll man nicht
        zurueck an den Seitenanfang muessen, um zu speichern. Nur am Breitbild - auf einem
        schmalen laege die Leiste unter dem Schalter der Navigation.
      */}
      <div className="z-10 mb-4 flex flex-wrap items-center gap-3 rounded-card border border-line bg-surface p-3 lg:sticky lg:top-0">
        <div className="relative min-w-56 flex-1">
          <Icon
            name="search"
            className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-text-faint"
          />
          <TextInput
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="Schlüssel oder Text suchen"
            aria-label="Texte durchsuchen"
            className="pl-9"
          />
        </div>
        {!file.default && (
          <Toggle checked={onlyMissing} onChange={setOnlyMissing}>
            nur fehlende
          </Toggle>
        )}
        {module && (
          <Toggle checked={onlyOwn} onChange={setOnlyOwn}>
            nur eigene
          </Toggle>
        )}
        <span className="text-xs text-text-faint" aria-live="polite">
          {filled} von {keys.length} {file.default ? "gefüllt" : "übersetzt"}
          {changed > 0 ? ` · ${changed} geändert` : ""}
        </span>
        <Button variant="primary" busy={busy} disabled={changed === 0} onClick={save}>
          Speichern
        </Button>
      </div>

      <Card padded={false}>
        <ul>
          {shown.map((key) => {
            const value = draft[key] ?? "";
            const missing = value.trim() === "";
            const own = isOwn(key);
            const hasBundled = bundled[key] !== undefined;
            // Bei einem Modul ist in der Standardsprache der Text aus dem JAR die
            // Vorlage - die Standardsprache selbst ist ja das, was man gerade aendert.
            const original = file.default ? bundled[key] : file.defaults[key];

            return (
              <li
                key={key}
                className="grid gap-x-6 gap-y-2 border-t border-line/70 px-4 py-3 first:border-t-0 lg:grid-cols-2"
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <code className="console break-all text-xs text-text">{key}</code>
                    {missing && !file.default && <Badge tone="warn">fehlt</Badge>}
                    {own && hasBundled && <Badge tone="brand">eigener Text</Badge>}
                    {module && !hasBundled && file.defaults[key] === undefined && (
                      <Badge>kennt das Modul nicht mehr</Badge>
                    )}
                  </div>

                  {/* Der Ausgangstext steht als Text daneben, nicht in einem Feld - er
                      ist hier nicht zu aendern, sondern die Vorlage. */}
                  {(!file.default || module) && (
                    <p className="console mt-1 text-xs leading-relaxed text-text-faint">
                      {original ??
                        (file.default
                          ? "(bringt das Modul nicht mit)"
                          : "(in der Standardsprache nicht vorhanden)")}
                    </p>
                  )}
                </div>

                <div className="flex items-start gap-2">
                  {/*
                    Kein Platzhalter mit dem Ausgangstext: Der steht schon daneben, und im
                    Feld saehe er aus wie ein bereits eingetragener Satz. Leer heisst leer.
                  */}
                  <TextInput
                    value={value}
                    onChange={(event) => setDraft({ ...draft, [key]: event.target.value })}
                    aria-label={key}
                    className={
                      "console text-xs " +
                      (value !== (file.entries[key] ?? "") ? "border-brand" : "")
                    }
                  />
                  {own && hasBundled && (
                    <Button
                      size="sm"
                      variant="ghost"
                      icon="refresh"
                      className="mt-1 shrink-0"
                      title="Wieder den Text des Moduls nehmen"
                      onClick={() => setDraft({ ...draft, [key]: bundled[key] })}
                    >
                      Zurücksetzen
                    </Button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>

        {shown.length === 0 && (
          <p className="py-10 text-center text-sm text-text-muted">Kein Treffer.</p>
        )}
      </Card>

      <p className="mt-4 max-w-4xl text-xs text-text-faint">
        Format ist MiniMessage (<code className="console">&lt;red&gt;Text&lt;/red&gt;</code>),
        Platzhalter sind benannt (<code className="console">&lt;spieler&gt;</code>) und
        müssen stehen bleiben. Ein Zeilenumbruch ist{" "}
        <code className="console">&lt;newline&gt;</code>.{" "}
        {module
          ? "Gespeichert wird nur, was vom Text des Moduls abweicht – alles andere folgt weiter seinen Updates."
          : "Beim Speichern wird die Datei flach neu geschrieben – eigene Kommentare darin gehen verloren."}
      </p>

      <ConfirmModal
        open={leaving}
        title={`${changed} ungespeicherte ${changed === 1 ? "Änderung" : "Änderungen"} verwerfen?`}
        description="Was du seit dem letzten Speichern eingetragen hast, ist danach weg."
        confirmLabel="Verwerfen"
        onConfirm={onBack}
        onClose={() => setLeaving(false)}
      />
    </div>
  );
}

function Toggle({
  checked,
  onChange,
  children,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  children: string;
}) {
  return (
    <label className="flex cursor-pointer items-center gap-2 text-xs text-text-muted">
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        className="size-4 accent-[var(--color-brand)]"
      />
      {children}
    </label>
  );
}

/** Eine neue Sprache anlegen - mit den Texten der Standardsprache als Vorlage. */
function CreateLocale({
  open,
  existing,
  onClose,
  onCreated,
}: {
  open: boolean;
  existing: string[];
  onClose: () => void;
  onCreated: () => Promise<void>;
}) {
  const toast = useToast();
  const [locale, setLocale] = useState("");
  const [busy, setBusy] = useState(false);

  const code = locale.trim();
  const invalid = code !== "" && !/^[a-z]{2,3}(_[A-Za-z]{2})?$/.test(code);
  const taken = existing.includes(code);

  async function create() {
    setBusy(true);
    try {
      const answer = await request<{ note: string }>("/api/v1/messages", {
        method: "POST",
        body: { locale: code },
      });
      toast.success(answer.note);
      setLocale("");
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
      title="Neue Sprache"
      description="Die neue Datei startet mit den Texten der Standardsprache - du ersetzt sie Zeile für Zeile. Die Texte der Module übersetzt du danach je Modul."
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Abbrechen
          </Button>
          <Button
            variant="primary"
            busy={busy}
            disabled={!code || invalid || taken}
            onClick={create}
          >
            Anlegen
          </Button>
        </>
      }
    >
      <Field
        label="Sprachkennung"
        hint="Wie der Client sie meldet: en, fr, pt_BR. Daraus wird messages/<kennung>.yml"
        error={
          invalid
            ? "Nur zwei oder drei Kleinbuchstaben, optional mit _XX (pt_BR)."
            : taken
              ? `Die Sprache ${code} gibt es schon.`
              : undefined
        }
      >
        {(props) => (
          <TextInput
            {...props}
            value={locale}
            onChange={(event) => setLocale(event.target.value)}
            placeholder="en"
          />
        )}
      </Field>
    </Modal>
  );
}
