package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class DocumentUpdateOccurrenceTest {

    private static final String PAYLOAD_FIELD = "payload";
    private static final String PAYLOAD_POINTER = "/payload";

    @Test
    void shouldOwnExactValuesAndReturnDetachedMutableViews() {
        // given
        Node suppliedBefore = value("before");
        Node suppliedAfter = value("after");
        DocumentProcessingRuntime.DocumentUpdateData occurrence =
                new DocumentProcessingRuntime.DocumentUpdateData(
                        "/scope/value",
                        suppliedBefore,
                        suppliedAfter,
                        JsonPatch.Op.REPLACE,
                        "/scope",
                        Arrays.asList("/scope", "/"));

        // when
        suppliedBefore.properties(
                PAYLOAD_FIELD, new Node().value("changed-input"));
        suppliedAfter.properties(
                PAYLOAD_FIELD, new Node().value("changed-input"));
        Node firstBefore = occurrence.before();
        Node firstAfter = occurrence.after();
        firstBefore.properties(
                PAYLOAD_FIELD, new Node().value("changed-view"));
        firstAfter.properties(
                PAYLOAD_FIELD, new Node().value("changed-view"));
        Node repeatedBefore = occurrence.before();
        Node repeatedAfter = occurrence.after();

        // then
        assertNotSame(firstBefore, repeatedBefore);
        assertNotSame(firstAfter, repeatedAfter);
        assertEquals(
                "before",
                repeatedBefore.getAsText(PAYLOAD_POINTER));
        assertEquals(
                "after",
                repeatedAfter.getAsText(PAYLOAD_POINTER));
    }

    @Test
    void shouldDefensivelyOwnAnUnmodifiableRecipientChain() {
        // given
        List<String> suppliedChain = new ArrayList<>(
                Arrays.asList("/scope/child", "/scope", "/"));
        DocumentProcessingRuntime.DocumentUpdateData occurrence =
                new DocumentProcessingRuntime.DocumentUpdateData(
                        "/scope/child/value",
                        null,
                        new Node().value("after"),
                        JsonPatch.Op.ADD,
                        "/scope/child",
                        suppliedChain);

        // when
        suppliedChain.clear();
        Throwable mutationFailure = captureFailure(
                () -> occurrence.cascadeScopes().add("/other"));

        // then
        assertEquals(
                Arrays.asList("/scope/child", "/scope", "/"),
                occurrence.recipientChain());
        assertEquals(occurrence.recipientChain(), occurrence.cascadeScopes());
        assertEquals(
                UnsupportedOperationException.class,
                mutationFailure.getClass());
    }

    private static Node value(String value) {
        return new Node().properties(
                PAYLOAD_FIELD,
                new Node().value(value));
    }
}
