package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

final class DocumentProcessingRuntimeCompositionTest {

    @Test
    void shouldKeepCanonicalAndResolvedReadsRepresentationBlind() {
        // given
        Node document = new Node().properties(
                "status", new Node().value("ready"));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document);

        // when
        Node resolved = runtime.resolvedNodeAt("/status");
        Node canonical = runtime.canonicalNodeAt("/status");

        // then
        assertEquals("ready", resolved.getValue());
        assertEquals("ready", canonical.getValue());
        assertNotSame(document.getProperties().get("status"), resolved);
        assertNotSame(document.getProperties().get("status"), canonical);
    }

    @Test
    void shouldRetainSemanticDemandFirstObservationOrder() {
        // given
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(new Node());

        // when
        runtime.recordSemanticDemand("body-blue-id");
        runtime.recordPatchSemanticDemands("/scope/member");
        runtime.recordSemanticDemand("body-blue-id");

        // then
        assertEquals(
                Arrays.asList("body-blue-id", "/scope"),
                runtime.conformanceTrace().semanticDemands());
    }

    @Test
    void shouldRollbackWholeMutationSessionWhenLaterPatchFails() {
        // given
        Node document = new Node().properties(
                "status", new Node().value("ready"));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(document);

        // when
        IllegalStateException failure = captureFailure(
                () -> runtime.applyPatches(
                        "/",
                        Arrays.asList(
                                JsonPatch.replace(
                                        "/status",
                                        new Node().value("running")),
                                JsonPatch.remove("/missing"))));

        // then
        assertEquals(IllegalStateException.class, failure.getClass());
        assertEquals("ready", document.getAsText("/status"));
        assertNull(document.getProperties().get("missing"));
    }
}
