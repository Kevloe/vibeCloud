import { Field, Select, TextInput } from "./Form";

/** Ein aenderbares Feld, wie der Master es meldet - leere Werteliste = freier Text. */
export type EditableField = {
  name: string;
  values: string[];
};

/**
 * Kurze Erklaerungen zu den Feldern.
 *
 * Bewusst nur Hinweise und keine Beschriftungen: Als Beschriftung waere das eine zweite
 * Liste neben der des Masters, und ein neues Feld hiesse im Dashboard dann gar nichts.
 * So steht ueber dem Feld immer der Name, den auch die Konsole kennt - und darunter, was
 * er bedeutet, soweit wir es wissen.
 */
const HINTS: Record<string, string> = {
  min_online: "So viele Server laufen immer.",
  max_online: "Mehr startet die Cloud nie.",
  max_players: "Slots je Server.",
  memory: "Arbeitsspeicher je Server in MB.",
  start_percent: "Ab dieser Auslastung wird nachgestartet, 0 = aus.",
  idle_timeout: "Sekunden, die ein leerer Server über min_online hinaus läuft.",
  join_priority: "Höher heißt: wird beim Verteilen zuerst gefüllt.",
  priority: "Reihenfolge beim Starten.",
  mc_version: "latest heißt: neueste Version mit stabilen Builds.",
  jar_source: "paper, velocity, template oder custom:<url>",
  template: "Vorlagenordner unter templates/.",
  name_pattern: "Muss %id% enthalten, sonst hießen alle Server gleich.",
  fallback: "Ziel, wenn ein Spieler von einem Server fliegt.",
  maintenance: "Wartungsmodus für diese Gruppe.",
  display_name: "So heißt der Rang in Meldungen.",
  prefix: "MiniMessage, zum Beispiel <red>[Admin] </red>",
  suffix: "Steht hinter dem Namen.",
  color: "MiniMessage-Farbe des Namens, zum Beispiel <red>",
  weight: "Höher schlägt niedriger bei geerbten Rechten.",
  chat_format: "Leer = Vorgabe der Cloud.",
  "servers.portRangeStart": "Erster Port für Gameserver.",
  "servers.portRangeEnd": "Letzter Port für Gameserver.",
  "servers.proxyPort": "Hierauf verbinden sich Spieler - nie im Gameserver-Bereich.",
  "servers.stopGraceSeconds": "Sekunden zwischen stop und dem harten Beenden.",
  "servers.schedulerIntervalSeconds": "Takt, in dem die Cloud Server nachstartet.",
  "servers.logRetentionDays": "So lange bleiben hochgeladene Logs auf dem Master.",
  "grpc.heartbeatIntervalSeconds": "Abstand der Lebenszeichen eines Nodes.",
  "grpc.heartbeatTimeoutSeconds": "Ohne Lebenszeichen gilt ein Node danach als ausgefallen.",
  "grpc.maxMessageSizeMb": "Grenze je Nachricht zwischen Master und Wrapper.",
};

/**
 * Alle Felder eines Datensatzes als ein Formular.
 *
 * In jedem Feld steht der aktuelle Wert - man soll sehen, was drinsteht, bevor man es
 * ueberschreibt. Geaenderte Felder bekommen eine Markierung, damit beim Speichern klar
 * ist, was gleich hinausgeht.
 */
export function FieldGrid({
  fields,
  draft,
  current,
  onChange,
  notes = {},
}: {
  fields: EditableField[];
  draft: Record<string, string>;
  current: Record<string, string>;
  onChange: (name: string, value: string) => void;
  /**
   * Ein Hinweis, der zu genau diesem Datensatz gehoert und deshalb vor der allgemeinen
   * Erklaerung steht - etwa "läuft noch mit 30" bei einer Einstellung, die erst nach dem
   * Neustart gilt.
   */
  notes?: Record<string, string>;
}) {
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      {fields.map((field) => {
        const value = draft[field.name] ?? "";
        const changed = value !== (current[field.name] ?? "");
        // true/false ist wirklich eine Auswahl - alles andere sind Vorschlaege.
        const isBoolean =
          field.values.length === 2 &&
          field.values.includes("true") &&
          field.values.includes("false");

        return (
          <Field
            key={field.name}
            label={field.name}
            hint={
              changed
                ? `Vorher: ${current[field.name] || "(leer)"}`
                : (notes[field.name] ?? HINTS[field.name])
            }
          >
            {(props) =>
              isBoolean ? (
                <Select
                  {...props}
                  value={value}
                  onChange={(event) => onChange(field.name, event.target.value)}
                  className={changed ? "border-brand" : ""}
                >
                  <option value="true">true</option>
                  <option value="false">false</option>
                </Select>
              ) : (
                <>
                  <TextInput
                    {...props}
                    value={value}
                    list={field.values.length > 0 ? `${props.id}-values` : undefined}
                    onChange={(event) => onChange(field.name, event.target.value)}
                    className={changed ? "border-brand" : ""}
                  />
                  {field.values.length > 0 && (
                    <datalist id={`${props.id}-values`}>
                      {field.values.map((entry) => (
                        <option key={entry} value={entry} />
                      ))}
                    </datalist>
                  )}
                </>
              )
            }
          </Field>
        );
      })}
    </div>
  );
}

/** Welche Felder sich gegenueber dem geladenen Stand geaendert haben. */
export function changedFields(
  draft: Record<string, string>,
  current: Record<string, string>,
): Array<[string, string]> {
  return Object.entries(draft).filter(
    ([name, value]) => value !== (current[name] ?? ""),
  );
}
