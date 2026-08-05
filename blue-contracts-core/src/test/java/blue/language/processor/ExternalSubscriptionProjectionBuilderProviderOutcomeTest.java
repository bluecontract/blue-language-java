package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies exact provider outcomes and locality during type-surface probing. */
final class ExternalSubscriptionProjectionBuilderProviderOutcomeTest {

    private static final String SELECTED_CHANNEL = "incoming";
    private static final String DECOY_CONTRACT = "a-decoy";

    @Test
    void shouldTreatMissingSelectedScopeTypeAsDeterministicInvalid() {
        // given
        Node exactType = typeWithContracts(new Node().properties(
                SELECTED_CHANNEL, new Node().value("selected")));
        String typeBlueId = blueId(exactType);
        RecordingSnapshotManager manager = new RecordingSnapshotManager();

        // when
        InvalidExecutionEvidenceException failure = assertThrows(
                InvalidExecutionEvidenceException.class,
                () -> contributes(
                        manager,
                        typeBlueId,
                        Collections.singleton(SELECTED_CHANNEL),
                        false));

        // then
        assertTrue(failure.getMessage().contains(typeBlueId));
        assertEquals(Collections.singletonList(typeBlueId), manager.reads);
    }

    @Test
    void shouldPreserveUnavailableSelectedScopeTypeOutcome() {
        // given
        Node exactType = typeWithContracts(new Node().properties(
                SELECTED_CHANNEL, new Node().value("selected")));
        String typeBlueId = blueId(exactType);
        ExecutionEvidenceUnavailableException expected =
                new ExecutionEvidenceUnavailableException(
                        "type provider offline",
                        Collections.singleton(typeBlueId));
        RecordingSnapshotManager manager = new RecordingSnapshotManager();
        manager.fail(typeBlueId, expected);

        // when
        ExecutionEvidenceUnavailableException failure = assertThrows(
                ExecutionEvidenceUnavailableException.class,
                () -> contributes(
                        manager,
                        typeBlueId,
                        Collections.singleton(SELECTED_CHANNEL),
                        false));

        // then
        assertSame(expected, failure);
        assertEquals(Collections.singletonList(typeBlueId), manager.reads);
    }

    @Test
    void shouldPreserveInvalidSelectedScopeTypeOutcome() {
        // given
        Node exactType = typeWithContracts(new Node().properties(
                SELECTED_CHANNEL, new Node().value("selected")));
        String typeBlueId = blueId(exactType);
        InvalidExecutionEvidenceException expected =
                new InvalidExecutionEvidenceException(
                        "type evidence rejected");
        RecordingSnapshotManager manager = new RecordingSnapshotManager();
        manager.fail(typeBlueId, expected);

        // when
        InvalidExecutionEvidenceException failure = assertThrows(
                InvalidExecutionEvidenceException.class,
                () -> contributes(
                        manager,
                        typeBlueId,
                        Collections.singleton(SELECTED_CHANNEL),
                        false));

        // then
        assertSame(expected, failure);
        assertEquals(Collections.singletonList(typeBlueId), manager.reads);
    }

    @Test
    void shouldRecognizeSelectedKeyBeforeReadingUnrelatedContractHeaders() {
        // given
        Node decoy = new Node().value("unrelated header");
        String decoyBlueId = blueId(decoy);
        Node exactType = typeWithContracts(new Node()
                .properties(DECOY_CONTRACT, reference(decoyBlueId))
                .properties(
                        SELECTED_CHANNEL,
                        new Node().value("selected")));
        String typeBlueId = blueId(exactType);
        RecordingSnapshotManager manager = new RecordingSnapshotManager();
        manager.provide(typeBlueId, exactType);

        // when
        boolean contributes = contributes(
                manager,
                typeBlueId,
                Collections.singleton(SELECTED_CHANNEL),
                true);

        // then
        assertTrue(contributes);
        assertEquals(Collections.singletonList(typeBlueId), manager.reads);
        assertFalse(manager.reads.contains(decoyBlueId));
    }

    @Test
    void shouldInspectOnlyReservedEmbeddedContractForRouting() {
        // given
        Node decoy = new Node().value("unrelated header");
        String decoyBlueId = blueId(decoy);
        Node embedded = new Node().type(
                new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED));
        Node exactType = typeWithContracts(new Node()
                .properties(DECOY_CONTRACT, reference(decoyBlueId))
                .properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        embedded));
        String typeBlueId = blueId(exactType);
        RecordingSnapshotManager manager = new RecordingSnapshotManager();
        manager.provide(typeBlueId, exactType);

        // when
        boolean contributes = contributes(
                manager,
                typeBlueId,
                Collections.<String>emptySet(),
                true);

        // then
        assertTrue(contributes);
        assertEquals(Collections.singletonList(typeBlueId), manager.reads);
        assertFalse(manager.reads.contains(decoyBlueId));
    }

    @Test
    void shouldTreatMissingReservedEmbeddedHeaderAsDeterministicInvalid() {
        // given
        Node embedded = new Node().type(
                new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED));
        String embeddedBlueId = blueId(embedded);
        Node exactType = typeWithContracts(new Node().properties(
                ProcessorContractConstants.KEY_EMBEDDED,
                reference(embeddedBlueId)));
        String typeBlueId = blueId(exactType);
        RecordingSnapshotManager manager = new RecordingSnapshotManager();
        manager.provide(typeBlueId, exactType);

        // when
        InvalidExecutionEvidenceException failure = assertThrows(
                InvalidExecutionEvidenceException.class,
                () -> contributes(
                        manager,
                        typeBlueId,
                        Collections.<String>emptySet(),
                        true));

        // then
        assertTrue(failure.getMessage().contains(embeddedBlueId));
        assertEquals(
                Arrays.asList(typeBlueId, embeddedBlueId),
                manager.reads);
    }

    @Test
    void shouldTreatMissingTypeContractsMapAsDeterministicInvalid() {
        // given
        Node exactContracts = new Node().properties(
                SELECTED_CHANNEL, new Node().value("selected"));
        String contractsBlueId = blueId(exactContracts);
        Node exactType = new Node().contracts(reference(contractsBlueId));
        String typeBlueId = blueId(exactType);
        RecordingSnapshotManager manager = new RecordingSnapshotManager();
        manager.provide(typeBlueId, exactType);

        // when
        InvalidExecutionEvidenceException failure = assertThrows(
                InvalidExecutionEvidenceException.class,
                () -> contributes(
                        manager,
                        typeBlueId,
                        Collections.singleton(SELECTED_CHANNEL),
                        false));

        // then
        assertTrue(failure.getMessage().contains(contractsBlueId));
        assertEquals(
                Arrays.asList(typeBlueId, contractsBlueId),
                manager.reads);
    }

    @Test
    void shouldResolveSelectedContractWithoutReadingUnrequestedHeader() {
        // given
        Node selected = new Node().value("selected header");
        Node decoy = new Node().value("unrequested header");
        String selectedBlueId = blueId(selected);
        String decoyBlueId = blueId(decoy);
        Node exactType = typeWithContracts(new Node()
                .properties(DECOY_CONTRACT, reference(decoyBlueId))
                .properties(SELECTED_CHANNEL, reference(selectedBlueId)));
        String typeBlueId = blueId(exactType);
        RecordingSnapshotManager manager = new RecordingSnapshotManager();
        manager.provide(typeBlueId, exactType);
        manager.provide(selectedBlueId, selected);
        manager.fail(
                decoyBlueId,
                new InvalidExecutionEvidenceException(
                        "unrequested header must remain cold"));
        manager.demandDuringResolution(
                contractPath(SELECTED_CHANNEL), selectedBlueId);

        // when
        try (ExternalDeliveryResolution ignored = builder(manager)
                .subscriptionResolution(projection(typeBlueId))) {
            // Resolution success is the selected FOUND outcome under test.
        }

        // then
        assertEquals(
                Arrays.asList(typeBlueId, selectedBlueId),
                manager.reads);
        assertFalse(manager.reads.contains(decoyBlueId));
        assertEquals(
                Collections.singleton(contractPath(DECOY_CONTRACT)),
                manager.preservedPaths);
    }

    @Test
    void shouldPreserveSelectedUnavailableOutcomeWithoutReadingDecoy() {
        // given
        Node selected = new Node().value("selected header");
        Node decoy = new Node().value("unrequested header");
        String selectedBlueId = blueId(selected);
        String decoyBlueId = blueId(decoy);
        Node exactType = typeWithContracts(new Node()
                .properties(DECOY_CONTRACT, reference(decoyBlueId))
                .properties(SELECTED_CHANNEL, reference(selectedBlueId)));
        String typeBlueId = blueId(exactType);
        ExecutionEvidenceUnavailableException expected =
                new ExecutionEvidenceUnavailableException(
                        "selected header provider offline",
                        Collections.singleton(selectedBlueId));
        RecordingSnapshotManager manager = new RecordingSnapshotManager();
        manager.provide(typeBlueId, exactType);
        manager.fail(selectedBlueId, expected);
        manager.fail(
                decoyBlueId,
                new InvalidExecutionEvidenceException(
                        "unrequested header must remain cold"));
        manager.demandDuringResolution(
                contractPath(SELECTED_CHANNEL), selectedBlueId);

        // when
        ExecutionEvidenceUnavailableException failure = assertThrows(
                ExecutionEvidenceUnavailableException.class,
                () -> builder(manager)
                        .subscriptionResolution(projection(typeBlueId)));

        // then
        assertSame(expected, failure);
        assertEquals(
                Arrays.asList(typeBlueId, selectedBlueId),
                manager.reads);
        assertFalse(manager.reads.contains(decoyBlueId));
        assertEquals(
                Collections.singleton(contractPath(DECOY_CONTRACT)),
                manager.preservedPaths);
    }

    private static boolean contributes(
            ProcessingSnapshotManager manager,
            String typeBlueId,
            Set<String> requestedKeys,
            boolean includeProcessEmbedded) {
        return ExternalSubscriptionProjectionBuilder
                .typeContributesToSubscriptionSurface(
                        manager,
                        reference(typeBlueId),
                        requestedKeys,
                        includeProcessEmbedded,
                        new LinkedHashSet<String>());
    }

    private static ExternalSubscriptionProjectionBuilder builder(
            ProcessingSnapshotManager manager) {
        return new ExternalSubscriptionProjectionBuilder(
                null,
                manager,
                null,
                Collections.<String, List<String>>emptyMap());
    }

    private static ExternalSubscriptionProjection projection(
            String typeBlueId) {
        Map<String, Set<String>> requested = new LinkedHashMap<>();
        requested.put(
                JsonPointer.ROOT,
                Collections.singleton(SELECTED_CHANNEL));
        return new ExternalSubscriptionProjection(
                new Node().type(reference(typeBlueId)),
                requested,
                Collections.<String, Map<String, String>>emptyMap());
    }

    private static String contractPath(String contractKey) {
        return ProcessorPointerConstants.relativeContractsEntry(contractKey);
    }

    private static Node typeWithContracts(Node contracts) {
        return new Node().contracts(contracts);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static final class RecordingSnapshotManager
            implements ProcessingSnapshotManager {

        private final Map<String, FrozenNode> content =
                new LinkedHashMap<>();
        private final Map<String, RuntimeException> failures =
                new LinkedHashMap<>();
        private final List<String> reads = new ArrayList<>();
        private Set<String> preservedPaths = Collections.emptySet();
        private String resolutionPath;
        private String resolutionBlueId;

        private void provide(String blueId, Node exact) {
            content.put(blueId, FrozenNode.fromNode(exact));
        }

        private void fail(String blueId, RuntimeException failure) {
            failures.put(blueId, failure);
        }

        private void demandDuringResolution(
                String path,
                String blueId) {
            resolutionPath = path;
            resolutionBlueId = blueId;
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            String blueId = reference.getReferenceBlueId();
            reads.add(blueId);
            RuntimeException failure = failures.get(blueId);
            if (failure != null) {
                throw failure;
            }
            return content.get(blueId);
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            throw new AssertionError("Resolution is outside this focused test");
        }

        @Override
        public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                Node document,
                Collection<String> preserved) {
            preservedPaths = new LinkedHashSet<>(preserved);
            if (resolutionBlueId != null
                    && !preservedPaths.contains(resolutionPath)) {
                FrozenNode selected = materializeVerifiedExactReference(
                        FrozenNode.fromNode(reference(resolutionBlueId)));
                if (selected == null) {
                    throw new InvalidExecutionEvidenceException(
                            "Selected contract header was not found");
                }
            }
            FrozenNode canonical = FrozenNode.fromNode(document);
            return ResolvedSnapshot.withDeferredResolution(
                    canonical,
                    FrozenNode.fromResolvedNode(document));
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            throw new AssertionError("Patching is outside this focused test");
        }
    }
}
