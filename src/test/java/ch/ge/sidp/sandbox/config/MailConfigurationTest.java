package ch.ge.sidp.sandbox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Test dedie a la configuration SMTP : verifie que les proprietes externalisees
 * (authentification, STARTTLS, STARTTLS obligatoire, versions TLS autorisees) sont bien
 * appliquees au {@link JavaMailSenderImpl} construit par {@link MailConfiguration}.
 * <p>
 * Aucun serveur SMTP reel n'est demarre ici : l'objectif est de detecter une regression
 * de cablage de configuration, pas de tester une vraie connexion (voir
 * {@code MailServiceIntegrationTest} pour ca).
 */
class MailConfigurationTest {

    private final MailConfiguration mailConfiguration = new MailConfiguration();
    private Properties testProperties;

    @BeforeEach
    void loadTestProperties() throws IOException {
        testProperties = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application-test.properties")) {
            assertNotNull(in, "application-test.properties doit etre present dans le classpath de test");
            testProperties.load(in);
        }
    }

    @Test
    void laConfigurationSmtpDoitEtreCorrectementAppliquee() {
        JavaMailSenderImpl javaMailSender = mailConfiguration.buildJavaMailSender(
                testProperties.getProperty("mail.protocol"),
                testProperties.getProperty("mail.host"),
                Integer.parseInt(testProperties.getProperty("mail.port")),
                testProperties.getProperty("mail.smtp.connectiontimeout"),
                testProperties.getProperty("mail.smtp.timeout"),
                testProperties.getProperty("mail.smtp.auth"),
                testProperties.getProperty("mail.smtp.starttls.enable"),
                testProperties.getProperty("mail.smtp.starttls.required"),
                testProperties.getProperty("mail.smtp.ssl.protocols"),
                "",
                testProperties.getProperty("mail.user"),
                testProperties.getProperty("mail.password"),
                testProperties.getProperty("mail.from"));

        assertEquals("smtp", javaMailSender.getProtocol());
        assertEquals("smtp.test.invalid", javaMailSender.getHost());
        assertEquals(2525, javaMailSender.getPort());
        assertEquals("test-user", javaMailSender.getUsername());
        assertEquals("test-password", javaMailSender.getPassword());

        Properties javaMailProperties = javaMailSender.getJavaMailProperties();
        assertEquals("true", javaMailProperties.getProperty("mail.smtp.auth"));
        assertEquals("true", javaMailProperties.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", javaMailProperties.getProperty("mail.smtp.starttls.required"));
        assertEquals("TLSv1.2", javaMailProperties.getProperty("mail.smtp.ssl.protocols"));
        assertEquals("test-from@example.com", javaMailProperties.getProperty("mail.from"));
    }

    @Test
    void aucuneAutreConfigurationNeDoitEcraserAuthEtStarttls() {
        JavaMailSenderImpl javaMailSender = mailConfiguration.buildJavaMailSender(
                "smtp", "localhost", 1025,
                "5000", "5000",
                "false", "false", "false", "",
                "",
                "user", "password", "");

        // Meme si les valeurs d'entree desactivent tout, on verifie que ce qui est
        // effectivement demande est bien ce qui est applique (pas ecrase par une
        // valeur par defaut cachee) : cablage 1-pour-1, sans valeur codee en dur.
        Properties javaMailProperties = javaMailSender.getJavaMailProperties();
        assertEquals("false", javaMailProperties.getProperty("mail.smtp.auth"));
        assertEquals("false", javaMailProperties.getProperty("mail.smtp.starttls.enable"));
        assertEquals("false", javaMailProperties.getProperty("mail.smtp.starttls.required"));
    }
}
