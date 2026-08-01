package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SubscriptionValidationServicesTest {

    private static final String ROOT_SCOPE = "/";
    private static final String CHILD_SCOPE = "/child";
    private static final String CHANNEL_KEY = "incoming";
    private static final String OTHER_CHANNEL_KEY = "other";
    private static final String CHECKPOINT_DOMAIN = "domain";

    @Test
    void shouldProjectChangedDirectSubscriptionSurface() {
        // given
        Node channel = scriptedChannel("topic");
        Node root = rootWithChannel(CHANNEL_KEY, channel);
        SubscriptionSurfaceValidationContext context =
                SubscriptionSurfaceValidationContext.builder(
                                root,
                                root.clone(),
                                Collections.singleton(
                                        "/contracts/incoming"),
                                GasSchedule.contracts10())
                        .build();
        SubscriptionSurfaceProjector projector =
                new SubscriptionSurfaceProjector(
                        null, null, null, null);
        Set<String> changes = projector.normalizeChangedPaths(
                context.changedPaths());

        // when
        Map<String, SubscriptionDelta.Entry> surface = projector.project(
                root,
                null,
                context.gasSchedule(),
                changes,
                context);

        // then
        assertEquals(1, surface.size());
        SubscriptionDelta.Entry projected =
                surface.values().iterator().next();
        assertEquals(ROOT_SCOPE, projected.scopePath());
        assertEquals(CHANNEL_KEY, projected.channelKey());
        assertEquals(
                Collections.singletonList("topic"),
                projected.subscriptionKeys());
        assertNull(projected.activationRootRevision());
    }

    @Test
    void shouldBuildReplacementDeltaWithExactCommitInterval() {
        // given
        Node root = new Node();
        ExternalOrderKey previousOrder = ExternalOrderKey.of(
                Arrays.asList(1, "source", 0));
        ExternalOrderKey committingOrder = ExternalOrderKey.of(
                Arrays.asList(2, "source", 0));
        SubscriptionDelta.Entry before = intervalEntry(
                CHANNEL_KEY, "old-topic", 3L, previousOrder);
        SubscriptionDelta.Entry after = unversionedEntry(
                CHANNEL_KEY, "new-topic");
        SubscriptionSurfaceValidationContext context =
                SubscriptionSurfaceValidationContext.builder(
                                root,
                                root.clone(),
                                Collections.singleton(
                                        "/contracts/incoming"),
                                GasSchedule.contracts10())
                        .committingInterval(committingOrder, 4L)
                        .build();
        ActivationIntervalValidator intervals =
                new ActivationIntervalValidator(
                        new SubscriptionSurfaceRules());
        SubscriptionDeltaBuilder builder =
                new SubscriptionDeltaBuilder(intervals);
        Map<String, SubscriptionDelta.Entry> beforeSurface =
                singletonSurface(before);
        Map<String, SubscriptionDelta.Entry> afterSurface =
                singletonSurface(after);

        // when
        SubscriptionDelta delta = builder.build(
                beforeSurface, afterSurface, context);

        // then
        assertEquals(1, delta.removed().size());
        assertEquals(1, delta.added().size());
        assertEquals(Long.valueOf(3L),
                delta.removed().get(0).activationRootRevision());
        assertEquals(previousOrder,
                delta.removed().get(0).startAfterExternalOrderKey());
        assertEquals(Long.valueOf(4L),
                delta.removed().get(0).endAtRootRevision());
        assertEquals(Long.valueOf(4L),
                delta.added().get(0).activationRootRevision());
        assertEquals(committingOrder,
                delta.added().get(0).startAfterExternalOrderKey());
        assertNull(delta.added().get(0).endAtRootRevision());
    }

    @Test
    void shouldSelectOnlyRetainedIntervalsAffectedByChangedDependency() {
        // given
        Node root = rootWithChannel(
                CHANNEL_KEY, scriptedChannel("topic"));
        SubscriptionDelta.Entry affected = unversionedEntry(
                CHANNEL_KEY, "topic");
        SubscriptionDelta.Entry unaffected = new SubscriptionDelta.Entry(
                CHILD_SCOPE,
                OTHER_CHANNEL_KEY,
                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                Collections.singletonList("other-contribution"),
                0,
                Collections.singletonList("other-topic"),
                "other-domain",
                (ExternalOrderKey) null);
        SubscriptionSurfaceValidationContext context =
                SubscriptionSurfaceValidationContext.builder(
                                root,
                                root.clone(),
                                Collections.singleton(
                                        "/contracts/incoming/"
                                                + "subscriptionKey"),
                                GasSchedule.contracts10())
                        .activeSubscriptionIntervals(
                                Arrays.asList(affected, unaffected))
                        .build();
        SubscriptionSurfaceRules rules = new SubscriptionSurfaceRules();
        ActivationIntervalValidator validator =
                new ActivationIntervalValidator(rules);

        // when
        Map<String, SubscriptionDelta.Entry> retained =
                validator.affectedRetainedSurface(
                        context,
                        rules.normalizeChanges(context.changedPaths()));

        // then
        assertEquals(1, retained.size());
        assertTrue(retained.containsKey(affected.occurrenceKey()));
        assertFalse(retained.containsKey(unaffected.occurrenceKey()));
    }

    private static Map<String, SubscriptionDelta.Entry> singletonSurface(
            SubscriptionDelta.Entry entry) {
        Map<String, SubscriptionDelta.Entry> result = new LinkedHashMap<>();
        result.put(entry.occurrenceKey(), entry);
        return result;
    }

    private static SubscriptionDelta.Entry unversionedEntry(
            String channelKey,
            String subscriptionKey) {
        return new SubscriptionDelta.Entry(
                ROOT_SCOPE,
                channelKey,
                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                Collections.singletonList(subscriptionKey),
                CHECKPOINT_DOMAIN);
    }

    private static SubscriptionDelta.Entry intervalEntry(
            String channelKey,
            String subscriptionKey,
            long activationRevision,
            ExternalOrderKey start) {
        return new SubscriptionDelta.Entry(
                ROOT_SCOPE,
                channelKey,
                RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                Collections.singletonList("contribution"),
                0,
                Collections.singletonList(subscriptionKey),
                CHECKPOINT_DOMAIN,
                activationRevision,
                start,
                null);
    }

    private static Node rootWithChannel(String key, Node channel) {
        return new Node().contracts(new Node().properties(key, channel));
    }

    private static Node scriptedChannel(String subscriptionKey) {
        Node channel = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL))
                .properties(
                        "subscriptionKey",
                        new Node().value(subscriptionKey))
                .properties(
                        "checkpointDomain",
                        new Node().value(CHECKPOINT_DOMAIN));
        channel.blueId(DirectBlueIdCalculator.calculateBlueId(channel));
        return channel;
    }
}
