import type { ReactNode } from "react";
import { Icon, type IconName } from "./Icon";

/**
 * Die Bausteine, aus denen jede Seite besteht.
 *
 * Es gibt keinen Kopfbereich im Dashboard - die Seitenleiste sagt, wo man ist. Den Titel
 * und die Knoepfe dazu bringt jede Seite selbst mit, ueber {@link PageHeader}.
 */
export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-xl font-semibold text-text">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/**
 * Ein Kasten mit Rahmen.
 *
 * Leicht durchscheinend ueber dem Hintergrund (Glassmorphism aus dem Entwurf), aber mit
 * klarer Linie - ohne sie verschwimmen in einer dunklen Oberflaeche alle Kanten.
 */
export function Card({
  title,
  action,
  children,
  className = "",
  padded = true,
  fill,
}: {
  title?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
  padded?: boolean;
  /**
   * Der Inhalt fuellt die Karte und scrollt in sich selbst - fuer die Serverkonsole.
   * Ohne das waere der Kasten so hoch wie sein Inhalt, und die ganze Seite wuerde
   * scrollen statt nur das Log.
   */
  fill?: boolean;
}) {
  return (
    <section
      className={
        "rounded-card border border-line bg-surface/70 backdrop-blur-sm " + className
      }
    >
      {title && (
        <div className="flex items-center justify-between gap-4 border-b border-line px-4 py-3">
          <h2 className="text-sm font-semibold tracking-wide text-text">{title}</h2>
          {action}
        </div>
      )}
      <div
        className={
          (padded ? "p-4 " : "") + (fill ? "flex min-h-0 flex-1 flex-col" : "")
        }
      >
        {children}
      </div>
    </section>
  );
}

/** Eine Kennzahl der Uebersicht. */
export function Stat({
  label,
  value,
  note,
  icon,
  tone = "neutral",
}: {
  label: string;
  value: string | number;
  note?: string;
  icon: IconName;
  tone?: "neutral" | "ok" | "warn" | "bad";
}) {
  const tones = {
    neutral: "text-brand bg-brand/10",
    ok: "text-ok bg-ok/10",
    warn: "text-warn bg-warn/10",
    bad: "text-bad bg-bad/10",
  };

  return (
    <div className="vc-fade-in rounded-card border border-line bg-surface/70 p-4 backdrop-blur-sm">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-text-faint">
            {label}
          </p>
          <p className="mt-1.5 text-2xl font-semibold tabular-nums text-text">{value}</p>
        </div>
        <span className={"rounded-md p-2 " + tones[tone]}>
          <Icon name={icon} className="size-5" />
        </span>
      </div>
      {note && <p className="mt-2 text-xs text-text-faint">{note}</p>}
    </div>
  );
}

/**
 * Ein Zustand als Punkt plus Wort.
 *
 * Nie nur die Farbe: Wer Rot und Gruen nicht unterscheiden kann, saehe sonst zwei
 * gleiche Punkte. Das Wort steht immer daneben.
 */
const STATES: Record<string, { dot: string; text: string }> = {
  RUNNING: { dot: "bg-ok", text: "text-ok" },
  STARTING: { dot: "bg-brand", text: "text-brand" },
  PREPARING: { dot: "bg-brand", text: "text-brand" },
  STOPPING: { dot: "bg-warn", text: "text-warn" },
  STOPPED: { dot: "bg-text-faint", text: "text-text-muted" },
  CRASHED: { dot: "bg-bad", text: "text-bad" },
  WAITING_FOR_NODE: { dot: "bg-warn", text: "text-warn" },
};

export function StateDot({ state }: { state: string }) {
  const colors = STATES[state] ?? { dot: "bg-text-faint", text: "text-text-muted" };
  return (
    <span className={"inline-flex items-center gap-2 text-xs font-medium " + colors.text}>
      <span className={"size-2 shrink-0 rounded-full " + colors.dot} />
      {state}
    </span>
  );
}

/** Eine kleine Marke neben einem Namen: "statisch", "Standard", "online". */
export function Badge({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: "neutral" | "ok" | "warn" | "bad" | "brand";
}) {
  const tones = {
    neutral: "border-line text-text-muted",
    ok: "border-ok/40 text-ok",
    warn: "border-warn/40 text-warn",
    bad: "border-bad/40 text-bad",
    brand: "border-brand/40 text-brand",
  };
  return (
    <span
      className={
        "inline-flex items-center rounded border px-1.5 py-0.5 text-[11px] leading-none whitespace-nowrap " +
        tones[tone]
      }
    >
      {children}
    </span>
  );
}

/**
 * Eine Tabelle mit festen Spaltenueberschriften.
 *
 * Die Kopfzeile bleibt beim Scrollen oben stehen - bei zwanzig Servern weiss man sonst
 * in der unteren Haelfte nicht mehr, welche Spalte welche ist.
 */
export function Table({
  columns,
  children,
  narrow,
}: {
  columns: ReactNode[];
  children: ReactNode;
  /** Fuer Tabellen in einer schmalen Spalte: keine Mindestbreite, also kein Schieben. */
  narrow?: boolean;
}) {
  return (
    <div className="overflow-x-auto">
      <table
        className={
          "w-full text-left text-sm " + (narrow ? "min-w-0" : "min-w-[40rem]")
        }
      >
        <thead className="sticky top-0 z-10 bg-surface/95 backdrop-blur">
          <tr className="text-xs font-medium uppercase tracking-wide text-text-faint">
            {columns.map((column, index) => (
              <th
                key={index}
                className={
                  "px-4 py-2.5 " +
                  (index === columns.length - 1 && column === "" ? "text-right" : "")
                }
              >
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

/** Eine Zeile - der Rahmen oben trennt, damit keine Zebrastreifen noetig sind. */
export function Row({
  children,
  highlighted,
}: {
  children: ReactNode;
  highlighted?: boolean;
}) {
  return (
    <tr
      className={
        "border-t border-line/70 transition-colors hover:bg-surface-2/60 " +
        (highlighted ? "bg-surface-2/60" : "")
      }
    >
      {children}
    </tr>
  );
}

export function Cell({
  children,
  className = "",
  align,
}: {
  children?: ReactNode;
  className?: string;
  align?: "right";
}) {
  return (
    <td
      className={
        "px-4 py-2.5 text-text-muted " +
        (align === "right" ? "text-right whitespace-nowrap " : "") +
        className
      }
    >
      {children}
    </td>
  );
}

/**
 * Eine leere Liste erklaert sich selbst.
 *
 * "Keine Daten" sagt nichts; hier steht, warum nichts da ist und was als Naechstes
 * passieren muss.
 */
export function Empty({
  icon,
  title,
  hint,
  action,
}: {
  icon: IconName;
  title: string;
  hint?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 py-12 text-center">
      <span className="rounded-full bg-surface-2 p-3 text-text-faint">
        <Icon name={icon} className="size-6" />
      </span>
      <p className="text-sm font-medium text-text">{title}</p>
      {hint && <p className="max-w-md text-sm text-text-muted">{hint}</p>}
      {action}
    </div>
  );
}

/** Ein Wertepaar in einer Detailansicht. */
export function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-line/60 py-1.5">
      <dt className="text-xs uppercase tracking-wide text-text-faint">{label}</dt>
      <dd className="truncate text-right text-sm text-text">{value}</dd>
    </div>
  );
}
