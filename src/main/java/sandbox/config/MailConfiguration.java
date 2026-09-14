package sandbox.config;

import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import sandbox.service.MailService;
import sandbox.service.MailServiceImpl;

/**
 * Configuration du {@link MailService} : centralise le cablage de la connexion SMTP
 * (authentification, TLS) a partir de proprietes externalisees (application.properties),
 * sans jamais coder de valeur specifique au Gateway SMTP en dur dans le code Java.
 */
@Configuration
public class MailConfiguration {

    @Bean
    public MailService mailSender(@Value("${mail.protocol}") String mailProtocol,
                                   @Value("${mail.host}") String mailHost,
                                   @Value("${mail.port}") int mailPort,
                                   @Value("${mail.smtp.connectiontimeout}") String connectiontimeout,
                                   @Value("${mail.smtp.timeout}") String timeout,
                                   @Value("${mail.smtp.auth}") String auth,
                                   @Value("${mail.smtp.starttls.enable}") String starttls,
                                   @Value("${mail.smtp.starttls.required}") String starttlsRequired,
                                   @Value("${mail.smtp.ssl.protocols}") String sslProtocols,
                                   @Value("${mail.smtp.ssl.trust:}") String sslTrust,
                                   @Value("${mail.user}") String smtpUser,
                                   @Value("${mail.password}") String smtpPassword,
                                   @Value("${mail.from:}") String mailFrom) {

        JavaMailSenderImpl javaMailSender = buildJavaMailSender(mailProtocol,
                mailHost,
                mailPort,
                connectiontimeout,
                timeout,
                auth,
                starttls,
                starttlsRequired,
                sslProtocols,
                sslTrust,
                smtpUser,
                smtpPassword,
                mailFrom);

        return new MailServiceImpl(javaMailSender);
    }

    /**
     * Construit le {@link JavaMailSenderImpl} a partir de valeurs deja resolues.
     * Extrait de {@link #mailSender} pour pouvoir etre appele directement depuis les
     * tests (avec des valeurs de test), sans passer par un contexte Spring complet,
     * et pour permettre d'inspecter le resultat (voir MailConfigurationTest).
     */
    JavaMailSenderImpl buildJavaMailSender(String mailProtocol,
                                            String mailHost,
                                            int mailPort,
                                            String connectiontimeout,
                                            String timeout,
                                            String auth,
                                            String starttls,
                                            String starttlsRequired,
                                            String sslProtocols,
                                            String sslTrust,
                                            String smtpUser,
                                            String smtpPassword,
                                            String mailFrom) {

        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();

        Properties props = new Properties();
        props.put("mail.smtp.connectiontimeout", connectiontimeout);
        props.put("mail.smtp.timeout", timeout);

        props.put("mail.smtp.auth", auth);

        props.put("mail.smtp.starttls.enable", starttls);
        props.put("mail.smtp.starttls.required", starttlsRequired);
        props.put("mail.smtp.ssl.protocols", sslProtocols);
        if (!sslTrust.isBlank()) {
            // Usage test uniquement (certificat auto-signe) : jamais "*" en production.
            props.put("mail.smtp.ssl.trust", sslTrust);
        }

        props.put("mail.from", mailFrom);

        javaMailSender.setJavaMailProperties(props);

        javaMailSender.setProtocol(mailProtocol);
        javaMailSender.setHost(mailHost);
        javaMailSender.setPort(mailPort);

        javaMailSender.setUsername(smtpUser);
        javaMailSender.setPassword(smtpPassword);

        return javaMailSender;
    }
}
