import { useEffect, useState } from "react";
import {
  changePassword,
  logout,
  refresh,
  request,
  type Module,
  type Session,
} from "./api";
import { MaintenanceToggle } from "./MaintenanceToggle";
import { Sidebar, type NavEntry } from "./ui/Sidebar";
import { useToast } from "./ui/Toast";
import { Login } from "./pages/Login";
import { ChangePassword } from "./pages/ChangePassword";
import { Overview } from "./pages/Overview";
import { Servers } from "./pages/Servers";
import { ServerDetail } from "./pages/ServerDetail";
import { Groups } from "./pages/Groups";
import { Nodes } from "./pages/Nodes";
import { Players } from "./pages/Players";
import { Ranks } from "./pages/Ranks";
import { Translations } from "./pages/Translations";
import { Modules } from "./pages/Modules";
import { Settings } from "./pages/Settings";
import { Sftp } from "./pages/Sftp";

/** Die Seiten des Dashboards. */
type Page =
  | "overview"
  | "servers"
  | "groups"
  | "nodes"
  | "players"
  | "ranks"
  | "messages"
  | "files"
  | "modules"
  | "settings";

const PAGES: Array<NavEntry<Page>> = [
  { id: "overview", label: "Übersicht", icon: "overview" },
  { id: "servers", label: "Server", icon: "server" },
  { id: "groups", label: "Gruppen", icon: "group" },
  { id: "nodes", label: "Nodes", icon: "node" },
  { id: "players", label: "Spieler", icon: "player" },
  { id: "ranks", label: "Ränge", icon: "rank" },
  { id: "messages", label: "Sprachen", icon: "language" },
  { id: "files", label: "Dateien", icon: "folder" },
  { id: "modules", label: "Module", icon: "module" },
  { id: "settings", label: "Einstellungen", icon: "settings" },
];

export function App() {
  const toast = useToast();
  const [session, setSession] = useState<Session | null>(null);
  const [checking, setChecking] = useState(true);
  const [page, setPage] = useState<Page>("overview");
  const [openServer, setOpenServer] = useState<string | null>(null);
  const [modules, setModules] = useState<Module[]>([]);

  // Beim Laden einmal versuchen, aus dem Cookie eine Sitzung zu bekommen. Ohne das
  // muesste man sich nach jedem Neuladen neu anmelden.
  useEffect(() => {
    refresh()
      .then(setSession)
      .finally(() => setChecking(false));
  }, []);

  // Welche Module laufen - damit Seiten ohne passendes Modul nicht erscheinen
  // (PLAN.md Abschnitt 12). Die Kopplung bleibt damit hier im Frontend sichtbar.
  useEffect(() => {
    if (!session || session.mustChangePassword) {
      return;
    }
    request<Module[]>("/api/v1/modules")
      .then(setModules)
      .catch(() => setModules([]));
  }, [session]);

  if (checking) {
    return (
      <div className="grid h-full place-items-center text-sm text-text-muted">
        Einen Moment ...
      </div>
    );
  }

  if (!session) {
    return <Login onLogin={setSession} />;
  }

  // Solange das Start-Passwort gilt, ist nichts anderes erreichbar - der Master lehnt
  // jeden anderen Aufruf ohnehin mit 403 ab.
  if (session.mustChangePassword) {
    return (
      <ChangePassword
        name={session.name}
        onDone={async (password) => {
          setSession(await changePassword(password));
          toast.success("Passwort gesetzt. Alle anderen Sitzungen sind beendet.");
        }}
      />
    );
  }

  return (
    <div className="flex h-full">
      <Sidebar
        entries={PAGES}
        current={page}
        onNavigate={(id) => {
          setOpenServer(null);
          setPage(id);
        }}
        footer={<MaintenanceToggle />}
        accountName={session.name}
        onLogout={async () => {
          await logout();
          setSession(null);
          toast.info("Abgemeldet.");
        }}
      />

      {/*
        Oben links bleibt auf schmalen Bildschirmen Platz fuer den Schalter der
        Seitenleiste - sonst laege er auf dem Seitentitel.
      */}
      <main className="flex h-full flex-1 flex-col overflow-y-auto px-4 pb-8 pt-16 sm:px-6 lg:pt-6">
        <div className="mx-auto flex min-h-0 w-full max-w-[90rem] flex-1 flex-col">
          {openServer ? (
            <ServerDetail name={openServer} onBack={() => setOpenServer(null)} />
          ) : (
            <>
              {page === "overview" && <Overview onOpenServer={setOpenServer} />}
              {page === "servers" && <Servers onOpenServer={setOpenServer} />}
              {page === "groups" && <Groups />}
              {page === "nodes" && <Nodes />}
              {page === "players" && <Players selfUuid={session.uuid} />}
              {page === "ranks" && <Ranks />}
              {page === "messages" && <Translations />}
              {page === "modules" && <Modules modules={modules} />}
              {page === "files" && <Sftp />}
              {page === "settings" && <Settings />}
            </>
          )}
        </div>
      </main>
    </div>
  );
}
