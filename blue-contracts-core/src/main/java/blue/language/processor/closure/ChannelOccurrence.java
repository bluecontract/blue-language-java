package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact Root-scoped Channel occurrence used by closure subscriptions. */
public final class ChannelOccurrence {

    /** Root scope path fixed by the Contracts 1.0 closure profile. */
    public static final String ROOT_SCOPE_PATH = "/";

    /** Root activation generation fixed by the closure profile. */
    public static final long ROOT_ACTIVATION_GENERATION = 0L;

    private final String channelOccurrenceIdentity;
    private final DocumentId managedDocumentId;
    private final String rawChannelKey;
    private final String effectiveRuntimeContributionBlueId;
    private final String subscriptionHeaderBlueId;

    /**
     * Creates and verifies one complete Channel occurrence.
     *
     * @param channelOccurrenceIdentity asserted exact occurrence identity
     * @param managedDocumentId independently managed document
     * @param scopePath required literal {@code /}
     * @param scopeActivationGeneration required literal {@code 0}
     * @param rawChannelKey exact raw Channel key
     * @param effectiveRuntimeContributionBlueId exact runtime contribution ID
     * @param subscriptionHeaderBlueId exact normalized header identity
     */
    public ChannelOccurrence(
            String channelOccurrenceIdentity,
            DocumentId managedDocumentId,
            String scopePath,
            long scopeActivationGeneration,
            String rawChannelKey,
            String effectiveRuntimeContributionBlueId,
            String subscriptionHeaderBlueId) {
        this.managedDocumentId = Objects.requireNonNull(
                managedDocumentId, "managedDocumentId");
        if (!ROOT_SCOPE_PATH.equals(scopePath)
                || scopeActivationGeneration != ROOT_ACTIVATION_GENERATION) {
            throw new IllegalArgumentException(
                    "Contracts 1.0 closure Channel occurrences are Root-scoped");
        }
        this.rawChannelKey = ClosureValueSupport.requireNonEmptyText(
                rawChannelKey, "rawChannelKey");
        this.effectiveRuntimeContributionBlueId =
                ClosureValueSupport.requireBlueId(
                        effectiveRuntimeContributionBlueId,
                        "effectiveRuntimeContributionBlueId");
        this.subscriptionHeaderBlueId = ClosureValueSupport.requireBlueId(
                subscriptionHeaderBlueId, "subscriptionHeaderBlueId");
        String asserted = ClosureValueSupport.requireSha256Identity(
                channelOccurrenceIdentity, "channelOccurrenceIdentity");
        String computed = ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.CHANNEL_OCCURRENCE,
                identityConstructorValue());
        if (!asserted.equals(computed)) {
            throw new IllegalArgumentException(
                    "channelOccurrenceIdentity does not identify this value");
        }
        this.channelOccurrenceIdentity = asserted;
    }

    /**
     * Constructs one occurrence while calculating its exact identity.
     *
     * @param managedDocumentId independently managed document
     * @param rawChannelKey exact raw Channel key
     * @param effectiveRuntimeContributionBlueId exact runtime contribution ID
     * @param subscriptionHeaderBlueId exact normalized header identity
     * @return verified immutable occurrence
     */
    public static ChannelOccurrence root(
            DocumentId managedDocumentId,
            String rawChannelKey,
            String effectiveRuntimeContributionBlueId,
            String subscriptionHeaderBlueId) {
        LinkedHashMap<String, Object> value = constructorValue(
                managedDocumentId,
                rawChannelKey,
                effectiveRuntimeContributionBlueId,
                subscriptionHeaderBlueId);
        String identity = ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.CHANNEL_OCCURRENCE,
                value);
        return new ChannelOccurrence(
                identity,
                managedDocumentId,
                ROOT_SCOPE_PATH,
                ROOT_ACTIVATION_GENERATION,
                rawChannelKey,
                effectiveRuntimeContributionBlueId,
                subscriptionHeaderBlueId);
    }

    /**
     * Returns exact Channel occurrence identity.
     *
     * @return exact Channel occurrence identity
     */
    public String channelOccurrenceIdentity() {
        return channelOccurrenceIdentity;
    }

    /**
     * Returns independently managed document.
     *
     * @return independently managed document
     */
    public DocumentId managedDocumentId() {
        return managedDocumentId;
    }

    /**
     * Returns literal Root path.
     *
     * @return literal Root path
     */
    public String scopePath() {
        return ROOT_SCOPE_PATH;
    }

    /**
     * Returns literal Root generation zero.
     *
     * @return literal Root generation zero
     */
    public long scopeActivationGeneration() {
        return ROOT_ACTIVATION_GENERATION;
    }

    /**
     * Returns exact raw Channel key.
     *
     * @return exact raw Channel key
     */
    public String rawChannelKey() {
        return rawChannelKey;
    }

    /**
     * Returns exact effective runtime contribution identity.
     *
     * @return exact effective runtime contribution identity
     */
    public String effectiveRuntimeContributionBlueId() {
        return effectiveRuntimeContributionBlueId;
    }

    /**
     * Returns exact normalized subscription-header identity.
     *
     * @return exact normalized subscription-header identity
     */
    public String subscriptionHeaderBlueId() {
        return subscriptionHeaderBlueId;
    }

    Map<String, Object> identityConstructorValue() {
        return constructorValue(
                managedDocumentId,
                rawChannelKey,
                effectiveRuntimeContributionBlueId,
                subscriptionHeaderBlueId);
    }

    private static LinkedHashMap<String, Object> constructorValue(
            DocumentId managedDocumentId,
            String rawChannelKey,
            String effectiveRuntimeContributionBlueId,
            String subscriptionHeaderBlueId) {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("managedDocumentId",
                Objects.requireNonNull(managedDocumentId,
                        "managedDocumentId").value());
        value.put("scopePath", ROOT_SCOPE_PATH);
        value.put("scopeActivationGeneration",
                Long.valueOf(ROOT_ACTIVATION_GENERATION));
        value.put("rawChannelKey", rawChannelKey);
        value.put("effectiveRuntimeContributionBlueId",
                effectiveRuntimeContributionBlueId);
        value.put("subscriptionHeaderBlueId", subscriptionHeaderBlueId);
        return value;
    }
}
