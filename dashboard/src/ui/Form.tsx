import { useId, type ReactNode } from "react";

/**
 * Ein beschriftetes Eingabefeld.
 *
 * Die Beschriftung steht ueber dem Feld und bleibt dort - ein Platzhalter als
 * Beschriftung verschwindet beim ersten Zeichen, und dann weiss niemand mehr, was in
 * dem Feld stehen soll.
 */
export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: (props: {
    id: string;
    "aria-describedby"?: string;
    "aria-invalid"?: true;
  }) => ReactNode;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;

  const describedBy = [hint ? hintId : null, error ? errorId : null]
    .filter(Boolean)
    .join(" ");

  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-xs font-medium text-text-muted">
        {label}
      </label>

      {children({
        id,
        "aria-describedby": describedBy || undefined,
        "aria-invalid": error ? true : undefined,
      })}

      {/* Der Fehler steht am Feld, nicht nur oben in einer Sammelmeldung. */}
      {error && (
        <p id={errorId} className="mt-1 text-xs text-bad">
          {error}
        </p>
      )}
      {hint && !error && (
        <p id={hintId} className="mt-1 text-xs text-text-faint">
          {hint}
        </p>
      )}
    </div>
  );
}

const CONTROL =
  "w-full rounded-md border border-line bg-bg px-3 py-2 text-sm text-text transition-colors " +
  "placeholder:text-text-faint hover:border-line-strong focus:border-brand focus:outline-none " +
  "aria-invalid:border-bad";

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={CONTROL + " " + (props.className ?? "")} />;
}

export function Select(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={CONTROL + " " + (props.className ?? "")} />;
}

export function TextArea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      spellCheck={false}
      {...props}
      className={CONTROL + " console leading-relaxed " + (props.className ?? "")}
    />
  );
}
