package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Identity-boundary checks for external delivery plans and evidence. */
final class VerifiedExecutionEvidenceIdentityValidationTest {

    private static final String ROOT_MASTER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    new Node().name("cyclic Root master"));
    private static final String EVENT_MASTER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    new Node().name("cyclic event master"));
    private static final String RESOURCE_MASTER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    new Node().name("cyclic resource master"));

    @Test
    void shouldBindCanonicalCyclicMemberSourceIdentities() {
        String rootBlueId = ROOT_MASTER_BLUE_ID + "#0";
        String eventBlueId = EVENT_MASTER_BLUE_ID + "#1";
        String resourceBlueId = RESOURCE_MASTER_BLUE_ID + "#2";
        ExternalDeliveryPlan plan = ExternalDeliveryPlan.builder()
                .revisions(7L, 7L)
                .eventOrderKey(orderKey())
                .availableExactNode(resourceBlueId)
                .requiredExactNode(resourceBlueId)
                .exactRuntimeState()
                .build();

        VerifiedExecutionEvidence evidence = plan.bind(
                rootBlueId, eventBlueId, "registry");

        assertEquals(rootBlueId, evidence.rootBlueId());
        assertEquals(eventBlueId, evidence.eventBlueId());
        assertEquals(Collections.singleton(resourceBlueId),
                evidence.availableExactNodeBlueIds());
        assertEquals(Collections.singleton(resourceBlueId),
                evidence.requiredExactNodeBlueIds());
    }

    @Test
    void shouldRejectMalformedEvidenceAndPlanIdentities() {
        String eventBlueId = EVENT_MASTER_BLUE_ID + "#0";

        assertThrows(IllegalArgumentException.class,
                () -> VerifiedExecutionEvidence.builder(
                                "not-a-blue-id", eventBlueId)
                        .revisions(7L, 7L)
                        .runtimeRegistryIdentity("registry")
                        .eventOrderKey(orderKey())
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> ExternalDeliveryPlan.builder()
                        .requiredExactNode("not-a-blue-id"));
    }

    @Test
    void shouldValidateEstablishedIdentitiesBeforeComparingBindings() {
        String rootBlueId = ROOT_MASTER_BLUE_ID + "#0";
        String eventBlueId = EVENT_MASTER_BLUE_ID + "#1";
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(rootBlueId, eventBlueId)
                        .revisions(7L, 7L)
                        .runtimeRegistryIdentity("registry")
                        .eventOrderKey(orderKey())
                        .build();

        assertThrows(IllegalArgumentException.class,
                () -> evidence.revalidateBinding(
                        rootBlueId, "not-a-blue-id", "registry"));
    }

    private static ExternalOrderKey orderKey() {
        return ExternalOrderKey.of(
                Collections.<Object>singletonList(7L));
    }
}
