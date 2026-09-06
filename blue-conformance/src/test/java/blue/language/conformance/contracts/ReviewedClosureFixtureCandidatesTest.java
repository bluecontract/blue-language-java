package blue.language.conformance.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReviewedClosureFixtureCandidatesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void shouldRequireExplicitUniqueAllowlistedFixtures() {
        // given
        String request = "fl-adm-07-finite-cyclic-route,c-clo-05-direct-both-members";

        // when
        List<String> selected = ReviewedClosureFixtureCandidates.selectedIds(request);

        // then
        assertEquals(Arrays.asList("c-clo-05-direct-both-members", "fl-adm-07-finite-cyclic-route"), selected);
        for (String invalid : Arrays.asList("", "all", "*", "../c-clo-05-direct-both-members",
                "c-clo-05-direct-both-members,", "c-clo-05-direct-both-members,c-clo-05-direct-both-members",
                "c-clo-10-unknown", "c-clo-23-06-unknown")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ReviewedClosureFixtureCandidates.selectedIds(invalid));
        }
        List<String> secondReview = Arrays.asList("c-clo-10-split-to-singletons",
                "c-clo-11-split-into-two-cycles", "c-clo-12-frozen-edge-removal",
                "c-clo-23-00-attach-a5-retry", "c-clo-28-containing-spine-identity-gas",
                "c-clo-28-mixed-result-shape", "c-clo-35-00-remove-and-create-successor");
        assertEquals(secondReview, ReviewedClosureFixtureCandidates.selectedIds(String.join(",", secondReview)));
    }

    @Test
    void shouldRequireEmptyTemporaryOutputOutsideAnySourceTree(@TempDir Path temporary)
            throws IOException {
        // given
        Path fixturePackage = Files.createDirectory(temporary.resolve("package"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        // when
        Path verified = ReviewedClosureFixtureCandidates.validateOutputDirectory(fixturePackage, output);
        Files.createFile(output.resolve("existing.json"));
        Path packageChild = Files.createDirectory(fixturePackage.resolve("candidate"));
        Path sourceChild = Files.createDirectories(temporary.resolve("src/review"));

        // then
        assertEquals(output.toRealPath(), verified);
        for (Path rejected : Arrays.asList(output, fixturePackage, packageChild, sourceChild)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ReviewedClosureFixtureCandidates.validateOutputDirectory(fixturePackage, rejected));
        }
        assertTrue(Files.exists(output.resolve("existing.json")));
    }

    @Test
    void shouldResolveOutputSymlinksBeforeProtectingTheFixturePackage(@TempDir Path temporary)
            throws IOException {
        // given
        Path fixturePackage = Files.createDirectory(temporary.resolve("package"));

        // when
        Path alias = Files.createSymbolicLink(temporary.resolve("candidate"), fixturePackage);

        // then
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedClosureFixtureCandidates.validateOutputDirectory(fixturePackage, alias));
        assertFalse(Files.exists(fixturePackage.resolve("review-index.json")));
    }

    @Test
    void shouldKeepArrayOrderNullPresenceAndExactEscapedPathsInDeltas() throws IOException {
        // given
        ObjectNode before = (ObjectNode) JSON.readTree("{\"a/b~\":[1,2],\"gone\":null,\"number\":5}");
        ObjectNode after = (ObjectNode) JSON.readTree("{\"a/b~\":[2,1,3],\"new\":null,\"number\":5}");
        after.put("number", 5L);
        // when
        ArrayNode deltas = ReviewedClosureFixtureCandidates.differences(before, after);
        // then
        assertEquals(5, deltas.size());
        assertEquals("/a~1b~0/0", deltas.get(0).path("path").asText());
        assertEquals(1, deltas.get(0).path("before").asInt());
        assertEquals(2, deltas.get(0).path("after").asInt());
        assertFalse(deltas.get(2).path("beforePresent").asBoolean());
        assertTrue(deltas.get(2).path("afterPresent").asBoolean());
        assertEquals("/gone", deltas.get(3).path("path").asText());
        assertTrue(deltas.get(3).path("before").isNull());
        assertFalse(deltas.get(3).path("afterPresent").asBoolean());
        assertEquals("/new", deltas.get(4).path("path").asText());
    }

    @Test
    void shouldRejectChangedAttemptOrStatusWithoutAcceptingANewBaseline() throws IOException {
        // given
        ObjectNode before = (ObjectNode) JSON.readTree("{\"attemptOutcome\":\"Complete\",\"status\":\"SUCCESS\"}");

        // when
        ReviewedClosureFixtureCandidates.requireSameOutcome("fixture", before, before.deepCopy());
        ObjectNode suspended = before.deepCopy().put("attemptOutcome", "NeedsResources");
        ObjectNode failed = before.deepCopy().put("status", "GAS_LIMIT_EXCEEDED");

        // then
        for (ObjectNode rejected : Arrays.asList(suspended, failed)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ReviewedClosureFixtureCandidates.requireSameOutcome("fixture", before, rejected));
        }
        assertEquals("SUCCESS", before.path("status").asText());
    }
}
