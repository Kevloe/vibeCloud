import { useEffect, useState } from "react";
import {
  ApiError,
  request,
  type LocaleFile,
  type LocaleSummary,
} from "../api";
import { Button } from "../ui/Button";
import { Field, TextInput } from "../ui/Form";
import { Icon } from "../ui/Icon";
import { Badge, Card, Cell, Empty, PageHeader, Row, Table } from "../ui/Layout";
import { ConfirmModal, Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/**
 * Sprachen anlegen und uebersetzen (PLAN.md Abschnitt 11a).
 *
 * Gearbeitet wird auf den Dateien in {@code messages/}. Nach jedem Speichern liest der
 * Master neu ein und verteilt an alle Plugins - eine Textkorrektur wirkt sofort, ohne
 * dass ein Server neu startet.
 */
export function Translations() {
  const toast = useToast();
  const [locales, setLocales] = useState<LocaleSummary[]>([]);
  const [busy, setBusy] = useState(false);

  const [editing, setEditing] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<LocaleSummary | null>(null);

  async function load() {
    try {
      const answer = await request<{ default: string; locales: LocaleSummary[] }>(
        "/api/v1/messages",
      );
      setLocales(answer.locales);
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

      <Card padded={false}>
        {locales.length === 0 ? (
          <Empty
            icon="language"
            title="Noch keine Sprachdatei"
            hint="Die Standardsprache legt der Master beim ersten Start selbst an."
          />
        ) : (
          <Table columns={["Sprache", "Texte", "Fehlend", ""]}>
            {locales.map((locale) => (
              <Row key={locale.locale} highlighted={locale.locale === editing}>
                <Cell>
                  <span className="inline-flex items-center gap-2 font-medium text-text">
                    {locale.locale}
                    {locale.default && <Badge tone="brand">Standard</Badge>}
                  </span>
                </Cell>
                <Cell className="tabular-nums">{locale.keys}</Cell>
                <Cell>
                  {locale.missing === 0 ? (
                    <Badge tone="ok">vollständig</Badge>
                  ) : (
                    <Badge tone="warn">
                      {locale.missing} fehlen – es greift Deutsch
                    </Badge>
                  )}
                </Cell>
                <Cell align="right">
                  <Button
                    size="sm"
                    icon="edit"
                    onClick={() => setEditing(locale.locale)}
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

      {editing && (
        <EditLocale
          locale={editing}
          onClose={() => setEditing(null)}
          onSaved={load}
        />
      )}

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
          "Standardsprache - ihre Einstellung bleibt, es gibt nur keine Texte mehr dazu."
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
 * Der Uebersetzungs-Editor.
 *
 * Links der Schluessel und der deutsche Text, rechts das Feld - ohne den Ausgangstext
 * uebersetzt niemand etwas. Leer gelassene Felder werden <b>nicht</b> gespeichert: Dann
 * greift die Standardsprache, und im Spiel steht ein deutscher Satz statt einer leeren
 * Zeile.
 */
function EditLocale({
  locale,
  onClose,
  onSaved,
}: {
  locale: string;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const toast = useToast();
  const [file, setFile] = useState<LocaleFile | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [filter, setFilter] = useState("");
  const [onlyMissing, setOnlyMissing] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    request<LocaleFile>(`/api/v1/messages/${locale}`)
      .then((loaded) => {
        setFile(loaded);
        setDraft(loaded.entries);
      })
      .catch((exception) =>
        toast.error(
          exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
        ),
      );
  }, [locale]);

  if (!file) {
    return (
      <Modal open title={`${locale} übersetzen`} onClose={onClose}>
        <p className="text-sm text-text-muted">Lade ...</p>
      </Modal>
    );
  }

  // Alle Schluessel der Standardsprache plus die, die es nur hier gibt - sonst fiele ein
  // eigener Schluessel beim naechsten Speichern still unter den Tisch.
  const keys = Array.from(
    new Set([...Object.keys(file.defaults), ...Object.keys(file.entries)]),
  ).sort();

  const shown = keys.filter((key) => {
    if (onlyMissing && (draft[key] ?? "").trim() !== "") {
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
      const answer = await request<{ note: string }>(`/api/v1/messages/${locale}`, {
        method: "PUT",
        body: { entries },
      });
      toast.success(answer.note);
      // Funktionale Form, weil TypeScript die Pruefung auf null hier nicht mehr sieht.
      setFile((previous) => (previous ? { ...previous, entries } : previous));
      await onSaved();
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Speichern fehlgeschlagen",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      wide
      title={`${locale} übersetzen`}
      description={
        file.default
          ? "Das ist die Standardsprache - sie ist die Vorlage für alle anderen und sollte vollständig bleiben."
          : "Leere Felder werden nicht gespeichert; dort greift die Standardsprache."
      }
      onClose={onClose}
      footer={
        <>
          <span className="mr-auto text-xs text-text-faint">
            {filled} von {keys.length} übersetzt
            {changed > 0 ? ` · ${changed} geändert` : ""}
          </span>
          <Button variant="ghost" onClick={onClose}>
            Schließen
          </Button>
          <Button variant="primary" busy={busy} disabled={changed === 0} onClick={save}>
            Speichern
          </Button>
        </>
      }
    >
      <div className="mb-3 flex flex-wrap items-center gap-3">
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
          <label className="flex cursor-pointer items-center gap-2 text-xs text-text-muted">
            <input
              type="checkbox"
              checked={onlyMissing}
              onChange={(event) => setOnlyMissing(event.target.checked)}
              className="size-4 accent-[var(--color-brand)]"
            />
            nur fehlende
          </label>
        )}
      </div>

      <div className="max-h-[55vh] space-y-3 overflow-y-auto pr-1">
        {shown.map((key) => {
          const value = draft[key] ?? "";
          const original = file.defaults[key] ?? "";
          const missing = value.trim() === "";

          return (
            <div key={key} className="rounded-md border border-line/70 p-3">
              <div className="flex items-baseline justify-between gap-3">
                <code className="console text-xs text-text">{key}</code>
                {missing && !file.default && <Badge tone="warn">fehlt</Badge>}
              </div>

              {/* Der Ausgangstext steht als Text daneben, nicht in einem Feld - er ist
                  hier nicht zu aendern, sondern die Vorlage. */}
              {!file.default && (
                <p className="console mt-1 text-xs leading-relaxed text-text-faint">
                  {original || "(in der Standardsprache nicht vorhanden)"}
                </p>
              )}

              {/*
                Kein Platzhalter mit dem deutschen Text: Der steht schon darueber, und im
                Feld saehe er aus wie ein bereits eingetragener Satz. Leer heisst leer.
              */}
              <TextInput
                value={value}
                onChange={(event) => setDraft({ ...draft, [key]: event.target.value })}
                aria-label={key}
                className={
                  "console mt-2 text-xs " +
                  (value !== (file.entries[key] ?? "") ? "border-brand" : "")
                }
              />
            </div>
          );
        })}

        {shown.length === 0 && (
          <p className="py-6 text-center text-sm text-text-muted">
            Kein Treffer.
          </p>
        )}
      </div>

      <p className="mt-3 text-xs text-text-faint">
        Format ist MiniMessage (<code className="console">&lt;red&gt;Text&lt;/red&gt;</code>),
        Platzhalter sind benannt (<code className="console">&lt;spieler&gt;</code>) und
        müssen stehen bleiben. Ein Zeilenumbruch ist{" "}
        <code className="console">&lt;newline&gt;</code>. Beim Speichern wird die Datei
        flach neu geschrieben – eigene Kommentare darin gehen verloren.
      </p>
    </Modal>
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
      description="Die neue Datei startet mit den Texten der Standardsprache - du ersetzt sie Zeile für Zeile."
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
