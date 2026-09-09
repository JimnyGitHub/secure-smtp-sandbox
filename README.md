# secure-smtp-sandbox

Projet **sandbox** montrant comment tester, avec [Testcontainers](https://testcontainers.com/),
un service Spring d'envoi de mail (`MailService`) qui se connecte à un serveur SMTP en
imposant l'**authentification** et le **chiffrement TLS** (STARTTLS obligatoire).

Le serveur SMTP est entièrement dockerisé : aucun vrai serveur mail n'est nécessaire, tout
tourne dans un conteneur [Mailpit](https://mailpit.axllent.org/), démarré et arrêté
automatiquement par les tests.

## Prérequis

- **Java 19+** (testé avec Java 21) et **Maven**.
- Un **environnement Docker fonctionnel**, c'est-à-dire un retour correct de la commande :

  ```shell
  docker info
  ```

  Si besoin, installer Docker :
  [Windows](https://docs.docker.com/desktop/install/windows-install/) /
  [Linux](https://docs.docker.com/desktop/install/linux-install/).

> Sans Docker disponible, les tests d'intégration ne plantent pas : ils s'auto-détectent et
> s'ignorent (`Pas de test -> environnement Docker non dispo`). Seuls les tests unitaires
> (qui ne nécessitent pas Docker) s'exécutent alors.

## Démarrer le projet

Ce projet n'a pas de point d'entrée exécutable (pas de `main`, pas de serveur à lancer) :
son fonctionnement se démontre à travers sa **suite de tests**, qui construit et interroge
elle-même un serveur SMTP jetable.

```shell
mvn clean test
```

Cette seule commande :

1. compile le projet ;
2. exécute les tests unitaires (`MailServiceTest`, `MailConfigurationTest`) — aucun Docker
   requis ;
3. exécute les tests d'intégration (`MailServiceIntegrationTest`) — Testcontainers démarre
   deux conteneurs Mailpit jetables, y envoie de vrais mails via SMTP, interroge leur API
   HTTP pour vérifier ce qui a été reçu, puis les détruit automatiquement à la fin.

Un run réussi se termine par :

```text
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Docker très récent (API >= 1.44) : `docker-java.properties`

Le fichier `src/test/resources/docker-java.properties` (`api.version=1.44`) est un
correctif de compatibilité pour les moteurs Docker récents (Docker Engine 29+), qui ont
relevé leur version minimale d'API au-dessus de celle utilisée par défaut par la version
de Testcontainers de ce projet — voir
[testcontainers-java#11210](https://github.com/testcontainers/testcontainers-java/issues/11210).
Il est inoffensif sur un Docker plus ancien : pas besoin de le retirer.

### Explorer Mailpit manuellement (optionnel)

Pour regarder l'interface web de Mailpit en dehors des tests automatisés :

```shell
docker run -p 1025:1025 -p 8025:8025 axllent/mailpit:v1.31.1
```

puis ouvrir [http://localhost:8025](http://localhost:8025) et envoyer un mail de test sur
`localhost:1025` (par exemple avec `swaks` ou tout client SMTP).

## Structure du projet

```text
src/main/java/ch/ge/sidp/sandbox/
├── config/
│   └── MailConfiguration.java   # cablage Spring du JavaMailSender (host, port, AUTH, TLS...)
└── service/
    ├── MailService.java         # interface d'envoi de mail
    └── MailServiceImpl.java     # implementation, delegue a un JavaMailSender deja configure

src/main/resources/
└── application.properties       # configuration SMTP externalisee (rien en dur dans le code)

src/test/java/ch/ge/sidp/sandbox/
├── config/MailConfigurationTest.java     # verifie le cablage de MailConfiguration (sans Docker)
└── service/
    ├── MailServiceTest.java               # test unitaire de MailServiceImpl (mock, sans Docker)
    └── MailServiceIntegrationTest.java     # tests d'integration Testcontainers + Mailpit

src/test/resources/
├── application-test.properties  # valeurs de test pour MailConfigurationTest
├── docker-java.properties       # voir plus haut
└── mailpit-tls/                 # certificat TLS auto-signe utilise par les tests
```

## Authentification SMTP et TLS

La configuration SMTP est entièrement externalisée dans `application.properties` — rien
n'est codé en dur dans `MailConfiguration` :

```properties
mail.smtp.auth=true
mail.smtp.starttls.enable=true
mail.smtp.starttls.required=true
mail.smtp.ssl.protocols=TLSv1.2 TLSv1.3
```

- `mail.smtp.auth` : active l'authentification SMTP (compte technique + mot de passe).
- `mail.smtp.starttls.enable` : le client *tente* STARTTLS.
- `mail.smtp.starttls.required` : le client **exige** STARTTLS — si le serveur ne le
  propose pas ou que la négociation TLS échoue, l'envoi est refusé (le message ne part
  jamais en clair).
- `mail.smtp.ssl.protocols` : restreint les versions TLS acceptées.

Le détail de la démarche de mise en conformité (contexte, exigences, plan d'implémentation)
se trouve dans [`.claude/authentification-smtp.md`](.claude/authentification-smtp.md).

## Tests d'intégration : Testcontainers + Mailpit

`MailServiceIntegrationTest` démarre deux conteneurs
[axllent/mailpit](https://hub.docker.com/r/axllent/mailpit), le successeur moderne de
MailHog. Mailpit a été préféré à `mailhog/mailhog` car il permet de configurer un serveur
qui **impose réellement** l'authentification et STARTTLS côté serveur (rejet des commandes
tant que le client n'a pas négocié TLS), ce que MailHog ne garantit pas — indispensable
pour prouver la conformité plutôt que de ne vérifier que le comportement du client.

Quatre scénarios sont couverts :

| Scénario | Configuration client | Résultat attendu |
|---|---|---|
| Nominal | bon user/password, STARTTLS actif | envoi réussi, message reçu |
| Mauvais mot de passe | bon user, mauvais password | `MailAuthenticationException`, rien reçu |
| Aucune authentification ni STARTTLS | pas d'identifiants, STARTTLS désactivé | `MailSendException` (serveur exige TLS), rien reçu |
| STARTTLS obligatoire non supporté par le serveur | `starttls.required=true` contre un serveur sans TLS | `MailSendException`, rien reçu |

Ces exceptions sont celles de Spring (`org.springframework.mail.*`) : `JavaMailSenderImpl`
encapsule les exceptions `javax.mail` sous-jacentes, et `MailServiceImpl` les laisse
remonter telles quelles à l'appelant.

> À noter : même avec `mail.smtp.starttls.enable=false`, JavaMail relance automatiquement
> une négociation STARTTLS si le serveur rejette une tentative d'authentification en clair
> avec *"Must issue a STARTTLS command first"*. Un mot de passe n'est donc jamais vérifié
> en clair dès qu'un identifiant est fourni — c'est pour ça que le scénario "sans TLS"
> ci-dessus ne fournit délibérément aucun identifiant, seul moyen d'obtenir un vrai rejet
> en clair côté serveur.

## Un peu plus sur Testcontainers

Testcontainers est une bibliothèque Java permettant de disposer d'un conteneur Docker pour
les tests, démarré et arrêté automatiquement autour de leur exécution.

Dépendance minimale pour l'utiliser (déjà présente dans le `pom.xml` de ce projet) :

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.21.3</version>
    <scope>test</scope>
</dependency>
```

Elle permet de créer un conteneur générique à partir de n'importe quelle image Docker :

```java
private static final GenericContainer<?> POSTGRE_SQL_TEST_CONTAINER =
    new GenericContainer<>("postgres:16")
        .withExposedPorts(5432);
```

Testcontainers :

- se base par défaut sur Docker Hub pour récupérer les images ;
- détecte automatiquement l'environnement Docker cible ;
- vérifie la bonne configuration du système au démarrage ;
- nettoie les conteneurs à la fin de l'exécution (via son sidecar Ryuk) ;
- supporte JUnit 4 et 5.

### Modules "prêts à l'usage"

Au-delà de `GenericContainer`, [testcontainers.com/modules](https://testcontainers.com/modules/)
propose des modules déjà paramétrés pour des technologies précises (port par défaut, login,
mot de passe...) : bases de données, files de messages, serveurs web, etc. Il est aussi
possible de construire une image entièrement personnalisée à la volée.

## Références

- [Mailpit](https://mailpit.axllent.org/) — le serveur SMTP de test utilisé ici.
- [Testcontainers](https://testcontainers.com/) — la bibliothèque de conteneurs jetables pour les tests.
- [`.claude/authentification-smtp.md`](.claude/authentification-smtp.md) — le plan de mise en conformité SMTP (contexte, exigences, démarche complète).
