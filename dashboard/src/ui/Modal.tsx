import { useEffect, useId, useRef, type ReactNode } from "react";
import { Icon } from "./Icon";
import { Button } from "./Button";

/**
 * Ein Dialog ueber der Seite.
 *
 * Alles, was der Benutzer ausfuellen oder bestaetigen muss, laeuft hierueber - die Seite
 * dahinter bleibt sichtbar, damit der Zusammenhang nicht verloren geht.
 *
 * Drei Dinge, ohne die ein Modal mit der Tastatur unbenutzbar waere: Der Fokus springt
 * beim Oeffnen hinein, bleibt mit Tab darin gefangen, und kehrt beim Schliessen dorthin
 * zurueck, wo er herkam.
 */
export function Modal({
  open,
  title,
  description,
  onClose,
  children,
  footer,
  wide,
}: {
  open: boolean;
  title: string;
  description?: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const returnTo = useRef<HTMLElement | null>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    if (!open) {
      return;
    }
    returnTo.current = document.activeElement as HTMLElement | null;

    // Auf das erste Eingabefeld, sonst auf den Dialog selbst - sonst stuende der Fokus
    // noch hinter dem Dialog auf der Seite.
    const focusable = panel.current?.querySelector<HTMLElement>(
      "input, select, textarea, button:not([data-close])",
    );
    (focusable ?? panel.current)?.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== "Tab" || !panel.current) {
        return;
      }
      // Tab-Falle: Vom letzten Element wieder auf das erste und umgekehrt.
      const elements = Array.from(
        panel.current.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((element) => element.offsetParent !== null);

      if (elements.length === 0) {
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];

      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("keydown", onKeyDown, true);
      returnTo.current?.focus();
    };
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-bg/80 p-4 backdrop-blur-sm sm:items-center"
      onMouseDown={(event) => {
        // Nur der Klick auf den Hintergrund schliesst - ein Zug aus dem Dialog heraus
        // (etwa beim Markieren von Text) darf ihn nicht wegwerfen.
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        className={
          "vc-slide-in my-auto w-full rounded-card border border-line bg-surface shadow-2xl outline-none " +
          (wide ? "max-w-3xl" : "max-w-lg")
        }
      >
        <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div>
            <h2 id={titleId} className="text-base font-semibold text-text">
              {title}
            </h2>
            {description && (
              <p id={descriptionId} className="mt-1 text-sm text-text-muted">
                {description}
              </p>
            )}
          </div>
          <button
            data-close
            onClick={onClose}
            aria-label="Dialog schliessen"
            className="rounded p-1 text-text-faint transition-colors hover:bg-surface-2 hover:text-text"
          >
            <Icon name="close" className="size-5" />
          </button>
        </div>

        <div className="px-5 py-4">{children}</div>

        {footer && (
          <div className="flex items-center justify-end gap-2 border-t border-line px-5 py-4">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Rueckfrage vor etwas, das sich nicht ohne Weiteres zuruecknehmen laesst.
 *
 * Mit Namen in der Frage: "Gruppe lobby loeschen?" ist eine Entscheidung, "Sind Sie
 * sicher?" ist eine Formalie, die man wegklickt. Der Satz darunter sagt, was wirklich
 * passiert - deshalb steht er als Text hier und nicht als feste Floskel.
 */
export function ConfirmModal({
  open,
  title,
  description,
  confirmLabel,
  onConfirm,
  onClose,
  busy,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  onConfirm: () => void;
  onClose: () => void;
  busy?: boolean;
}) {
  return (
    <Modal
      open={open}
      title={title}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Abbrechen
          </Button>
          <Button variant="danger" onClick={onConfirm} busy={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <p className="text-sm text-text-muted">{description}</p>
    </Modal>
  );
}
