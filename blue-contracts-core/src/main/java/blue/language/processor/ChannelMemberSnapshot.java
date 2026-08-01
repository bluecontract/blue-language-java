package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable read-only view of one effective same-scope Channel header.
 *
 * <p>This snapshot is deliberately distinct from
 * {@link ExternalChannelMemberSnapshot}. It proves that an effective contract
 * has a Channel runtime role and exposes its immutable header facts, but it
 * cannot evaluate the Channel as an External source, derive subscription or
 * checkpoint state, execute handlers, or materialize an executable body.</p>
 *
 * <p>The header node is returned defensively and carries no synthetic merged
 * contract identity. {@link #headerIdentityBlueId()} is the exact identity of
 * the frozen effective header used to create this view. Ordered Source
 * contribution identities remain available separately.</p>
 */
public final class ChannelMemberSnapshot {

    private final String channelKey;
    private final int order;
    private final String effectiveTypeBlueId;
    private final String role;
    private final List<String> sourceContributionNodeBlueIds;
    private final List<String> deterministicDependencyNodeBlueIds;
    private final String headerIdentityBlueId;
    private final Node contractNode;

    ChannelMemberSnapshot(
            String channelKey,
            int order,
            String effectiveTypeBlueId,
            String role,
            List<String> sourceContributionNodeBlueIds,
            List<String> deterministicDependencyNodeBlueIds,
            String headerIdentityBlueId,
            Node contractNode) {
        this.channelKey = requireText(channelKey, "channelKey");
        this.order = order;
        this.effectiveTypeBlueId =
                requireText(effectiveTypeBlueId, "effectiveTypeBlueId");
        if (!EffectiveContractSnapshotConstants
                .Role.EXTERNAL_CHANNEL.equals(role)
                && !EffectiveContractSnapshotConstants
                .Role.PROCESSOR_CHANNEL.equals(role)) {
            throw new IllegalArgumentException(
                    "Unsupported Channel runtime role: " + role);
        }
        this.role = role;
        this.sourceContributionNodeBlueIds =
                immutable(sourceContributionNodeBlueIds);
        this.deterministicDependencyNodeBlueIds =
                immutable(deterministicDependencyNodeBlueIds);
        this.headerIdentityBlueId =
                requireText(headerIdentityBlueId, "headerIdentityBlueId");
        this.contractNode =
                Objects.requireNonNull(contractNode, "contractNode").clone();
    }

    /**
     * Freezes the sanitized effective header of one Channel-role contract.
     *
     * <p>Package-private callers share this factory so dispatch verification
     * and External-function evaluation cannot disagree about the target
     * identity. Executable-body fields are never consulted.</p>
     */
    static ChannelMemberSnapshot from(
            EffectiveContractSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Node headerNode = new Node().type(
                new Node().blueId(
                        snapshot.effectiveTypeBlueId()));
        for (Map.Entry<String, FrozenNode> field
                : snapshot.headerFields().entrySet()) {
            headerNode.properties(
                    field.getKey(),
                    field.getValue().toNode());
        }
        MaterializationProvenance.clear(headerNode);
        FrozenNode exactHeader =
                FrozenNode.fromResolvedNode(headerNode);
        return new ChannelMemberSnapshot(
                snapshot.key(),
                snapshot.order(),
                snapshot.effectiveTypeBlueId(),
                snapshot.role(),
                snapshot.sourceContributionNodeBlueIds(),
                snapshot.deterministicDependencyNodeBlueIds(),
                exactHeader.blueId(),
                exactHeader.toNode());
    }

    /**
     * Returns the exact raw same-scope contract key.
     *
     * @return the contract key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns the effective Channel dispatch order, defaulting to zero.
     *
     * @return the deterministic dispatch order
     */
    public int order() {
        return order;
    }

    /**
     * Returns the exact effective runtime type BlueId.
     *
     * @return the runtime type identity
     */
    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    /**
     * Returns {@code external-channel} or {@code processor-channel}.
     *
     * @return the effective channel role
     */
    public String role() {
        return role;
    }

    /**
     * Returns whether this Channel also has External-source semantics.
     *
     * @return {@code true} for an External Channel
     */
    public boolean externalSource() {
        return EffectiveContractSnapshotConstants
                .Role.EXTERNAL_CHANNEL.equals(role);
    }

    /**
     * Returns exact ancestor-to-descendant Source contribution identities.
     *
     * @return an immutable, deterministic identity list
     */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /**
     * Returns deterministic header dependencies carried by the effective
     * Channel snapshot.
     *
     * @return an immutable dependency identity list
     */
    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

    /**
     * Returns the exact frozen effective-header identity consulted by the
     * classification function.
     *
     * @return the effective-header BlueId
     */
    public String headerIdentityBlueId() {
        return headerIdentityBlueId;
    }

    /**
     * Returns a defensive copy of the immutable effective Channel header.
     *
     * @return a mutable copy owned by the caller
     */
    public Node contractNode() {
        return contractNode.clone();
    }

    private static List<String> immutable(List<String> source) {
        return Collections.unmodifiableList(
                new ArrayList<>(
                        Objects.requireNonNull(source, "source")));
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }
}
