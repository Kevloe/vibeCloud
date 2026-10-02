import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Icon } from "./Icon";

/**
 * Kurze Rueckmeldungen am unteren Rand.
 *
 * Toasts sind fuer Dinge, die man zur Kenntnis nimmt und dann vergisst - "gespeichert",
 * "Node gesperrt". Alles, was der Benutzer lesen und entscheiden muss, gehoert in ein
 * Modal oder an das Formular selbst.
 */
type Kind = "success" | "error" | "info";

type Toast = {
  id: number;
  kind: Kind;
  text: string;
};

type Api = {
  success: (text: string) => void;
  error: (text: string) => void;
  info: (text: string) => void;
};

const ToastContext = createContext<Api | null>(null);

/** Fehler bleiben laenger stehen - sie sind seltener und wichtiger. */
const LIFETIME: Record<Kind, number> = {
  success: 4000,
  info: 4000,
  error: 7000,
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const remove = useCallback((id: number) => {
    setToasts((previous) => previous.filter((toast) => toast.id !== id));
  }, []);

  const push = useCallback((kind: Kind, text: string) => {
    const id = nextId.current++;
    setToasts((previous) => [...previous, { id, kind, text }]);
  }, []);

  const api = useMemo<Api>(
    () => ({
      success: (text) => push("success", text),
      error: (text) => push("error", text),
      info: (text) => push("info", text),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}

      {/*
        Zwei Bereiche statt einem: Erfolg und Hinweis werden hoeflich angekuendigt
        (polite), ein Fehler unterbricht (assertive). Mit einem gemeinsamen Bereich
        muesste man sich fuer eines von beidem entscheiden.
      */}
      <div
        className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-[22rem] max-w-[calc(100vw-2rem)] flex-col gap-2"
        aria-live="polite"
      >
        {toasts
          .filter((toast) => toast.kind !== "error")
          .map((toast) => (
            <ToastCard key={toast.id} toast={toast} onDone={remove} />
          ))}
      </div>

      <div
        className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-[22rem] max-w-[calc(100vw-2rem)] flex-col gap-2"
        aria-live="assertive"
      >
        {toasts
          .filter((toast) => toast.kind === "error")
          .map((toast) => (
            <ToastCard key={toast.id} toast={toast} onDone={remove} />
          ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): Api {
  const api = useContext(ToastContext);
  if (!api) {
    throw new Error("useToast braucht einen ToastProvider darueber");
  }
  return api;
}

function ToastCard({
  toast,
  onDone,
}: {
  toast: Toast;
  onDone: (id: number) => void;
}) {
  // Verschwindet von selbst - ein Toast, der stehen bleibt, ist ein Dialog ohne Knopf.
  useEffect(() => {
    const timer = setTimeout(() => onDone(toast.id), LIFETIME[toast.kind]);
    return () => clearTimeout(timer);
  }, [toast, onDone]);

  const colors: Record<Kind, string> = {
    success: "border-ok/40 text-ok",
    error: "border-bad/40 text-bad",
    info: "border-brand/40 text-brand",
  };
  const icons: Record<Kind, "check" | "alert" | "info"> = {
    success: "check",
    error: "alert",
    info: "info",
  };

  return (
    <div
      role={toast.kind === "error" ? "alert" : "status"}
      className={
        "vc-slide-in pointer-events-auto flex items-start gap-3 rounded-card border bg-surface/95 p-3 shadow-lg backdrop-blur " +
        colors[toast.kind]
      }
    >
      <Icon name={icons[toast.kind]} className="mt-0.5 size-4 shrink-0" />
      <p className="flex-1 text-sm text-text">{toast.text}</p>
      <button
        onClick={() => onDone(toast.id)}
        aria-label="Meldung schliessen"
        className="rounded text-text-faint transition-colors hover:text-text"
      >
        <Icon name="close" className="size-4" />
      </button>
    </div>
  );
}
