package blue.language.processor;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingInputAdmissionTest {

    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.<Object>asList(
                    1, "fragment-input", 1));
    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";

    @Test
    void shouldKeepMissingInvalidRootFallbackAbsent() {
        // given

        // when
        DocumentProcessingResult result =
                ProcessingDocumentValidator.validateRaw(
                        com.fasterxml.jackson.databind.node.NullNode
                                .getInstance(),
                        null);

        // then
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status());
        assertNull(result.document());
    }

    @Test
    void shouldVerifyBlueFacadeProcessesExactPureReferenceRootAndEvent() {
        // given
        Node root = new Node()
                .properties(
                        "state",
                        new Node().value("ready"));
        Node event = new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value("none"))
                .properties(
                        "eventId",
                        new Node().value("facade-event"));
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(root);
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(
                        root, event);
        java.util.List<String> requests =
                new java.util.ArrayList<>();
        NodeProvider trackingProvider = blueId -> {
            requests.add(blueId);
            return graph.provider()
                    .fetchByBlueId(blueId);
        };

        // when
        try (Blue blue = new Blue(
                trackingProvider)) {
            DocumentProcessingResult result =
                    blue.processDocument(
                            reference(rootBlueId),
                            reference(eventBlueId));

            // then
            assertEquals(
                    ProcessorStatus.NO_MATCH,
                    result.status(),
                    result.diagnostic() == null
                            ? "processing returned no diagnostic"
                            : result.diagnostic().message());
            assertEquals(
                    rootBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            result.document()));
            assertTrue(result.events().isEmpty());
            assertEquals(
                    Arrays.asList(
                            rootBlueId, eventBlueId),
                    requests);
        }
    }

    @Test
    void shouldVerifyPublicProcessAdmitsExactRootAndEventWithoutOpeningUnrelatedReference() {
        // given
        Node unrelated = new Node().properties(
                "payload", new Node().value("must remain cold"));
        String unrelatedBlueId =
                DirectBlueIdCalculator.calculateBlueId(unrelated);
        Node root = new Node()
                .properties("state", new Node().value("ready"))
                .properties(
                        "unrelated",
                        unrelated);
        Node event = new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value("none"))
                .properties("eventId", new Node().value("E1"));
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(root);
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(root, event);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .provider(graph.provider());
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();

        // when
        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            DocumentProcessingResult result =
                    processor.processDocument(
                            reference(rootBlueId),
                            reference(eventBlueId));
            Node retained = NodePathEditor.getOrNull(
                    result.document(), "/unrelated");

            // then
            assertEquals(
                    ProcessorStatus.NO_MATCH,
                    result.status());
            assertEquals(
                    Arrays.asList(rootBlueId, eventBlueId),
                    fragments.requests());
            assertFalse(
                    fragments.requests().contains(
                            unrelatedBlueId));
            assertEquals(0, fragments.fullSnapshotBuilds());
            assertEquals(1, derivations.get());
            assertEquals(1, verifications.get());
            assertNotNull(retained);
            assertTrue(retained.isReferenceOnly());
            assertEquals(
                    unrelatedBlueId, retained.getBlueId());
        }
    }

    @Test
    void shouldVerifySnapshotEntryAdmitsPureReferenceEventOnly() {
        // given
        Node unrelated = new Node().value(
                "snapshot sibling remains cold");
        String unrelatedBlueId =
                DirectBlueIdCalculator.calculateBlueId(unrelated);
        Node root = new Node().properties(
                "unrelated",
                reference(unrelatedBlueId));
        Node event = new Node().properties(
                "subscriptionKey",
                new Node().value("none"));
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(event);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .provider(graph.provider());
        ResolvedSnapshot snapshot =
                ResolvedSnapshot.withDeferredResolution(
                        FrozenNode.fromNode(root),
                        FrozenNode.fromResolvedNode(root),
                        CanonicalTypeIdentityLookup.incomplete());

        // when
        try (DocumentProcessor processor = processor(
                fragments,
                new AtomicInteger(),
                new AtomicInteger())) {
            DocumentProcessingResult result =
                    processor.processDocument(
                            snapshot,
                            reference(eventBlueId));

            // then
            assertEquals(
                    ProcessorStatus.NO_MATCH,
                    result.status());
            assertEquals(
                    Collections.singletonList(eventBlueId),
                    fragments.requests());
            assertFalse(
                    fragments.requests().contains(
                            unrelatedBlueId));
            assertEquals(0, fragments.fullSnapshotBuilds());
        }
    }

    @Test
    void shouldVerifyScopeAdmissionOpensOnlyReferenceAncestorsOnSelectedPath() {
        // given
        Node unrelated = new Node().value(
                "unrelated root branch");
        String unrelatedBlueId =
                DirectBlueIdCalculator.calculateBlueId(unrelated);
        Node selectedSide = new Node().value(
                "unrelated selected sibling");
        String selectedSideBlueId =
                DirectBlueIdCalculator.calculateBlueId(selectedSide);
        Node nested = new Node().properties(
                "leaf", new Node().value("selected"));
        String nestedBlueId =
                DirectBlueIdCalculator.calculateBlueId(nested);
        Node selected = new Node()
                .properties(
                        "nested", nested)
                .properties(
                        "side", selectedSide);
        String selectedBlueId =
                DirectBlueIdCalculator.calculateBlueId(selected);
        Node root = new Node()
                .properties(
                        "selected", selected)
                .properties(
                        "unrelated", unrelated);
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(root);
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(root);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .provider(graph.provider());
        ProcessingInputAdmission admission =
                new ProcessingInputAdmission(fragments);

        // when
        ProcessingInputAdmission.AdmittedNode admitted =
                admission.materializeTopLevel(
                        reference(rootBlueId),
                        "Processing Root");
        admitted = admission.materializeScopePaths(
                admitted,
                Collections.singletonList(
                        "/selected/nested"));

        // then
        assertEquals(
                Arrays.asList(
                        rootBlueId,
                        selectedBlueId,
                        nestedBlueId),
                fragments.requests());
        assertEquals(
                rootBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        admitted.node()));
        assertFalse(NodePathEditor.getOrNull(
                admitted.node(),
                "/selected").isReferenceOnly());
        assertFalse(NodePathEditor.getOrNull(
                admitted.node(),
                "/selected/nested").isReferenceOnly());
        assertTrue(NodePathEditor.getOrNull(
                admitted.node(),
                "/selected/side").isReferenceOnly());
        assertTrue(NodePathEditor.getOrNull(
                admitted.node(),
                "/unrelated").isReferenceOnly());
        assertFalse(
                admission.deferredSnapshot(admitted)
                        .isResolutionComplete());
    }

    @Test
    void shouldVerifyTopLevelCyclicMemberIsRejectedWithoutProviderDemand() {
        // given
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();
        ProcessingInputAdmission admission =
                new ProcessingInputAdmission(fragments);

        // when
        InvalidExecutionEvidenceException failure =
                FailureCapture.captureFailure(
                () -> admission.materializeTopLevel(
                        reference(CYCLIC_MEMBER_BLUE_ID),
                        "Processing Root"));

        // then
        assertNotNull(failure);
        assertTrue(failure.getMessage()
                .contains("cannot be an independently processed"));
        assertEquals(
                ProcessorErrorCategory
                        .CyclicMemberProcessingRootUnsupported,
                failure.errorCategory());
        assertTrue(fragments.requests().isEmpty());
    }

    @Test
    void shouldVerifyTopLevelCyclicMemberEventHasDistinctDiagnostic() {
        // given
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();
        ProcessingInputAdmission admission =
                new ProcessingInputAdmission(fragments);

        // when
        InvalidExecutionEvidenceException failure =
                FailureCapture.captureFailure(
                () -> admission.materializeTopLevel(
                        reference(CYCLIC_MEMBER_BLUE_ID),
                        "Processing Event"));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory
                        .CyclicMemberProcessingEventUnsupported,
                failure.errorCategory());
        assertTrue(fragments.requests().isEmpty());
    }

    @Test
    void shouldVerifyMaterializedCyclicMemberEventRetainsTheTopLevelBoundary() {
        // given
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();
        ProcessingInputAdmission admission =
                new ProcessingInputAdmission(fragments);
        Node materializedMember =
                reference(CYCLIC_MEMBER_BLUE_ID)
                        .properties(
                                "body",
                                new Node().value("verified by owning set"));

        // when
        InvalidExecutionEvidenceException failure =
                FailureCapture.captureFailure(
                () -> admission.materializeTopLevel(
                        materializedMember,
                        "Processing Event"));

        // then
        assertNotNull(failure);
        assertEquals(
                ProcessorErrorCategory
                        .CyclicMemberProcessingEventUnsupported,
                failure.errorCategory());
        assertTrue(fragments.requests().isEmpty());
    }

    @Test
    void shouldVerifyTerminatedRootRejectsCyclicEventAcrossNodeAndSnapshotEntries() {
        // given
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();
        Node root = terminatedRoot();
        ResolvedSnapshot snapshot = new ResolvedSnapshot(
                root.clone(),
                root.clone(),
                DirectBlueIdCalculator.calculateBlueId(root));
        VerifiedExecutionEvidence evidence =
                evidence(root, CYCLIC_MEMBER_BLUE_ID);

        // when
        List<DocumentProcessingResult> results;
        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            results = Arrays.asList(
                    processor.processDocument(
                            root.clone(),
                            reference(CYCLIC_MEMBER_BLUE_ID)),
                    processor.processDocument(
                            root.clone(),
                            materializedCyclicMemberEvent(),
                            evidence),
                    processor.processDocument(
                            snapshot,
                            reference(CYCLIC_MEMBER_BLUE_ID)),
                    processor.processDocument(
                            snapshot,
                            materializedCyclicMemberEvent(),
                            evidence));
        }

        // then
        for (DocumentProcessingResult result : results) {
            assertCyclicEventInvalid(result);
        }
        assertTrue(fragments.requests().isEmpty());
        assertEquals(0, derivations.get());
        assertEquals(0, verifications.get());
    }

    @Test
    void shouldVerifyTerminatedRootRejectsCyclicEventAcrossAttemptEvidenceEntries() {
        // given
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();
        Node root = terminatedRoot();
        VerifiedExecutionEvidence evidence =
                evidence(root, CYCLIC_MEMBER_BLUE_ID);

        // when
        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            ProcessAttemptResult derivedAttempt =
                    processor.processAttempt(
                            root.clone(),
                            reference(CYCLIC_MEMBER_BLUE_ID));
            ProcessAttemptResult evidenceAttempt =
                    processor.processAttempt(
                            root.clone(),
                            materializedCyclicMemberEvent(),
                            evidence);

            // then
            assertEquals(
                    ProcessAttemptResult.Kind.COMPLETE,
                    derivedAttempt.kind());
            assertEquals(
                    ProcessAttemptResult.Kind.COMPLETE,
                    evidenceAttempt.kind());
            assertCyclicEventInvalid(
                    derivedAttempt.processResult());
            assertCyclicEventInvalid(
                    evidenceAttempt.processResult());
        }

        assertTrue(fragments.requests().isEmpty());
        assertEquals(0, derivations.get());
        assertEquals(0, verifications.get());
    }

    @Test
    void shouldVerifyTerminatedRootStillValidatesGenericEventBlueIdSyntax() {
        // given
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();

        // when
        try (DocumentProcessor processor = processor(
                fragments,
                new AtomicInteger(),
                new AtomicInteger())) {
            DocumentProcessingResult result =
                    processor.processDocument(
                            terminatedRoot(),
                            new Node().blueId("not-a-blue-id"));

            // then
            assertEquals(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    result.status());
            assertNotNull(result.diagnostic());
            assertEquals(
                    ProcessorErrorCategory.InvalidProcessingEvent,
                    result.diagnostic().category());
        }

        assertTrue(fragments.requests().isEmpty());
    }

    @Test
    void shouldVerifyScopeAdmissionRejectsOpaqueCyclicBoundaryBeforeProviderDemand() {
        // given
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();
        ProcessingInputAdmission admission =
                new ProcessingInputAdmission(fragments);
        ProcessingInputAdmission.AdmittedNode admitted =
                ProcessingInputAdmission.AdmittedNode.unchanged(
                        new Node().properties(
                                "cyclic",
                                reference(CYCLIC_MEMBER_BLUE_ID)));

        // when
        InvalidExecutionEvidenceException failure =
                FailureCapture.captureFailure(
                () -> admission.materializeScopePaths(
                        admitted,
                        Collections.singletonList(
                                "/cyclic/embedded")));

        // then
        assertNotNull(failure);
        assertTrue(failure.getMessage()
                .contains("cannot cross opaque cyclic-set member"));
        assertEquals(
                ProcessorErrorCategory
                        .CyclicSetEmbeddedBoundaryUnsupported,
                failure.errorCategory());
        assertTrue(fragments.requests().isEmpty());
    }

    @Test
    void shouldVerifyMismatchedExactRootEvidenceIsDeterministicallyInvalid() {
        // given
        Node expected = new Node().properties(
                "state", new Node().value("expected"));
        String requestedBlueId =
                DirectBlueIdCalculator.calculateBlueId(expected);
        Node wrong = new Node().properties(
                "state", new Node().value("wrong"));
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .uncheckedExact(
                                requestedBlueId, wrong);
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();

        // when
        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            DocumentProcessingResult result =
                    processor.processDocument(
                            reference(requestedBlueId),
                            new Node().value("event"));

            // then
            assertEquals(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    result.status());
            assertEquals(0L, result.totalGas());
            assertNotNull(result.diagnostic());
            assertTrue(result.diagnostic().message()
                    .contains(requestedBlueId));
            assertTrue(result.diagnostic().message()
                    .contains("does not match"));
            assertEquals(
                    Collections.singletonList(
                            requestedBlueId),
                    fragments.requests());
            assertEquals(0, derivations.get());
            assertEquals(0, verifications.get());
        }
    }

    @Test
    void shouldVerifyNotFoundTopLevelRootCompletesAsInvalidWithoutGas() {
        // given
        Node expected = new Node().properties(
                "state", new Node().value("not-found"));
        String requestedBlueId =
                DirectBlueIdCalculator.calculateBlueId(expected);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();

        // when
        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            ProcessAttemptResult attempt =
                    processor.processAttempt(
                            reference(requestedBlueId),
                            new Node().value("event"));

            // then
            assertEquals(
                    ProcessAttemptResult.Kind.COMPLETE,
                    attempt.kind());
            assertNotNull(attempt.processResult());
            assertEquals(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    attempt.processResult().status());
            assertEquals(0L, attempt.processResult().totalGas());
            assertEquals(Long.valueOf(0L), attempt.portableGas());
            assertTrue(attempt.requiredExactBlueIds().isEmpty());
            assertEquals(
                    Collections.singletonList(
                            requestedBlueId),
                    fragments.requests());
            assertEquals(0, derivations.get());
            assertEquals(0, verifications.get());
        }
    }

    @Test
    void shouldVerifyUnavailableTopLevelRootSuspendsAttemptBeforeGasOrEffects() {
        // given
        Node expected = new Node().properties(
                "state", new Node().value("unavailable"));
        String requestedBlueId =
                DirectBlueIdCalculator.calculateBlueId(expected);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .unavailable(requestedBlueId);
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();

        // when
        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            ProcessAttemptResult attempt =
                    processor.processAttempt(
                            reference(requestedBlueId),
                            new Node().value("event"));

            // then
            assertEquals(
                    ProcessAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind());
            assertEquals(
                    Collections.singletonList(
                            requestedBlueId),
                    attempt.requiredExactBlueIds());
            assertNull(attempt.processResult());
            assertNull(attempt.portableGas());
            assertEquals(
                    Collections.singletonList(
                            requestedBlueId),
                    fragments.requests());
            assertEquals(0, fragments.fullSnapshotBuilds());
            assertEquals(0, derivations.get());
            assertEquals(0, verifications.get());
        }
    }

    @Test
    void shouldVerifyEventNotFoundIsInvalidButEventUnavailableSuspends() {
        // given
        Node root = new Node().value("root");
        Node event = new Node().properties(
                "subscriptionKey",
                new Node().value("none"));
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(root);
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
        ExactNodeGraphFragments rootFragments =
                new ExactNodeGraphFragments(root);
        StrictFragmentSnapshotManager notFound =
                new StrictFragmentSnapshotManager()
                        .provider(rootFragments.provider());
        StrictFragmentSnapshotManager unavailable =
                new StrictFragmentSnapshotManager()
                        .provider(rootFragments.provider())
                        .unavailable(eventBlueId);

        // when
        ProcessAttemptResult notFoundAttempt;
        try (DocumentProcessor processor = processor(
                notFound,
                new AtomicInteger(),
                new AtomicInteger())) {
            notFoundAttempt =
                    processor.processAttempt(
                            reference(rootBlueId),
                            reference(eventBlueId));
        }
        ProcessAttemptResult unavailableAttempt;
        try (DocumentProcessor processor = processor(
                unavailable,
                new AtomicInteger(),
                new AtomicInteger())) {
            unavailableAttempt =
                    processor.processAttempt(
                            reference(rootBlueId),
                            reference(eventBlueId));
        }

        // then
        assertEquals(
                ProcessAttemptResult.Kind.COMPLETE,
                notFoundAttempt.kind());
        assertNotNull(notFoundAttempt.processResult());
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                notFoundAttempt.processResult().status());
        assertEquals(0L,
                notFoundAttempt.processResult().totalGas());
        assertEquals(
                Arrays.asList(rootBlueId, eventBlueId),
                notFound.requests());
        assertEquals(
                ProcessAttemptResult.Kind.NEEDS_RESOURCES,
                unavailableAttempt.kind());
        assertEquals(
                Collections.singletonList(eventBlueId),
                unavailableAttempt.requiredExactBlueIds());
        assertNull(unavailableAttempt.processResult());
        assertNull(unavailableAttempt.portableGas());
        assertEquals(
                Arrays.asList(rootBlueId, eventBlueId),
                unavailable.requests());
    }

    private static DocumentProcessor processor(
            StrictFragmentSnapshotManager fragments,
            AtomicInteger derivations,
            AtomicInteger verifications) {
        ExternalDeliveryPlan plan =
                ExternalDeliveryPlan.builder()
                        .revisions(7L, 7L)
                        .eventOrderKey(EVENT_ORDER)
                        .exactRuntimeState()
                        .build();
        return DocumentProcessor.builder()
                .snapshotStore(fragments)
                .deliveryPlanDeriver(
                        (root, event) -> {
                            assertFalse(root.isReferenceOnly());
                            assertFalse(event.isReferenceOnly());
                            derivations.incrementAndGet();
                            return plan;
                        })
                .evidenceVerifier(
                        (root, event, evidence) -> {
                            assertFalse(root.isReferenceOnly());
                            assertFalse(event.isReferenceOnly());
                            verifications.incrementAndGet();
                        })
                .runtimeRegistryIdentity(
                        RuntimeBlueIds
                                .REGISTRY_PACKAGE_IDENTITY)
                .build();
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static Node materializedCyclicMemberEvent() {
        return reference(CYCLIC_MEMBER_BLUE_ID)
                .properties(
                        "body",
                        new Node().value("verified by owning set"));
    }

    private static Node terminatedRoot() {
        return new Node().contracts(
                new Node().properties(
                        "terminated",
                        new Node()
                                .type(new Node().blueId(
                                        RuntimeBlueIds
                                                .PROCESSING_TERMINATED_MARKER))
                                .properties(
                                        "cause",
                                        new Node().value("business"))
                                .properties(
                                        "reason",
                                        new Node().value("complete"))));
    }

    private static VerifiedExecutionEvidence evidence(
            Node root,
            String eventBlueId) {
        return VerifiedExecutionEvidence.builder(
                        DirectBlueIdCalculator.calculateBlueId(root),
                        eventBlueId)
                .revisions(7L, 7L)
                .runtimeRegistryIdentity(
                        RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY)
                .eventOrderKey(EVENT_ORDER)
                .activeSubscriptionIntervals(
                        Collections
                                .<SubscriptionDelta.Entry>emptyList())
                .build();
    }

    private static void assertCyclicEventInvalid(
            DocumentProcessingResult result) {
        assertNotNull(result);
        assertEquals(
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                result.status());
        assertEquals(0L, result.totalGas());
        assertNotNull(result.diagnostic());
        assertEquals(
                ProcessorErrorCategory
                        .CyclicMemberProcessingEventUnsupported,
                result.diagnostic().category());
    }

    private static final class StrictFragmentSnapshotManager
            implements ProcessingSnapshotManager {
        private final Map<String, Node> exact =
                new LinkedHashMap<>();
        private final Set<String> unchecked =
                new LinkedHashSet<>();
        private final Set<String> unavailable =
                new LinkedHashSet<>();
        private final List<String> requests =
                new java.util.ArrayList<>();
        private NodeProvider provider;
        private int fullSnapshotBuilds;

        StrictFragmentSnapshotManager provider(
                NodeProvider provider) {
            this.provider =
                    new VerifyingNodeProvider(provider);
            return this;
        }

        StrictFragmentSnapshotManager exact(
                String blueId,
                Node node) {
            exact.put(blueId, node.clone());
            return this;
        }

        StrictFragmentSnapshotManager uncheckedExact(
                String blueId,
                Node node) {
            exact.put(blueId, node.clone());
            unchecked.add(blueId);
            return this;
        }

        StrictFragmentSnapshotManager unavailable(
                String blueId) {
            unavailable.add(blueId);
            return this;
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            fullSnapshotBuilds++;
            throw new AssertionError(
                    "Admission must not invoke full snapshot resolution");
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            String blueId =
                    reference.getReferenceBlueId();
            requests.add(blueId);
            if (unavailable.contains(blueId)) {
                throw new IllegalStateException(
                        "Provider unavailable for requested BlueId "
                                + blueId);
            }
            Node node = exact.get(blueId);
            if (node == null && provider != null) {
                List<Node> nodes =
                        provider.fetchByBlueId(blueId);
                node = nodes != null
                        && nodes.size() == 1
                        ? nodes.get(0)
                        : null;
            }
            if (node == null) {
                return null;
            }
            if (!unchecked.contains(blueId)) {
                assertEquals(
                        blueId,
                        DirectBlueIdCalculator.calculateBlueId(
                                node));
            }
            return FrozenNode.fromNode(node);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            throw new AssertionError(
                    "Admission characterization does not patch");
        }

        List<String> requests() {
            return Collections.unmodifiableList(
                    new java.util.ArrayList<>(requests));
        }

        int fullSnapshotBuilds() {
            return fullSnapshotBuilds;
        }
    }
}
