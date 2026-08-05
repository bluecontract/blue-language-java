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
    private final Map<String, ExecutableBodySourceDescriptor>
            executableBodySourceDescriptorsByField;
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
        this.executableBodySourceDescriptorsByField =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                builder.executableBodySourceDescriptorsByField));
        validateExecutableBodySourceDescriptors();
        this.deterministicDependencyNodeBlueIds =
                immutable(builder.deterministicDependencyNodeBlueIds);
    }

    /**
     * Starts a snapshot for one effective same-scope contract.
     *
     * @param scopePath normalized owning scope
     * @param key exact contract key
     * @return a new mutable builder
     */
    public static Builder builder(String scopePath, String key) {
        return new Builder(scopePath, key);
    }

    /**
     * Returns the normalized scope that owns this contract occurrence.
     *
     * @return normalized owning scope
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the exact same-scope contract key.
     *
     * @return exact same-scope contract key
     */
    public String key() {
        return key;
    }

    /**
     * Returns source contribution identities in merge order.
     *
     * @return immutable ancestor-to-descendant source identities
     */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /**
     * Returns the effective runtime type identity used for dispatch.
     *
     * @return exact effective runtime type BlueId
     */
    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    /**
     * Returns the recognized runtime dispatch role.
     *
     * @return deterministic runtime dispatch role
     */
    public String role() {
        return role;
    }

    /**
     * Returns the effective contract ordering value.
     *
     * @return effective dispatch order
     */
    public int order() {
        return order;
    }

    /**
     * Returns normalized scalar fields used for dispatch.
     *
     * @return immutable normalized dispatch-field values
     */
    public Map<String, String> dispatchFields() {
        return dispatchFields;
    }

    /**
     * Exact immutable effective header fields, excluding every field declared
     * by the selected runtime as an executable body.
     *
     * <p>The fields are exposed individually so this snapshot never invents a
     * BlueId for the effective merged contract.</p>
     *
     * @return immutable field-to-frozen-value mapping
     */
    public Map<String, FrozenNode> headerFields() {
        return headerFields;
    }

    /**
     * Ordered executable-body field names declared by the selected runtime
     * type. A declared field remains present here when the effective contract
     * supplies no body at that field.
     *
     * @return immutable ordered executable-body field names
     */
    public List<String> executableBodyFields() {
        return executableBodyFields;
    }

    /**
     * Returns identities of present executable bodies in declared field order.
     *
     * @return immutable executable-body identities in field order
     */
    public List<String> executableBodyNodeBlueIds() {
        return executableBodyNodeBlueIds;
    }

    /**
     * Exact identities of the executable bodies that are present, keyed by
     * their registered field names. A pure-reference body contributes its
     * requested identity without being materialized.
     *
     * @return immutable field-to-body-identity mapping
     */
    public Map<String, String> executableBodyNodeBlueIdsByField() {
        return executableBodyNodeBlueIdsByField;
    }

    /**
     * Exact Source descriptors for the executable bodies that are present,
     * keyed by their registered field names.
     *
     * <p>A descriptor keeps the preserved body BlueId and its owning Source
     * contribution separate from the effective merged contract, for which no
     * synthetic identity exists.</p>
     *
     * @return immutable field-to-source-descriptor mapping
     */
    public Map<String, ExecutableBodySourceDescriptor>
    executableBodySourceDescriptorsByField() {
        return executableBodySourceDescriptorsByField;
    }

    /**
     * Returns exact dependency identities used to validate this snapshot.
     *
     * @return immutable exact dependency identities in deterministic order
     */
    public List<String> deterministicDependencyNodeBlueIds() {
        return deterministicDependencyNodeBlueIds;
    }

    private void validateExecutableBodySourceDescriptors() {
        for (Map.Entry<String, ExecutableBodySourceDescriptor> entry
                : executableBodySourceDescriptorsByField.entrySet()) {
            String field = entry.getKey();
            ExecutableBodySourceDescriptor descriptor =
                    entry.getValue();
            if (!scopePath.equals(descriptor.scopePath())
                    || !key.equals(descriptor.contractKey())
                    || !effectiveTypeBlueId.equals(
                            descriptor.effectiveTypeBlueId())
                    || !field.equals(descriptor.bodyField())
                    || !Objects.equals(
                            executableBodyNodeBlueIdsByField.get(field),
                            descriptor.bodyNodeBlueId())
                    || !sourceContributionNodeBlueIds.equals(
                            descriptor.sourceContributionNodeBlueIds())) {
                throw new IllegalArgumentException(
                        "Executable-body descriptor is not bound to its effective contract snapshot");
            }
        }
    }

    private static List<String> immutable(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    /**
     * Mutable, single-use accumulator for an effective contract snapshot.
     */
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
        private final Map<String, ExecutableBodySourceDescriptor>
                executableBodySourceDescriptorsByField =
                new LinkedHashMap<>();
        private final List<String> deterministicDependencyNodeBlueIds = new ArrayList<>();

        private Builder(String scopePath, String key) {
            this.scopePath = scopePath;
            this.key = key;
        }

        /**
         * Appends one exact source contribution identity.
         *
         * @param blueId exact source contribution identity; null is ignored
         * @return this builder
         */
        public Builder sourceContribution(String blueId) {
            if (blueId != null) {
                sourceContributionNodeBlueIds.add(blueId);
            }
            return this;
        }

        /**
         * Sets the recognized effective runtime type identity.
         *
         * @param blueId exact effective runtime type identity
         * @return this builder
         */
        public Builder effectiveTypeBlueId(String blueId) {
            this.effectiveTypeBlueId = blueId;
            return this;
        }

        /**
         * Sets the deterministic dispatch role.
         *
         * @param role deterministic runtime dispatch role
         * @return this builder
         */
        public Builder role(String role) {
            this.role = role;
            return this;
        }

        /**
         * Sets the deterministic dispatch ordering value.
         *
         * @param order deterministic dispatch order
         * @return this builder
         */
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        /**
         * Adds a normalized non-null dispatch field.
         *
         * @param name field name; null is ignored
         * @param value field value converted to text; null is ignored
         * @return this builder
         */
        public Builder dispatchField(String name, Object value) {
            if (name != null && value != null) {
                dispatchFields.put(name, String.valueOf(value));
            }
            return this;
        }

        /**
         * Appends a legacy executable-body identity.
         *
         * @param blueId exact body identity; null is ignored
         * @return this builder
         */
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

        Builder executableBodySourceDescriptor(
                String field,
                ExecutableBodySourceDescriptor descriptor) {
            if (field != null && descriptor != null) {
                if (!field.equals(descriptor.bodyField())) {
                    throw new IllegalArgumentException(
                            "Executable-body descriptor field mismatch");
                }
                executableBodySourceDescriptorsByField.put(
                        field, descriptor);
            }
            return this;
        }

        /**
         * Appends one exact dependency identity.
         *
         * @param blueId exact dependency identity; null is ignored
         * @return this builder
         */
        public Builder deterministicDependency(String blueId) {
            if (blueId != null) {
                deterministicDependencyNodeBlueIds.add(blueId);
            }
            return this;
        }

        /**
         * Validates and freezes the accumulated snapshot.
         *
         * @return a new immutable snapshot
         * @throws NullPointerException when a required identity is absent
         * @throws IllegalArgumentException when body-source metadata is inconsistent
         */
        public EffectiveContractSnapshot build() {
            return new EffectiveContractSnapshot(this);
        }
    }
}
