package blue.language.processor;

/**
 * Stable vocabulary used to construct processor-owned identity descriptors.
 *
 * <p>These names and values are hashed as Blue content. Changing any constant
 * therefore changes checkpoint or dependency identities and requires an
 * explicit protocol-version decision.</p>
 */
final class ProcessorIdentityConstants {

    /** Contracts protocol version committed by checkpoint-domain descriptors. */
    static final String CONTRACTS_VERSION = "1.0";

    /**
     * Separator retained by the internal compound selector-key encoding.
     *
     * <p>This value is identity-adjacent compatibility data; changing it would
     * alter lookup-key behavior for retained dependency snapshots.</p>
     */
    static final String SELECTOR_COMPONENT_DELIMITER = "\u0000";

    private ProcessorIdentityConstants() {
    }

    /**
     * Identity-bearing descriptor field names.
     */
    static final class Field {
        static final String KIND = "kind";
        static final String CONTRACTS_VERSION = "contractsVersion";
        static final String CHANNEL_KEY = "channelKey";
        static final String ORDER = "order";
        static final String EFFECTIVE_TYPE_BLUE_ID =
                "effectiveTypeBlueId";
        static final String SOURCE_CONTRIBUTION_NODE_BLUE_IDS =
                "sourceContributionNodeBlueIds";
        static final String DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS =
                "deterministicDependencyNodeBlueIds";
        static final String RUNTIME_DISCRIMINATOR =
                "runtimeDiscriminator";
        static final String ORDERED_DEPENDENCY_NODE_BLUE_IDS =
                "orderedDependencyNodeBlueIds";
        static final String ORDERED_CHANNEL_ENTRY_IDENTITY_BLUE_IDS =
                "orderedChannelEntryIdentityBlueIds";
        static final String EFFECTIVE_CONTRACT_KEYS =
                "effectiveContractKeys";
        static final String CHECKPOINT_DOMAIN_BLUE_ID =
                "checkpointDomainBlueId";
        static final String ROLE = "role";
        static final String HEADER_IDENTITY_BLUE_ID =
                "headerIdentityBlueId";
        static final String EXCLUDING_CHANNEL_KEY =
                "excludingChannelKey";
        static final String ORDERED_MEMBER_IDENTITY_BLUE_IDS =
                "orderedMemberIdentityBlueIds";
        static final String ORDERED_MEMBER_EFFECTIVE_TYPE_BLUE_IDS =
                "orderedMemberEffectiveTypeBlueIds";

        private Field() {
        }
    }

    /**
     * Identity-bearing descriptor kind discriminators.
     */
    static final class Kind {
        static final String WHOLE_SAME_SCOPE_EXTERNAL_SURFACE =
                "whole-same-scope-external-surface";
        static final String WHOLE_SAME_SCOPE_CHANNEL_CATALOG =
                "whole-same-scope-channel-catalog";
        static final String SAME_SCOPE_CHANNEL_HEADER =
                "same-scope-channel-header";
        static final String SAME_SCOPE_EXTERNAL_TYPE_FAMILY =
                "same-scope-external-type-family";
        static final String SAME_SCOPE_EXTERNAL_ASSIGNABLE_TYPE_FAMILY =
                "same-scope-external-assignable-type-family";

        private Kind() {
        }
    }
}
