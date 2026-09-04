package blue.language.processor.closure;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedDocumentInitializationEligibilityTest {

    private static final DocumentId SOURCE = new DocumentId("source");
    private static final DocumentId TARGET = new DocumentId("target");
    private static final String POLICY = hash('a');
    private static final String TARGET_BLUE_ID = hash('b');

    @Test
    void classifiesOnlyAnInactivePendingNullNonPublicTargetAsDormant() {
        ManagedOccurrenceBinding prospective = binding(
                SOURCE, TARGET, false, null);

        assertTrue(ManagedDocumentInitializationEligibility
                .isDormantProspectiveOnlyTarget(
                        document(TARGET, false),
                        Collections.singletonList(prospective),
                        Collections.<DirectLogicalDelivery>emptyList()));
        assertFalse(ManagedDocumentInitializationEligibility
                .isDormantProspectiveOnlyTarget(
                        document(TARGET, true),
                        Collections.singletonList(prospective),
                        Collections.<DirectLogicalDelivery>emptyList()));
        assertFalse(ManagedDocumentInitializationEligibility
                .isDormantProspectiveOnlyTarget(
                        document(TARGET, false),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        Collections.<DirectLogicalDelivery>emptyList()));
    }

    @Test
    void activeHistoricalSourceAndDirectRolesAllRequireInitialization() {
        DirectLogicalDelivery direct = new DirectLogicalDelivery(
                ManagedScopeKey.root(TARGET), "channel", "delivery", 0L);

        assertFalse(dormant(binding(SOURCE, TARGET, true, null)));
        assertFalse(dormant(binding(
                SOURCE, TARGET, false, Long.valueOf(3L))));
        assertFalse(ManagedDocumentInitializationEligibility
                .isDormantProspectiveOnlyTarget(
                        document(TARGET, false),
                        Collections.singletonList(binding(
                                SOURCE, TARGET, false, null)),
                        Collections.singletonList(direct)));
        assertFalse(ManagedDocumentInitializationEligibility
                .isDormantProspectiveOnlyTarget(
                        document(TARGET, false),
                        Arrays.asList(
                                binding(SOURCE, TARGET, false, null),
                                binding(TARGET, SOURCE, false, null)),
                        Collections.<DirectLogicalDelivery>emptyList()));
    }

    private static boolean dormant(ManagedOccurrenceBinding binding) {
        return ManagedDocumentInitializationEligibility
                .isDormantProspectiveOnlyTarget(
                        document(TARGET, false),
                        Collections.singletonList(binding),
                        Collections.<DirectLogicalDelivery>emptyList());
    }

    private static ManagedOccurrenceBinding binding(
            DocumentId source,
            DocumentId target,
            boolean active,
            Long pendingHistoricalEpoch) {
        return ManagedOccurrenceBinding.derived(
                POLICY,
                source,
                ScopeAddress.embedded("/reserved", 1L),
                target,
                TARGET_BLUE_ID,
                active,
                pendingHistoricalEpoch);
    }

    private static ManagedDocumentSnapshot document(
            DocumentId documentId,
            boolean publicRoot) {
        return new ManagedDocumentSnapshot(
                documentId,
                TARGET_BLUE_ID,
                new Node().name(documentId.value()),
                false,
                false,
                publicRoot,
                0L,
                1L);
    }

    private static String hash(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
