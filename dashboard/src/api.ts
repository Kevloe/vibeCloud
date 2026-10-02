/**
 * Zugriff auf die REST-Schnittstelle des Masters.
 *
 * Das Zugangstoken liegt absichtlich nur im Speicher dieser Seite und nicht im
 * localStorage: Dort koennte es jedes Skript lesen, und es ueberlebt das Schliessen des
 * Tabs. Das langlebige Refresh-Token steckt in einem HttpOnly-Cookie, an das kein Skript
 * herankommt - ein Neuladen holt daraus ein neues Zugangstoken.
 */

let accessToken: string | null = null;

export type Session = {
  token: string;
  expiresInSeconds: number;
  name: string;
  uuid: string;
  mustChangePassword: boolean;
};

export function setToken(token: string | null): void {
  accessToken = token;
}

export function hasToken(): boolean {
  return accessToken !== null;
}

/** Ein Fehler mit dem Text, den der Master geschickt hat. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

type Options = {
  method?: string;
  body?: unknown;
  /** Beim Erneuern selbst nicht erneuern - das waere eine Endlosschleife. */
  retryOnUnauthorized?: boolean;
};

/**
 * Ruft einen Endpunkt auf.
 *
 * Bei 401 wird einmal versucht, das Zugangstoken zu erneuern. Das passiert nach 15
 * Minuten regelmaessig, und der Benutzer soll davon nichts merken.
 */
export async function request<T>(path: string, options: Options = {}): Promise<T> {
  const { method = "GET", body, retryOnUnauthorized = true } = options;

  const response = await fetch(path, {
    method,
    headers: {
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (response.status === 401 && retryOnUnauthorized) {
    const refreshed = await refresh();
    if (refreshed) {
      return request<T>(path, { ...options, retryOnUnauthorized: false });
    }
  }

  if (!response.ok) {
    const text = await response.text();
    let message = text;
    try {
      message = (JSON.parse(text) as { error?: string }).error ?? text;
    } catch {
      // Keine JSON-Antwort - dann steht der Rohtext in der Meldung.
    }
    throw new ApiError(response.status, message || response.statusText);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export async function login(username: string, password: string): Promise<Session> {
  const session = await request<Session>("/api/v1/auth/login", {
    method: "POST",
    body: { username, password },
    retryOnUnauthorized: false,
  });
  accessToken = session.token;
  return session;
}

/**
 * Holt ein neues Zugangstoken aus dem Cookie.
 *
 * Wird beim Start der Seite und nach einem 401 aufgerufen. Schlaegt es fehl, ist die
 * Sitzung vorbei - etwa weil ein Recht entzogen wurde.
 */
export async function refresh(): Promise<Session | null> {
  try {
    const session = await request<Session>("/api/v1/auth/refresh", {
      method: "POST",
      retryOnUnauthorized: false,
    });
    accessToken = session.token;
    return session;
  } catch {
    accessToken = null;
    return null;
  }
}

export async function changePassword(password: string): Promise<Session> {
  const session = await request<Session>("/api/v1/auth/password", {
    method: "POST",
    body: { password },
    retryOnUnauthorized: false,
  });
  accessToken = session.token;
  return session;
}

export async function logout(): Promise<void> {
  try {
    await request("/api/v1/auth/logout", { method: "POST", retryOnUnauthorized: false });
  } finally {
    accessToken = null;
  }
}

/**
 * Oeffnet einen WebSocket.
 *
 * Ein Browser kann beim Verbindungsaufbau keine Kopfzeilen setzen, deshalb holt die
 * Seite erst eine Einmal-Karte und haengt sie an die Adresse. Sie gilt 30 Sekunden und
 * genau einmal - steht sie spaeter in einem Protokoll, ist sie wertlos.
 */
export async function openSocket(path: string): Promise<WebSocket> {
  const { ticket } = await request<{ ticket: string }>("/api/v1/auth/ws-ticket", {
    method: "POST",
  });
  const protocol = location.protocol === "https:" ? "wss" : "ws";
  return new WebSocket(`${protocol}://${location.host}${path}?ticket=${ticket}`);
}

// ---------------------------------------------------------------- Typen der Endpunkte

export type Server = {
  name: string;
  group: string;
  platform: string;
  node: string;
  port: number;
  state: string;
  static: boolean;
  players: number;
  maxPlayers: number;
  startedAt: string;
};

export type Group = {
  name: string;
  platform: string;
  static: boolean;
  minOnline: number;
  maxOnline: number;
  memoryMb: number;
  online: number;
  /** Aenderbares Feld -> aktueller Wert. Der Master liefert beides zusammen. */
  fields: Record<string, string>;
};

export type Node = {
  name: string;
  connected: boolean;
  enabled: boolean;
  maxMemoryMb: number;
  servers: number;
  lastSeen: string;
};

export type Rank = {
  id: string;
  displayName: string;
  prefix: string;
  weight: number;
  default: boolean;
  inherits: string[];
  fields: Record<string, string>;
};

export type Module = {
  id: string;
  name: string;
  version: string;
  enabled: boolean;
};

export type Player = {
  uuid: string;
  name: string;
  platform: string;
  rank: string;
  rankExpiresAt?: string;
  locale?: string;
  firstLogin: string;
  lastLogin: string;
  lastServer?: string;
  playtimeSeconds: number;
  /** Ob der Spieler gerade verbunden ist - aus der Meldung der Proxys. */
  online: boolean;
};

/** Eine Aufnahme aus /ws/events. */
export type Snapshot = {
  type: "servers";
  servers: Array<{
    name: string;
    group: string;
    node: string;
    state: string;
    players: number;
    maxPlayers: number;
  }>;
  players: number;
};

export type Overview = {
  servers: number;
  serversRunning: number;
  groups: number;
  nodes: number;
  nodesConnected: number;
  modules: number;
  playersOnline: number;
  playersKnown: number;
};

/** Ein Spieler in einer Liste - weniger Felder als die Detailansicht. */
export type PlayerSummary = {
  uuid: string;
  name: string;
  rank: string;
  lastLogin: string;
  lastServer: string;
  online: boolean;
};

export type OnlinePlayer = {
  uuid: string;
  name: string;
  server: string;
  proxy: string;
  since: string;
};

/** Ein aenderbares Feld einer Gruppe samt erlaubter Werte (leer = freier Text). */
export type Field = {
  name: string;
  values: string[];
};

/** Eine Sprachdatei in der Uebersicht. */
export type LocaleSummary = {
  locale: string;
  /** Die Standardsprache - sie ist die Vorlage und laesst sich nicht loeschen. */
  default: boolean;
  keys: number;
  missing: number;
};

/** Eine Sprachdatei zum Bearbeiten - mit der Standardsprache als Vorlage daneben. */
export type LocaleFile = {
  locale: string;
  default: boolean;
  entries: Record<string, string>;
  defaults: Record<string, string>;
};

/**
 * Eine einzelne Rechte-Regel.
 *
 * `group` und `server` stehen einzeln neben `context`, obwohl `context` dasselbe als Text
 * sagt: Beim Entfernen muessen sie genauso wieder mitgehen, und aus "group=lobby" muesste
 * die Oberflaeche sie erst zerlegen.
 */
export type PermissionRule = {
  node: string;
  /** true erlaubt, false verbietet. */
  value: boolean;
  /** "global", "group=lobby" oder "server=lobby-1" - fertig zum Anzeigen. */
  context: string;
  group: string;
  server: string;
  /** Leer heisst permanent. */
  expiresAt: string;
  /** Nur bei Regeln mit Herkunft: PLAYER, OWN_RANK oder INHERITED_RANK. */
  tier?: "PLAYER" | "OWN_RANK" | "INHERITED_RANK";
  /** Woher die Regel kommt - ein Rangname oder "Spieler". */
  source?: string;
  weight?: number;
};

/** Die Regeln eines Rangs: eigene und die seiner Elternraenge. */
export type RankPermissions = {
  rank: string;
  own: PermissionRule[];
  inherited: PermissionRule[];
};

/** Die Regeln eines Spielers: eigene und alles, was sonst noch zutrifft. */
export type PlayerPermissions = {
  uuid: string;
  name: string;
  rank: string;
  own: PermissionRule[];
  effective: PermissionRule[];
};

/**
 * Die Antwort von `perm check`: nicht nur ja oder nein, sondern warum.
 *
 * `overridden` ist der halbe Zweck - oft steht genau dort der Denkfehler, etwa ein Verbot
 * in einem geerbten Rang, das niemand vermutet hat.
 */
export type PermissionCheck = {
  player: string;
  node: string;
  context: string;
  allowed: boolean;
  decidedBy: PermissionRule | null;
  overridden: PermissionRule[];
};

/** Ein Knoten aus dem Katalog - Vorschlag, keine Auswahl. */
export type PermissionNode = {
  node: string;
  description: string;
};
