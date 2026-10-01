package com.jobfinder.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobfinder.core.documents.internal.DocumentDtos.Change;
import com.jobfinder.core.documents.internal.DocumentDtos.ChangeState;
import com.jobfinder.core.shared.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The draft's content is always the source with the accepted changes applied; flags are tied to their change. */
class ChangeMaterializerTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode read(String path) {
        try (InputStream in = ChangeMaterializerTests.class.getResourceAsStream(path)) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final JsonNode source = read("/documents/source-resume.json");
    private final JsonNode tailoring = read("/ai-service/tailor-resume-ok.json");

    private List<Change> changesOf(JsonNode response) {
        List<Change> changes = new ArrayList<>();
        for (JsonNode c : response.get("changes")) {
            changes.add(new Change(c.get("id").asString(), c.get("section").asString(), c.get("op").asString(),
                    c.get("path").asString(), nullable(c.get("before")), nullable(c.get("after")),
                    c.get("rationale").asString(), ChangeState.ACCEPTED, false));
        }
        return changes;
    }

    private static JsonNode nullable(JsonNode n) {
        return n == null || n.isNull() ? null : n;
    }

    private static Change change(String id, String section, String op, String path, JsonNode after) {
        return new Change(id, section, op, path, null, after, "r", ChangeState.ACCEPTED, false);
    }

    @Test
    void withEveryChangeAcceptedTheContentIsExactlyTheModelsResume() {
        var result = ChangeMaterializer.materialize(source, changesOf(tailoring));

        assertThat(result.content()).isEqualTo(tailoring.get("resume"));
    }

    @Test
    void withNoChangesTheContentIsTheSource() {
        assertThat(ChangeMaterializer.materialize(source, List.of()).content()).isEqualTo(source);
    }

    @Test
    void theSourceIsNeverModified() {
        JsonNode copy = source.deepCopy();

        ChangeMaterializer.materialize(source, changesOf(tailoring));

        assertThat(source).isEqualTo(copy);
    }

    @Test
    void aRejectedReplaceBringsTheSourceUnitBack() {
        List<Change> changes = changesOf(tailoring);
        changes.set(1, changes.get(1).withState(ChangeState.REJECTED));

        JsonNode content = ChangeMaterializer.materialize(source, changes).content();

        assertThat(content.get("experience").get(0)).isEqualTo(source.get("experience").get(0));
        assertThat(content.get("experience").get(1)).isEqualTo(tailoring.get("resume").get("experience").get(1));
        assertThat(content.get("summary")).isEqualTo(tailoring.get("resume").get("summary"));
    }

    @Test
    void aRejectedFieldChangeBringsTheSourceFieldBack() {
        List<Change> changes = changesOf(tailoring);
        changes.set(0, changes.get(0).withState(ChangeState.REJECTED));
        changes.set(3, changes.get(3).withState(ChangeState.REJECTED));

        JsonNode content = ChangeMaterializer.materialize(source, changes).content();

        assertThat(content.get("summary")).isEqualTo(source.get("summary"));
        assertThat(content.get("skills")).isEqualTo(source.get("skills"));
    }

    @Test
    void anAddedEntryIsInsertedAtItsPositionAndLeavesWhenRejected() {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("company", "Examplecorp");
        entry.put("title", "Engineer");
        List<Change> changes = new ArrayList<>(List.of(change("c1", "EXPERIENCE", "ADD", "experience[1]", entry)));

        var added = ChangeMaterializer.materialize(source, changes);

        assertThat(added.content().get("experience")).hasSize(3);
        assertThat(added.content().get("experience").get(1)).isEqualTo(entry);
        assertThat(added.content().get("experience").get(2)).isEqualTo(source.get("experience").get(1));
        assertThat(added.origin()).containsEntry("experience[1]", "c1");

        changes.set(0, changes.get(0).withState(ChangeState.REJECTED));
        assertThat(ChangeMaterializer.materialize(source, changes).content()).isEqualTo(source);
    }

    @Test
    void aRemovedEntryIsDroppedAndComesBackWhenRejected() {
        List<Change> changes = new ArrayList<>(
                List.of(change("c1", "EXPERIENCE", "REMOVE", "experience[0]", null)));

        var removed = ChangeMaterializer.materialize(source, changes);

        assertThat(removed.content().get("experience")).hasSize(1);
        assertThat(removed.content().get("experience").get(0)).isEqualTo(source.get("experience").get(1));

        changes.set(0, changes.get(0).withState(ChangeState.REJECTED));
        assertThat(ChangeMaterializer.materialize(source, changes).content()).isEqualTo(source);
    }

    @Test
    void theOriginMapNamesTheChangeBehindEachUnitAndFlagsFollowIt() {
        var result = ChangeMaterializer.materialize(source, changesOf(tailoring));

        assertThat(result.origin()).containsEntry("summary", "c1").containsEntry("experience[0]", "c2")
                .containsEntry("experience[1]", "c3").containsEntry("skills", "c4");
        assertThat(result.changeFor("experience[0].bullets[2]")).isEqualTo("c2");
        assertThat(result.changeFor("skills[7]")).isEqualTo("c4");
        assertThat(result.changeFor("summary")).isEqualTo("c1");
        assertThat(result.changeFor("education[0].degree")).isNull();
        assertThat(result.changeFor(null)).isNull();
    }

    @Test
    void positionsAndSectionsOfUnitPathsAreRecognisedAndOthersRefused() {
        assertThat(ChangeMaterializer.index("experience[12]")).isEqualTo(12);
        assertThat(ChangeMaterializer.index("summary")).isEqualTo(-1);
        assertThat(ChangeMaterializer.sectionOf("experience[0]")).isEqualTo("EXPERIENCE");
        assertThat(ChangeMaterializer.sectionOf("skills")).isEqualTo("SKILLS");
        assertThat(ChangeMaterializer.sectionOf("contact")).isNull();
        assertThat(ChangeMaterializer.sectionOf("experience[0].company")).isNull();
        assertThat(ChangeMaterializer.unitOf(source, "summary")).isEqualTo(source.get("summary"));
        assertThat(ChangeMaterializer.unitOf(source, "experience[1]")).isEqualTo(source.get("experience").get(1));
        assertThatThrownBy(() -> ChangeMaterializer.unitOf(source, "experience[5]")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ChangeMaterializer.unitOf(source, "contact")).isInstanceOf(ApiException.class);
    }

    @Test
    void editedTextsAreShapeCheckedPerSection() {
        ChangeMaterializer.validateAfter("SUMMARY", JSON.valueToTree("fine"));
        ChangeMaterializer.validateAfter("SKILLS", JSON.valueToTree(List.of("Java", "SQL")));
        assertThatThrownBy(() -> ChangeMaterializer.validateAfter("SKILLS", JSON.valueToTree("Java")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ChangeMaterializer.validateAfter("SKILLS", JSON.valueToTree(List.of(" "))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ChangeMaterializer.validateAfter("SUMMARY", JSON.valueToTree("x".repeat(2001))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ChangeMaterializer.validateAfter("EXPERIENCE", JSON.valueToTree("text")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ChangeMaterializer.validateAfter("EXPERIENCE",
                JSON.createObjectNode().put("company", "A"))).isInstanceOf(ApiException.class);
    }
}
