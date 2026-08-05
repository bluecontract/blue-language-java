package blue.language.processor;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class DocumentProcessingRuntimeOwnershipTest {

    @Test
    void shouldKeepOperationalCountersOwnedByOneInvocation() {
        // given
        DocumentProcessingRuntime first =
                new DocumentProcessingRuntime(new Node());
        DocumentProcessingRuntime second =
                new DocumentProcessingRuntime(new Node());

        // when
        first.applyPatch(
                "/",
                JsonPatch.add("/first", new Node().value("applied")));

        // then
        assertNotSame(first.countersForTest(), second.countersForTest());
        assertSame(first.counters(), first.countersForTest());
        assertEquals(1L, first.countersForTest().batchPatchCalls());
        assertEquals(0L, second.countersForTest().batchPatchCalls());
    }

    @Test
    void shouldReleasePreparedTransactionOwnershipExactlyOnce() {
        // given
        TrackingSnapshotManager manager = new TrackingSnapshotManager();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(new Node(), null, manager);
        PreparedPatchTransaction transaction = runtime.preparePatchSequence(
                "/",
                Collections.singletonList(
                        JsonPatch.add("/value", new Node().value(1))),
                null);

        // when
        transaction.applyNext(0);
        ProcessingSnapshotManager activeDuringTransaction =
                runtime.activeSequenceSnapshotManager;
        transaction.close();
        transaction.close();

        // then
        assertEquals(1, manager.openCalls);
        assertEquals(1, manager.releaseCalls);
        assertSame(manager.openedScope, activeDuringTransaction);
        assertNull(runtime.activeSequenceSnapshotManager);
        assertEquals(1L,
                runtime.countersForTest().patchSequencesPrepared());
    }

    private static final class TrackingSnapshotManager
            implements ProcessingSnapshotManager {

        private int openCalls;
        private int releaseCalls;
        private ProcessingSnapshotManager openedScope;

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            FrozenNode canonical =
                    FrozenNode.fromUncheckedCanonicalNode(document.clone());
            return new ResolvedSnapshot(
                    canonical,
                    FrozenNode.fromResolvedNode(document.clone()),
                    canonical.blueId());
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            CanonicalPatchResult result = new CanonicalOverlayPatchEngine(
                    snapshot.frozenCanonicalRoot()).apply(patch);
            return new ResolvedSnapshot(
                    result.root(),
                    FrozenNode.fromResolvedNode(result.root().toNode()),
                    result.blueId());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return snapshot;
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            openCalls++;
            openedScope = new TrackingSequenceScope(this);
            return openedScope;
        }
    }

    private static final class TrackingSequenceScope
            implements ProcessingSnapshotManager {

        private final TrackingSnapshotManager owner;
        private boolean released;

        private TrackingSequenceScope(TrackingSnapshotManager owner) {
            this.owner = owner;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return owner.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return owner.applyPatch(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            return owner.cacheSnapshot(snapshot);
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            return this;
        }

        @Override
        public void releaseTransientState() {
            if (!released) {
                released = true;
                owner.releaseCalls++;
            }
        }
    }
}
