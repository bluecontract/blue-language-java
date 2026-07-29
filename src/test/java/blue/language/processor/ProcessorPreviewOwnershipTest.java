package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetProperty;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorPreviewOwnershipTest {

    @Test
    void shouldVerifySuccessfulBufferingTransfersAndReleasesPreviewOwnership() {
        // given
        TrackingSnapshotManager manager = new TrackingSnapshotManager();
        Fixture fixture = fixture(manager);
        List<JsonPatch> patches = Collections.singletonList(
                JsonPatch.add("/applied", new Node().value("committed")));
        WorkingDocument.Preview preview = preview(fixture.context, patches);

        // when
        fixture.context.applyPreviewedPatches(patches, preview);
        fixture.context.applyBufferedEffects();

        // then
        assertEquals("committed", fixture.execution.runtime().document().getAsText("/applied"));
        assertNull(preview.patch(0));
        assertEquals(manager.openCalls, manager.releaseCalls);
    }

    @Test
    void shouldVerifyFatalExitReleasesBufferedPreview() {
        // given
        TrackingSnapshotManager manager = new TrackingSnapshotManager();
        Fixture fixture = fixture(manager);
        List<JsonPatch> patches = Collections.singletonList(
                JsonPatch.add("/notApplied", new Node().value(1)));
        WorkingDocument.Preview preview = preview(fixture.context, patches);

        // when
        fixture.context.applyPreviewedPatches(patches, preview);
        Throwable fatalFailure = captureFailure(
                () -> fixture.context.throwFatal(
                        "fatal after rejected anonymous gas"));

        // then
        assertInstanceOf(ProcessorFatalException.class,
                fatalFailure);
        assertNull(preview.patch(0));
        assertEquals(manager.openCalls, manager.releaseCalls);
        assertNull(nodeAt(fixture.execution.runtime().document(), "/notApplied"));
    }

    @Test
    void shouldVerifyProtectedStatePreviewFailureDoesNotLeakItselfOrEarlierBufferedPreview() {
        // given
        TrackingSnapshotManager manager = new TrackingSnapshotManager();
        Fixture fixture = fixture(manager);
        List<JsonPatch> reserved = Collections.singletonList(
                JsonPatch.add("/contracts/checkpoint", new Node().value("forbidden")));
        List<JsonPatch> later = Collections.singletonList(
                JsonPatch.add("/notApplied", new Node().value(2)));
        WorkingDocument.Preview laterPreview = preview(fixture.context, later);

        // when
        fixture.context.applyPreviewedPatches(later, laterPreview);
        Throwable previewFailure = captureFailure(
                () -> preview(fixture.context, reserved));
        Throwable fatalFailure = captureFailure(
                () -> fixture.context.throwFatal(
                        "abort after protected-state rejection"));

        // then
        assertInstanceOf(ProcessorFailureException.class,
                previewFailure);
        assertInstanceOf(ProcessorFatalException.class,
                fatalFailure);
        assertNull(laterPreview.patch(0));
        assertEquals(manager.openCalls, manager.releaseCalls);
        assertNull(nodeAt(fixture.execution.runtime().document(), "/notApplied"));
    }

    @Test
    void shouldVerifyHandlerExceptionReleasesPreviewRetainedByBufferedEffects() {
        // given
        TrackingSnapshotManager manager = new TrackingSnapshotManager();
        PreviewThenThrowProcessor handler = new PreviewThenThrowProcessor();
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(handler)
                .build();
        DocumentProcessor owner = DocumentProcessor.builder()
                .withRegistry(registry)
                .withSnapshotManager(manager)
                .build();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, new Node());
        SetProperty contract = new SetProperty();
        contract.setChannelKey("events");
        ContractBundle bundle = ContractBundle.builder()
                .addHandler("throwing", contract)
                .build();
        ChannelRunner runner = new ChannelRunner(owner,
                execution,
                execution.runtime(),
                new CheckpointManager(execution.runtime()));

        // when
        Throwable runFailure = captureFailure(
                () -> runner.runHandlers(
                        "/", bundle, "events", new Node()));

        // then
        assertInstanceOf(RunTerminationException.class,
                runFailure);
        assertTrue(handler.preview != null);
        assertNull(handler.preview.patch(0));
        assertEquals(manager.openCalls, manager.releaseCalls);
        assertNull(nodeAt(execution.runtime().document(), "/notApplied"));
    }

    @Test
    void shouldVerifyFailedWorkingPreviewRetainReleasesTheUnreturnedFork() {
        // given
        TrackingSnapshotManager manager = new TrackingSnapshotManager();
        manager.failNextRetain = true;
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node(), null, manager);

        // when
        Throwable retainFailure = captureFailure(() -> {
            try (WorkingDocument working = runtime.workingDocument("/")) {
                working.previewAndApplyPatches(Collections.singletonList(
                        JsonPatch.add("/value", new Node().value(1))));
            }
        });
        int openCalls = manager.openCalls;
        int releaseCalls = manager.releaseCalls;

        // then
        assertInstanceOf(IllegalStateException.class,
                retainFailure);
        assertEquals(2, openCalls,
                "one working scope and one handoff fork must have opened");
        assertEquals(openCalls, releaseCalls,
                "both the failed fork and the working scope must be released");
    }

    @Test
    void shouldVerifyFailedFinalPromotionReleasesItsScopeAndSecondCloseRetriesInANewScope() {
        // given
        TrackingSnapshotManager manager = new TrackingSnapshotManager();
        manager.failNextCacheSnapshot = true;
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document, null, manager);
        List<JsonPatch> patches = java.util.Arrays.asList(
                JsonPatch.add("/prefix", new Node().value("committed")),
                JsonPatch.add("/suffix", new Node().value("not-consumed")));
        DocumentProcessingRuntime.PreparedPatchSequence sequence =
                runtime.preparePatchSequence("/", patches, null);

        // when
        sequence.applyNext(0);
        Throwable firstCloseFailure =
                captureFailure(sequence::close);
        String committedPrefix =
                document.getAsText("/prefix");
        int openCallsAfterFailure =
                manager.openCalls;
        int releaseCallsAfterFailure =
                manager.releaseCalls;
        sequence.close();
        int cacheSnapshotAttempts =
                manager.cacheSnapshotAttempts;
        int finalOpenCalls = manager.openCalls;
        int finalReleaseCalls = manager.releaseCalls;
        long finalSnapshotCacheInserts =
                runtime.sequenceFinalSnapshotCacheInsertsForTest();

        // then
        assertInstanceOf(IllegalStateException.class,
                firstCloseFailure);
        assertEquals("committed", committedPrefix);
        assertEquals(1, openCallsAfterFailure);
        assertEquals(1, releaseCallsAfterFailure);
        assertEquals(2, cacheSnapshotAttempts);
        assertEquals(2, finalOpenCalls,
                "retry must open a fresh transient publication scope");
        assertEquals(finalOpenCalls, finalReleaseCalls);
        assertEquals(1, finalSnapshotCacheInserts);
    }

    @Test
    void shouldVerifyClosedContextRejectsLatePreviewTransferAndCloseRemainsIdempotent() {
        // given
        TrackingSnapshotManager manager = new TrackingSnapshotManager();
        Fixture fixture = fixture(manager);
        List<JsonPatch> patches = Collections.singletonList(
                JsonPatch.add("/late", new Node().value("not accepted")));
        WorkingDocument.Preview preview = preview(fixture.context, patches);

        // when
        fixture.context.close();
        fixture.context.close();
        Throwable lateTransferFailure = captureFailure(
                () -> fixture.context.applyPreviewedPatches(patches, preview));
        boolean callerStillOwnsPreview =
                preview.patch(0) != null;
        preview.close();
        int openCalls = manager.openCalls;
        int releaseCalls = manager.releaseCalls;
        Node lateValue = nodeAt(
                fixture.execution.runtime().document(),
                "/late");

        // then
        assertInstanceOf(IllegalStateException.class,
                lateTransferFailure);
        assertTrue(callerStillOwnsPreview,
                "rejected transfer must leave preview ownership with the caller");
        assertEquals(openCalls, releaseCalls);
        assertNull(lateValue);
    }

    private Fixture fixture(TrackingSnapshotManager manager) {
        DocumentProcessor processor = DocumentProcessor.builder()
                .withSnapshotManager(manager)
                .build();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(
                processor, new Node());
        ProcessorExecutionContext context = execution.createContext(
                "/", ContractBundle.empty(), new Node(), false);
        return new Fixture(execution, context);
    }

    private WorkingDocument.Preview preview(ProcessorExecutionContext context,
                                            List<JsonPatch> patches) {
        try (WorkingDocument working = context.newWorkingDocument()) {
            return working.previewAndApplyPatches(patches);
        }
    }

    private static Node nodeAt(Node document, String path) {
        try {
            return document.getNode(path);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final class Fixture {
        private final ProcessorEngine.Execution execution;
        private final ProcessorExecutionContext context;

        private Fixture(ProcessorEngine.Execution execution,
                        ProcessorExecutionContext context) {
            this.execution = execution;
            this.context = context;
        }
    }

    private static final class PreviewThenThrowProcessor
            implements HandlerProcessor<SetProperty> {
        private WorkingDocument.Preview preview;

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            List<JsonPatch> patches = Collections.singletonList(
                    JsonPatch.add("/notApplied", new Node().value(1)));
            try (WorkingDocument working = context.newWorkingDocument()) {
                preview = working.previewAndApplyPatches(patches);
            }
            context.applyPreviewedPatches(patches, preview);
            throw new IllegalStateException("handler failed after buffering preview");
        }
    }

    private static final class TrackingSnapshotManager
            implements ProcessingSnapshotManager {
        private int openCalls;
        private int releaseCalls;
        private int cacheSnapshotAttempts;
        private boolean failNextRetain;
        private boolean failNextCacheSnapshot;

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            FrozenNode canonical = FrozenNode.fromUncheckedCanonicalNode(document.clone());
            return new ResolvedSnapshot(canonical,
                    FrozenNode.fromResolvedNode(document.clone()),
                    canonical.blueId());
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            CanonicalPatchResult patched = snapshot.applyCanonicalPatch(patch);
            return new ResolvedSnapshot(patched.root(),
                    FrozenNode.fromResolvedNode(patched.root().toNode()),
                    patched.blueId());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            cacheSnapshotAttempts++;
            if (failNextCacheSnapshot) {
                failNextCacheSnapshot = false;
                throw new IllegalStateException("simulated final cache failure");
            }
            return snapshot;
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            openCalls++;
            return new TrackingScope(this);
        }
    }

    private static final class TrackingScope implements ProcessingSnapshotManager {
        private final TrackingSnapshotManager owner;
        private boolean released;

        private TrackingScope(TrackingSnapshotManager owner) {
            this.owner = owner;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return owner.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return owner.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
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
        public ProcessingSnapshotManager forkTransientSequence() {
            owner.openCalls++;
            return new TrackingScope(owner);
        }

        @Override
        public void retainTransientState(FrozenNode canonicalRoot, FrozenNode resolvedRoot) {
            if (owner.failNextRetain) {
                owner.failNextRetain = false;
                throw new IllegalStateException("simulated retain failure");
            }
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
