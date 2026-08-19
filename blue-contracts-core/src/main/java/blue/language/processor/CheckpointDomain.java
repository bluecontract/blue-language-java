package blue.language.processor;

import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default deterministic checkpoint-domain derivation.
 *
 * <p>The dependency-aware form commits the exact ordered identities captured
 * by same-scope member, type-family, or whole-surface consultation. Changing
 * those semantics rotates the domain even when the channel's subscription-key
 * set is unchanged.</p>
 */
public final class CheckpointDomain {

    private CheckpointDomain() {
    }

    /**
     * Derives a checkpoint domain without additional same-scope dependencies.
     *
     * @param effectiveTypeBlueId exact effective channel type identity
     * @param sourceContributionNodeBlueIds ordered source contribution identities
     * @param runtimeDiscriminator optional runtime implementation discriminator
     * @return the deterministic domain BlueId
     * @throws IllegalArgumentException when {@code effectiveTypeBlueId} is empty
     */
    public static String derive(String effectiveTypeBlueId,
                                List<String> sourceContributionNodeBlueIds,
                                String runtimeDiscriminator) {
        return derive(
                effectiveTypeBlueId,
                sourceContributionNodeBlueIds,
                ExternalChannelDependencySnapshot.none(),
                runtimeDiscriminator);
    }

    /**
     * Derives a checkpoint domain that commits all consulted dependencies.
     *
     * <p>Null contribution or dependency collections are interpreted as empty;
     * the returned identity is therefore deterministic for equivalent semantic
     * input and never depends on mutable collection identity.</p>
     *
     * @param effectiveTypeBlueId exact effective channel type identity
     * @param sourceContributionNodeBlueIds ordered source contribution identities
     * @param dependencies exact same-scope dependencies, or {@code null}
     * @param runtimeDiscriminator optional runtime implementation discriminator
     * @return the deterministic domain BlueId
     * @throws IllegalArgumentException when {@code effectiveTypeBlueId} is empty
     */
    public static String derive(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            ExternalChannelDependencySnapshot dependencies,
            String runtimeDiscriminator) {
        return DirectBlueIdCalculator.calculateBlueId(value(
                effectiveTypeBlueId,
                sourceContributionNodeBlueIds,
                dependencies,
                runtimeDiscriminator));
    }

    /**
     * Constructs the complete exact default-domain value before it is reduced
     * to a BlueId reference in processor-owned checkpoint state.
     *
     * <p>This is the authoritative inverse surface used by atomic hosts that
     * must publish complete before/after checkpoint evidence. It shares the
     * same constructor as {@link #derive(String, List,
     * ExternalChannelDependencySnapshot, String)}.</p>
     *
     * @param effectiveTypeBlueId exact effective channel type identity
     * @param sourceContributionNodeBlueIds ordered source identities
     * @param dependencies exact deterministic dependencies, or {@code null}
     * @param runtimeDiscriminator optional non-empty runtime discriminator
     * @return detached complete exact domain value
     */
    public static Node value(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            ExternalChannelDependencySnapshot dependencies,
            String runtimeDiscriminator) {
        if (effectiveTypeBlueId == null || effectiveTypeBlueId.isEmpty()) {
            throw new IllegalArgumentException("effectiveTypeBlueId must not be empty");
        }
        List<String> contributions = sourceContributionNodeBlueIds != null
                ? sourceContributionNodeBlueIds
                : Collections.<String>emptyList();
        Node domain = new Node()
                .properties(
                        ProcessorIdentityConstants.Field.CONTRACTS_VERSION,
                        new Node().value(
                                ProcessorIdentityConstants.CONTRACTS_VERSION))
                .properties(
                        ProcessorIdentityConstants.Field.EFFECTIVE_TYPE_BLUE_ID,
                        new Node().value(effectiveTypeBlueId));
        List<Node> contributionItems = new ArrayList<Node>();
        for (String blueId : contributions) {
            if (blueId == null || blueId.isEmpty()) {
                throw new IllegalArgumentException(
                        "source contribution BlueIds must be non-empty");
            }
            contributionItems.add(new Node().value(blueId));
        }
        domain.properties(
                ProcessorIdentityConstants.Field
                        .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                new Node().items(contributionItems));
        ExternalChannelDependencySnapshot exactDependencies =
                dependencies != null
                        ? dependencies
                        : ExternalChannelDependencySnapshot.none();
        if (!exactDependencies
                .deterministicDependencyNodeBlueIds()
                .isEmpty()) {
            List<Node> dependencyItems = new ArrayList<Node>();
            for (String blueId : exactDependencies
                    .deterministicDependencyNodeBlueIds()) {
                dependencyItems.add(
                        new Node().value(blueId));
            }
            domain.properties(
                    ProcessorIdentityConstants.Field
                            .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                    new Node().items(dependencyItems));
        }
        if (runtimeDiscriminator != null && !runtimeDiscriminator.isEmpty()) {
            domain.properties(
                    ProcessorIdentityConstants.Field.RUNTIME_DISCRIMINATOR,
                    new Node().value(runtimeDiscriminator));
        }
        return domain;
    }
}
