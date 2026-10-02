import { useState } from "react";
import { ApiError, login, type Session } from "../api";
import { Button } from "../ui/Button";
import { Field, TextInput } from "../ui/Form";
import { Icon } from "../ui/Icon";
import { useToast } from "../ui/Toast";

/**
 * Anmeldung.
 *
 * Es gibt bewusst keine Registrierung und kein "Passwort vergessen": Ein Zugang entsteht
 * nur in-game ueber `/acp create`, und ein neues Start-Passwort gibt `/acp changepw`.
 *
 * Der Fehler steht am Formular, nicht in einem Toast - er muss stehen bleiben, bis man
 * es noch einmal versucht hat.
 */
export function Login({ onLogin }: { onLogin: (session: Session) => void }) {
  const toast = useToast();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const session = await login(username, password);
      onLogin(session);
      if (!session.mustChangePassword) {
        toast.success(`Willkommen, ${session.name}.`);
      }
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
      {/* Ein Schimmer der Markenfarben hinter der Karte - sonst ist die Seite leer. */}
      <div className="relative w-full max-w-sm">
        <div
          aria-hidden="true"
          className="absolute -inset-8 rounded-full bg-brand/10 blur-3xl"
        />

        <form
          onSubmit={submit}
          className="vc-slide-in relative rounded-card border border-line bg-surface/80 p-8 backdrop-blur"
        >
          <h1 className="bg-gradient-to-r from-brand to-brand-2 bg-clip-text text-center text-2xl font-semibold text-transparent">
            vibeCloud
          </h1>
          <p className="mb-6 mt-2 text-center text-sm text-text-muted">
            Zugang gibt es nur in-game: <code className="console">/acp create</code>
          </p>

          <div className="space-y-4">
            <Field label="Minecraft-Name">
              {(props) => (
                <TextInput
                  {...props}
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoFocus
                  autoComplete="username"
                />
              )}
            </Field>

            <Field label="Passwort">
              {(props) => (
                <TextInput
                  {...props}
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="current-password"
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
            disabled={!username || !password}
            className="mt-6 w-full"
          >
            Anmelden
          </Button>
        </form>
      </div>
    </div>
  );
}
