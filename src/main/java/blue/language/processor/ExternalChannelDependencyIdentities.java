package blue.language.processor;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Builds canonical Blue identities for external dependency descriptors. */
final class ExternalChannelDependencyIdentities {

    private ExternalChannelDependencyIdentities() {
    }

    static String surface(List<String> orderedIdentities) {
        Node descriptor = new Node()
                .properties(
                        ProcessorIdentityConstants.Field.KIND,
                        new Node().value(
                                ProcessorIdentityConstants.Kind
                                        .WHOLE_SAME_SCOPE_EXTERNAL_SURFACE))
                .properties(
                        ProcessorIdentityConstants.Field
                                .ORDERED_DEPENDENCY_NODE_BLUE_IDS,
                        textList(orderedIdentities));
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

    static String channelCatalog(
            List<ExternalChannelDependencySnapshot.ChannelEntry> entries,
            List<String> contractKeys) {
        List<String> identities = new ArrayList<>(entries.size());
        for (ExternalChannelDependencySnapshot.ChannelEntry entry : entries) {
            identities.add(entry.identityBlueId());
        }
        Node descriptor = new Node()
                .properties(
                        ProcessorIdentityConstants.Field.KIND,
                        new Node().value(
                                ProcessorIdentityConstants.Kind
                                        .WHOLE_SAME_SCOPE_CHANNEL_CATALOG))
                .properties(
                        ProcessorIdentityConstants.Field
                                .ORDERED_CHANNEL_ENTRY_IDENTITY_BLUE_IDS,
                        textList(identities))
                .properties(
                        ProcessorIdentityConstants.Field
                                .EFFECTIVE_CONTRACT_KEYS,
                        textList(contractKeys));
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

    static String entry(
            String channelKey,
            int order,
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            List<String> deterministicDependencyNodeBlueIds,
            String checkpointDomainBlueId) {
        Node descriptor = new Node()
                .properties(ProcessorIdentityConstants.Field.CHANNEL_KEY,
                        new Node().value(channelKey))
                .properties(ProcessorIdentityConstants.Field.ORDER,
                        new Node().value(BigInteger.valueOf(order)))
                .properties(
                        ProcessorIdentityConstants.Field.EFFECTIVE_TYPE_BLUE_ID,
                        new Node().value(effectiveTypeBlueId))
                .properties(
                        ProcessorIdentityConstants.Field
                                .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                        textList(sourceContributionNodeBlueIds))
                .properties(
                        ProcessorIdentityConstants.Field
                                .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                        textList(deterministicDependencyNodeBlueIds))
                .properties(
                        ProcessorIdentityConstants.Field
                                .CHECKPOINT_DOMAIN_BLUE_ID,
                        new Node().value(checkpointDomainBlueId));
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

    static String channelEntry(
            String channelKey,
            int order,
            String effectiveTypeBlueId,
            String role,
            List<String> sourceContributionNodeBlueIds,
            List<String> deterministicDependencyNodeBlueIds,
            String headerIdentityBlueId) {
        Node descriptor = new Node()
                .properties(ProcessorIdentityConstants.Field.KIND,
                        new Node().value(
                                ProcessorIdentityConstants.Kind
                                        .SAME_SCOPE_CHANNEL_HEADER))
                .properties(ProcessorIdentityConstants.Field.CHANNEL_KEY,
                        new Node().value(channelKey))
                .properties(ProcessorIdentityConstants.Field.ORDER,
                        new Node().value(BigInteger.valueOf(order)))
                .properties(
                        ProcessorIdentityConstants.Field.EFFECTIVE_TYPE_BLUE_ID,
                        new Node().value(effectiveTypeBlueId))
                .properties(ProcessorIdentityConstants.Field.ROLE,
                        new Node().value(role))
                .properties(
                        ProcessorIdentityConstants.Field
                                .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                        textList(sourceContributionNodeBlueIds))
                .properties(
                        ProcessorIdentityConstants.Field
                                .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                        textList(deterministicDependencyNodeBlueIds))
                .properties(
                        ProcessorIdentityConstants.Field
                                .HEADER_IDENTITY_BLUE_ID,
                        new Node().value(headerIdentityBlueId));
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

    static String typeFamily(
            String excludingChannelKey,
            String effectiveTypeBlueId,
            ExternalChannelDependencySnapshot.TypeMatchMode matchMode,
            List<ExternalChannelDependencySnapshot.Member> members) {
        List<String> memberIdentities = new ArrayList<>(members.size());
        for (ExternalChannelDependencySnapshot.Member member : members) {
            memberIdentities.add(member.identityBlueId());
        }
        Node descriptor = new Node()
                .properties(ProcessorIdentityConstants.Field.KIND,
                        new Node().value(
                                matchMode == ExternalChannelDependencySnapshot
                                        .TypeMatchMode.EXACT
                                        ? ProcessorIdentityConstants.Kind
                                        .SAME_SCOPE_EXTERNAL_TYPE_FAMILY
                                        : ProcessorIdentityConstants.Kind
                                        .SAME_SCOPE_EXTERNAL_ASSIGNABLE_TYPE_FAMILY))
                .properties(
                        ProcessorIdentityConstants.Field.EXCLUDING_CHANNEL_KEY,
                        new Node().value(excludingChannelKey))
                .properties(
                        ProcessorIdentityConstants.Field.EFFECTIVE_TYPE_BLUE_ID,
                        new Node().value(effectiveTypeBlueId))
                .properties(
                        ProcessorIdentityConstants.Field
                                .ORDERED_MEMBER_IDENTITY_BLUE_IDS,
                        textList(memberIdentities));
        if (matchMode == ExternalChannelDependencySnapshot
                .TypeMatchMode.ASSIGNABLE) {
            List<String> actualTypes = new ArrayList<>(members.size());
            for (ExternalChannelDependencySnapshot.Member member : members) {
                actualTypes.add(member.effectiveTypeBlueId());
            }
            descriptor.properties(
                    ProcessorIdentityConstants.Field
                            .ORDERED_MEMBER_EFFECTIVE_TYPE_BLUE_IDS,
                    textList(actualTypes));
        }
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

    static String member(
            String channelKey,
            int order,
            List<String> sourceContributionNodeBlueIds,
            List<String> deterministicDependencyNodeBlueIds) {
        Node descriptor = new Node()
                .properties(ProcessorIdentityConstants.Field.CHANNEL_KEY,
                        new Node().value(channelKey))
                .properties(ProcessorIdentityConstants.Field.ORDER,
                        new Node().value(BigInteger.valueOf(order)))
                .properties(
                        ProcessorIdentityConstants.Field
                                .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                        textList(sourceContributionNodeBlueIds))
                .properties(
                        ProcessorIdentityConstants.Field
                                .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                        textList(deterministicDependencyNodeBlueIds));
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

    static Node textList(List<String> values) {
        List<Node> items = new ArrayList<>(values.size());
        for (String value : values) {
            items.add(new Node().value(value));
        }
        return new Node().items(items);
    }
}
