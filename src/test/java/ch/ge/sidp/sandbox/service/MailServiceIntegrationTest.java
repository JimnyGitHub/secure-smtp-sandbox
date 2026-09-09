package ch.ge.sidp.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import ch.ge.sidp.sandbox.config.MailConfiguration;

/**
 * Tests d'integration d'envoi de mail avec un reel serveur SMTP, verifiant en particulier
 * l'authentification SMTP et le chiffrement TLS (STARTTLS obligatoire).
 * <p>
 * Prerequis :
 * <p>
 * Il faut un environnement Docker fonctionnel (retour correct de la commande "docker info")
 * pour faire fonctionner les tests de cette classe. Sans cela, les tests sont ignores
 * ("Pas de test -> environnement Docker non dispo").
 * <p>
 * Cette classe utilise :
 * <p>
 * - <a href="https://www.testcontainers.org/">testcontainers</a>
 * <p>
 * - un conteneur base sur <a href="https://mailpit.axllent.org/">Mailpit</a>, le successeur
 * de MailHog. Contrairement a l'image {@code mailhog/mailhog}, Mailpit permet de configurer
 * un serveur SMTP qui exige reellement l'authentification et STARTTLS (rejet des commandes
 * si le client n'a pas negocie TLS), ce qui est necessaire pour prouver la mise en conformite.
 * <p>
 * Note : {@code MailServiceImpl} laisse remonter les exceptions de
 * {@code JavaMailSenderImpl}, qui sont des {@link org.springframework.mail.MailException}
 * (Spring encapsule les exceptions {@code javax.mail} sous-jacentes), d'ou les types
 * verifies ici (voir aussi {@code .claude/temp/MailServiceImpl.java}).
 */
class MailServiceIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailServiceIntegrationTest.class);

    private static final String MAILPIT_IMAGE = "axllent/mailpit:v1.31.1";
    private static final int PORT_SMTP = 1025;
    private static final int PORT_HTTP = 8025;

    private static final String TEST_USER = "test-user";
    private static final String TEST_PASSWORD = "test-password";
    private static final String WRONG_PASSWORD = "wrong-password";

    private static final String FROM = "test.sender@noDomain.abc";
    private static final String[] TO = {"test.receiver@noDomain.abc"};

    private static boolean dockerAvailable;

    /** Serveur SMTP exigeant AUTH + STARTTLS obligatoire (certificat de test fourni). */
    private static GenericContainer<?> secureContainer;

    /** Serveur SMTP avec AUTH, mais sans aucun support TLS (aucun certificat fourni). */
    private static GenericContainer<?> noTlsContainer;

    private static final MailConfiguration MAIL_CONFIGURATION = new MailConfiguration();

    @BeforeAll
    static void startContainersSiDockerDisponible() {
        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable) {
            LOGGER.warn("Pas de test -> environnement Docker non dispo");
            return;
        }

        secureContainer = new GenericContainer<>(DockerImageName.parse(MAILPIT_IMAGE))
                .withExposedPorts(PORT_SMTP, PORT_HTTP)
                .withEnv("MP_SMTP_AUTH", TEST_USER + ":" + TEST_PASSWORD)
                .withEnv("MP_SMTP_REQUIRE_STARTTLS", "true")
                .withEnv("MP_SMTP_TLS_CERT", "/tls/cert.pem")
                .withEnv("MP_SMTP_TLS_KEY", "/tls/key.pem")
                .withCopyFileToContainer(MountableFile.forClasspathResource("mailpit-tls/cert.pem"), "/tls/cert.pem")
                .withCopyFileToContainer(MountableFile.forClasspathResource("mailpit-tls/key.pem"), "/tls/key.pem")
                .waitingFor(Wait.forListeningPort());
        secureContainer.start();

        noTlsContainer = new GenericContainer<>(DockerImageName.parse(MAILPIT_IMAGE))
                .withExposedPorts(PORT_SMTP, PORT_HTTP)
                .withEnv("MP_SMTP_AUTH", TEST_USER + ":" + TEST_PASSWORD)
                // Mailpit refuse de demarrer avec AUTH active sans TLS, sauf autorisation
                // explicite : c'est exactement le serveur "sans TLS du tout" dont on a
                // besoin pour le scenario STARTTLS obligatoire non supporte par le serveur.
                .withEnv("MP_SMTP_AUTH_ALLOW_INSECURE", "true")
                .waitingFor(Wait.forListeningPort());
        noTlsContainer.start();
    }

    @AfterAll
    static void arreterLesContainers() {
        if (secureContainer != null) {
            secureContainer.stop();
        }
        if (noTlsContainer != null) {
            noTlsContainer.stop();
        }
    }

    @Test
    void envoiAvecAuthEtTlsCorrectsDoitReussirEtLeMessageDoitEtreRecu() throws IOException {
        assumeTrue(dockerAvailable, "Pas de test -> environnement Docker non dispo");

        String subject = "Scenario nominal";
        String text = "AUTH reussie et TLS etabli";

        MailService mailService = buildMailService(secureContainer, TEST_USER, TEST_PASSWORD, true, true, true);

        assertDoesNotThrow(() -> mailService.send(FROM, TO, subject, text));

        assertMessageRecu(secureContainer, subject, text);
    }

    @Test
    void envoiAvecMauvaisMotDePasseDoitEchouerEtAucunMessageNeDoitEtreEnvoye() throws IOException {
        assumeTrue(dockerAvailable, "Pas de test -> environnement Docker non dispo");

        String subject = "Scenario mauvais mot de passe";
        String text = "Message refuse (mauvais mot de passe)";

        MailService mailService = buildMailService(secureContainer, TEST_USER, WRONG_PASSWORD, true, true, true);

        // Meme si starttls.enable=true ici, remarque : JavaMail retente automatiquement
        // STARTTLS puis AUTH lorsque le serveur repond "Must issue a STARTTLS command
        // first" a un AUTH tente en clair. Le mot de passe est donc de toute facon
        // toujours verifie sur un canal chiffre, jamais en clair.
        assertThrows(MailAuthenticationException.class, () -> mailService.send(FROM, TO, subject, text));

        assertMessageAbsent(secureContainer, text);
    }

    @Test
    void envoiSansAuthNiStarttlsDoitEchouerCarLeServeurExigeTls() throws IOException {
        assumeTrue(dockerAvailable, "Pas de test -> environnement Docker non dispo");

        String subject = "Scenario sans TLS";
        String text = "Message refuse (sans STARTTLS)";

        // Le serveur exige STARTTLS pour toute commande autre que NOOP/EHLO/STARTTLS/QUIT
        // (MP_SMTP_REQUIRE_STARTTLS=true). Ici, aucun identifiant n'est fourni : Spring ne
        // transmet alors aucun couple user/password a Transport.connect(...), donc aucun
        // AUTH n'est tente (avec des identifiants, JavaMail retenterait sinon
        // automatiquement STARTTLS puis AUTH, voir le scenario mauvais mot de passe
        // ci-dessus). Sans AUTH ni STARTTLS, la simple commande MAIL FROM est donc
        // directement refusee par le serveur : le message ne peut pas partir en clair.
        MailService mailService = buildMailService(secureContainer, "", "", false, false, false);

        assertThrows(MailSendException.class, () -> mailService.send(FROM, TO, subject, text));

        assertMessageAbsent(secureContainer, text);
    }

    @Test
    void starttlsObligatoireCoteClientDoitEchouerSiLeServeurNeProposePasStarttls() throws IOException {
        assumeTrue(dockerAvailable, "Pas de test -> environnement Docker non dispo");

        String subject = "Scenario STARTTLS obligatoire non supporte par le serveur";
        String text = "Message refuse (STARTTLS non supporte par le serveur)";

        // mail.smtp.starttls.required=true cote client, alors que noTlsContainer ne
        // propose pas STARTTLS dans son EHLO : JavaMail doit refuser d'envoyer.
        MailService mailService = buildMailService(noTlsContainer, TEST_USER, TEST_PASSWORD, true, true, true);

        assertThrows(MailSendException.class, () -> mailService.send(FROM, TO, subject, text));

        assertMessageAbsent(noTlsContainer, text);
    }

    private MailService buildMailService(GenericContainer<?> container,
                                          String user,
                                          String password,
                                          boolean auth,
                                          boolean starttlsEnable,
                                          boolean starttlsRequired) {
        return MAIL_CONFIGURATION.mailSender(
                "smtp",
                container.getHost(),
                container.getMappedPort(PORT_SMTP),
                "5000",
                "5000",
                String.valueOf(auth),
                String.valueOf(starttlsEnable),
                String.valueOf(starttlsRequired),
                "TLSv1.2 TLSv1.3",
                container.getHost(), // certificat de test auto-signe : on ne fait confiance qu'a cet hote
                user,
                password,
                FROM);
    }

    private void assertMessageRecu(GenericContainer<?> container, String subject, String text) throws IOException {
        String messagesJson = lireLesMessages(container);
        assertTrue(messagesJson.contains(subject), "Le mail doit contenir " + subject);
        assertTrue(messagesJson.contains(text), "Le mail doit contenir " + text);
    }

    private void assertMessageAbsent(GenericContainer<?> container, String text) throws IOException {
        String messagesJson = lireLesMessages(container);
        assertFalse(messagesJson.contains(text), "Le mail ne doit pas contenir " + text);
    }

    private String lireLesMessages(GenericContainer<?> container) throws IOException {
        Integer httpPort = container.getMappedPort(PORT_HTTP);
        String urlStr = "http://" + container.getHost() + ":" + httpPort + "/api/v1/messages";
        LOGGER.info("urlStr -> {}", urlStr);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        assertNotNull(conn);
        conn.setRequestMethod("GET");
        conn.connect();

        int responseCode = conn.getResponseCode();
        assertEquals(200, responseCode, "La reponse doit etre HTTP/1.0 200 OK");

        StringBuilder dataDuMail = new StringBuilder();
        try (Scanner scanner = new Scanner(url.openStream())) {
            while (scanner.hasNext()) {
                dataDuMail.append(scanner.nextLine());
            }
        }
        LOGGER.info(dataDuMail.toString());
        return dataDuMail.toString();
    }
}
