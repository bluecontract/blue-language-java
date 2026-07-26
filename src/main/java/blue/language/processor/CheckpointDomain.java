package blue.language.processor;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

import java.util.List;

/**
 * Default deterministic checkpoint-domain derivation.
 */
public final class CheckpointDomain {

    private CheckpointDomain() {
    }

    public static String derive(String effectiveTypeBlueId,
                                List<String> sourceContributionNodeBlueIds,
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
        if (runtimeDiscriminator != null && !runtimeDiscriminator.isEmpty()) {
            domain.properties("runtimeDiscriminator", new Node().value(runtimeDiscriminator));
        }
        return BlueIdCalculator.calculateBlueId(domain);
    }
}
