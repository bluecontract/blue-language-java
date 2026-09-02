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

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessingRuntimeDeferredPublicationTest {

    @Test
    void shouldKeepSourceLaneSelectedWhileCanonicalIdentityRetriesAuthoritatively() {
        // given
        Node inlineType = new Node()
                .name("Inline Runtime Type")
                .properties("inherited", new Node().value("yes"));
        Node document = new Node()
                .type(inlineType.clone())
                .properties("counter", new Node().value(1));
        InlineTypeManager manager = new InlineTypeManager(inlineType);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document, null, manager);
        runtime.snapshot = manager.deferredSource(document);

        // when
        Node selected = runtime.selectedDocument();
        Node canonicalType = runtime.canonicalNodeAt("/").getType();
        FrozenNode charged = runtime.identityChargeCanonicalRoot();

        // then
        assertFalse(runtime.snapshot().hasCanonicalIdentity());
        assertEquals("Inline Runtime Type", selected.getType().getName());
        assertNull(selected.getType().getBlueId());
        assertTrue(canonicalType.isReferenceOnly());
        assertEquals(manager.typeBlueId, canonicalType.getBlueId());
        assertEquals(manager.canonical(document).blueId(), charged.blueId());
        assertEquals(1, manager.eagerCalls,
                "one authoritative retry must serve all identity reads in the state");
        assertNull(document.getType().getBlueId(),
                "identity reconstruction must not mutate Source");
    }

    @Test
    void shouldPatchSourceLaneWithoutRewritingInlineTypeRepresentation() {
        // given
        Node inlineType = new Node()
                .name("Inline Runtime Type")
                .properties("inherited", new Node().value("yes"));
        Node document = new Node()
                .type(inlineType.clone())
                .properties("counter", new Node().value(1));
        InlineTypeManager manager = new InlineTypeManager(inlineType);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document, null, manager);
        runtime.snapshot = manager.deferredSource(document);

        // when
        runtime.applyPatch(
                "/",
                JsonPatch.replace("/counter", new Node().value(2)));

        // then
        assertEquals(2, document.getAsInteger("/counter"));
        assertEquals("Inline Runtime Type", document.getType().getName());
        assertNull(document.getType().getBlueId(),
                "selected Source must retain its authored inline type");
        assertTrue(runtime.snapshot().canonicalRoot().getType()
                .isReferenceOnly());
        assertEquals(manager.typeBlueId,
                runtime.snapshot().canonicalRoot().getType().getBlueId());
    }

    @Test
    void shouldVerifyEagerSnapshotAdmissionRestoresOnlyDeclaredExecutableBody() {
        // given
        Node patchEntry = new Node()
                .properties("op",
                        new Node().value("replace"))
                .properties("path",
                        new Node().value("/value"))
                .properties("val",
                        new Node().value(1));
        Node canonicalBody = new Node()
                .properties("patches",
                        new Node().items(
                                Collections.singletonList(
                                        patchEntry)));
        Node handlerType =
                new Node().name("Snapshot Handler");
        String handlerTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        handlerType);
        Node handler = new Node()
                .type(new Node().blueId(
                        handlerTypeBlueId))
                .properties(
                        "result",
                        canonicalBody);
        Node child = new Node()
                .contracts(new Node().properties(
                        "handler",
                        handler.clone()));
        Node canonical = new Node()
                .properties("ordinary",
                        new Node().value("unchanged"))
                .properties("child", child)
                .contracts(new Node()
                        .properties(
                                "embedded",
                                new Node()
                                        .type(new Node().blueId(
                                                RuntimeBlueIds
                                                        .PROCESS_EMBEDDED))
                                        .properties(
                                                "paths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/child"))))
                        .properties(
                        "handler",
                        handler));
        Node eagerlyResolved = canonical.clone();
        eagerlyResolved.getProperties()
                .get("ordinary")
                .name("resolved-only");
        eagerlyResolved.getContracts()
                .getProperties().get("handler")
                .getProperties().get("result")
                .getProperties().get("patches")
                .getItems().get(0)
                .type(new Node().blueId(
                        RuntimeBlueIds.JSON_PATCH_ENTRY));
        eagerlyResolved.getProperties().get("child")
                .getContracts()
                .getProperties().get("handler")
                .getProperties().get("result")
                .getProperties().get("patches")
                .getItems().get(0)
                .type(new Node().blueId(
                        RuntimeBlueIds.JSON_PATCH_ENTRY));
        String canonicalBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        canonical);
        ResolvedSnapshot eagerSnapshot =
                new ResolvedSnapshot(
                        FrozenNode.fromNode(canonical),
                        FrozenNode.fromResolvedNode(
                                eagerlyResolved),
                        canonicalBlueId);
        RecordingManager manager =
                new RecordingManager(false);

        // when
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        eagerSnapshot,
                        null,
                        null,
                        manager,
                        NoOpProcessingObserver.INSTANCE,
                        new GasMeter(),
                        Collections.singletonMap(
                                handlerTypeBlueId,
                                Collections.singletonList(
                                        "result")));
        ResolvedSnapshot runtimeSnapshot = runtime.snapshot();
        Node resolvedRootBody =
                runtime.resolvedNodeAt(
                        "/contracts/handler/result");
        Node resolvedRootPatch =
                runtime.resolvedNodeAt(
                        "/contracts/handler/result/patches/0");
        Node resolvedChildPatch =
                runtime.resolvedNodeAt(
                        "/child/contracts/handler/result/patches/0");
        Node resolvedOrdinary =
                runtime.resolvedNodeAt("/ordinary");

        // then
        assertFalse(runtimeSnapshot.isResolutionComplete());
        assertEquals(canonicalBlueId, runtimeSnapshot.blueId());
        assertEquals(canonicalBlueId,
                runtimeSnapshot
                        .frozenCanonicalRoot()
                        .blueId());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        canonicalBody),
                DirectBlueIdCalculator.calculateBlueId(
                        resolvedRootBody));
        assertNull(resolvedRootPatch.getType());
        assertNull(resolvedChildPatch.getType());
        assertEquals("resolved-only", resolvedOrdinary.getName());
        assertEquals(0, manager.resolutionCalls,
                "admission must reuse the supplied verified resolved lane");
    }

    @Test
    void shouldVerifySelectedDirectWriteKeepsDeferredSnapshotInvocationLocal() {
        // given
        Fixture fixture = new Fixture(true);

        // when
        fixture.runtime.directWrite(
                "/counter", new Node().value(2));

        // then
        assertEquals(2, ((Number) fixture.runtime
                .document().getProperties()
                .get("counter")
                .getValue()).intValue());
        assertFalse(fixture.runtime.snapshot()
                .isResolutionComplete());
        assertEquals(0, fixture.manager.cacheCalls,
                "runtime must not present a deferred lane to a host publication hook");
    }

    @Test
    void shouldVerifyCompleteReturningPreservationOverrideIsForcedInvocationLocal() {
        // given
        Fixture fixture = new Fixture(false);

        // when
        fixture.runtime.directWrite(
                "/counter", new Node().value(2));

        // then
        assertFalse(fixture.runtime.snapshot()
                .isResolutionComplete());
        assertEquals(0, fixture.manager.cacheCalls,
                "a host cannot publish a lane produced under nonempty preservation");
    }

    private static final class Fixture {
        private final RecordingManager manager;
        private final DocumentProcessingRuntime runtime;

        private Fixture(boolean deferred) {
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
            this.manager =
                    new RecordingManager(deferred);
            this.runtime = new DocumentProcessingRuntime(
                    document,
                    null,
                    null,
                    manager,
                    NoOpProcessingObserver.INSTANCE,
                    new GasMeter(),
                    Collections.singletonMap(
                            handlerTypeBlueId,
                            Collections.singletonList(
                                    "program")));
        }
    }

    private static final class RecordingManager
            implements ProcessingSnapshotManager {
        private final boolean deferred;
        private int cacheCalls;
        private int resolutionCalls;

        private RecordingManager(boolean deferred) {
            this.deferred = deferred;
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            resolutionCalls++;
            return complete(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                java.util.Collection<String> preservedPaths) {
            ResolvedSnapshot complete =
                    complete(document);
            return deferred
                    ? ResolvedSnapshot
                    .withDeferredResolution(
                            complete.frozenCanonicalRoot(),
                            complete.frozenResolvedRoot(),
                            complete.canonicalTypeIdentities())
                    : complete;
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

        private ResolvedSnapshot complete(Node document) {
            Node canonical = document.clone();
            return new ResolvedSnapshot(
                    canonical,
                    canonical.clone(),
                    DirectBlueIdCalculator.calculateBlueId(
                            canonical));
        }
    }

    private static final class InlineTypeManager
            implements ProcessingSnapshotManager {
        private final Node inlineType;
        private final FrozenNode inlineTypeStructure;
        private final String typeBlueId;
        private final CanonicalTypeIdentityLookup identities;
        private int eagerCalls;

        private InlineTypeManager(Node inlineType) {
            this.inlineType = inlineType.clone();
            this.inlineTypeStructure = FrozenNode.fromResolvedNode(
                    this.inlineType);
            this.typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                    this.inlineType);
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
                                    InlineTypeManager.this.inlineType
                                            .clone(),
                                    InlineTypeManager.this.inlineType
                                            .clone()));
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
                    return InlineTypeManager.this.inlineTypeStructure
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
                            && inlineTypeStructure.sameResolvedStructure(
                                    FrozenNode.fromResolvedNode(
                                            completedType))) {
                        return typeBlueId;
                    }
                    throw new IllegalStateException(
                            "Unexpected completed type in test fixture");
                }
            };
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            eagerCalls++;
            return canonical(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            eagerCalls++;
            return canonical(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return snapshot;
        }

        private ResolvedSnapshot deferredSource(Node source) {
            return ResolvedSnapshot.withDeferredSource(
                    FrozenNode.fromResolvedNode(source.clone()),
                    FrozenNode.fromResolvedNode(source.clone()),
                    CanonicalTypeIdentityLookup.incomplete());
        }

        private ResolvedSnapshot canonical(Node source) {
            Node canonical = source.clone();
            if (canonical.getType() != null
                    && !canonical.getType().isReferenceOnly()) {
                canonical.type(new Node().blueId(typeBlueId));
            }
            return ResolvedSnapshot.withCanonicalTypeIdentities(
                    FrozenNode.fromNode(canonical),
                    FrozenNode.fromResolvedNode(source.clone()),
                    identities);
        }
    }
}
