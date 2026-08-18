package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Complete exact state of one closure subscription row. */
public final class SubscriptionState {

    private final String subscriptionIdentity;
    private final ChannelOccurrence channelOccurrence;
    private final String documentBlueId;
    private final long graphGeneration;
    private final long componentGeneration;

    /**
     * Creates and verifies one subscription state.
     *
     * @param subscriptionIdentity asserted exact subscription identity
     * @param channelOccurrence complete Root-scoped Channel occurrence
     * @param documentBlueId exact managed-document identity
     * @param graphGeneration exact graph generation
     * @param componentGeneration exact component generation
     */
    public SubscriptionState(
            String subscriptionIdentity,
            ChannelOccurrence channelOccurrence,
            String documentBlueId,
            long graphGeneration,
            long componentGeneration) {
        this.channelOccurrence = Objects.requireNonNull(
                channelOccurrence, "channelOccurrence");
        this.documentBlueId = ClosureValueSupport.requireBlueId(
                documentBlueId, "documentBlueId");
        this.graphGeneration = ClosureValueSupport.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.componentGeneration = ClosureValueSupport.requireSafeInteger(
                componentGeneration, "componentGeneration");
        String asserted = ClosureValueSupport.requireSha256Identity(
                subscriptionIdentity, "subscriptionIdentity");
        String computed = ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.SUBSCRIPTION,
                identityConstructorValue());
        if (!asserted.equals(computed)) {
            throw new IllegalArgumentException(
                    "subscriptionIdentity does not identify this state");
        }
        this.subscriptionIdentity = asserted;
    }

    /**
     * Creates one state while calculating its exact identity.
     *
     * @param channelOccurrence complete Root-scoped Channel occurrence
     * @param documentBlueId exact managed-document identity
     * @param graphGeneration exact graph generation
     * @param componentGeneration exact component generation
     * @return verified immutable state
     */
    public static SubscriptionState identified(
            ChannelOccurrence channelOccurrence,
            String documentBlueId,
            long graphGeneration,
            long componentGeneration) {
        LinkedHashMap<String, Object> value = constructorValue(
                channelOccurrence,
                documentBlueId,
                graphGeneration,
                componentGeneration);
        String identity = ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.SUBSCRIPTION,
                value);
        return new SubscriptionState(
                identity,
                channelOccurrence,
                documentBlueId,
                graphGeneration,
                componentGeneration);
    }

    /**
     * Returns exact subscription identity.
     *
     * @return exact subscription identity
     */
    public String subscriptionIdentity() {
        return subscriptionIdentity;
    }

    /**
     * Returns complete Root-scoped Channel occurrence.
     *
     * @return complete Root-scoped Channel occurrence
     */
    public ChannelOccurrence channelOccurrence() {
        return channelOccurrence;
    }

    /**
     * Returns exact managed-document identity.
     *
     * @return exact managed-document identity
     */
    public String documentBlueId() {
        return documentBlueId;
    }

    /**
     * Returns exact graph generation.
     *
     * @return exact graph generation
     */
    public long graphGeneration() {
        return graphGeneration;
    }

    /**
     * Returns exact component generation.
     *
     * @return exact component generation
     */
    public long componentGeneration() {
        return componentGeneration;
    }

    Map<String, Object> identityConstructorValue() {
        return constructorValue(
                channelOccurrence,
                documentBlueId,
                graphGeneration,
                componentGeneration);
    }

    private static LinkedHashMap<String, Object> constructorValue(
            ChannelOccurrence channelOccurrence,
            String documentBlueId,
            long graphGeneration,
            long componentGeneration) {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("channelOccurrenceIdentity",
                Objects.requireNonNull(channelOccurrence,
                        "channelOccurrence").channelOccurrenceIdentity());
        value.put("documentBlueId", documentBlueId);
        value.put("graphGeneration", Long.valueOf(graphGeneration));
        value.put("componentGeneration", Long.valueOf(componentGeneration));
        return value;
    }
}
