package blue.language.processor;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

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

    public static String derive(String effectiveTypeBlueId,
                                List<String> sourceContributionNodeBlueIds,
                                String runtimeDiscriminator) {
        return derive(
                effectiveTypeBlueId,
                sourceContributionNodeBlueIds,
                ExternalChannelDependencySnapshot.none(),
                runtimeDiscriminator);
    }

    public static String derive(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            ExternalChannelDependencySnapshot dependencies,
            String runtimeDiscriminator) {
        if (effectiveTypeBlueId == null || effectiveTypeBlueId.isEmpty()) {
            throw new IllegalArgumentException("effectiveTypeBlueId must not be empty");
        }
        Node domain = new Node()
                .properties("contractsVersion", new Node().value("1.0"))
                .properties("effectiveTypeBlueId", new Node().value(effectiveTypeBlueId));
        java.util.List<Node> contributionItems = new java.util.ArrayList<>();
        if (sourceContributionNodeBlueIds != null) {
            for (String blueId : sourceContributionNodeBlueIds) {
                contributionItems.add(new Node().value(blueId));
            }
        }
        domain.properties("sourceContributionNodeBlueIds",
                new Node().items(contributionItems));
        ExternalChannelDependencySnapshot exactDependencies =
                dependencies != null
                        ? dependencies
                        : ExternalChannelDependencySnapshot.none();
        if (!exactDependencies
                .deterministicDependencyNodeBlueIds()
                .isEmpty()) {
            java.util.List<Node> dependencyItems =
                    new java.util.ArrayList<>();
            for (String blueId : exactDependencies
                    .deterministicDependencyNodeBlueIds()) {
                dependencyItems.add(
                        new Node().value(blueId));
            }
            domain.properties(
                    "deterministicDependencyNodeBlueIds",
                    new Node().items(dependencyItems));
        }
        if (runtimeDiscriminator != null && !runtimeDiscriminator.isEmpty()) {
            domain.properties("runtimeDiscriminator", new Node().value(runtimeDiscriminator));
        }
        return BlueIdCalculator.calculateBlueId(domain);
    }
}
