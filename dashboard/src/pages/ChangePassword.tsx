import { useState } from "react";
import { ApiError } from "../api";
import { Button } from "../ui/Button";
import { Field, TextInput } from "../ui/Form";
import { Icon } from "../ui/Icon";

/**
 * Zwangswechsel des Start-Passworts.
 *
 * Solange es gilt, lehnt der Master jeden anderen Aufruf mit 403 ab - diese Seite ist
 * also nicht nur eine Empfehlung, sondern der einzige Weg weiter.
 */
export function ChangePassword({
  name,
  onDone,
}: {
  name: string;
  onDone: (password: string) => Promise<void>;
}) {
  const [password, setPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Erst meckern, wenn etwas drinsteht - eine Fehlermeldung an einem leeren Feld, das
  // man gerade erst gesehen hat, ist nur im Weg.
  const tooShort = password.length > 0 && password.length < 10;
  const mismatch = repeat.length > 0 && password !== repeat;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onDone(password);
    } catch (exception) {
      setError(
        exception instanceof ApiError ? exception.message : "Der Master antwortet nicht.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid h-full place-items-center p-4">
      <form
        onSubmit={submit}
        className="vc-slide-in w-full max-w-sm rounded-card border border-line bg-surface/80 p-8 backdrop-blur"
      >
        <span className="inline-flex rounded-md bg-warn/10 p-2 text-warn">
          <Icon name="key" className="size-5" />
        </span>
        <h1 className="mt-3 text-lg font-semibold text-text">Eigenes Passwort setzen</h1>
        <p className="mb-6 mt-1 text-sm text-text-muted">
          {name}, das Start-Passwort aus dem Chat gilt nur für diesen einen Schritt.
        </p>

        <div className="space-y-4">
          <Field
            label="Neues Passwort"
            hint="Mindestens 10 Zeichen."
            error={tooShort ? "Mindestens 10 Zeichen." : undefined}
          >
            {(props) => (
              <TextInput
                {...props}
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoFocus
                autoComplete="new-password"
              />
            )}
          </Field>

          <Field
            label="Wiederholen"
            error={mismatch ? "Die beiden stimmen nicht überein." : undefined}
          >
            {(props) => (
              <TextInput
                {...props}
                type="password"
                value={repeat}
                onChange={(event) => setRepeat(event.target.value)}
                autoComplete="new-password"
              />
            )}
          </Field>
        </div>

        {error && (
          <p
            role="alert"
            className="mt-4 flex items-start gap-2 rounded-md border border-bad/40 bg-bad/10 px-3 py-2 text-sm text-bad"
          >
            <Icon name="alert" className="mt-0.5 size-4 shrink-0" />
            {error}
          </p>
        )}

        <Button
          type="submit"
          variant="primary"
          busy={busy}
          disabled={password.length < 10 || password !== repeat}
          className="mt-6 w-full"
        >
          Passwort setzen
        </Button>

        <p className="mt-4 text-xs text-text-faint">
          Danach sind alle offenen Sitzungen beendet - auch die von jemandem, der das
          Start-Passwort mitgelesen hat.
        </p>
      </form>
    </div>
  );
}
