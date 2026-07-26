package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessingSnapshotManagerPreservationTest {

    @Test
    void defaultFailsClosedForNonemptyPreservationRequest() {
        CountingManager manager = new CountingManager();

        assertThrows(UnsupportedOperationException.class,
                () -> manager.fromDocumentPreservingPaths(
                        new Node(),
                        Collections.singleton("/contracts/h/result")));
        assertEquals(0, manager.fromDocumentCalls);
    }

    @Test
    void emptyPreservationRequestUsesOrdinaryResolution() {
        CountingManager manager = new CountingManager();
        Node document = new Node().value("ordinary");

        ResolvedSnapshot result =
                manager.fromDocumentPreservingPaths(
                        document, Collections.emptyList());

        assertEquals(1, manager.fromDocumentCalls);
        assertEquals("ordinary", result.resolvedRoot().getValue());
    }

    @Test
    void transientPreservationDelegatesToSingleAwareOverride() {
        PreservationAwareManager manager =
                new PreservationAwareManager();
        Node document = new Node().value("deferred");

        ResolvedSnapshot result =
                manager.fromDocumentTransientPreservingPaths(
                        document, Collections.singleton("/body"));

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
                BlueIdCalculator.calculateBlueId(canonical));
    }
}
