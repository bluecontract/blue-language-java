package blue.language.processor;

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
    private final List<String> executableBodyNodeBlueIds;
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
        this.executableBodyNodeBlueIds = immutable(builder.executableBodyNodeBlueIds);
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

    public List<String> executableBodyNodeBlueIds() {
        return executableBodyNodeBlueIds;
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
        private final List<String> executableBodyNodeBlueIds = new ArrayList<>();
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
