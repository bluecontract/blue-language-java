package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Closure-neutral exact projection of one independently managed Root Channel.
 *
 * <p>The projection contains only processor-derived immutable evidence. It
 * deliberately contains no managed-document identity, graph generation, or
 * component generation; those values belong to the closure orchestrator.</p>
 */
public final class ManagedRootChannelOccurrence {

    private final String rawChannelKey;
    private final int order;
    private final boolean externalSource;
    private final String effectiveTypeBlueId;
    private final String effectiveRuntimeContributionBlueId;
    private final String subscriptionHeaderBlueId;
    private final List<String> sourceContributionNodeBlueIds;
    private final List<String> deterministicDependencyNodeBlueIds;

    ManagedRootChannelOccurrence(
            String rawChannelKey,
            int order,
            boolean externalSource,
            String effectiveTypeBlueId,
            String effectiveRuntimeContributionBlueId,
            String subscriptionHeaderBlueId,
            List<String> sourceContributionNodeBlueIds,
            List<String> deterministicDependencyNodeBlueIds) {
        this.rawChannelKey = requireText(rawChannelKey, "rawChannelKey");
        this.order = order;
        this.externalSource = externalSource;
        this.effectiveTypeBlueId = requireText(
                effectiveTypeBlueId, "effectiveTypeBlueId");
        this.effectiveRuntimeContributionBlueId = requireText(
                effectiveRuntimeContributionBlueId,
                "effectiveRuntimeContributionBlueId");
        this.subscriptionHeaderBlueId = requireText(
                subscriptionHeaderBlueId, "subscriptionHeaderBlueId");
        this.sourceContributionNodeBlueIds = immutableText(
                sourceContributionNodeBlueIds,
                "sourceContributionNodeBlueIds");
        this.deterministicDependencyNodeBlueIds = immutableText(
                deterministicDependencyNodeBlueIds,
                "deterministicDependencyNodeBlueIds");
    }

    /**
     * Returns the exact raw Root contract key.
     *
     * @return non-empty raw Channel key
     */
    public String rawChannelKey() { return rawChannelKey; }

    /**
     * Returns the effective dispatch order used by canonical projection.
     *
     * @return effective Channel order
     */
    public int order() { return order; }

    /**
     * Reports whether this Channel may accept an external source occurrence.
     *
     * @return {@code true} for a registered External Channel
     */
    public boolean externalSource() { return externalSource; }

    /**
     * Returns the exact effective runtime type identity.
     *
     * @return effective Channel type BlueId
     */
    public String effectiveTypeBlueId() { return effectiveTypeBlueId; }

    /**
     * Returns the exact normalized effective contribution identity.
     *
     * @return ordinary BlueId of the effective contribution
     */
    public String effectiveRuntimeContributionBlueId() {
        return effectiveRuntimeContributionBlueId;
    }

    /**
     * Returns the exact sanitized effective subscription-header identity.
     *
     * @return ordinary BlueId of the effective Channel header
     */
    public String subscriptionHeaderBlueId() {
        return subscriptionHeaderBlueId;
    }

    /**
     * Returns exact source contribution identities in effective order.
     *
     * @return immutable ordered Source BlueIds
     */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /**
     * Returns exact static header dependencies in deterministic order.
     *
     * @return immutable ordered dependency BlueIds
     */
    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }

    private static List<String> immutableText(
            List<String> source,
            String label) {
        List<String> result = new ArrayList<String>();
        for (String value : Objects.requireNonNull(source, label)) {
            result.add(requireText(value, label + " element"));
        }
        return Collections.unmodifiableList(result);
    }
}
