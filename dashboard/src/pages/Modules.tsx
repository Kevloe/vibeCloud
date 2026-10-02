import { useEffect, useState } from "react";
import { ApiError, request, type Module } from "../api";
import { Button } from "../ui/Button";
import { TextArea } from "../ui/Form";
import { Icon } from "../ui/Icon";
import { Badge, Card, Empty, PageHeader } from "../ui/Layout";
import { Modal } from "../ui/Modal";
import { useToast } from "../ui/Toast";

/**
 * Module und ihre Konfiguration.
 *
 * Geladen und entladen wird nur in der Konsole - Code zur Laufzeit aus einem Browser zu
 * tauschen, waere der falsche Ort. Die {@code config.json} laesst sich hier aber
 * bearbeiten: Das ist die Datei, die man im Betrieb wirklich oft anfasst.
 */
export function Modules({ modules }: { modules: Module[] }) {
  const [editing, setEditing] = useState<Module | null>(null);

  return (
    <div>
      <PageHeader
        title="Module"
        subtitle="Laden und Entladen gehört in die Konsole - hier geht es um die Einstellungen."
      />

      {modules.length === 0 ? (
        <Card padded={false}>
          <Empty
            icon="module"
            title="Kein Modul geladen"
            hint={
              <>
                JARs gehören nach <code className="console">modules/</code>, geladen wird
                mit <code className="console">module load &lt;id&gt;</code>.
              </>
            }
          />
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {modules.map((module) => (
            <div
              key={module.id}
              className="vc-fade-in flex flex-col rounded-card border border-line bg-surface/70 p-4 backdrop-blur-sm"
            >
              <div className="flex items-start justify-between gap-2">
                <span className="inline-flex items-center gap-2">
                  <span className="rounded-md bg-brand/10 p-1.5 text-brand">
                    <Icon name="module" className="size-4" />
                  </span>
                  <span className="font-medium text-text">{module.name}</span>
                </span>
                {module.enabled ? (
                  <Badge tone="ok">aktiv</Badge>
                ) : (
                  <Badge>geladen</Badge>
                )}
              </div>
              <p className="mt-2 text-sm text-text-muted">
                {module.id} <span className="text-text-faint">{module.version}</span>
              </p>
              <Button
                size="sm"
                icon="edit"
                className="mt-4 self-start"
                onClick={() => setEditing(module)}
              >
                config.json
              </Button>
            </div>
          ))}
        </div>
      )}

      {editing && <EditConfig module={editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

/**
 * Die {@code config.json} eines Moduls im Dialog.
 *
 * Das JSON wird schon hier geprueft: So steht der Fehler am Text und nicht erst in der
 * Antwort des Masters, nachdem man gespeichert hat.
 */
function EditConfig({ module, onClose }: { module: Module; onClose: () => void }) {
  const toast = useToast();
  const [content, setContent] = useState("");
  const [original, setOriginal] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    request<{ content: string; exists: boolean }>(`/api/v1/modules/${module.id}/config`)
      .then((answer) => {
        setContent(answer.content);
        setOriginal(answer.content);
        setLoaded(true);
        if (!answer.exists) {
          toast.info(
            "Es gibt noch keine Datei - das Modul legt sie an, wenn es seine " +
              "Einstellungen zum ersten Mal liest.",
          );
        }
      })
      .catch((exception) =>
        toast.error(
          exception instanceof ApiError ? exception.message : "Laden fehlgeschlagen",
        ),
      );
  }, [module.id]);

  function jsonError(): string | null {
    if (!content.trim()) {
      return null;
    }
    try {
      JSON.parse(content);
      return null;
    } catch (exception) {
      return exception instanceof Error ? exception.message : "Kein gültiges JSON";
    }
  }

  async function save() {
    setBusy(true);
    try {
      const answer = await request<{ note: string }>(
        `/api/v1/modules/${module.id}/config`,
        { method: "PUT", body: { content } },
      );
      setOriginal(content);
      toast.success(answer.note);
    } catch (exception) {
      toast.error(
        exception instanceof ApiError ? exception.message : "Speichern fehlgeschlagen",
      );
    } finally {
      setBusy(false);
    }
  }

  const broken = jsonError();
  const changed = content !== original;

  return (
    <Modal
      open
      wide
      title={`${module.name} · config.json`}
      description="Gespeichert wird sofort, wirksam mit module reload in der Konsole - ein Modul liest seine Einstellungen beim Aktivieren."
      onClose={onClose}
      footer={
        <>
          {broken && (
            <span className="mr-auto flex items-center gap-2 text-xs text-warn">
              <Icon name="alert" className="size-4 shrink-0" />
              Kein gültiges JSON: {broken}
            </span>
          )}
          <Button disabled={!changed} onClick={() => setContent(original)}>
            Verwerfen
          </Button>
          <Button
            variant="primary"
            busy={busy}
            disabled={!changed || broken !== null}
            onClick={save}
          >
            Speichern
          </Button>
        </>
      }
    >
      <TextArea
        value={content}
        onChange={(event) => setContent(event.target.value)}
        rows={18}
        aria-label={`Konfiguration von ${module.name}`}
        aria-invalid={broken !== null ? true : undefined}
        placeholder={loaded ? "{}" : "Lade ..."}
        className="text-xs"
      />
    </Modal>
  );
}
