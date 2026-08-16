package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact default checkpoint-domain Blue value.
 *
 * <p>The identity of this value is a Blue Language direct BlueId, not a
 * Contracts RFC-8785/SHA-256 platform identity. Optional fields are absent
 * from {@link #exactValue()} under precisely the same conditions as the
 * production {@code blue.language.processor.CheckpointDomain} constructor.</p>
 */
public final class CheckpointDomain {
    public static final String CONTRACTS_VERSION = "1.0";

    private final String effectiveTypeBlueId;
    private final List<String> sourceContributionNodeBlueIds;
    private final List<String> deterministicDependencyNodeBlueIds;
    private final String runtimeDiscriminator;

    public CheckpointDomain(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            List<String> deterministicDependencyNodeBlueIds,
            String runtimeDiscriminator) {
        this.effectiveTypeBlueId = requireNonEmpty(
                effectiveTypeBlueId, "effectiveTypeBlueId");
        this.sourceContributionNodeBlueIds = immutableBlueIds(
                sourceContributionNodeBlueIds, "sourceContributionNodeBlueIds");
        this.deterministicDependencyNodeBlueIds = immutableBlueIds(
                deterministicDependencyNodeBlueIds,
                "deterministicDependencyNodeBlueIds");
        this.runtimeDiscriminator = runtimeDiscriminator == null
                || runtimeDiscriminator.isEmpty() ? null : runtimeDiscriminator;
    }

    public CheckpointDomain(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            String runtimeDiscriminator) {
        this(
                effectiveTypeBlueId,
                sourceContributionNodeBlueIds,
                Collections.<String>emptyList(),
                runtimeDiscriminator);
    }

    public String contractsVersion() { return CONTRACTS_VERSION; }
    public String effectiveTypeBlueId() { return effectiveTypeBlueId; }
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }
    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }
    public String runtimeDiscriminator() { return runtimeDiscriminator; }

    /** Returns the exact Blue object, omitting semantically absent fields. */
    public Map<String, Object> exactValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("contractsVersion", CONTRACTS_VERSION);
        value.put("effectiveTypeBlueId", effectiveTypeBlueId);
        value.put("sourceContributionNodeBlueIds", sourceContributionNodeBlueIds);
        if (!deterministicDependencyNodeBlueIds.isEmpty()) {
            value.put(
                    "deterministicDependencyNodeBlueIds",
                    deterministicDependencyNodeBlueIds);
        }
        if (runtimeDiscriminator != null) {
            value.put("runtimeDiscriminator", runtimeDiscriminator);
        }
        return Collections.unmodifiableMap(value);
    }

    /** Calculates the exact direct BlueId through the embedding Language API. */
    public String checkpointDomainBlueId(DirectBlueIdCalculator calculator) {
        return Objects.requireNonNull(
                Objects.requireNonNull(calculator, "calculator")
                        .calculate(exactValue()),
                "checkpointDomainBlueId");
    }

    public interface DirectBlueIdCalculator {
        String calculate(Map<String, Object> exactBlueValue);
    }

    private static List<String> immutableBlueIds(
            List<String> values, String field) {
        ArrayList<String> copy = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                copy.add(requireNonEmpty(value, field + " item"));
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireNonEmpty(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field);
        }
        return value;
    }
}
