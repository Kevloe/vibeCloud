import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Icon, type IconName } from "./Icon";

type Variant = "primary" | "secondary" | "ghost" | "danger";
type Size = "sm" | "md";

const VARIANTS: Record<Variant, string> = {
  primary: "bg-brand text-bg hover:bg-brand/90 border border-transparent font-medium",
  secondary: "bg-surface-2 text-text hover:bg-line border border-line",
  ghost: "text-text-muted hover:bg-surface-2 hover:text-text border border-transparent",
  danger: "bg-bad/15 text-bad hover:bg-bad/25 border border-bad/40",
};

const SIZES: Record<Size, string> = {
  // 32px hoch - in einer Tabellenzeile ist mehr im Weg. Allein stehende Knoepfe sind md.
  sm: "h-8 px-2.5 text-xs gap-1.5",
  md: "h-10 px-4 text-sm gap-2",
};

export function Button({
  variant = "secondary",
  size = "md",
  icon,
  busy,
  children,
  className = "",
  ...rest
}: {
  variant?: Variant;
  size?: Size;
  icon?: IconName;
  /** Zeigt, dass gerade etwas laeuft, und sperrt den Knopf gegen Doppelklicks. */
  busy?: boolean;
  children?: ReactNode;
} & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...rest}
      disabled={rest.disabled || busy}
      aria-busy={busy || undefined}
      className={
        "inline-flex items-center justify-center rounded-md transition-colors duration-150 disabled:opacity-40 " +
        VARIANTS[variant] +
        " " +
        SIZES[size] +
        " " +
        className
      }
    >
      {busy ? (
        <Spinner />
      ) : (
        icon && <Icon name={icon} className={size === "sm" ? "size-3.5" : "size-4"} />
      )}
      {children}
    </button>
  );
}

/** Kreis, der sich dreht - ohne Bewegung bleibt er einfach stehen. */
function Spinner() {
  return (
    <svg viewBox="0 0 24 24" className="size-4 animate-spin" aria-hidden="true">
      <circle
        cx="12"
        cy="12"
        r="9"
        stroke="currentColor"
        strokeWidth="2.5"
        fill="none"
        opacity="0.25"
      />
      <path
        d="M21 12a9 9 0 0 0-9-9"
        stroke="currentColor"
        strokeWidth="2.5"
        fill="none"
        strokeLinecap="round"
      />
    </svg>
  );
}
