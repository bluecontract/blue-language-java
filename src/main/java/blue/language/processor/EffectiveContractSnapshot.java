package blue.language.processor;

import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Immutable out-of-band identity and dispatch snapshot of one effective
 * contract. No synthetic merged-contract BlueId is created.
 */
public final class EffectiveContractSnapshot {

    private final String scopePath;
    private final String key;
    private final List<String> sourceContributionNodeBlueIds;
    private final String effectiveTypeBlueId;
    private final String role;
    private final int order;
    private final Map<String, String> dispatchFields;
    private final Map<String, FrozenNode> headerFields;
    private final List<String> executableBodyFields;
    private final List<String> executableBodyNodeBlueIds;
    private final Map<String, String> executableBodyNodeBlueIdsByField;
    private final List<String> deterministicDependencyNodeBlueIds;

    private EffectiveContractSnapshot(Builder builder) {
        this.scopePath = Objects.requireNonNull(builder.scopePath, "scopePath");
        this.key = Objects.requireNonNull(builder.key, "key");
        this.sourceContributionNodeBlueIds = immutable(builder.sourceContributionNodeBlueIds);
        this.effectiveTypeBlueId =
                Objects.requireNonNull(builder.effectiveTypeBlueId, "effectiveTypeBlueId");
        this.role = Objects.requireNonNull(builder.role, "role");
        this.order = builder.order;
        this.dispatchFields =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.dispatchFields));
        this.headerFields =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.headerFields));
        this.executableBodyFields = immutable(builder.executableBodyFields);
        this.executableBodyNodeBlueIds = immutable(builder.executableBodyNodeBlueIds);
        this.executableBodyNodeBlueIdsByField =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                builder.executableBodyNodeBlueIdsByField));
        this.deterministicDependencyNodeBlueIds =
                immutable(builder.deterministicDependencyNodeBlueIds);
    }

    public static Builder builder(String scopePath, String key) {
        return new Builder(scopePath, key);
    }

    public String scopePath() {
        return scopePath;
    }

    public String key() {
        return key;
    }

    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    public String role() {
        return role;
    }

    public int order() {
        return order;
    }

    public Map<String, String> dispatchFields() {
        return dispatchFields;
    }

    /**
     * Exact immutable effective header fields, excluding every field declared
     * by the selected runtime as an executable body.
     *
     * <p>The fields are exposed individually so this snapshot never invents a
     * BlueId for the effective merged contract.</p>
     */
    public Map<String, FrozenNode> headerFields() {
        return headerFields;
    }

    /**
     * Ordered executable-body field names declared by the selected runtime
     * type. A declared field remains present here when the effective contract
     * supplies no body at that field.
     */
    public List<String> executableBodyFields() {
        return executableBodyFields;
    }

    public List<String> executableBodyNodeBlueIds() {
        return executableBodyNodeBlueIds;
    }

    /**
     * Exact identities of the executable bodies that are present, keyed by
     * their registered field names. A pure-reference body contributes its
     * requested identity without being materialized.
     */
    public Map<String, String> executableBodyNodeBlueIdsByField() {
        return executableBodyNodeBlueIdsByField;
    }

    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

    private static List<String> immutable(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public static final class Builder {
        private final String scopePath;
        private final String key;
        private final List<String> sourceContributionNodeBlueIds = new ArrayList<>();
        private String effectiveTypeBlueId;
        private String role;
        private int order;
        private final Map<String, String> dispatchFields = new LinkedHashMap<>();
        private final Map<String, FrozenNode> headerFields =
                new LinkedHashMap<>();
        private final List<String> executableBodyFields =
                new ArrayList<>();
        private final List<String> executableBodyNodeBlueIds = new ArrayList<>();
        private final Map<String, String> executableBodyNodeBlueIdsByField =
                new LinkedHashMap<>();
        private final List<String> deterministicDependencyNodeBlueIds = new ArrayList<>();

        private Builder(String scopePath, String key) {
            this.scopePath = scopePath;
            this.key = key;
        }

        public Builder sourceContribution(String blueId) {
            if (blueId != null) {
                sourceContributionNodeBlueIds.add(blueId);
            }
            return this;
        }

        public Builder effectiveTypeBlueId(String blueId) {
            this.effectiveTypeBlueId = blueId;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder dispatchField(String name, Object value) {
            if (name != null && value != null) {
                dispatchFields.put(name, String.valueOf(value));
            }
            return this;
        }

        public Builder executableBody(String blueId) {
            if (blueId != null) {
                executableBodyNodeBlueIds.add(blueId);
            }
            return this;
        }

        Builder headerField(String name, FrozenNode value) {
            if (name != null && value != null) {
                headerFields.put(name, value);
            }
            return this;
        }

        Builder executableBodyField(String name) {
            if (name != null && !executableBodyFields.contains(name)) {
                executableBodyFields.add(name);
            }
            return this;
        }

        Builder executableBody(String field, String blueId) {
            executableBodyField(field);
            if (field != null && blueId != null) {
                executableBodyNodeBlueIdsByField.put(field, blueId);
                executableBodyNodeBlueIds.add(blueId);
            }
            return this;
        }

        public Builder deterministicDependency(String blueId) {
            if (blueId != null) {
                deterministicDependencyNodeBlueIds.add(blueId);
            }
            return this;
        }

        public EffectiveContractSnapshot build() {
            return new EffectiveContractSnapshot(this);
        }
    }
}
