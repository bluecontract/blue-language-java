package blue.language.processor;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BlueContractsTest {

    @Test
    void shouldProcessThroughFocusedServiceAndLeaveLanguageOpen() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();
        BlueContracts contracts = BlueContracts.builder(
                language.processing()).build();
        Node root = new Node().value("root");
        Node event = new Node().value("event");

        // when
        DocumentProcessingResult result = contracts.process(root, event);
        contracts.close();
        String directBlueId = language.identity().directBlueId(root);

        // then
        assertNotNull(result);
        assertNotNull(result.status());
        assertFalse(directBlueId.isEmpty());
        assertThrows(IllegalStateException.class,
                () -> contracts.process(root, event));
        language.close();
    }

    @Test
    void shouldExposeManagedHostServicesOnlyWhileOpen() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();
        BlueContracts contracts = BlueContracts.builder(
                language.processing()).build();

        // when
        ProcessorRuntimeAccess runtimeAccess = contracts.runtimeAccess();
        SubscriptionSurfaceProjection projection =
                contracts.subscriptionSurfaceProjection();
        IndexedDeliveryEvaluator evaluator =
                contracts.indexedDeliveryEvaluator();
        ExternalDeliveryPlanDeriver deriver =
                contracts.currentRootDeliveryPlanDeriver(
                        0L,
                        ExternalOrderKey.of(Collections.emptyList()),
                        Collections.<SubscriptionDelta.Entry>emptyList());
        contracts.close();

        // then
        assertNotNull(projection);
        assertNotNull(evaluator);
        assertNotNull(deriver);
        assertFalse(runtimeAccess.isCurrent());
        assertThrows(IllegalStateException.class,
                contracts::runtimeAccess);
        assertThrows(IllegalStateException.class,
                contracts::subscriptionSurfaceProjection);
        assertThrows(IllegalStateException.class,
                contracts::indexedDeliveryEvaluator);
        language.close();
    }

    @Test
    void shouldTranslateExactProviderAbsenceToNull() {
        // given
        String absentBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("absent"));

        // when
        FrozenNode materialized;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            materialized = manager.materializeVerifiedExactReference(
                    reference(absentBlueId));
        }

        // then
        assertNull(materialized);
    }

    @Test
    void shouldTranslateProviderUnavailabilityToRetryableEvidence() {
        // given
        String unavailableBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("unavailable"));
        NodeProvider provider = providerWithResult(
                unavailableBlueId,
                NodeProviderResult.unavailable("offline"));

        // when
        ExecutionEvidenceUnavailableException failure;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> manager.materializeVerifiedExactReference(
                            reference(unavailableBlueId)));
        }

        // then
        assertEquals(Collections.singletonList(unavailableBlueId),
                failure.requiredExactBlueIds());
        assertEquals("offline", failure.getMessage());
    }

    @Test
    void shouldTranslateInvalidProviderEvidenceToTerminalFailure() {
        // given
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("expected"));
        NodeProvider provider = providerWithResult(
                requestedBlueId,
                NodeProviderResult.found(Collections.singletonList(
                        new Node().value("wrong"))));

        // when
        RuntimeException failure;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> manager.materializeVerifiedExactReference(
                            reference(requestedBlueId)));
        }

        // then
        assertNotNull(failure.getMessage());
    }

    private static FrozenNode reference(String blueId) {
        return FrozenNode.fromNode(new Node().blueId(blueId));
    }

    private static NodeProvider providerWithResult(
            String requestedBlueId,
            NodeProviderResult providerResult) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome() == NodeProviderOutcome.FOUND
                        ? result.nodes()
                        : null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return requestedBlueId.equals(blueId)
                        ? providerResult
                        : NodeProviderResult.notFound();
            }
        };
    }
}
