package de.kevloe.vibecloud.master.console;

import de.kevloe.vibecloud.api.server.CloudServer;
import de.kevloe.vibecloud.master.server.ServerRegistry;
import de.kevloe.vibecloud.master.server.ServerService;

import java.util.List;

/**
 * {@code screen <server>} haengt sich an die Live-Konsole eines Servers
 * (PLAN.md Abschnitt 12).
 *
 * <p>Die Konsole bleibt dabei bedienbar: Eingaben gehen an den Server weiter, bis man mit
 * {@code /detach} wieder loest. Absichtlich kein Blockieren des Eingabe-Threads - sonst
 * waere der Master bei einem haengenden Server nicht mehr steuerbar.
 */
public final class ScreenCommand implements CommandRegistry.Command {

    private final ConsoleBuffer console;
    private final ServerRegistry registry;
    private final ServerService service;
    private final AttachmentState state;

    public ScreenCommand(ConsoleBuffer console, ServerRegistry registry, ServerService service,
                         AttachmentState state) {
        this.console = console;
        this.registry = registry;
        this.service = service;
        this.state = state;
    }

    @Override
    public String name() {
        return "screen";
    }

    @Override
    public String description() {
        return "An die Live-Konsole eines Servers haengen";
    }

    @Override
    public String usage() {
        return "screen <server>   (loesen mit /detach)";
    }

    @Override
    public List<String> complete(List<String> args) {
        return registry.all().stream().map(CloudServer::name).toList();
    }

    /** Nicht im Spiel: Ein Live-Log laesst sich im Chat nicht sinnvoll darstellen. */
    @Override
    public boolean availableInGame() {
        return false;
    }

    @Override
    public void execute(CommandOutput out, List<String> args) {
        if (args.isEmpty()) {
            out.error("Syntax: " + usage());
            return;
        }
        String serverName = args.getFirst();
        if (registry.find(serverName).isEmpty()) {
            out.error(serverName + " ist nicht bekannt.");
            return;
        }

        state.detach();

        List<String> history = console.history(serverName);
        if (history.isEmpty()) {
            out.info("(noch keine Ausgabe von " + serverName + ")");
        } else {
            out.info("--- letzte " + history.size() + " Zeilen von " + serverName + " ---");
            history.forEach(out::info);
        }
        out.success("An " + serverName + " gehaengt. Eingaben gehen an den Server, "
                    + "loesen mit /detach");

        Runnable detach = console.attach(serverName, out::info);
        state.attach(serverName, detach, service);
    }

    /**
     * Haelt fest, an welchem Server die Konsole gerade haengt.
     *
     * <p>Liegt getrennt, weil das Terminal diesen Zustand braucht: Solange etwas angehaengt
     * ist, gehen Eingaben an den Server statt an die Befehlsverarbeitung.
     */
    public static final class AttachmentState {

        private volatile String serverName;
        private volatile Runnable detachHook;
        private volatile ServerService service;

        public synchronized void attach(String server, Runnable detachHook, ServerService service) {
            this.serverName = server;
            this.detachHook = detachHook;
            this.service = service;
        }

        public synchronized void detach() {
            if (detachHook != null) {
                detachHook.run();
            }
            serverName = null;
            detachHook = null;
            service = null;
        }

        public boolean isAttached() {
            return serverName != null;
        }

        public String attachedServer() {
            return serverName;
        }

        /** Leitet eine Eingabezeile an den angehaengten Server weiter. */
        public boolean forward(String line) {
            String target = serverName;
            ServerService current = service;
            if (target == null || current == null) {
                return false;
            }
            return current.execute(target, line, "CONSOLE");
        }
    }
}
