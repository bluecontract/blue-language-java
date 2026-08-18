package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ClosureDirectSeedPlannerTest {

    private static final String SHA_A =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String BLUE_A =
            "8XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8";
    private static final String BLUE_B =
            "6ZEqCbcDrgozabAdvxqbUVsG8z8NGxFv86ot2Go55xRZ";

    @Test
    void shouldMatchReleasedCclo34FirstSeedIdentities() {
        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(new DocumentId("a")),
                "source",
                "start",
                0L);

        ClosureWorkOccurrence work = ClosureDirectSeedPlanner.plan(
                "sha256:6d08aa24f386414deb0dadffdfc94f355036b217a1542c062eb3c8ee7e71e432",
                "4za3n8bAtn5YGRqAEeW6iRswtRnLgGkyZg9EN64gwLy2",
                Collections.singletonList(component("a", BLUE_A, SHA_A)),
                Collections.singletonList(delivery)).get(0);

        assertEquals(
                "sha256:1daa50609cd58f0d3ffb483be2ed6b3cf340e3d1f3de96d5101778a58fa4aa9e",
                work.targetManagedScopeIdentity());
        assertEquals(
                "sha256:8fc445f892c5c6962bcf955f1cd6f33104dbaa7492d2a6e2bdc61d062c0194ff",
                work.sourceOccurrenceIdentity());
        assertEquals(
                "sha256:f7d010ef6e251088f345137ef5ebdace6d7ee90fcede2b054b4a4ed6576b98a3",
                work.workIdentity());
    }

    @Test
    void shouldSeparateRawSnapshotOrderFromComponentExecutionOrder() {
        List<ComponentSnapshot> components = Arrays.asList(
                component("a", BLUE_A, SHA_A),
                component("z", BLUE_B, SHA_B));
        List<DirectLogicalDelivery> rawOrder = Arrays.asList(
                new DirectLogicalDelivery(
                        ManagedScopeKey.root(new DocumentId("z")),
                        "z-channel", "z", 0L),
                new DirectLogicalDelivery(
                        ManagedScopeKey.root(new DocumentId("a")),
                        "a-channel", "a", 1L));

        List<ClosureWorkOccurrence> work = ClosureDirectSeedPlanner.plan(
                SHA_A,
                BLUE_A,
                components,
                rawOrder);

        assertEquals(new DocumentId("a"), work.get(0).targetDocumentId());
        assertEquals(new DocumentId("z"), work.get(1).targetDocumentId());
        assertEquals(0L, work.get(0).ordinal());
        assertEquals(1L, work.get(1).ordinal());
    }

    @Test
    void shouldRejectTargetOutsideVerifiedPartition() {
        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(new DocumentId("z")),
                "channel", "logical", 0L);

        assertThrows(IllegalArgumentException.class, () ->
                ClosureDirectSeedPlanner.plan(
                        SHA_A,
                        BLUE_A,
                        Collections.singletonList(
                                component("a", BLUE_A, SHA_A)),
                        Collections.singletonList(delivery)));
    }

    private static ComponentSnapshot component(
            String documentId,
            String blueId,
            String identity) {
        return new ComponentSnapshot(
                identity,
                identity,
                1L,
                ComponentKind.ACYCLIC,
                Collections.singletonList(new DocumentId(documentId)),
                Collections.singletonList(blueId),
                null,
                null,
                null);
    }
}
