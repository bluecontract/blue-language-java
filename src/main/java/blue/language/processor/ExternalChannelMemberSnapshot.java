package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable same-scope External Channel view exposed to another registered
 * External Channel runtime function.
 *
 * <p>A view returned by direct or whole-surface lookup already has its derived
 * subscription header. A type-family lookup is shallow: identity fields and
 * exact contract content are available immediately, while
 * {@link #dependencies()}, {@link #channelKeys()},
 * {@link #checkpointDomainBlueId()}, and {@link #evaluate(Node)} resolve only
 * the selected member and promote it to a full dependency of the owner.
 * Member event evaluation is available only from an event-evaluation function;
 * a snapshot retained or consulted by a subscription-header function fails
 * closed when {@code evaluate} is called.</p>
 */
public final class ExternalChannelMemberSnapshot {

    /** Phase-bound strategy for evaluating an exact event. */
    interface Evaluator {

        /**
         * Evaluates one defensively copied event.
         *
         * @param exactEvent exact event owned by the evaluator
         * @return immutable member evaluation
         */
        ExternalChannelMemberEvaluation evaluate(Node exactEvent);
    }

    /** Lazily resolved subscription header for one selected member. */
    interface Header {

        /**
         * Resolves the member's exact dependencies.
         *
         * @return immutable dependency snapshot
         */
        ExternalChannelDependencySnapshot dependencies();

        /**
         * Resolves the finite subscription-key set.
         *
         * @return immutable keys in runtime-defined order
         */
        List<String> channelKeys();

        /**
         * Resolves the member's checkpoint domain.
         *
         * @return exact checkpoint-domain BlueId
         */
        String checkpointDomainBlueId();
    }

    private final String channelKey;
    private final int order;
    private final String effectiveTypeBlueId;
    private final List<String> sourceContributionNodeBlueIds;
    private final Header header;
    private final Node contractNode;
    private final Evaluator evaluator;

    ExternalChannelMemberSnapshot(
            String channelKey,
            int order,
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            ExternalChannelDependencySnapshot dependencies,
            List<String> channelKeys,
            String checkpointDomainBlueId,
            Node contractNode,
            Evaluator evaluator) {
        this.channelKey = Objects.requireNonNull(
                channelKey, "channelKey");
        this.order = order;
        this.effectiveTypeBlueId = Objects.requireNonNull(
                effectiveTypeBlueId, "effectiveTypeBlueId");
        this.sourceContributionNodeBlueIds =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                sourceContributionNodeBlueIds));
        final ExternalChannelDependencySnapshot exactDependencies =
                Objects.requireNonNull(
                        dependencies, "dependencies");
        final List<String> exactKeys =
                Collections.unmodifiableList(
                        new ArrayList<>(channelKeys));
        final String exactDomain = Objects.requireNonNull(
                checkpointDomainBlueId,
                "checkpointDomainBlueId");
        this.header = new Header() {
            /** {@inheritDoc} */
            @Override
            public ExternalChannelDependencySnapshot dependencies() {
                return exactDependencies;
            }

            /** {@inheritDoc} */
            @Override
            public List<String> channelKeys() {
                return exactKeys;
            }

            /** {@inheritDoc} */
            @Override
            public String checkpointDomainBlueId() {
                return exactDomain;
            }
        };
        this.contractNode = Objects.requireNonNull(
                contractNode, "contractNode").clone();
        this.evaluator = Objects.requireNonNull(
                evaluator, "evaluator");
    }

    ExternalChannelMemberSnapshot(
            String channelKey,
            int order,
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            Node contractNode,
            Header header,
            Evaluator evaluator) {
        this.channelKey = Objects.requireNonNull(
                channelKey, "channelKey");
        this.order = order;
        this.effectiveTypeBlueId = Objects.requireNonNull(
                effectiveTypeBlueId, "effectiveTypeBlueId");
        this.sourceContributionNodeBlueIds =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                sourceContributionNodeBlueIds));
        this.header = Objects.requireNonNull(header, "header");
        this.contractNode = Objects.requireNonNull(
                contractNode, "contractNode").clone();
        this.evaluator = Objects.requireNonNull(
                evaluator, "evaluator");
    }

    /**
     * Returns the member's key within its owning scope.
     *
     * @return exact same-scope channel key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns the member's stable dispatch position.
     *
     * @return deterministic channel dispatch order
     */
    public int order() {
        return order;
    }

    /**
     * Returns the effective type used to select the registered runtime.
     *
     * @return exact effective runtime type BlueId
     */
    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    /**
     * Returns identities of the exact nodes contributing to this member.
     *
     * @return immutable ancestor-to-descendant contribution identities
     */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /**
     * Resolves the exact dependencies captured for this member.
     *
     * @return immutable dependency snapshot
     */
    public ExternalChannelDependencySnapshot dependencies() {
        return header.dependencies();
    }

    /**
     * Resolves the member's finite subscription-key set.
     *
     * @return immutable keys in runtime-defined order
     */
    public List<String> channelKeys() {
        return header.channelKeys();
    }

    /**
     * Resolves the member's exact checkpoint domain.
     *
     * @return checkpoint-domain BlueId
     */
    public String checkpointDomainBlueId() {
        return header.checkpointDomainBlueId();
    }

    /**
     * Returns the immutable effective contract content defensively.
     *
     * @return a mutable copy owned by the caller
     */
    public Node contractNode() {
        return contractNode.clone();
    }

    /**
     * Evaluates an exact event using this member's registered functions.
     *
     * @param exactEvent exact event; cloned before evaluation
     * @return immutable evaluation result
     * @throws NullPointerException when {@code exactEvent} is null
     * @throws IllegalStateException when evaluation is unavailable in this phase
     */
    public ExternalChannelMemberEvaluation evaluate(
            Node exactEvent) {
        return evaluator.evaluate(
                Objects.requireNonNull(
                        exactEvent, "exactEvent").clone());
    }
}
