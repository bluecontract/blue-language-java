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

    interface Evaluator {
        ExternalChannelMemberEvaluation evaluate(Node exactEvent);
    }

    interface Header {
        ExternalChannelDependencySnapshot dependencies();

        List<String> channelKeys();

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
            @Override
            public ExternalChannelDependencySnapshot dependencies() {
                return exactDependencies;
            }

            @Override
            public List<String> channelKeys() {
                return exactKeys;
            }

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

    public String channelKey() {
        return channelKey;
    }

    public int order() {
        return order;
    }

    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    public ExternalChannelDependencySnapshot dependencies() {
        return header.dependencies();
    }

    public List<String> channelKeys() {
        return header.channelKeys();
    }

    public String checkpointDomainBlueId() {
        return header.checkpointDomainBlueId();
    }

    public Node contractNode() {
        return contractNode.clone();
    }

    public ExternalChannelMemberEvaluation evaluate(
            Node exactEvent) {
        return evaluator.evaluate(
                Objects.requireNonNull(
                        exactEvent, "exactEvent").clone());
    }
}
