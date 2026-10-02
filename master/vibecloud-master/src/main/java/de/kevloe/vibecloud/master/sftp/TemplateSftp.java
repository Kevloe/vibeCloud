package de.kevloe.vibecloud.master.sftp;

import de.kevloe.vibecloud.master.config.MasterConfig;
import de.kevloe.vibecloud.master.server.ServerGroupRepository;
import de.kevloe.vibecloud.master.template.TemplateStore;
import de.kevloe.vibecloud.sftp.SftpAuthority;
import de.kevloe.vibecloud.sftp.SftpGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * SFTP-Zugang zu den Templates der Gruppen - am Master, weil sie dort liegen.
 *
 * <p>Das ist die Antwort auf "wie bearbeite ich einen dynamischen Server": gar nicht. Sein
 * Verzeichnis entsteht bei jedem Start aus dem Template und ist nach dem Stopp weg. Was
 * bleiben soll, gehoert ins Template - und wirkt von dort auf jeden <b>neu gestarteten</b>
 * Server der Gruppe, ohne dass irgendwo ein Cache geleert werden muss
 * ({@link TemplateStore#buildManifest}).
 *
 * <p>Der Benutzername ist {@code <zugang>.<gruppe>}, das Recht
 * {@code vibecloud.sftp.template.<gruppe>}. Zugaenge und Passwoerter sind dieselben wie
 * fuer die Verzeichnisse statischer Server; nur die Adresse ist eine andere - hier der
 * Master, dort der Node.
 *
 * <p>{@code templates/global/} ist <b>nicht</b> erreichbar. Dort liegen die Dateien aller
 * Gruppen und das Cloud-Plugin selbst.
 */
public final class TemplateSftp implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateSftp.class);

    private final ServerGroupRepository groups;
    private final TemplateStore templates;
    private final SftpAccountService accounts;

    private volatile SftpGateway gateway;

    public TemplateSftp(ServerGroupRepository groups, TemplateStore templates,
                        SftpAccountService accounts) {
        this.groups = groups;
        this.templates = templates;
        this.accounts = accounts;
    }

    /**
     * Startet den Zugang, falls eingeschaltet.
     *
     * <p>Laesst sich der Port nicht oeffnen, laeuft der Master ohne weiter - die Cloud ist
     * wichtiger als der Dateizugang zu ihren Templates.
     *
     * @param hostKeyFile wo der Host-Schluessel liegt; fehlt er, wird er erzeugt
     */
    public void start(MasterConfig.Sftp config, Path hostKeyFile) {
        if (!config.enabled) {
            LOG.info("SFTP fuer Templates ist aus (sftp.enabled in der config.json)");
            return;
        }
        SftpGateway starting = new SftpGateway("Templates", config.bindAddress, config.port,
                hostKeyFile, this::directoryOf,
                (account, group, password, ip) -> {
                    SftpAccountService.Decision decision =
                            accounts.authenticateTemplate(account, group, password, ip);
                    return new SftpAuthority.Decision(decision.allowed(), decision.detail());
                });
        try {
            starting.start();
            gateway = starting;
        } catch (IOException exception) {
            LOG.error("SFTP fuer Templates konnte auf {}:{} nicht starten ({}) - der Master "
                      + "laeuft ohne weiter", config.bindAddress, config.port,
                    exception.getMessage());
            starting.close();
        }
    }

    /** Das Template-Verzeichnis einer Gruppe - leer, wenn es die Gruppe nicht gibt. */
    private Optional<Path> directoryOf(String groupName) {
        return groups.find(groupName).flatMap(templates::editableDirectory);
    }

    /** Ob das Template dieser Gruppe ueber SFTP erreichbar waere. */
    public boolean isEditable(String groupName) {
        return directoryOf(groupName).isPresent();
    }

    /** Der Port, auf dem gelauscht wird. 0 = aus. */
    public int port() {
        SftpGateway current = gateway;
        return current == null ? 0 : current.port();
    }

    /** Fingerprint des Host-Schluessels - leer, wenn kein SFTP laeuft. */
    public String hostKey() {
        SftpGateway current = gateway;
        return current == null ? "" : current.fingerprint();
    }

    @Override
    public void close() {
        SftpGateway current = gateway;
        gateway = null;
        if (current != null) {
            current.close();
        }
    }
}
