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

/** Container and compose hygiene (ASVS 14.1; ADR 0037), checked from the files so a regression needs no Docker. */
class ContainerHygieneTests {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();

    private static List<Path> dockerfiles() throws IOException {
        try (Stream<Path> files = Files.list(REPO.resolve("infra/docker"))) {
            return files.filter(f -> f.getFileName().toString().endsWith(".Dockerfile")).sorted().toList();
        }
    }

    @Test
    void everyImageRunsAsANonRootUser() throws IOException {
        List<String> root = new ArrayList<>();
        for (Path file : dockerfiles()) {
            List<String> lines = Files.readAllLines(file);
            String lastUser = null;
            for (String line : lines) {
                if (line.startsWith("FROM ")) {
                    lastUser = null; // a new stage starts as root until it says otherwise
                }
                if (line.startsWith("USER ")) {
                    lastUser = line.substring(5).trim();
                }
            }
            if (lastUser == null || lastUser.equals("root") || lastUser.equals("0")) {
                root.add(file.getFileName().toString());
            }
        }
        assertThat(root).as("final stage must set a non-root USER").isEmpty();
    }

    @Test
    void noBaseImageUsesTheLatestOrAnUntaggedReference() throws IOException {
        Pattern from = Pattern.compile("^FROM\\s+(\\S+)");
        List<String> floating = new ArrayList<>();
        for (Path file : dockerfiles()) {
            for (String line : Files.readAllLines(file)) {
                Matcher m = from.matcher(line);
                if (m.find() && !m.group(1).equals("build")) {
                    String image = m.group(1);
                    if (!image.contains(":") || image.endsWith(":latest")) {
                        floating.add(file.getFileName() + ": " + image);
                    }
                }
            }
        }
        assertThat(floating).isEmpty();
    }

    @Test
    void theDevelopmentComposeFilePublishesPortsOnLoopbackOnly() throws IOException {
        List<String> open = new ArrayList<>();
        for (String line : Files.readAllLines(REPO.resolve("infra/docker-compose.yml"))) {
            String text = line.strip();
            if (text.startsWith("- \"") && text.matches("- \"[^\"]*:\\d+\"") && !text.startsWith("- \"127.0.0.1:")
                    && !text.contains("${WEB_PORT")) {
                open.add(text);
            }
        }
        assertThat(open).as("only the web app may publish on every interface").isEmpty();
    }

    @Test
    void theProductionOverlayPointsTheCheckoutReturnAndCancelUrlsAtTheWebApp() throws IOException {
        // The application defaults are localhost URLs; in production a paying user must come back to the real site.
        String overlay = Files.readString(REPO.resolve("infra/docker-compose.prod.yml"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(application).contains("return-url: ${BILLING_RETURN_URL:").contains("cancel-url: ${BILLING_CANCEL_URL:");
        Matcher block = Pattern.compile("(?m)^  core-api:\\n((?:    .*\\n|\\n)*)").matcher(overlay);
        assertThat(block.find()).isTrue();
        assertThat(block.group(1)).contains("BILLING_RETURN_URL: ${WEB_BASE_URL}/billing/return\n")
                .contains("BILLING_CANCEL_URL: ${WEB_BASE_URL}/billing/return?canceled=1\n");
        // The route they name exists in the web app.
        assertThat(REPO.resolve("apps/web/src/app/(app)/billing/return/page.tsx")).exists();
    }

    @Test
    void theProductionOverlayResetsEveryPublishedPortExceptWebAndDropsCapabilities() throws IOException {
        String overlay = Files.readString(REPO.resolve("infra/docker-compose.prod.yml"));

        for (String service : List.of("postgres", "redis", "rabbitmq", "core-api", "ai-service")) {
            Matcher block = Pattern.compile("(?m)^  " + service + ":\\n((?:    .*\\n|\\n)*)").matcher(overlay);
            assertThat(block.find()).as(service + " is in the overlay").isTrue();
            assertThat(block.group(1)).as(service + " publishes no port").contains("ports: !reset []");
        }
        assertThat(overlay).contains("cap_drop: [\"ALL\"]").contains("no-new-privileges:true")
                .contains("read_only: true").contains("SPRING_PROFILES_ACTIVE: prod");
        Matcher web = Pattern.compile("(?m)^  web:\\n((?:    .*\\n|\\n)*)").matcher(overlay);
        assertThat(web.find()).isTrue();
        assertThat(web.group(1)).doesNotContain("ports: !reset");
    }
}
