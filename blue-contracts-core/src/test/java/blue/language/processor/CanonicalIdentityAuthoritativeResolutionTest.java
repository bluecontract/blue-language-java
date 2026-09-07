package blue.language.processor;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CanonicalIdentityAuthoritativeResolutionTest {

    @Test
    void shouldRebuildCompleteIdentityWithoutOpeningManagedReference() {
        Node peer = new Node().properties(
                "value", new Node().value("overlay-only"));
        String peerBlueId = DirectBlueIdCalculator.calculateBlueId(peer);
        CountingMissingProvider ambient =
                new CountingMissingProvider(peerBlueId);

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(ambient)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager base =
                    new LanguageProcessingSnapshotManager(scope);
            Node source = new Node()
                    .type(new Node().name("Managed Root inline type"))
                    .properties("peer", new Node().blueId(peerBlueId));
            ManagedDocumentResolutionOverlay overlay =
                    new ManagedDocumentResolutionOverlay(
                            Collections.singletonMap(peerBlueId, peer),
                            Collections.singletonMap(
                                    "/peer", peerBlueId),
                            Collections.emptyMap());
            ProcessingSnapshotManager managed =
                    new ManagedDocumentOverlaySnapshotManager(
                            base, overlay);

            ResolvedSnapshot rebuilt = managed
                    .fromDocumentTransientForCanonicalIdentity(source);

            assertTrue(rebuilt.hasCanonicalIdentity());
            assertTrue(rebuilt.isResolutionComplete());
            assertTrue(rebuilt.canonicalTypeIdentities()
                    .hasCompleteCoverage());
            assertTrue(rebuilt.resolvedRoot().getProperties()
                    .get("peer").isReferenceOnly());
            assertEquals(peerBlueId, rebuilt.resolvedRoot()
                    .getProperties().get("peer").getBlueId());
            assertEquals(0, ambient.requestedReads.get(),
                    "the admitted overlay must precede ambient providers");
        }
    }

    @Test
    void shouldFailClosedWhenDelegateCannotResolveOverlayIdentity() {
        Node peer = new Node().value("overlay-only");
        String peerBlueId = DirectBlueIdCalculator.calculateBlueId(peer);
        NonOverlayManager base = new NonOverlayManager();
        ProcessingSnapshotManager managed =
                new ManagedDocumentOverlaySnapshotManager(
                        base,
                        new ManagedDocumentResolutionOverlay(
                                Collections.singletonMap(
                                        peerBlueId, peer),
                                Collections.singletonMap(
                                        "/peer", peerBlueId),
                                Collections.emptyMap()));

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> managed
                        .fromDocumentTransientForCanonicalIdentity(
                                new Node().properties(
                                        "peer",
                                        new Node().blueId(peerBlueId))));

        assertTrue(failure.getMessage().contains(
                "operation-local exact evidence"));
        assertEquals(0, base.ordinaryResolutions.get(),
                "overlay evidence must not be discarded for an ambient retry");
    }

    @Test
    void shouldFailBeforeResolutionWhenBoundOverlayContentIsMissing() {
        Node peer = new Node().value("missing");
        String peerBlueId = DirectBlueIdCalculator.calculateBlueId(peer);
        NonOverlayManager base = new NonOverlayManager();
        ProcessingSnapshotManager managed =
                new ManagedDocumentOverlaySnapshotManager(
                        base,
                        new ManagedDocumentResolutionOverlay(
                                Collections.<String, Node>emptyMap(),
                                Collections.singletonMap(
                                        "/peer", peerBlueId),
                                Collections.emptyMap()));

        ExecutionEvidenceUnavailableException failure = assertThrows(
                ExecutionEvidenceUnavailableException.class,
                () -> managed
                        .fromDocumentTransientForCanonicalIdentity(
                                new Node().properties(
                                        "peer",
                                        new Node().blueId(peerBlueId))));

        assertTrue(failure.getMessage().contains(peerBlueId));
        assertEquals(0, base.ordinaryResolutions.get());
    }

    @Test
    void shouldUseAuthoritativeIdentityForReferenceSafeSourceWithManager() {
        IdentityOnlyManager manager = new IdentityOnlyManager();
        Node source = new Node().properties(
                "value", new Node().value("canonical"));

        String blueId = CanonicalIdentityEvidence.sourceBlueId(
                source, manager, "test source");

        assertEquals(DirectBlueIdCalculator.calculateBlueId(source), blueId);
        assertEquals(1, manager.identityResolutions.get());
        assertEquals(0, manager.ordinaryResolutions.get());
    }

    @Test
    void shouldKeepOrdinaryReferencesColdWhileCanonicalizingInlineTypes() {
        Node cold = new Node().properties(
                "payload", new Node().value("must remain cold"));
        String coldBlueId = DirectBlueIdCalculator.calculateBlueId(cold);
        CountingMissingProvider provider =
                new CountingMissingProvider(coldBlueId);
        Node inlineType = new Node().name("Inline source type");
        Node source = new Node()
                .type(inlineType.clone())
                .properties("cold", new Node().blueId(coldBlueId));
        Node expectedCanonical = source.clone().type(
                new Node().blueId(
                        DirectBlueIdCalculator.calculateBlueId(inlineType)));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            String actual = CanonicalIdentityEvidence.sourceBlueId(
                    source,
                    new LanguageProcessingSnapshotManager(scope),
                    "inline source with cold reference");

            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            expectedCanonical),
                    actual);
            assertEquals(0, provider.requestedReads.get(),
                    "ordinary reference content must not be demanded merely "
                            + "to establish its enclosing Source identity");
        }
    }

    @Test
    void shouldCanonicalizeNestedTypesInsideExactExecutableFields() {
        Node parentType = new Node()
                .name("Executable parent type")
                .properties("inherited", new Node().value("fixed"));
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(
                parentType);
        Node inlineOperationType = new Node()
                .name("Executable operation type")
                .type(new Node().blueId(parentBlueId))
                .properties(
                        "inherited", new Node().value("fixed"),
                        "own", new Node().value("operation"));

        CanonicalTypeIdentityEvidence operationTypeEvidence;
        try (BlueLanguage bootstrap = BlueLanguage.builder()
                .nodeProvider(blueId -> parentBlueId.equals(blueId)
                        ? Collections.singletonList(parentType.clone())
                        : Collections.<Node>emptyList())
                .build();
             LanguageProcessing.Scope scope =
                     bootstrap.processing().openScope()) {
            operationTypeEvidence = scope.resolveTypeDeclarationIdentity(
                    inlineOperationType.clone());
        }
        String operationTypeBlueId = operationTypeEvidence.blueId();
        Node canonicalOperationType =
                operationTypeEvidence.canonicalTypeIdentityInput();
        NodeProvider provider = blueId -> {
            if (parentBlueId.equals(blueId)) {
                return Collections.singletonList(parentType.clone());
            }
            if (operationTypeBlueId.equals(blueId)) {
                return Collections.singletonList(
                        canonicalOperationType.clone());
            }
            return Collections.emptyList();
        };
        Node inlineRoot = rootWithExecutablePatchValue(
                inlineOperationType);
        Node referencedRoot = rootWithExecutablePatchValue(
                new Node().blueId(operationTypeBlueId));
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectBlueIdCalculator.calculateBlueId(inlineRoot),
                "direct identity must reject an authored inline type in a "
                        + "reserved type position");

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            String resultPath = "/contracts/creator/result";

            String inlineBlueId = CanonicalIdentityEvidence
                    .sourceBlueIdWithCanonicalExactFields(
                            inlineRoot,
                            manager,
                            "inline executable Root",
                            Collections.singleton(resultPath),
                            Collections.singleton(resultPath));
            String referencedBlueId = CanonicalIdentityEvidence
                    .sourceBlueIdWithCanonicalExactFields(
                            referencedRoot,
                            manager,
                            "referenced executable Root",
                            Collections.singleton(resultPath),
                            Collections.singleton(resultPath));

            assertEquals(referencedBlueId, inlineBlueId);
        }
    }

    @Test
    void shouldKeepTypeContributedExactReferenceColdDuringSourceIdentityResolution() {
        Node coldBody = new Node().properties(
                "payload", new Node().value("must remain cold"));
        String coldBodyBlueId = DirectBlueIdCalculator.calculateBlueId(
                coldBody);
        Node scopeType = new Node()
                .name("Type-contributed exact field")
                .properties(
                        "inheritedResult",
                        new Node().blueId(coldBodyBlueId));
        String scopeTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                scopeType);
        Node source = new Node().type(
                new Node().blueId(scopeTypeBlueId));
        AtomicInteger typeReads = new AtomicInteger();
        AtomicInteger coldBodyReads = new AtomicInteger();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(blueId -> {
                    if (scopeTypeBlueId.equals(blueId)) {
                        typeReads.incrementAndGet();
                        return Collections.singletonList(scopeType.clone());
                    }
                    if (coldBodyBlueId.equals(blueId)) {
                        coldBodyReads.incrementAndGet();
                        return Collections.singletonList(coldBody.clone());
                    }
                    return Collections.emptyList();
                })
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            String inheritedResultPath = "/inheritedResult";

            String actual = CanonicalIdentityEvidence
                    .sourceBlueIdWithCanonicalExactFields(
                            source,
                            new LanguageProcessingSnapshotManager(scope),
                            "type-contributed exact field",
                            Collections.singleton(inheritedResultPath),
                            Collections.singleton(inheritedResultPath));

            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(source),
                    actual);
            assertTrue(typeReads.get() > 0,
                    "the source type must still be verified");
            assertEquals(0, coldBodyReads.get(),
                    "an absent type-contributed exact field must remain cold");
        }
    }

    private static Node rootWithExecutablePatchValue(Node operationType) {
        Node operation = new Node()
                .type(operationType.clone())
                .properties("operationId", new Node().value("nested"));
        Node patch = new Node().properties(
                "op", new Node().value("add"),
                "path", new Node().value("/contracts/nested"),
                "val", operation);
        Node result = new Node().properties(
                "patches", new Node().items(patch));
        return new Node().contracts(new Node().properties(
                "creator", new Node().properties("result", result)));
    }

    @Test
    void shouldCanonicalizeDerivableFieldsBehindPureTypeReference() {
        Node type = new Node()
                .name("Fixed value type")
                .value("fixed");
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(type);
        Node source = new Node()
                .type(new Node().blueId(typeBlueId))
                .value("fixed");

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(blueId -> typeBlueId.equals(blueId)
                        ? Collections.singletonList(type.clone())
                        : Collections.<Node>emptyList())
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            String expected = manager
                    .fromDocumentTransientForCanonicalIdentity(source)
                    .blueId();

            String actual = CanonicalIdentityEvidence.sourceBlueId(
                    source, manager, "fixed value source");

            assertEquals(expected, actual);
            assertFalse(actual.equals(
                    DirectBlueIdCalculator.calculateBlueId(source)),
                    "raw Source hashing must not retain a derivable value");
        }
    }

    @Test
    void shouldRemoveResolvedBlueIdMetadataBeforeDirectIdentity() {
        Node source = new Node()
                .name("Resolved content")
                .properties("value", new Node().value("canonical"));
        String expected = DirectBlueIdCalculator.calculateBlueId(source);
        source.blueId(expected);

        String actual = CanonicalIdentityEvidence.sourceBlueId(
                source, new IdentityOnlyManager(), "resolved source");

        assertEquals(expected, actual);
    }

    @Test
    void shouldUseDirectIdentityWithoutManagerForExactReferenceSafeInput() {
        Node source = new Node().properties(
                "value", new Node().value("canonical"),
                "typed", new Node().type(
                        new Node().blueId(
                                BlueLanguageConstants.TEXT_TYPE_BLUE_ID)));

        String blueId = CanonicalIdentityEvidence.sourceBlueId(
                source, null, "test exact source");

        assertEquals(DirectBlueIdCalculator.calculateBlueId(source), blueId);
    }

    @Test
    void shouldRequireManagerForMaterializedInlineTypeIdentity() {
        Node source = new Node().type(
                new Node().name("Inline type"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> CanonicalIdentityEvidence.sourceBlueId(
                        source, null, "test inline source"));

        assertTrue(failure.getMessage().contains(
                "requires Language canonicalization evidence"));
    }

    @Test
    void shouldCanonicalizeAuthoredInlineTypeAsATypePosition() {
        Node inlineType = new Node()
                .name("Inline contract type")
                .description("Canonical declaration content");
        String expected = DirectBlueIdCalculator.calculateBlueId(
                inlineType);

        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            String actual = CanonicalIdentityEvidence.sourceTypeBlueId(
                    inlineType,
                    new LanguageProcessingSnapshotManager(scope),
                    "inline contract");

            assertEquals(expected, actual);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DirectBlueIdCalculator.calculateBlueId(
                            new Node().type(inlineType.clone())),
                    "direct identity must reject an authored inline type in "
                            + "the probe document's reserved type position");
        }
    }

    @Test
    void shouldIdentifyInlineParentConstraintsWithoutProbingAnInstance() {
        Node parent = new Node()
                .name("Required-field parent")
                .properties(
                        "requiredText",
                        new Node()
                                .type(new Node().blueId(
                                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                                .schema(new Schema()
                                        .required(true)
                                        .minLength(3)));
        Node child = new Node()
                .name("Inline child")
                .type(parent.clone());
        Node validInstance = new Node()
                .type(child.clone())
                .properties("requiredText", new Node().value("valid"));

        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            String actual = CanonicalIdentityEvidence.sourceTypeBlueId(
                    child.clone(),
                    new LanguageProcessingSnapshotManager(scope),
                    "constrained inline contract");
            ResolvedSnapshot resolved = scope.resolveTransient(validInstance);
            String expected = resolved.canonicalTypeIdentities()
                    .requireCanonicalTypeBlueId(
                            resolved.resolvedRoot().getType(),
                            resolved.sourceRoot().getType());

            assertEquals(expected, actual);
        }
    }

    @Test
    void shouldResolveDeclarationParentsThroughManagedExactOverlay() {
        Node parent = new Node()
                .name("Overlay parent")
                .properties(
                        "requiredText",
                        new Node()
                                .type(new Node().blueId(
                                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                                .schema(new Schema().required(true)));
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(parent);
        Node child = new Node()
                .name("Overlay child")
                .type(new Node().blueId(parentBlueId));
        CountingMissingProvider ambient =
                new CountingMissingProvider(parentBlueId);

        try (BlueLanguage expectedLanguage = BlueLanguage.builder()
                .nodeProvider(blueId -> parentBlueId.equals(blueId)
                        ? Collections.singletonList(parent.clone())
                        : Collections.<Node>emptyList())
                .build();
             LanguageProcessing.Scope expectedScope =
                     expectedLanguage.processing().openScope();
             BlueLanguage actualLanguage = BlueLanguage.builder()
                     .nodeProvider(ambient)
                     .build();
             LanguageProcessing.Scope actualScope =
                     actualLanguage.processing().openScope()) {
            String expected = expectedScope
                    .resolveTypeDeclarationIdentity(child.clone())
                    .blueId();
            ProcessingSnapshotManager manager =
                    new ManagedDocumentOverlaySnapshotManager(
                            new LanguageProcessingSnapshotManager(actualScope),
                            new ManagedDocumentResolutionOverlay(
                                    Collections.singletonMap(
                                            parentBlueId, parent),
                                    Collections.<String, String>emptyMap(),
                                    Collections.emptyMap()));

            String actual = CanonicalIdentityEvidence.sourceTypeBlueId(
                    child,
                    manager,
                    "overlay-backed inline contract");

            assertEquals(expected, actual);
            assertEquals(0, ambient.requestedReads.get());
        }
    }

    @Test
    void shouldUseExactOverlayWithoutManagedPathBindingsForDocumentIdentity() {
        Node parent = new Node()
                .name("Overlay parent")
                .properties(
                        "requiredText",
                        new Node()
                                .type(new Node().blueId(
                                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                                .schema(new Schema().required(true)));
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(parent);
        Node child = new Node()
                .name("Overlay child")
                .type(new Node().blueId(parentBlueId));
        Node source = new Node().type(child.clone()).properties(
                "requiredText", new Node().value("present"));
        CountingMissingProvider ambient =
                new CountingMissingProvider(parentBlueId);

        try (BlueLanguage expectedLanguage = BlueLanguage.builder()
                .nodeProvider(blueId -> parentBlueId.equals(blueId)
                        ? Collections.singletonList(parent.clone())
                        : Collections.<Node>emptyList())
                .build();
             LanguageProcessing.Scope expectedScope =
                     expectedLanguage.processing().openScope();
             BlueLanguage actualLanguage = BlueLanguage.builder()
                     .nodeProvider(ambient)
                     .build();
             LanguageProcessing.Scope actualScope =
                     actualLanguage.processing().openScope()) {
            String expected = expectedScope.resolveTransient(source).blueId();
            ProcessingSnapshotManager manager =
                    new ManagedDocumentOverlaySnapshotManager(
                            new LanguageProcessingSnapshotManager(actualScope),
                            new ManagedDocumentResolutionOverlay(
                                    Collections.singletonMap(
                                            parentBlueId, parent),
                                    Collections.<String, String>emptyMap(),
                                    Collections.emptyMap()));

            ResolvedSnapshot actual = manager
                    .fromDocumentTransientForCanonicalIdentity(source);

            assertEquals(expected, actual.blueId());
            assertEquals(0, ambient.requestedReads.get());
        }
    }

    @Test
    void shouldKeepRootBindingsWhileNestedValuesAndHostedOutputUseExactOverlay() {
        Node parent = new Node()
                .name("Overlay parent")
                .properties(
                        "requiredText",
                        new Node()
                                .type(new Node().blueId(
                                        BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                                .schema(new Schema().required(true)));
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(parent);
        Node child = new Node()
                .name("Overlay child")
                .type(new Node().blueId(parentBlueId));
        Node source = new Node().type(child.clone()).properties(
                "requiredText", new Node().value("present"));
        CountingMissingProvider ambient =
                new CountingMissingProvider(parentBlueId);

        try (BlueLanguage expectedLanguage = BlueLanguage.builder()
                .nodeProvider(blueId -> parentBlueId.equals(blueId)
                        ? Collections.singletonList(parent.clone())
                        : Collections.<Node>emptyList())
                .build();
             LanguageProcessing.Scope expectedScope =
                     expectedLanguage.processing().openScope();
             BlueLanguage actualLanguage = BlueLanguage.builder()
                     .nodeProvider(ambient)
                     .build();
             LanguageProcessing.Scope actualScope =
                     actualLanguage.processing().openScope()) {
            String expected = expectedScope.resolveTransient(source).blueId();
            ProcessingSnapshotManager manager =
                    new ManagedDocumentOverlaySnapshotManager(
                            new LanguageProcessingSnapshotManager(actualScope),
                            new ManagedDocumentResolutionOverlay(
                                    Collections.singletonMap(
                                            parentBlueId, parent),
                                    Collections.singletonMap("/peer", parentBlueId),
                                    Collections.emptyMap()));

            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> manager.fromDocumentTransientForCanonicalIdentity(source));
            ResolvedSnapshot actual = manager.forValueIdentity()
                    .fromDocumentTransientForCanonicalIdentity(source);
            assertEquals(expected, CanonicalIdentityEvidence.executableBodyBlueId(
                    source, manager, "nested executable value"));
            GasMeter gas = new GasMeter(GasSchedule.contracts10());
            RuntimeWorkSession session = new RuntimeWorkSession(
                    gas, RuntimeWorkSession.Mode.PROCESSING);
            try {
                SemanticOutputBoundary boundary = new SemanticOutputBoundary(
                        session, actualLanguage.processing().runtimeAccess(), manager, gas.semantic());
                assertEquals(expected, boundary.admit(source).blueId(),
                        "ordinary hosted values without contract fields retain the overlay");
            } finally {
                session.close();
            }
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> manager.fromDocumentTransientForCanonicalIdentity(source));
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> manager.fromDocumentTransientForCanonicalIdentity(source.clone()
                            .properties("peer", new Node().value("forged"))));

            assertEquals(expected, actual.blueId());
            assertEquals(0, ambient.requestedReads.get());
        }
    }

    @Test
    void shouldIgnoreMixedResolvedBlueIdWhenCanonicalizingAuthoredType() {
        Node semanticType = new Node()
                .name("Authored inline type")
                .description("Mixed BlueId is not reference provenance");
        Node mixed = semanticType.clone().blueId(
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().name("Unrelated content")));

        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            String actual = CanonicalIdentityEvidence.sourceTypeBlueId(
                    mixed,
                    new LanguageProcessingSnapshotManager(scope),
                    "mixed inline contract");

            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(semanticType),
                    actual);
        }
    }

    @Test
    void shouldVerifyCyclicProofWhenOverlaySuppliesTypeDeclaration() {
        Node placeholder = new Node()
                .name("Overlay cyclic type")
                .properties("self", new Node().blueId("this#0"));
        CyclicSetFinalization finalization =
                CircularSetIdentityCalculator
                        .calculateCircularSetFinalization(
                                Collections.singletonList(placeholder));
        String memberBlueId = finalization.memberBlueIdsInInputOrder().get(0);
        Node resolvedMember = placeholder.clone();
        resolvedMember.getProperties().get("self").blueId(memberBlueId);
        CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                Collections.singletonList(placeholder));
        CountingMissingProvider ambient =
                new CountingMissingProvider(memberBlueId);

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(ambient)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager manager =
                    new ManagedDocumentOverlaySnapshotManager(
                            new LanguageProcessingSnapshotManager(scope),
                            new ManagedDocumentResolutionOverlay(
                                    Collections.singletonMap(
                                            memberBlueId, resolvedMember),
                                    Collections.singletonMap("/peer", memberBlueId),
                                    Collections.singletonMap(
                                            finalization.masterBlueId(), proof)));

            assertEquals(
                    memberBlueId,
                    manager.forValueIdentity().resolveTypeDeclarationIdentity(
                            new Node().blueId(memberBlueId)).blueId());
            Node value = new Node().type(new Node().blueId(memberBlueId))
                    .properties("tag", new Node().value("present"));
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> manager.fromDocumentTransientForCanonicalIdentity(value));
            ResolvedSnapshot valueSnapshot = manager.forValueIdentity()
                    .fromDocumentTransientForCanonicalIdentity(value);
            assertEquals(memberBlueId, valueSnapshot.canonicalRoot().getType().getBlueId());
            assertEquals(0, ambient.requestedReads.get(),
                    "proof-bearing overlay must precede ambient content");
        }
    }

    @Test
    void shouldRejectCyclicOverlayMemberWithoutCompleteProof() {
        Node placeholder = new Node().properties(
                "self", new Node().blueId("this#0"));
        CyclicSetFinalization finalization =
                CircularSetIdentityCalculator
                        .calculateCircularSetFinalization(
                                Collections.singletonList(placeholder));
        String memberBlueId = finalization.memberBlueIdsInInputOrder().get(0);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ManagedDocumentResolutionOverlay(
                        Collections.singletonMap(memberBlueId, placeholder),
                        Collections.emptyMap(),
                        Collections.emptyMap()));

        assertTrue(failure.getMessage().contains("complete cyclic-set proof"));
    }

    private static final class CountingMissingProvider
            implements NodeProvider {
        private final String trackedBlueId;
        private final AtomicInteger requestedReads = new AtomicInteger();

        private CountingMissingProvider(String trackedBlueId) {
            this.trackedBlueId = trackedBlueId;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            if (trackedBlueId.equals(blueId)) {
                requestedReads.incrementAndGet();
            }
            return Collections.emptyList();
        }
    }

    private static class NonOverlayManager
            implements ProcessingSnapshotManager {
        final AtomicInteger ordinaryResolutions =
                new AtomicInteger();

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            ordinaryResolutions.incrementAndGet();
            return snapshot(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return snapshot;
        }
    }

    private static final class IdentityOnlyManager
            extends NonOverlayManager {
        private final AtomicInteger identityResolutions =
                new AtomicInteger();

        @Override
        public ResolvedSnapshot fromDocumentTransientForCanonicalIdentity(
                Node document) {
            identityResolutions.incrementAndGet();
            try (BlueLanguage language = BlueLanguage.builder().build();
                 LanguageProcessing.Scope scope =
                         language.processing().openScope()) {
                return scope.resolveTransient(document);
            }
        }
    }

    private static ResolvedSnapshot snapshot(Node source) {
        Node canonical = source.clone();
        return new ResolvedSnapshot(
                canonical,
                canonical.clone(),
                DirectBlueIdCalculator.calculateBlueId(canonical));
    }
}
