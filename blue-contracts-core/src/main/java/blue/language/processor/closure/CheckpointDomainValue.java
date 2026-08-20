package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete exact Blue value used to derive a checkpoint-domain BlueId. */
public final class CheckpointDomainValue {

    /** Exact Contracts version encoded in every domain value. */
    public static final String CONTRACTS_VERSION = "1.0";

    private final String effectiveTypeBlueId;
    private final List<String> sourceContributionNodeBlueIds;
    private final List<String> deterministicDependencyNodeBlueIds;
    private final String runtimeDiscriminator;
    private final String blueId;

    /**
     * Creates and independently identifies one exact checkpoint-domain value.
     * Empty optional dependency input is normalized to the required omitted
     * field; an empty runtime discriminator is never accepted.
     *
     * @param effectiveTypeBlueId exact effective Channel type identity
     * @param sourceContributionNodeBlueIds ordered source contribution IDs
     * @param deterministicDependencyNodeBlueIds ordered dependencies, or empty
     * @param runtimeDiscriminator non-empty runtime discriminator, or null
     */
    public CheckpointDomainValue(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            List<String> deterministicDependencyNodeBlueIds,
            String runtimeDiscriminator) {
        this.effectiveTypeBlueId = ClosureValueSupport.requireBlueId(
                effectiveTypeBlueId, "effectiveTypeBlueId");
        this.sourceContributionNodeBlueIds = immutableBlueIds(
                sourceContributionNodeBlueIds,
                "sourceContributionNodeBlueIds");
        this.deterministicDependencyNodeBlueIds = immutableBlueIds(
                deterministicDependencyNodeBlueIds,
                "deterministicDependencyNodeBlueIds");
        this.runtimeDiscriminator = runtimeDiscriminator == null
                ? null
                : ClosureValueSupport.requireNonEmptyText(
                        runtimeDiscriminator, "runtimeDiscriminator");
        this.blueId = ClosureIdentityService.INSTANCE
                .checkpointDomainBlueId(identityValue());
    }

    /**
     * Returns exact encoded Contracts version.
     *
     * @return exact encoded Contracts version
     */
    public String contractsVersion() {
        return CONTRACTS_VERSION;
    }

    /**
     * Returns exact effective Channel type BlueId.
     *
     * @return exact effective Channel type BlueId
     */
    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    /**
     * Returns immutable ordered source-contribution identities.
     *
     * @return immutable ordered source-contribution identities
     */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /**
     * Returns optional deterministic dependencies.  An empty list represents
     * an omitted serialized field.
     *
     * @return immutable ordered dependency identities
     */
    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

    /**
     * Returns optional non-empty runtime discriminator.
     *
     * @return optional non-empty runtime discriminator
     */
    public String runtimeDiscriminator() {
        return runtimeDiscriminator;
    }

    /**
     * Returns direct Blue Language identity of the complete exact value.
     *
     * @return direct Blue Language identity of the complete exact value
     */
    public String blueId() {
        return blueId;
    }

    Map<String, Object> identityValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("contractsVersion", CONTRACTS_VERSION);
        value.put("effectiveTypeBlueId", effectiveTypeBlueId);
        value.put("sourceContributionNodeBlueIds",
                new ArrayList<String>(sourceContributionNodeBlueIds));
        if (!deterministicDependencyNodeBlueIds.isEmpty()) {
            value.put("deterministicDependencyNodeBlueIds",
                    new ArrayList<String>(
                            deterministicDependencyNodeBlueIds));
        }
        if (runtimeDiscriminator != null) {
            value.put("runtimeDiscriminator", runtimeDiscriminator);
        }
        return value;
    }

    boolean sameValue(CheckpointDomainValue other) {
        return other != null
                && effectiveTypeBlueId.equals(other.effectiveTypeBlueId)
                && sourceContributionNodeBlueIds.equals(
                        other.sourceContributionNodeBlueIds)
                && deterministicDependencyNodeBlueIds.equals(
                        other.deterministicDependencyNodeBlueIds)
                && Objects.equals(
                        runtimeDiscriminator, other.runtimeDiscriminator);
    }

    private static List<String> immutableBlueIds(
            List<String> values,
            String field) {
        ArrayList<String> copy = new ArrayList<String>(
                Objects.requireNonNull(values, field));
        for (int index = 0; index < copy.size(); index++) {
            copy.set(index, ClosureValueSupport.requireBlueId(
                    copy.get(index), field + " item"));
        }
        return Collections.unmodifiableList(copy);
    }
}
