package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredSnapshotProvenancePropagationTest {

    @Test
    void shouldRetainSourceLaneWhenCanonicalIdentityIsAlsoAvailable() {
        // given
        Node inlineType = new Node().name("Inline Source Type");
        Node source = new Node()
                .type(inlineType.clone())
                .properties("counter", new Node().value(1));
        InlineSourceManager manager = new InlineSourceManager(inlineType);
        ResolvedSnapshot sourceBacked = ResolvedSnapshot.withSource(
                FrozenNode.fromResolvedNode(source),
                FrozenNode.fromResolvedNode(source),
                manager.identities,
                true);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                sourceBacked,
                null,
                manager);

        // when
        runtime.applyPatch(
                "/",
                JsonPatch.replace(
                        "/counter",
                        new Node().value(2)));

        // then
        assertTrue(runtime.snapshot().isSourceBacked());
        assertTrue(runtime.snapshot().hasCanonicalIdentity());
        assertTrue(runtime.snapshot().isResolutionComplete());
        assertEquals(2, runtime.snapshot().sourceRoot()
                .getAsInteger("/counter"));
        assertEquals("Inline Source Type", runtime.snapshot()
                .sourceRoot().getType().getName());
        assertNull(runtime.snapshot().sourceRoot().getType().getBlueId());
        assertTrue(runtime.snapshot().canonicalRoot().getType()
                .isReferenceOnly());
        assertEquals(manager.typeBlueId, runtime.snapshot()
                .canonicalRoot().getType().getBlueId());
        assertTrue(manager.transientCalls > 0,
                "the full fallback must exercise lane preservation");
        assertTrue(manager.cachedSourceBacked,
                "publication must retain explicit Source provenance");
    }

    @Test
    void shouldForceDeferredResolutionWithoutDroppingSourceProvenance() {
        // given
        Node inlineType = new Node().name("Deferred Inline Source Type");
        Node source = new Node().type(inlineType.clone());
        InlineSourceManager manager = new InlineSourceManager(inlineType);
        ResolvedSnapshot complete = ResolvedSnapshot.withSource(
                FrozenNode.fromResolvedNode(source),
                FrozenNode.fromResolvedNode(source),
                manager.identities,
                true);

        // when
        ResolvedSnapshot deferred =
                ExecutableBodyPathCatalog.forceDeferredResolution(
                        complete);

        // then
        assertTrue(deferred.isSourceBacked());
        assertFalse(deferred.isResolutionComplete());
        assertEquals("Deferred Inline Source Type",
                deferred.sourceRoot().getType().getName());
        assertNull(deferred.sourceRoot().getType().getBlueId());
        assertEquals(manager.typeBlueId,
                deferred.canonicalRoot().getType().getBlueId());
    }

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
                            complete.frozenResolvedRoot(),
                            complete.canonicalTypeIdentities());
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
                            complete.frozenResolvedRoot(),
                            complete.canonicalTypeIdentities());
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

    private static final class InlineSourceManager
            implements ProcessingSnapshotManager {
        private final FrozenNode inlineType;
        private final String typeBlueId;
        private final CanonicalTypeIdentityLookup identities;
        private int transientCalls;
        private boolean cachedSourceBacked;

        private InlineSourceManager(Node inlineType) {
            this.inlineType = FrozenNode.fromResolvedNode(inlineType);
            this.typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                    inlineType);
            this.identities = new CanonicalTypeIdentityLookup() {
                @Override
                public boolean hasCompleteCoverage() {
                    return true;
                }

                @Override
                public Optional<CanonicalTypeIdentityEvidence>
                findCanonicalTypeIdentityEvidence(Node completedType) {
                    String blueId = requireCanonicalTypeBlueId(completedType);
                    return Optional.of(completedType.isReferenceOnly()
                            ? CanonicalTypeIdentityEvidence
                            .referenceSource(blueId)
                            : CanonicalTypeIdentityEvidence
                            .authoredInline(
                                    blueId,
                                    InlineSourceManager.this.inlineType
                                            .toNode(),
                                    InlineSourceManager.this.inlineType
                                            .toNode()));
                }

                @Override
                public Optional<CanonicalTypeIdentityEvidence>
                findCanonicalTypeIdentityEvidence(
                        Node completedType,
                        Node authoredTypeSource) {
                    CanonicalTypeIdentityEvidence evidence =
                            findCanonicalTypeIdentityEvidence(
                                    completedType).get();
                    if (authoredTypeSource == null) {
                        return Optional.of(evidence);
                    }
                    if (authoredTypeSource.isReferenceOnly()) {
                        return evidence.blueId().equals(
                                authoredTypeSource.getBlueId())
                                ? Optional.of(evidence)
                                : Optional
                                .<CanonicalTypeIdentityEvidence>empty();
                    }
                    return InlineSourceManager.this.inlineType
                            .sameResolvedStructure(
                                    FrozenNode.fromResolvedNode(
                                            authoredTypeSource))
                            ? Optional.of(evidence)
                            : Optional
                            .<CanonicalTypeIdentityEvidence>empty();
                }

                @Override
                public String requireCanonicalTypeBlueId(
                        Node completedType) {
                    if (completedType != null
                            && completedType.isReferenceOnly()) {
                        return completedType.getBlueId();
                    }
                    if (completedType != null
                            && InlineSourceManager.this.inlineType
                            .sameResolvedStructure(
                                    FrozenNode.fromResolvedNode(
                                            completedType))) {
                        return typeBlueId;
                    }
                    throw new IllegalStateException(
                            "Unexpected completed type");
                }
            };
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return canonicalOnly(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            transientCalls++;
            return canonicalOnly(document);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(
                ResolvedSnapshot snapshot) {
            cachedSourceBacked = snapshot.isSourceBacked();
            return snapshot;
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            throw new UnsupportedOperationException(
                    "test manager requires authoritative fallback");
        }

        private ResolvedSnapshot canonicalOnly(Node source) {
            Node canonical = source.clone();
            canonical.type(new Node().blueId(typeBlueId));
            return ResolvedSnapshot.withCanonicalTypeIdentities(
                    FrozenNode.fromNode(canonical),
                    FrozenNode.fromResolvedNode(source),
                    identities);
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
