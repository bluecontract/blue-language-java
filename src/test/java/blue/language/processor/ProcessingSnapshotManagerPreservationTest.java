package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ProcessingSnapshotManagerPreservationTest {

    @Test
    void shouldVerifyDefaultFailsClosedForNonemptyPreservationRequest() {
        // given
        CountingManager manager = new CountingManager();

        // when
        UnsupportedOperationException failure =
                FailureCapture.captureFailure(
                () -> manager.fromDocumentPreservingPaths(
                        new Node(),
                        Collections.singleton("/contracts/h/result")));

        // then
        assertNotNull(failure);
        assertEquals(0, manager.fromDocumentCalls);
    }

    @Test
    void shouldVerifyEmptyPreservationRequestUsesOrdinaryResolution() {
        // given
        CountingManager manager = new CountingManager();
        Node document = new Node().value("ordinary");

        // when
        ResolvedSnapshot result =
                manager.fromDocumentPreservingPaths(
                        document, Collections.emptyList());

        // then
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals("ordinary", result.resolvedRoot().getValue());
    }

    @Test
    void shouldVerifyTransientPreservationDelegatesToSingleAwareOverride() {
        // given
        PreservationAwareManager manager =
                new PreservationAwareManager();
        Node document = new Node().value("deferred");

        // when
        ResolvedSnapshot result =
                manager.fromDocumentTransientPreservingPaths(
                        document, Collections.singleton("/body"));

        // then
        assertSame(manager.preservedSnapshot, result);
        assertEquals(1, manager.preservationCalls);
        assertEquals(0, manager.transientCalls);
    }

    private static class CountingManager
            implements ProcessingSnapshotManager {
        private int fromDocumentCalls;

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            fromDocumentCalls++;
            return snapshot(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return snapshot;
        }
    }

    private static final class PreservationAwareManager
            extends CountingManager {
        private final ResolvedSnapshot preservedSnapshot =
                snapshot(new Node().value("preserved"));
        private int preservationCalls;
        private int transientCalls;

        @Override
        public ResolvedSnapshot fromDocumentTransient(
                Node document) {
            transientCalls++;
            return snapshot(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            preservationCalls++;
            return preservedSnapshot;
        }
    }

    private static ResolvedSnapshot snapshot(Node node) {
        Node canonical = node.clone();
        return new ResolvedSnapshot(
                canonical,
                canonical.clone(),
                DirectBlueIdCalculator.calculateBlueId(canonical));
    }
}
