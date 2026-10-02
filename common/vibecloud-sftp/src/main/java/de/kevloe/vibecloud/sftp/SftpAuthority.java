package de.kevloe.vibecloud.sftp;

/**
 * Wer ueber eine SFTP-Anmeldung entscheidet - im Betrieb der Master.
 *
 * <p>Als eigene Schnittstelle, damit der {@link SftpGateway} die Verbindung zum Master
 * nicht kennen muss - und sich ohne einen pruefen laesst.
 */
@FunctionalInterface
public interface SftpAuthority {

    /**
     * @param account    der Teil des Benutzernamens vor dem letzten Punkt
     * @param target     wohin es gehen soll - am Wrapper ein statischer Server, am Master
     *                   eine Gruppe
     * @param clientIp   Adresse des Clients, fuer das Protokoll
     */
    Decision check(String account, String target, String password, String clientIp);

    /**
     * @param detail Grund einer Ablehnung - fuer das Log, nie fuer den Client
     */
    record Decision(boolean allowed, String detail) {

        public static Decision denied(String detail) {
            return new Decision(false, detail);
        }
    }
}
