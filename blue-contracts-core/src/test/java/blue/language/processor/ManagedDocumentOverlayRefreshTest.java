package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedDocumentOverlayRefreshTest {
    @Test
    void continuationRefreshReachesAlreadyCreatedTransientViewsAndRejectsTheOldPathBinding() {
        Node before = new Node().name("Before"), after = new Node().name("After");
        String beforeId = id(before), afterId = id(after);
        ManagedDocumentOverlaySnapshotManager manager = manager(beforeId, before);
        ManagedDocumentOverlaySnapshotManager transientView = (ManagedDocumentOverlaySnapshotManager) manager.transientSequence();
        ManagedDocumentOverlaySnapshotManager fork = (ManagedDocumentOverlaySnapshotManager) transientView.forkTransientSequence();
        assertEquals("Before", fork.materializeVerifiedManagedRead("/child", reference(beforeId)).getName());
        manager.refresh(overlay(afterId, after));
        for (ManagedDocumentOverlaySnapshotManager current : Arrays.asList(manager, transientView, fork)) {
            assertEquals("After", current.materializeVerifiedManagedRead("/child", reference(afterId)).getName());
            assertThrows(InvalidExecutionEvidenceException.class, () -> current.materializeVerifiedManagedRead("/child", reference(beforeId)));
        }
    }

    @Test
    void refreshedMissingManagedContentRemainsANamedNoncommittingNeedWithoutAmbientFallback() {
        Node before = new Node().name("Before"), after = new Node().name("After");
        String afterId = id(after);
        ManagedDocumentOverlaySnapshotManager manager = manager(id(before), before);
        manager.refresh(new ManagedDocumentResolutionOverlay(Collections.emptyMap(), Collections.singletonMap("/child", afterId),
                Collections.singleton(afterId)));
        assertThrows(ExecutionEvidenceUnavailableException.class, () -> manager.materializeVerifiedManagedRead("/child", reference(afterId)));
        assertThrows(ExecutionEvidenceUnavailableException.class, () -> manager.materializeVerifiedExactReference(reference(afterId)));
    }

    @Test
    void refreshIsInvocationLocalAndDoesNotMutateTheCapturedOverlay() {
        Node before = new Node().name("Before"), after = new Node().name("After");
        ManagedDocumentResolutionOverlay initial = overlay(id(before), before);
        ManagedDocumentOverlaySnapshotManager first = new ManagedDocumentOverlaySnapshotManager(new NoAmbientLookup(), initial);
        ManagedDocumentOverlaySnapshotManager second = new ManagedDocumentOverlaySnapshotManager(new NoAmbientLookup(), initial);
        first.refresh(overlay(id(after), after));
        first.refresh(null);
        assertEquals("After", first.materializeVerifiedManagedRead("/child", reference(id(after))).getName());
        assertEquals("Before", second.materializeVerifiedManagedRead("/child", reference(id(before))).getName());
        assertEquals(id(before), initial.expectedManagedBlueIdsByPath().get("/child"));
    }

    private static ManagedDocumentOverlaySnapshotManager manager(String id, Node value) {
        return new ManagedDocumentOverlaySnapshotManager(new NoAmbientLookup(), overlay(id, value));
    }
    private static ManagedDocumentResolutionOverlay overlay(String id, Node value) {
        return new ManagedDocumentResolutionOverlay(Collections.singletonMap(id, value), Collections.singletonMap("/child", id));
    }
    private static String id(Node value) { return DirectBlueIdCalculator.calculateBlueId(value); }
    private static FrozenNode reference(String id) { return FrozenNode.fromNode(new Node().blueId(id)); }
    private static final class NoAmbientLookup implements ProcessingSnapshotManager {
        @Override public ResolvedSnapshot fromDocument(Node document) { throw new AssertionError("Ambient content lookup"); }
        @Override public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) { throw new AssertionError("Unexpected patch"); }
        @Override public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) { throw new AssertionError("Unexpected cache write"); }
    }
}
