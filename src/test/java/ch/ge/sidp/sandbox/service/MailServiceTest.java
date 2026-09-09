package ch.ge.sidp.sandbox.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessagePreparator;

/**
 * Test unitaire du comportement de {@link MailServiceImpl} : verifie que la methode
 * {@code send(...)} declenche bien un appel a {@code mailSender.send(...)}, sans se
 * preoccuper de la connexion reelle au serveur SMTP, de l'authentification ou de TLS
 * (voir {@code MailServiceIntegrationTest} pour ca). Le {@link JavaMailSenderImpl} est
 * mocke.
 */
class MailServiceTest {

    private final JavaMailSenderImpl mailSender = mock(JavaMailSenderImpl.class);
    private final MailServiceImpl mailService = new MailServiceImpl(mailSender);

    @Test
    void laMethodeSendDoitEnvoyerUnSimpleMailMessage() {
        String from = "test.sender@noDomain.abc";
        String[] to = new String[]{"test.receiver@noDomain.abc"};
        String subject = "Message de test";
        String text = "Ceci est un message de test";

        mailService.send(from, to, subject, text);

        // un envoi d'un SimpleMailMessage est effectue une fois
        verify(mailSender).send(any(SimpleMailMessage.class));

        // aucun MimeMessage n'est envoye
        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(mailSender, never()).send(any(MimeMessage[].class));

        // aucun MimeMessagePreparator n'est envoye
        verify(mailSender, never()).send(any(MimeMessagePreparator.class));
        verify(mailSender, never()).send(any(MimeMessagePreparator[].class));
    }
}
