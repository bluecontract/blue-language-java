package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractSurfaceReconcilerTest {

    private static final String OPERATION_TYPE = "operation-type";

    @Test
    void shouldCaptureExactSurfaceAndOrderEveryDeltaByScopeThenKey() {
        // given
        ContractSurfaceReconciler reconciler = reconciler();
        ContractSurfaceReconciler.Capture before = reconciler.newCapture();
        ContractSurfaceReconciler.Capture after = reconciler.newCapture();
        before.record("/", beforeBundle());
        after.record("/", afterBundle());

        SubscriptionDelta.Entry oldSubscription = subscription("old");
        SubscriptionDelta.Entry newSubscription = subscription("new");
        SubscriptionDelta subscriptions = new SubscriptionDelta(
                Collections.singletonList(newSubscription),
                Collections.singletonList(oldSubscription));

        // when
        ContractSurfaceDelta delta = reconciler.reconcile(
                id("before-root"),
                id("after-root"),
                before,
                after,
                subscriptions).surfaceDelta();

        // then
        assertNotEquals(delta.beforeIdentity(), delta.afterIdentity());
        assertEquals(
                Arrays.asList(
                        "aChannel", "bOperation", "cRemoved",
                        "dAdded", "embedded"),
                keys(delta.contracts()));
        assertChanges(
                delta.contracts(),
                ContractSurfaceDelta.Change.REPLACE,
                ContractSurfaceDelta.Change.REPLACE,
                ContractSurfaceDelta.Change.REMOVE,
                ContractSurfaceDelta.Change.ADD,
                ContractSurfaceDelta.Change.REPLACE);
        assertEquals(Collections.singletonList("aChannel"),
                keys(delta.channels()));
        assertEquals(
                Arrays.asList("bOperation", "cRemoved", "dAdded"),
                keys(delta.operations()));
        assertChanges(
                delta.operations(),
                ContractSurfaceDelta.Change.REPLACE,
                ContractSurfaceDelta.Change.REMOVE,
                ContractSurfaceDelta.Change.ADD);

        assertEquals(1, delta.processEmbedded().size());
        ContractSurfaceDelta.ProcessEmbeddedDeclarationDelta embedded =
                delta.processEmbedded().get(0);
        assertEquals(ContractSurfaceDelta.Change.REPLACE,
                embedded.change());
        assertEquals(Collections.singletonList("/peer"),
                embedded.before().explicitPaths());
        assertTrue(embedded.before().collectionPaths().isEmpty());
        assertTrue(embedded.after().explicitPaths().isEmpty());
        assertEquals(Collections.singletonList("/children"),
                embedded.after().collectionPaths());

        assertEquals(1, delta.subscriptions().size());
        assertEquals(ContractSurfaceDelta.Change.REPLACE,
                delta.subscriptions().get(0).change());
        assertEquals(Collections.singletonList("old"),
                delta.subscriptions().get(0).before().subscriptionKeys());
        assertEquals(Collections.singletonList("new"),
                delta.subscriptions().get(0).after().subscriptionKeys());

        ContractSurfaceDelta.ContractOccurrence channel =
                delta.contracts().get(0).before();
        assertEquals(Collections.singletonList(id("channel-before")),
                channel.sourceContributionNodeBlueIds());
        assertEquals(id("before"),
                channel.headerFieldBlueIds().get("path"));
    }

    @Test
    void shouldRetireOldRoleAndAddNewRouteWhenRoleChanges() {
        // given
        ContractSurfaceReconciler reconciler = reconciler();
        ContractSurfaceReconciler.Capture before = reconciler.newCapture();
        ContractSurfaceReconciler.Capture after = reconciler.newCapture();
        before.record("/", bundle(snapshot(
                "route", "channel-type",
                EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL,
                "before")));
        after.record("/", bundle(snapshot(
                "route", OPERATION_TYPE,
                EffectiveContractSnapshotConstants.Role.HANDLER,
                "after")));

        // when
        ContractSurfaceDelta delta = reconciler.reconcile(
                id("before"), id("after"), before, after,
                SubscriptionDelta.empty()).surfaceDelta();

        // then
        assertEquals(ContractSurfaceDelta.Change.REPLACE,
                delta.contracts().get(0).change());
        assertEquals(ContractSurfaceDelta.Change.REMOVE,
                delta.channels().get(0).change());
        assertEquals(ContractSurfaceDelta.Change.ADD,
                delta.operations().get(0).change());
    }

    @Test
    void shouldRejectConflictingOccurrenceCapture() {
        // given
        ContractSurfaceReconciler reconciler = reconciler();
        ContractSurfaceReconciler.Capture capture = reconciler.newCapture();
        ContractBundle first = bundle(snapshot(
                "same", "channel-type",
                EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL,
                "one"));
        capture.record("/", first);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> capture.record(
                        "/", bundle(snapshot(
                        "same", "channel-type",
                        EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL,
                        "two"))));

        // then
        assertTrue(failure instanceof IllegalStateException);
    }

    @Test
    void shouldProduceEmptyDeltaForIdenticalSurfaces() {
        // given
        ContractSurfaceReconciler reconciler = reconciler();
        ContractBundle surface = bundle(snapshot(
                "same", "channel-type",
                EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL,
                "one"));
        ContractSurfaceReconciler.Capture before = reconciler.newCapture();
        ContractSurfaceReconciler.Capture after = reconciler.newCapture();
        before.record("/", surface);
        after.record("/", surface);

        // when
        ContractSurfaceDelta noOp = reconciler.reconcile(
                id("same"), id("same"), before, after,
                SubscriptionDelta.empty()).surfaceDelta();

        // then
        assertTrue(noOp.contracts().isEmpty());
        assertTrue(noOp.channels().isEmpty());
        assertTrue(noOp.operations().isEmpty());
        assertTrue(noOp.processEmbedded().isEmpty());
        assertTrue(noOp.subscriptions().isEmpty());
    }

    private static ContractSurfaceReconciler reconciler() {
        return new ContractSurfaceReconciler(
                OPERATION_TYPE::equals);
    }

    private static ContractBundle beforeBundle() {
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/peer");
        embedded.setKey("embedded");
        return ContractBundle.builder()
                .addEffectiveContractSnapshot(embeddedSnapshot(
                        "embedded", "before-embedded"))
                .addEffectiveContractSnapshot(snapshot(
                        "cRemoved", OPERATION_TYPE,
                        EffectiveContractSnapshotConstants.Role.HANDLER,
                        "removed"))
                .addEffectiveContractSnapshot(snapshot(
                        "bOperation", OPERATION_TYPE,
                        EffectiveContractSnapshotConstants.Role.HANDLER,
                        "before-operation"))
                .addEffectiveContractSnapshot(snapshot(
                        "aChannel", "channel-type",
                        EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL,
                        "channel-before"))
                .setEmbedded(embedded)
                .build();
    }

    private static ContractBundle afterBundle() {
        ProcessEmbedded embedded =
                new ProcessEmbedded().addCollectionPath("/children");
        embedded.setKey("embedded");
        return ContractBundle.builder()
                .addEffectiveContractSnapshot(snapshot(
                        "dAdded", OPERATION_TYPE,
                        EffectiveContractSnapshotConstants.Role.HANDLER,
                        "added"))
                .addEffectiveContractSnapshot(snapshot(
                        "aChannel", "channel-type",
                        EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL,
                        "channel-after"))
                .addEffectiveContractSnapshot(snapshot(
                        "bOperation", OPERATION_TYPE,
                        EffectiveContractSnapshotConstants.Role.HANDLER,
                        "after-operation"))
                .addEffectiveContractSnapshot(embeddedSnapshot(
                        "embedded", "after-embedded"))
                .setEmbedded(embedded)
                .build();
    }

    private static ContractBundle bundle(EffectiveContractSnapshot snapshot) {
        return ContractBundle.builder()
                .addEffectiveContractSnapshot(snapshot)
                .build();
    }

    private static EffectiveContractSnapshot embeddedSnapshot(
            String key,
            String source) {
        return snapshot(
                key,
                "process-embedded-type",
                EffectiveContractSnapshotConstants.Role.PROCESS_EMBEDDED,
                source);
    }

    private static EffectiveContractSnapshot snapshot(
            String key,
            String type,
            String role,
            String source) {
        return EffectiveContractSnapshot.builder("/", key)
                .sourceContribution(id(source))
                .effectiveTypeBlueId(type)
                .role(role)
                .headerField("path", FrozenNode.fromNode(
                        new Node().value(source.contains("before")
                                ? "before" : "after")))
                .build();
    }

    private static SubscriptionDelta.Entry subscription(String key) {
        return new SubscriptionDelta.Entry(
                "/", "aChannel", "external-type",
                Collections.singletonList(key), "checkpoint-domain");
    }

    private static List<String> keys(
            List<? extends ContractSurfaceDelta.ContractOccurrenceDelta>
                    values) {
        java.util.ArrayList<String> result =
                new java.util.ArrayList<String>();
        for (ContractSurfaceDelta.ContractOccurrenceDelta value : values) {
            result.add(value.key());
        }
        return result;
    }

    private static void assertChanges(
            List<? extends ContractSurfaceDelta.ContractOccurrenceDelta>
                    values,
            ContractSurfaceDelta.Change... expected) {
        java.util.ArrayList<ContractSurfaceDelta.Change> actual =
                new java.util.ArrayList<ContractSurfaceDelta.Change>();
        for (ContractSurfaceDelta.ContractOccurrenceDelta value : values) {
            actual.add(value.change());
        }
        assertEquals(Arrays.asList(expected), actual);
    }

    private static String id(String value) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value(value));
    }
}
