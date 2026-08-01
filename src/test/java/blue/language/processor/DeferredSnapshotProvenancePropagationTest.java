package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeferredSnapshotProvenancePropagationTest {

    @Test
    void shouldVerifyWorkingDocumentRetainsDeferredProvenanceAndSkipsPublication() {
        // given
        Fixture fixture = new Fixture();
        ResolvedSnapshot committed;
        boolean workingResolutionComplete;

        // when
        try (WorkingDocument working = new WorkingDocument(
                "/",
                fixture.snapshot.frozenCanonicalRoot(),
                fixture.snapshot.frozenResolvedRoot(),
                null,
                null,
                fixture.manager,
                fixture.snapshot,
                false,
                false,
                PatchSource.LEGACY_PUBLIC_API,
                NoOpProcessingObserver.INSTANCE,
                Collections.singleton("/"),
                fixture.executableBodyFields,
                fixture.snapshot.isResolutionComplete())) {
            working.applyPatch(JsonPatch.replace(
                    "/counter", new Node().value(2)));
            workingResolutionComplete =
                    working.snapshot().isResolutionComplete();
            committed = working.commitSnapshot();
        }

        // then
        assertFalse(workingResolutionComplete);
        assertFalse(committed.isResolutionComplete());
        assertEquals(2, ((Number) committed
                .canonicalAt("/counter")
                .getValue()).intValue());
        assertEquals(1, fixture.manager.preservationCalls);
        assertEquals(0, fixture.manager.eagerCalls);
        assertEquals(0, fixture.manager.cacheCalls);
        assertEquals(Collections.singleton(
                        "/contracts/handler/program"),
                fixture.manager.lastPreservedPaths);
    }

    @Test
    void shouldVerifySnapshotNativeBatchFallbackKeepsDeferredExecutableBodyLocal() {
        // given
        Fixture fixture = new Fixture();
        DocumentProcessingRuntime runtime = fixture.runtime();

        // when
        runtime.applyPatch("/", JsonPatch.add(
                "/contracts/handler/enabled",
                new Node().value(true)));

        // then
        assertFalse(runtime.snapshot().isResolutionComplete());
        assertEquals(Boolean.TRUE, runtime.snapshot()
                .canonicalAt("/contracts/handler/enabled")
                .getValue());
        assertEquals(1, fixture.manager.preservationCalls);
        assertEquals(0, fixture.manager.eagerCalls);
        assertEquals(0, fixture.manager.cacheCalls);
        assertEquals(Collections.singleton(
                        "/contracts/handler/program"),
                fixture.manager.lastPreservedPaths);
    }

    @Test
    void shouldVerifyProviderFailureTerminationSpliceInheritsBaseCompleteness() {
        // given
        Fixture fixture = new Fixture();
        fixture.manager.failPreservation = true;
        DocumentProcessingRuntime runtime = fixture.runtime();
        Node marker = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESSING_TERMINATED_MARKER))
                .properties("cause",
                        new Node().value("provider"));

        // when
        runtime.directWrite("/contracts/terminated", marker);

        // then
        assertFalse(runtime.snapshot().isResolutionComplete());
        assertNotNull(runtime.snapshot()
                .canonicalAt("/contracts/terminated"));
        assertEquals(1, fixture.manager.preservationCalls);
        assertEquals(0, fixture.manager.eagerCalls);
        assertEquals(0, fixture.manager.cacheCalls);
    }

    private static final class Fixture {
        private final ResolvedSnapshot snapshot;
        private final RecordingManager manager =
                new RecordingManager();
        private final Map<String, List<String>>
                executableBodyFields;

        private Fixture() {
            Node body = new Node().value("program");
            String bodyBlueId =
                    DirectBlueIdCalculator.calculateBlueId(body);
            Node handlerType =
                    new Node().name("Deferred Handler");
            String handlerTypeBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            handlerType);
            Node handler = new Node()
                    .type(new Node().blueId(
                            handlerTypeBlueId))
                    .properties("program",
                            new Node().blueId(bodyBlueId));
            Node document = new Node()
                    .properties("counter",
                            new Node().value(1))
                    .contracts(new Node().properties(
                            "handler", handler));
            ResolvedSnapshot complete =
                    snapshot(document);
            this.snapshot = ResolvedSnapshot
                    .withDeferredResolution(
                            complete.frozenCanonicalRoot(),
                            complete.frozenResolvedRoot());
            this.executableBodyFields =
                    Collections.singletonMap(
                            handlerTypeBlueId,
                            Collections.singletonList(
                                    "program"));
        }

        private DocumentProcessingRuntime runtime() {
            return new DocumentProcessingRuntime(
                    snapshot,
                    null,
                    null,
                    manager,
                    NoOpProcessingObserver.INSTANCE,
                    new GasMeter(),
                    executableBodyFields);
        }
    }

    private static final class RecordingManager
            implements ProcessingSnapshotManager {
        private int preservationCalls;
        private int eagerCalls;
        private int cacheCalls;
        private boolean failPreservation;
        private Set<String> lastPreservedPaths =
                Collections.emptySet();

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            eagerCalls++;
            return snapshot(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(
                Node document) {
            eagerCalls++;
            return snapshot(document);
        }

        @Override
        public ResolvedSnapshot
        fromDocumentTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            preservationCalls++;
            lastPreservedPaths = Collections.unmodifiableSet(
                    new LinkedHashSet<>(preservedPaths));
            if (failPreservation) {
                throw new IllegalArgumentException(
                        "provider unavailable for deferred executable body");
            }
            ResolvedSnapshot complete = snapshot(document);
            return ResolvedSnapshot
                    .withDeferredResolution(
                            complete.frozenCanonicalRoot(),
                            complete.frozenResolvedRoot());
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(
                ResolvedSnapshot snapshot) {
            cacheCalls++;
            if (!snapshot.isResolutionComplete()) {
                throw new AssertionError(
                        "deferred snapshot reached host cache");
            }
            return snapshot;
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return snapshot;
        }
    }

    private static ResolvedSnapshot snapshot(
            Node document) {
        Node canonical = document.clone();
        return new ResolvedSnapshot(
                canonical,
                canonical.clone(),
                DirectBlueIdCalculator.calculateBlueId(
                        canonical));
    }
}
