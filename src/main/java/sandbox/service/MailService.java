package sandbox.service;


/**
 * Interface expostant les méthodes nécessaires à l'envoi de mails
 */
public interface MailService
{

    /**
     * Envoi d'un mail.
     *
     */
    void send(String from,
              String[] to,
              String subject,
              String text);
}
