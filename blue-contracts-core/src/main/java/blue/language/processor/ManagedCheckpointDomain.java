package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Complete exact default checkpoint-domain value and its ordinary BlueId. */
public final class ManagedCheckpointDomain {

    private final String effectiveTypeBlueId;
    private final List<String> sourceContributionNodeBlueIds;
    private final List<String> deterministicDependencyNodeBlueIds;
    private final String runtimeDiscriminator;
    private final FrozenNode exactValue;
    private final String blueId;

    ManagedCheckpointDomain(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            List<String> deterministicDependencyNodeBlueIds,
            String runtimeDiscriminator,
            Node exactValue,
            String assertedBlueId) {
        this.effectiveTypeBlueId = requireText(
                effectiveTypeBlueId, "effectiveTypeBlueId");
        this.sourceContributionNodeBlueIds = immutableText(
                sourceContributionNodeBlueIds,
                "sourceContributionNodeBlueIds");
        this.deterministicDependencyNodeBlueIds = immutableText(
                deterministicDependencyNodeBlueIds,
                "deterministicDependencyNodeBlueIds");
        this.runtimeDiscriminator = runtimeDiscriminator;
        this.exactValue = FrozenNode.fromNode(
                Objects.requireNonNull(exactValue, "exactValue").clone());
        String calculated = DirectBlueIdCalculator.calculateBlueId(
                this.exactValue.toNode());
        if (!calculated.equals(assertedBlueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Checkpoint-domain value does not identify asserted BlueId",
                    ProcessorErrorCategory.CheckpointPolicyError);
        }
        this.blueId = calculated;
    }

    /**
     * Returns the exact effective External Channel type identity.
     *
     * @return effective type BlueId
     */
    public String effectiveTypeBlueId() { return effectiveTypeBlueId; }

    /**
     * Returns exact source contributions in runtime-defined effective order.
     *
     * @return immutable ordered Source BlueIds
     */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /**
     * Returns every exact dependency committed by the domain constructor.
     *
     * @return immutable ordered dependency BlueIds
     */
    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

    /**
     * Returns the runtime-selected discriminator when one is present.
     *
     * @return discriminator Text, or {@code null}
     */
    public String runtimeDiscriminator() { return runtimeDiscriminator; }

    /**
     * Returns the complete exact checkpoint-domain value defensively.
     *
     * @return detached exact domain value
     */
    public Node exactValue() { return exactValue.toNode(); }

    /**
     * Returns the ordinary Blue Language identity of {@link #exactValue()}.
     *
     * @return exact checkpoint-domain BlueId
     */
    public String blueId() { return blueId; }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }

    private static List<String> immutableText(
            List<String> values,
            String label) {
        List<String> result = new ArrayList<String>();
        for (String value : Objects.requireNonNull(values, label)) {
            result.add(requireText(value, label + " element"));
        }
        return Collections.unmodifiableList(result);
    }
}
