package com.jobfinder.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * ASVS 2.10.4 and 14.1.3: no secret has a usable default anywhere it is configured. A secret-looking variable
 * (PASSWORD, SECRET, TOKEN, KEY in its name) may default to nothing, or be required with {@code :?}; it may not
 * default to a value. Reads the files in the repository, so a regression is caught without starting anything.
 */
class SecretsConfigTests {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Pattern PLACEHOLDER = Pattern
            .compile("\\$\\{([A-Z0-9_]*(?:PASSWORD|SECRET|TOKEN|KEY)[A-Z0-9_]*)(:-|:\\?|:)([^}]*)}");
    private static final Pattern SECRET_ASSIGNMENT = Pattern
            .compile("(?i)^\\s*(?:ENV\\s+)?[A-Z0-9_]*(?:PASSWORD|SECRET|TOKEN|API_KEY|ACCESS_KEY)[A-Z0-9_]*\\s*[:=]\\s*(\\S+)");

    private static List<String> defaultedSecrets(Path file) throws IOException {
        List<String> found = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(Files.readString(file));
        while (m.find()) {
            boolean required = m.group(2).equals(":?");
            if (!required && !m.group(3).isEmpty()) {
                found.add(file.getFileName() + ": " + m.group(1) + " defaults to a value");
            }
        }
        return found;
    }

    @Test
    void noSecretInApplicationConfigOrComposeHasAUsableDefault() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String relative : List.of("services/core-api/src/main/resources/application.yml",
                "services/core-api/src/main/resources/application-prod.yml", "infra/docker-compose.yml")) {
            violations.addAll(defaultedSecrets(REPO.resolve(relative)));
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void thePasswordsOfTheDatabaseAndTheBrokerAreRequired() throws IOException {
        String yaml = Files.readString(REPO.resolve("services/core-api/src/main/resources/application.yml"));
        String compose = Files.readString(REPO.resolve("infra/docker-compose.yml"));

        assertThat(yaml).contains("${POSTGRES_PASSWORD}").contains("${RABBITMQ_PASSWORD}");
        assertThat(compose).contains("${POSTGRES_PASSWORD:?").contains("${RABBITMQ_PASSWORD:?")
                .contains("${JWT_SECRET:?").contains("${AI_SERVICE_TOKEN:?");
    }

    @Test
    void envExampleHoldsOnlyPlaceholdersForSecrets() throws IOException {
        List<String> real = new ArrayList<>();
        for (String line : Files.readAllLines(REPO.resolve(".env.example"))) {
            if (line.startsWith("#")) {
                continue;
            }
            Matcher m = SECRET_ASSIGNMENT.matcher(line);
            if (m.find() && !m.group(1).startsWith("change-me")) {
                real.add(line.substring(0, line.indexOf('=')));
            }
        }
        assertThat(real).isEmpty();
    }

    @Test
    void dockerfilesAndWorkflowsInlineNoSecret() throws IOException {
        List<String> inlined = new ArrayList<>();
        try (Stream<Path> files = Stream.concat(Files.list(REPO.resolve("infra/docker")),
                Files.list(REPO.resolve(".github/workflows")))) {
            for (Path file : files.toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher m = SECRET_ASSIGNMENT.matcher(line);
                    if (m.find() && !m.group(1).startsWith("${") && !m.group(1).startsWith("$")
                            && !m.group(1).startsWith("\"${")) {
                        inlined.add(file.getFileName() + ": " + line.strip());
                    }
                }
            }
        }
        assertThat(inlined).isEmpty();
    }
}
