import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { ApiError, request, type Maintenance } from "./api";
import { ConfirmModal } from "./ui/Modal";
import { useToast } from "./ui/Toast";

/** Wer die Wartung einer Gruppe aendert, sagt es der Seitenleiste hiermit. */
export const MAINTENANCE_CHANGED = "vibecloud:maintenance";

/**
 * Der Wartungsmodus des ganzen Netzwerks - unten in der Seitenleiste.
 *
 * Dort und nicht auf einer Seite, weil er von ueberall erreichbar sein muss und man von
 * ueberall sehen soll, dass er an ist: Ein Netzwerk, in das niemand hereinkommt, ist kein
 * Zustand, den man erst auf der Einstellungsseite entdecken will.
 *
 * Zu sehen ist der Block nur fuer den, der die Wartung ueberhaupt ansehen darf
 * ({@code maintenance list}). Der Schalter selbst haengt an der Richtung: Wer nur
 * aufheben darf, kann nicht einschalten - wie in der Konsole.
 *
 * Einschalten fragt nach, Aufheben nicht: Das eine sperrt alle neuen Logins aus, das
 * andere laesst sie wieder herein.
 */
export function MaintenanceToggle() {
  const toast = useToast();
  const [state, setState] = useState<Maintenance | null>(null);
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      setState(await request<Maintenance>("/api/v1/settings/maintenance"));
    } catch {
      // Kein Recht oder keine Verbindung - dann gibt es hier nichts zu zeigen. Eine
      // Fehlermeldung waere fuer jeden ohne dieses Recht bei jedem Laden dieselbe.
      setState(null);
    }
  }

  useEffect(() => {
    void load();
    // Die Wartung laesst sich auch in der Konsole und von anderen umschalten. Eine halbe
    // Minute Verzug ist fuer eine Anzeige genug; geschaltet wird ohnehin beim Master.
    const timer = setInterval(() => void load(), 30_000);
    const changed = () => void load();
    window.addEventListener(MAINTENANCE_CHANGED, changed);
    return () => {
      clearInterval(timer);
      window.removeEventListener(MAINTENANCE_CHANGED, changed);
    };
  }, []);

  if (!state) {
    return null;
  }

  async function set(active: boolean) {
    setBusy(true);
    try {
      const answer = await request<{ note: string; plugins: number }>(
        "/api/v1/settings/maintenance",
        { method: "PUT", body: { active } },
      );
      toast.success(`${answer.note} An ${answer.plugins} Server gemeldet.`);
      setAsking(false);
      await load();
    } catch (exception) {
      toast.error(exception instanceof ApiError ? exception.message : "Fehlgeschlagen");
    } finally {
      setBusy(false);
    }
  }

  const allowed = state.active ? state.mayDisable : state.mayEnable;

  return (
    <div className="border-t border-line px-4 py-3">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <p
            className={
              "text-sm font-medium " + (state.active ? "text-warn" : "text-text")
            }
          >
            Wartungsmodus
          </p>
          {/* Der Zustand als Wort - die Farbe des Schalters allein waere zu wenig. */}
          <p className="text-xs text-text-faint">
            {state.active ? "aktiv - Logins gesperrt" : "aus"}
          </p>
        </div>

        <button
          role="switch"
          aria-checked={state.active}
          aria-label="Wartungsmodus für das ganze Netzwerk"
          disabled={busy || !allowed}
          title={
            allowed
              ? undefined
              : state.active
                ? "Dir fehlt das Recht vibecloud.command.maintenance.off"
                : "Dir fehlt das Recht vibecloud.command.maintenance.on"
          }
          onClick={() => (state.active ? set(false) : setAsking(true))}
          className={
            "relative h-6 w-11 shrink-0 rounded-full border transition-colors disabled:opacity-40 " +
            (state.active ? "border-warn bg-warn/80" : "border-line bg-surface-2")
          }
        >
          <span
            className={
              "absolute top-0.5 size-[18px] rounded-full transition-all " +
              (state.active ? "left-[22px] bg-bg" : "left-0.5 bg-text-muted")
            }
          />
        </button>
      </div>

      {state.groups.length > 0 && (
        <p className="mt-1.5 truncate text-xs text-text-faint" title={state.groups.join(", ")}>
          Gruppen in Wartung: {state.groups.join(", ")}
        </p>
      )}

      {/*
        Ueber ein Portal: Die Seitenleiste wird mit transform verschoben, und darin waere
        ein "fixed"-Dialog an ihr festgemacht statt am Fenster.
      */}
      {createPortal(
        <ConfirmModal
          open={asking}
          title="Wartungsmodus für das ganze Netzwerk einschalten?"
          description={
            "Ab sofort werden neue Logins abgelehnt, und die Serverliste zeigt den " +
            "Wartungstext. Spieler, die schon online sind, bleiben es. Wer " +
            "vibecloud.maintenance.bypass hat, kommt weiter herein."
          }
          confirmLabel="Einschalten"
          busy={busy}
          onConfirm={() => set(true)}
          onClose={() => setAsking(false)}
        />,
        document.body,
      )}
    </div>
  );
}
