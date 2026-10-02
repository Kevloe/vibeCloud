import { useEffect, useState } from "react";
import { ApiError, request, type CloudConfig } from "../api";
import { Button } from "../ui/Button";
import { changedFields, FieldGrid } from "../ui/FieldsForm";
import { Badge, Card, PageHeader } from "../ui/Layout";
import { useToast } from "../ui/Toast";

/**
 * Die allgemeinen Einstellungen der Cloud - die aenderbaren Felder der config.json.
 *
 * Der Wartungsmodus stand frueher auch hier. Er sitzt jetzt unten in der Seitenleiste:
 * Er gilt sofort und fuer alle, und so etwas soll von jeder Seite aus zu sehen und zu
 * schalten sein. Was hier bleibt, wirkt erst nach einem Neustart des Masters.
 */
export function Settings() {
  return (
    <div>
      <PageHeader
        title="Einstellungen"
        subtitle="Was für die ganze Cloud gilt - Gruppen, Ränge und Module haben ihre eigenen Seiten, der Wartungsmodus sitzt unten in der Seitenleiste."
      />
      <ConfigCard />
    </div>
  );
}

/** Der Text, mit dem ein Kasten erklaert, warum er leer ist. */
function reason(exception: unknown): string {
  return exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen";
}

/**
 * Die aenderbaren Felder der config.json.
 *
 * Gespeichert wird in einem Aufruf und nicht je Feld wie bei Gruppen und Raengen: Die
 * Felder haengen voneinander ab. Wer den Portbereich verschiebt, haette zwischen zwei
 * Aufrufen einen Bereich, der hinter seinem eigenen Ende anfaengt - und der Master lehnte
 * schon den ersten ab.
 */
function ConfigCard() {
  const toast = useToast();
  const [config, setConfig] = useState<CloudConfig | null>(null);
  const [denied, setDenied] = useState<string | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      const loaded = await request<CloudConfig>("/api/v1/settings/config");
      setConfig(loaded);
      setDraft(Object.fromEntries(loaded.fields.map((field) => [field.name, field.value])));
      setDenied(null);
    } catch (exception) {
      setDenied(reason(exception));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  if (!config) {
    return (
      <Card title="config.json">
        <p className="text-sm text-text-muted">{denied ?? "Lade ..."}</p>
      </Card>
    );
  }

  const current = Object.fromEntries(
    config.fields.map((field) => [field.name, field.value]),
  );
  const changed = changedFields(draft, current);

  // Was gespeichert ist, aber noch nicht gilt - sonst saehe die Seite nach dem Speichern
  // aus, als waere alles schon wirksam.
  const pending = config.fields.filter((field) => field.value !== field.running);
  const notes = Object.fromEntries(
    pending.map((field) => [field.name, `Läuft noch mit ${field.running} - bis zum Neustart.`]),
  );

  async function save() {
    setBusy(true);
    try {
      const answer = await request<{ note: string }>("/api/v1/settings/config", {
        method: "PATCH",
        body: { values: Object.fromEntries(changed) },
      });
      toast.success(answer.note);
      await load();
    } catch (exception) {
      // Der Master nennt das Feld und den Grund - gespeichert ist dann nichts, auch
      // nicht die Felder, die fuer sich gestimmt haetten.
      toast.error(
        exception instanceof ApiError ? exception.message : "Speichern fehlgeschlagen",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card
      title="config.json"
      action={
        pending.length > 0 && (
          <Badge tone="warn">
            {pending.length} {pending.length === 1 ? "Änderung wartet" : "Änderungen warten"}{" "}
            auf den Neustart
          </Badge>
        )
      }
    >
      <p className="mb-4 max-w-3xl text-sm text-text-muted">
        Der Master liest diese Datei beim Start. Gespeichert wird sofort,{" "}
        <span className="text-text">wirksam wird es nach einem Neustart des Masters</span>{" "}
        - die Gameserver laufen dabei weiter.
      </p>

      <FieldGrid
        fields={config.fields.map((field) => ({ name: field.name, values: [] }))}
        draft={draft}
        current={current}
        notes={notes}
        onChange={(name, value) => setDraft({ ...draft, [name]: value })}
      />

      <div className="mt-5 flex flex-wrap items-center justify-end gap-2 border-t border-line pt-4">
        <span className="mr-auto text-xs text-text-faint">
          {changed.length === 0
            ? "Nichts geändert."
            : `${changed.length} geändert: ${changed.map(([name]) => name).join(", ")}`}
        </span>
        <Button disabled={changed.length === 0} onClick={() => setDraft(current)}>
          Verwerfen
        </Button>
        <Button
          variant="primary"
          busy={busy}
          disabled={changed.length === 0}
          onClick={save}
        >
          Speichern
        </Button>
      </div>

      {/*
        Was fehlt, steht dabei - sonst sucht man hier den Port und findet ihn nicht. Die
        Datenbank steht absichtlich gar nicht da.
      */}
      <div className="mt-6">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-text-faint">
          Nur in der Datei
        </h3>
        <p className="mt-1 max-w-3xl text-xs text-text-faint">
          Diese Werte entscheiden, ob Master und Dashboard überhaupt erreichbar sind. Ein
          Tippfehler ließe sich von hier aus nicht mehr zurücknehmen - deshalb bleiben sie,
          wie die Zugangsdaten der Datenbank, in der config.json auf dem Server.
        </p>
        <dl className="mt-2 grid gap-x-8 sm:grid-cols-2">
          {/* Nicht Detail: Das schreibt die Beschriftung gross, und ein Feldname ist
              genau so zu lesen, wie er in der Datei steht. */}
          {Object.entries(config.locked).map(([name, value]) => (
            <div
              key={name}
              className="flex items-baseline justify-between gap-4 border-b border-line/60 py-1.5"
            >
              <dt className="console text-xs text-text-muted">{name}</dt>
              <dd className="console text-xs text-text">{value}</dd>
            </div>
          ))}
        </dl>
      </div>
    </Card>
  );
}
