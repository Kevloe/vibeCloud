import { useEffect, useState, type ReactNode } from "react";
import { Icon, type IconName } from "./Icon";

export type NavEntry<T extends string> = {
  id: T;
  label: string;
  icon: IconName;
};

/**
 * Die einzige Navigation des Dashboards.
 *
 * Kein Kopfbereich: Alles steht links - oben die Seiten, unten der eigene Zugang. Damit
 * gibt es genau einen Ort, an dem man sich orientiert, und die ganze Breite bleibt fuer
 * Tabellen und Logs.
 *
 * Auf einem schmalen Bildschirm faehrt sie als Schublade ueber die Seite. Ein zweiter
 * Navigationsweg unten waere eine zweite Wahrheit - und sieben Punkte sind fuer eine
 * untere Leiste zu viele.
 */
export function Sidebar<T extends string>({
  entries,
  current,
  onNavigate,
  accountName,
  onLogout,
  footer,
}: {
  entries: Array<NavEntry<T>>;
  current: T;
  onNavigate: (id: T) => void;
  accountName: string;
  onLogout: () => void;
  /** Steht zwischen den Seiten und dem eigenen Zugang - etwa der Wartungsschalter. */
  footer?: ReactNode;
}) {
  const [open, setOpen] = useState(false);

  // Mit Escape wieder zu - wer die Schublade mit der Tastatur geoeffnet hat, soll sie
  // nicht suchen muessen.
  useEffect(() => {
    if (!open) {
      return;
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open]);

  return (
    <>
      {/* Der Schalter steht frei auf der Seite, damit es keine Kopfzeile braucht. */}
      <button
        onClick={() => setOpen(true)}
        aria-label="Navigation öffnen"
        aria-expanded={open}
        className="fixed left-3 top-3 z-30 rounded-md border border-line bg-surface/90 p-2 text-text-muted backdrop-blur transition-colors hover:text-text lg:hidden"
      >
        <Icon name="overview" className="size-5" />
      </button>

      {open && (
        <div
          className="fixed inset-0 z-30 bg-bg/70 backdrop-blur-sm lg:hidden"
          onMouseDown={() => setOpen(false)}
        />
      )}

      <nav
        aria-label="Hauptnavigation"
        className={
          "fixed inset-y-0 left-0 z-40 flex w-60 flex-col border-r border-line bg-surface transition-transform duration-200 lg:static lg:z-auto lg:translate-x-0 " +
          (open ? "translate-x-0" : "-translate-x-full")
        }
      >
        <div className="flex items-center justify-between px-5 py-5">
          <span className="bg-gradient-to-r from-brand to-brand-2 bg-clip-text text-lg font-semibold text-transparent">
            vibeCloud
          </span>
          <button
            onClick={() => setOpen(false)}
            aria-label="Navigation schließen"
            className="rounded p-1 text-text-faint hover:text-text lg:hidden"
          >
            <Icon name="close" className="size-5" />
          </button>
        </div>

        <ul className="flex-1 space-y-0.5 overflow-y-auto px-2">
          {entries.map((entry) => {
            const active = entry.id === current;
            return (
              <li key={entry.id}>
                <button
                  onClick={() => {
                    onNavigate(entry.id);
                    setOpen(false);
                  }}
                  // aria-current sagt der Sprachausgabe, wo man ist. Der Balken links
                  // sagt es dem Auge - Farbe allein waere zu wenig.
                  aria-current={active ? "page" : undefined}
                  className={
                    "flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors " +
                    (active
                      ? "bg-surface-2 font-medium text-text"
                      : "text-text-muted hover:bg-surface-2/60 hover:text-text")
                  }
                >
                  <span
                    className={
                      "-ml-3 h-5 w-0.5 rounded-full " +
                      (active ? "bg-brand" : "bg-transparent")
                    }
                  />
                  <Icon
                    name={entry.icon}
                    className={"size-[18px] " + (active ? "text-brand" : "")}
                  />
                  {entry.label}
                </button>
              </li>
            );
          })}
        </ul>

        {/* Was fuer das ganze Netzwerk gilt und von jeder Seite aus erreichbar sein muss. */}
        {footer}

        {/*
          Der eigene Zugang steht unten, weil man ihn selten braucht - aber immer an
          derselben Stelle findet.
        */}
        <div className="border-t border-line p-3">
          <div className="flex items-center gap-3 px-1 py-1">
            <span className="grid size-8 shrink-0 place-items-center rounded-full bg-brand/15 text-sm font-semibold text-brand">
              {accountName.slice(0, 1).toUpperCase()}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-text">{accountName}</p>
              <p className="text-xs text-text-faint">angemeldet</p>
            </div>
          </div>
          <button
            onClick={onLogout}
            className="mt-2 flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm text-text-muted transition-colors hover:bg-bad/10 hover:text-bad"
          >
            <Icon name="logout" className="size-[18px]" />
            Abmelden
          </button>
        </div>
      </nav>
    </>
  );
}
