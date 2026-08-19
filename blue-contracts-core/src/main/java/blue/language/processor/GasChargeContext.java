package blue.language.processor;

/**
 * Immutable deterministic attribution attached to a gas trace entry.
 *
 * <p>Scope, contract, and logical path are optional because some kernel work
 * is global. The reason is always non-null, and the shared empty value is safe
 * to reuse because the class has no mutable state.</p>
 */
public final class GasChargeContext {

    private static final GasChargeContext EMPTY =
            new GasChargeContext(
                    null, null, null, null, null, null, null,
                    "unspecified", null, null, null);

    private final String documentId;
    private final String scopePath;
    private final Long activationGeneration;
    private final Long componentGeneration;
    private final String contractKey;
    private final String logicalPath;
    private final String workOccurrenceId;
    private final String reason;
    private final Long finalizationOrdinal;
    private final String finalizationComponentIdentity;
    private final Long finalizationComponentGeneration;

    private GasChargeContext(String documentId,
                             String scopePath,
                             Long activationGeneration,
                             Long componentGeneration,
                             String contractKey,
                             String logicalPath,
                             String workOccurrenceId,
                             String reason,
                             Long finalizationOrdinal,
                             String finalizationComponentIdentity,
                             Long finalizationComponentGeneration) {
        this.documentId = documentId;
        this.scopePath = scopePath;
        this.activationGeneration = activationGeneration;
        this.componentGeneration = componentGeneration;
        this.contractKey = contractKey;
        this.logicalPath = logicalPath;
        this.workOccurrenceId = workOccurrenceId;
        this.reason = reason != null ? reason : "unspecified";
        boolean noFinalizationOwner = finalizationOrdinal == null
                && finalizationComponentIdentity == null
                && finalizationComponentGeneration == null;
        boolean completeFinalizationOwner = finalizationOrdinal != null
                && finalizationComponentIdentity != null
                && finalizationComponentGeneration != null;
        if (!noFinalizationOwner && !completeFinalizationOwner) {
            throw new IllegalArgumentException(
                    "Finalization charge ownership must be complete or absent");
        }
        requireNonNegative(finalizationOrdinal, "finalizationOrdinal");
        requireNonNegative(
                finalizationComponentGeneration,
                "finalizationComponentGeneration");
        this.finalizationOrdinal = finalizationOrdinal;
        this.finalizationComponentIdentity =
                finalizationComponentIdentity;
        this.finalizationComponentGeneration =
                finalizationComponentGeneration;
    }

    /**
     * Returns the attribution used for global work with no semantic owner.
     *
     * @return shared attribution with no scope, contract, or path
     */
    public static GasChargeContext empty() {
        return EMPTY;
    }

    /**
     * Creates a complete immutable charge attribution.
     *
     * @param scopePath optional scope attribution
     * @param contractKey optional contract attribution
     * @param logicalPath optional logical path attribution
     * @param reason deterministic charge reason, or {@code null}
     * @return immutable attribution context
     */
    public static GasChargeContext of(String scopePath,
                                      String contractKey,
                                      String logicalPath,
                                      String reason) {
        return new GasChargeContext(
                null,
                scopePath,
                null,
                null,
                contractKey,
                logicalPath,
                null,
                reason,
                null,
                null,
                null);
    }

    /**
     * Creates complete affected-closure charge attribution.
     *
     * <p>The closure profile currently uses Root scope ({@code /}) and
     * activation generation zero, but this value deliberately records the
     * exact fields instead of inferring them while serializing the trace.</p>
     *
     * @param documentId optional stable managed-document identity
     * @param scopePath optional managed scope path
     * @param activationGeneration optional scope generation, paired with path
     * @param componentGeneration optional component generation
     * @param contractKey optional exact contract key
     * @param logicalPath optional deterministic logical path
     * @param workOccurrenceId optional owning work identity
     * @param reason deterministic reason, or {@code null}
     * @return immutable complete attribution
     */
    public static GasChargeContext closure(
            String documentId,
            String scopePath,
            Long activationGeneration,
            Long componentGeneration,
            String contractKey,
            String logicalPath,
            String workOccurrenceId,
            String reason) {
        if ((scopePath == null) != (activationGeneration == null)) {
            throw new IllegalArgumentException(
                    "scopePath and activationGeneration must appear together");
        }
        requireNonNegative(activationGeneration, "activationGeneration");
        requireNonNegative(componentGeneration, "componentGeneration");
        return new GasChargeContext(
                documentId,
                scopePath,
                activationGeneration,
                componentGeneration,
                contractKey,
                logicalPath,
                workOccurrenceId,
                reason,
                null,
                null,
                null);
    }

    /**
     * Adds the exact tentative-component-finalization owner used only if this
     * charge is rejected.
     *
     * <p>The metadata is deliberately not part of an admitted gas-trace entry;
     * it selects the closed {@code FINALIZATION} rejected-charge owner branch.
     * Calling this method does not change ordinary attribution fields.</p>
     *
     * @param ordinal invocation-global tentative-finalization ordinal
     * @param componentIdentity stable component identity
     * @param componentGeneration exact component generation
     * @return immutable attribution carrying exact finalization ownership
     */
    public GasChargeContext withFinalizationOwner(
            long ordinal,
            String componentIdentity,
            long componentGeneration) {
        if (componentIdentity == null || componentIdentity.isEmpty()) {
            throw new IllegalArgumentException(
                    "Finalization component identity must not be empty");
        }
        requireNonNegative(Long.valueOf(ordinal), "finalizationOrdinal");
        requireNonNegative(
                Long.valueOf(componentGeneration),
                "finalizationComponentGeneration");
        return new GasChargeContext(
                documentId,
                scopePath,
                activationGeneration,
                this.componentGeneration,
                contractKey,
                logicalPath,
                workOccurrenceId,
                reason,
                Long.valueOf(ordinal),
                componentIdentity,
                Long.valueOf(componentGeneration));
    }

    /**
     * Creates an attribution containing only a deterministic reason.
     *
     * @param reason deterministic charge reason
     * @return reason-only context
     */
    public static GasChargeContext reason(String reason) {
        return of(null, null, null, reason);
    }

    /**
     * Returns the attributed managed document.
     *
     * @return document identity, or {@code null}
     */
    public String documentId() {
        return documentId;
    }

    /**
     * Returns the semantic scope charged for the work.
     *
     * @return attributed scope, or {@code null}
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the attributed managed-scope activation generation.
     *
     * @return scope generation, or {@code null}
     */
    public Long activationGeneration() {
        return activationGeneration;
    }

    /**
     * Returns the attributed component generation.
     *
     * @return component generation, or {@code null}
     */
    public Long componentGeneration() {
        return componentGeneration;
    }

    /**
     * Returns the contract charged for the work.
     *
     * @return attributed contract key, or {@code null}
     */
    public String contractKey() {
        return contractKey;
    }

    /**
     * Returns the logical document path charged for the work.
     *
     * @return attributed logical path, or {@code null}
     */
    public String logicalPath() {
        return logicalPath;
    }

    /**
     * Returns the owning affected-closure work occurrence identity.
     *
     * @return work occurrence identity, or {@code null}
     */
    public String workOccurrenceId() {
        return workOccurrenceId;
    }

    /**
     * Returns the deterministic reason recorded in the gas trace.
     *
     * @return non-null deterministic reason
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the invocation-global tentative-finalization ordinal when this
     * charge has a {@code FINALIZATION} rejection owner.
     *
     * @return finalization ordinal, or {@code null}
     */
    public Long finalizationOrdinal() {
        return finalizationOrdinal;
    }

    /**
     * Returns the stable component identity for a finalization-owned charge.
     *
     * @return component identity, or {@code null}
     */
    public String finalizationComponentIdentity() {
        return finalizationComponentIdentity;
    }

    /**
     * Returns the component generation for a finalization-owned charge.
     *
     * @return component generation, or {@code null}
     */
    public Long finalizationComponentGeneration() {
        return finalizationComponentGeneration;
    }

    GasChargeContext withAttributionDefaults(GasChargeContext defaults) {
        if (defaults == null || defaults == EMPTY) {
            return this;
        }
        return new GasChargeContext(
                documentId != null ? documentId : defaults.documentId,
                scopePath != null ? scopePath : defaults.scopePath,
                activationGeneration != null
                        ? activationGeneration
                        : defaults.activationGeneration,
                componentGeneration != null
                        ? componentGeneration
                        : defaults.componentGeneration,
                contractKey != null ? contractKey : defaults.contractKey,
                logicalPath != null ? logicalPath : defaults.logicalPath,
                workOccurrenceId != null
                        ? workOccurrenceId
                        : defaults.workOccurrenceId,
                reason,
                finalizationOrdinal != null
                        ? finalizationOrdinal
                        : defaults.finalizationOrdinal,
                finalizationComponentIdentity != null
                        ? finalizationComponentIdentity
                        : defaults.finalizationComponentIdentity,
                finalizationComponentGeneration != null
                        ? finalizationComponentGeneration
                        : defaults.finalizationComponentGeneration);
    }

    private static void requireNonNegative(Long value, String field) {
        if (value != null && value.longValue() < 0L) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
