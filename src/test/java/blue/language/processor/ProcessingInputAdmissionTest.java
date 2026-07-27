package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodePathEditor;
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

    @Test
    void blueFacadeProcessesExactPureReferenceRootAndEvent() {
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
                BlueIdCalculator.calculateBlueId(root);
        String eventBlueId =
                BlueIdCalculator.calculateBlueId(event);
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

        try (Blue blue = new Blue(
                trackingProvider)) {
            DocumentProcessingResult result =
                    blue.processDocument(
                            reference(rootBlueId),
                            reference(eventBlueId));

            assertEquals(
                    ProcessorStatus.NO_MATCH,
                    result.status());
            assertEquals(
                    rootBlueId,
                    BlueIdCalculator.calculateBlueId(
                            result.document()));
            assertTrue(result.events().isEmpty());
            assertEquals(
                    Arrays.asList(
                            rootBlueId, eventBlueId),
                    requests);
        }
    }

    @Test
    void publicProcessAdmitsExactRootAndEventWithoutOpeningUnrelatedReference() {
        Node unrelated = new Node().properties(
                "payload", new Node().value("must remain cold"));
        String unrelatedBlueId =
                BlueIdCalculator.calculateBlueId(unrelated);
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
                BlueIdCalculator.calculateBlueId(root);
        String eventBlueId =
                BlueIdCalculator.calculateBlueId(event);
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(root, event);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .provider(graph.provider());
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();

        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            DocumentProcessingResult result =
                    processor.processDocument(
                            reference(rootBlueId),
                            reference(eventBlueId));

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
            Node retained = NodePathEditor.getOrNull(
                    result.document(), "/unrelated");
            assertNotNull(retained);
            assertTrue(retained.isReferenceOnly());
            assertEquals(
                    unrelatedBlueId, retained.getBlueId());
        }
    }

    @Test
    void snapshotEntryAdmitsPureReferenceEventOnly() {
        Node unrelated = new Node().value(
                "snapshot sibling remains cold");
        String unrelatedBlueId =
                BlueIdCalculator.calculateBlueId(unrelated);
        Node root = new Node().properties(
                "unrelated",
                reference(unrelatedBlueId));
        Node event = new Node().properties(
                "subscriptionKey",
                new Node().value("none"));
        String eventBlueId =
                BlueIdCalculator.calculateBlueId(event);
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(event);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .provider(graph.provider());
        ResolvedSnapshot snapshot =
                ResolvedSnapshot.withDeferredResolution(
                        FrozenNode.fromNode(root),
                        FrozenNode.fromResolvedNode(root));

        try (DocumentProcessor processor = processor(
                fragments,
                new AtomicInteger(),
                new AtomicInteger())) {
            DocumentProcessingResult result =
                    processor.processDocument(
                            snapshot,
                            reference(eventBlueId));

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
    void scopeAdmissionOpensOnlyReferenceAncestorsOnSelectedPath() {
        Node unrelated = new Node().value(
                "unrelated root branch");
        String unrelatedBlueId =
                BlueIdCalculator.calculateBlueId(unrelated);
        Node selectedSide = new Node().value(
                "unrelated selected sibling");
        String selectedSideBlueId =
                BlueIdCalculator.calculateBlueId(selectedSide);
        Node nested = new Node().properties(
                "leaf", new Node().value("selected"));
        String nestedBlueId =
                BlueIdCalculator.calculateBlueId(nested);
        Node selected = new Node()
                .properties(
                        "nested", nested)
                .properties(
                        "side", selectedSide);
        String selectedBlueId =
                BlueIdCalculator.calculateBlueId(selected);
        Node root = new Node()
                .properties(
                        "selected", selected)
                .properties(
                        "unrelated", unrelated);
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(root);
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(root);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .provider(graph.provider());
        ProcessingInputAdmission admission =
                new ProcessingInputAdmission(fragments);

        ProcessingInputAdmission.AdmittedNode admitted =
                admission.materializeTopLevel(
                        reference(rootBlueId),
                        "Processing Root");
        admitted = admission.materializeScopePaths(
                admitted,
                Collections.singletonList(
                        "/selected/nested"));

        assertEquals(
                Arrays.asList(
                        rootBlueId,
                        selectedBlueId,
                        nestedBlueId),
                fragments.requests());
        assertEquals(
                rootBlueId,
                BlueIdCalculator.calculateBlueId(
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
    void mismatchedExactRootEvidenceIsDeterministicallyInvalid() {
        Node expected = new Node().properties(
                "state", new Node().value("expected"));
        String requestedBlueId =
                BlueIdCalculator.calculateBlueId(expected);
        Node wrong = new Node().properties(
                "state", new Node().value("wrong"));
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .uncheckedExact(
                                requestedBlueId, wrong);
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();

        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            DocumentProcessingResult result =
                    processor.processDocument(
                            reference(requestedBlueId),
                            new Node().value("event"));

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
    void notFoundTopLevelRootCompletesAsInvalidWithoutGas() {
        Node expected = new Node().properties(
                "state", new Node().value("not-found"));
        String requestedBlueId =
                BlueIdCalculator.calculateBlueId(expected);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager();
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();

        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            ProcessAttemptResult attempt =
                    processor.processAttempt(
                            reference(requestedBlueId),
                            new Node().value("event"));

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
    void unavailableTopLevelRootSuspendsAttemptBeforeGasOrEffects() {
        Node expected = new Node().properties(
                "state", new Node().value("unavailable"));
        String requestedBlueId =
                BlueIdCalculator.calculateBlueId(expected);
        StrictFragmentSnapshotManager fragments =
                new StrictFragmentSnapshotManager()
                        .unavailable(requestedBlueId);
        AtomicInteger derivations = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();

        try (DocumentProcessor processor = processor(
                fragments, derivations, verifications)) {
            ProcessAttemptResult attempt =
                    processor.processAttempt(
                            reference(requestedBlueId),
                            new Node().value("event"));

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
    void eventNotFoundIsInvalidButEventUnavailableSuspends() {
        Node root = new Node().value("root");
        Node event = new Node().properties(
                "subscriptionKey",
                new Node().value("none"));
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(root);
        String eventBlueId =
                BlueIdCalculator.calculateBlueId(event);
        ExactNodeGraphFragments rootFragments =
                new ExactNodeGraphFragments(root);

        StrictFragmentSnapshotManager notFound =
                new StrictFragmentSnapshotManager()
                        .provider(rootFragments.provider());
        try (DocumentProcessor processor = processor(
                notFound,
                new AtomicInteger(),
                new AtomicInteger())) {
            ProcessAttemptResult attempt =
                    processor.processAttempt(
                            reference(rootBlueId),
                            reference(eventBlueId));

            assertEquals(
                    ProcessAttemptResult.Kind.COMPLETE,
                    attempt.kind());
            assertNotNull(attempt.processResult());
            assertEquals(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    attempt.processResult().status());
            assertEquals(0L, attempt.processResult().totalGas());
            assertEquals(
                    Arrays.asList(rootBlueId, eventBlueId),
                    notFound.requests());
        }

        StrictFragmentSnapshotManager unavailable =
                new StrictFragmentSnapshotManager()
                        .provider(rootFragments.provider())
                        .unavailable(eventBlueId);
        try (DocumentProcessor processor = processor(
                unavailable,
                new AtomicInteger(),
                new AtomicInteger())) {
            ProcessAttemptResult attempt =
                    processor.processAttempt(
                            reference(rootBlueId),
                            reference(eventBlueId));

            assertEquals(
                    ProcessAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind());
            assertEquals(
                    Collections.singletonList(eventBlueId),
                    attempt.requiredExactBlueIds());
            assertNull(attempt.processResult());
            assertNull(attempt.portableGas());
            assertEquals(
                    Arrays.asList(rootBlueId, eventBlueId),
                    unavailable.requests());
        }
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
                .withSnapshotManager(fragments)
                .withExternalDeliveryPlanDeriver(
                        (root, event) -> {
                            assertFalse(root.isReferenceOnly());
                            assertFalse(event.isReferenceOnly());
                            derivations.incrementAndGet();
                            return plan;
                        })
                .withExternalDeliveryEvidenceVerifier(
                        (root, event, evidence) -> {
                            assertFalse(root.isReferenceOnly());
                            assertFalse(event.isReferenceOnly());
                            verifications.incrementAndGet();
                        })
                .withRuntimeRegistryIdentity(
                        RuntimeBlueIds
                                .REGISTRY_PACKAGE_IDENTITY)
                .build();
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
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
                        BlueIdCalculator.calculateBlueId(
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
