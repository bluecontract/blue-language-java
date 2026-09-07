package blue.language.processor.closure;

import java.util.Objects;

/**
 * Exact immutable evidence binding one authored embedded occurrence to a
 * stable managed-document lineage.
 */
public final class ManagedOccurrenceBinding
        implements Comparable<ManagedOccurrenceBinding> {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private final String occurrenceIdentity;
    private final String bindingIdentity;
    private final String bindingPolicyIdentity;
    private final DocumentId sourceDocumentId;
    private final ScopeAddress sourceAddress;
    private final DocumentId targetDocumentId;
    private final String expectedTargetBlueId;
    private final boolean active;
    private final Long pendingHistoricalEpoch;
    private final ManagedRepresentationCursor pendingRepresentationCursor;

    /**
     * Creates one closed occurrence-binding row.
     *
     * @param occurrenceIdentity stable occurrence identity
     * @param bindingIdentity exact state-specific binding identity
     * @param bindingPolicyIdentity selected binding-policy identity
     * @param sourceDocumentId containing authored document lineage
     * @param sourceAddress authored embedded occurrence address
     * @param targetDocumentId selected managed target lineage
     * @param expectedTargetBlueId exact authored target state
     * @param active whether this row contributes a graph edge
     * @param pendingHistoricalEpoch nullable historical catch-up cursor;
     *     {@code -1} denotes the authored pre-initialization value
     */
    public ManagedOccurrenceBinding(
            String occurrenceIdentity,
            String bindingIdentity,
            String bindingPolicyIdentity,
            DocumentId sourceDocumentId,
            ScopeAddress sourceAddress,
            DocumentId targetDocumentId,
            String expectedTargetBlueId,
            boolean active,
            Long pendingHistoricalEpoch) {
        this(occurrenceIdentity, bindingIdentity, bindingPolicyIdentity,
                sourceDocumentId, sourceAddress, targetDocumentId,
                expectedTargetBlueId, active, pendingHistoricalEpoch, null);
    }

    private ManagedOccurrenceBinding(String occurrenceIdentity, String bindingIdentity,
            String bindingPolicyIdentity, DocumentId sourceDocumentId,
            ScopeAddress sourceAddress, DocumentId targetDocumentId,
            String expectedTargetBlueId, boolean active, Long pendingHistoricalEpoch,
            ManagedRepresentationCursor pendingRepresentationCursor) {
        this.pendingRepresentationCursor = pendingRepresentationCursor;
        if (pendingRepresentationCursor != null && (active || pendingHistoricalEpoch == null
                || pendingHistoricalEpoch.longValue() < 0L)) {
            throw new IllegalArgumentException("Representation progress requires an inactive numbered historical cursor");
        }
        this.occurrenceIdentity = ClosureValueSupport.requireSha256Identity(
                occurrenceIdentity, "occurrenceIdentity");
        this.bindingIdentity = ClosureValueSupport.requireSha256Identity(
                bindingIdentity, "bindingIdentity");
        this.bindingPolicyIdentity = ClosureValueSupport.requireSha256Identity(
                bindingPolicyIdentity, "bindingPolicyIdentity");
        this.sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        this.sourceAddress = Objects.requireNonNull(
                sourceAddress, "sourceAddress");
        if (sourceAddress.isRoot()) {
            throw new IllegalArgumentException(
                    "An embedded occurrence must have a non-Root source address");
        }
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.expectedTargetBlueId = ClosureValueSupport.requireBlueId(
                expectedTargetBlueId, "expectedTargetBlueId");
        this.active = active;
        this.pendingHistoricalEpoch = pendingHistoricalEpoch == null
                ? null
                : Long.valueOf(ClosureValueSupport.requireManagedEpochCursor(
                        pendingHistoricalEpoch.longValue(),
                        "pendingHistoricalEpoch"));
        if (active && this.pendingHistoricalEpoch != null) {
            throw new IllegalArgumentException(
                    "An active occurrence cannot retain a historical cursor");
        }
    }

    /**
     * Derives both normative identities from complete typed occurrence
     * evidence and creates the corresponding row.
     *
     * <p>This is the authoritative construction boundary for hosts that own
     * occurrence lifecycle but must not reproduce Contracts canonical JSON or
     * hashing.</p>
     *
     * @param bindingPolicyIdentity selected binding-policy identity
     * @param sourceDocumentId containing authored document lineage
     * @param sourceAddress authored embedded occurrence address
     * @param targetDocumentId selected managed target lineage
     * @param expectedTargetBlueId exact authored target state
     * @param active whether this row contributes a graph edge
     * @param pendingHistoricalEpoch nullable historical catch-up cursor;
     *     {@code -1} denotes the authored pre-initialization value
     * @return exact Contracts-owned occurrence row
     */
    public static ManagedOccurrenceBinding derived(
            String bindingPolicyIdentity,
            DocumentId sourceDocumentId,
            ScopeAddress sourceAddress,
            DocumentId targetDocumentId,
            String expectedTargetBlueId,
            boolean active,
            Long pendingHistoricalEpoch) {
        String policy = ClosureValueSupport.requireSha256Identity(
                bindingPolicyIdentity, "bindingPolicyIdentity");
        DocumentId source = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        ScopeAddress address = requireEmbedded(sourceAddress);
        DocumentId target = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        String expected = ClosureValueSupport.requireBlueId(
                expectedTargetBlueId, "expectedTargetBlueId");
        return new ManagedOccurrenceBinding(
                IDENTITIES.managedOccurrenceIdentity(
                        source, address, target, policy),
                IDENTITIES.managedOccurrenceBindingIdentity(
                        source, address, target, expected, policy),
                policy,
                source,
                address,
                target,
                expected,
                active,
                pendingHistoricalEpoch);
    }

    /**
     * Verifies supplied identity assertions against complete typed evidence
     * before returning the corresponding row.
     *
     * @param occurrenceIdentity claimed stable occurrence identity
     * @param bindingIdentity claimed exact-state binding identity
     * @param bindingPolicyIdentity selected binding-policy identity
     * @param sourceDocumentId containing authored document lineage
     * @param sourceAddress authored embedded occurrence address
     * @param targetDocumentId selected managed target lineage
     * @param expectedTargetBlueId exact authored target state
     * @param active whether this row contributes a graph edge
     * @param pendingHistoricalEpoch nullable historical catch-up cursor;
     *     {@code -1} denotes the authored pre-initialization value
     * @return verified Contracts-owned occurrence row
     */
    public static ManagedOccurrenceBinding verified(
            String occurrenceIdentity,
            String bindingIdentity,
            String bindingPolicyIdentity,
            DocumentId sourceDocumentId,
            ScopeAddress sourceAddress,
            DocumentId targetDocumentId,
            String expectedTargetBlueId,
            boolean active,
            Long pendingHistoricalEpoch) {
        ManagedOccurrenceBinding selected = new ManagedOccurrenceBinding(
                occurrenceIdentity,
                bindingIdentity,
                bindingPolicyIdentity,
                sourceDocumentId,
                sourceAddress,
                targetDocumentId,
                expectedTargetBlueId,
                active,
                pendingHistoricalEpoch);
        String exactOccurrence = IDENTITIES.managedOccurrenceIdentity(
                selected.sourceDocumentId(),
                selected.sourceAddress(),
                selected.targetDocumentId(),
                selected.bindingPolicyIdentity());
        if (!selected.occurrenceIdentity().equals(exactOccurrence)) {
            throw new IllegalArgumentException(
                    "occurrenceIdentity does not match its closed evidence");
        }
        String exactBinding = IDENTITIES.managedOccurrenceBindingIdentity(
                selected.sourceDocumentId(),
                selected.sourceAddress(),
                selected.targetDocumentId(),
                selected.expectedTargetBlueId(),
                selected.bindingPolicyIdentity());
        if (!selected.bindingIdentity().equals(exactBinding)) {
            throw new IllegalArgumentException(
                    "bindingIdentity does not match its closed evidence");
        }
        return selected;
    }

    /**
     * Returns the documented value.
     *
     * @return stable occurrence identity
     */
    public String occurrenceIdentity() {
        return occurrenceIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return state-specific binding identity
     */
    public String bindingIdentity() {
        return bindingIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return selected binding-policy identity
     */
    public String bindingPolicyIdentity() {
        return bindingPolicyIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return source document lineage
     */
    public DocumentId sourceDocumentId() {
        return sourceDocumentId;
    }

    /**
     * Returns the documented value.
     *
     * @return source occurrence address
     */
    public ScopeAddress sourceAddress() {
        return sourceAddress;
    }

    /**
     * Returns the documented value.
     *
     * @return absolute authored source path
     */
    public String sourcePath() {
        return sourceAddress.path();
    }

    /**
     * Returns the documented value.
     *
     * @return positive occurrence generation
     */
    public long activationGeneration() {
        return sourceAddress.activationGeneration();
    }

    /**
     * Returns the documented value.
     *
     * @return target document lineage
     */
    public DocumentId targetDocumentId() {
        return targetDocumentId;
    }

    /**
     * Returns the documented value.
     *
     * @return exact expected target BlueId
     */
    public String expectedTargetBlueId() {
        return expectedTargetBlueId;
    }

    /**
     * Returns the documented value.
     *
     * @return whether this row contributes an active graph edge
     */
    public boolean active() {
        return active;
    }

    /**
     * Returns the documented value.
     *
     * @return nullable historical catch-up cursor, with {@code -1} denoting
     *     the authored pre-initialization value
     */
    public Long pendingHistoricalEpoch() {
        return pendingHistoricalEpoch;
    }

    /**
     * Returns the pending exact representation position, or null outside such traversal.
     *
     * @return the pending exact representation position, or null outside such traversal
     */
    public ManagedRepresentationCursor pendingRepresentationCursor() { return pendingRepresentationCursor; }

    /**
     * Copies this binding with the specified historical representation cursor.
     * @param cursor new position, or null to clear representation traversal
     * @return a new binding retaining every other field
     */
    public ManagedOccurrenceBinding withRepresentationCursor(ManagedRepresentationCursor cursor) {
        return new ManagedOccurrenceBinding(occurrenceIdentity, bindingIdentity, bindingPolicyIdentity,
                sourceDocumentId, sourceAddress, targetDocumentId, expectedTargetBlueId,
                active, pendingHistoricalEpoch, cursor);
    }

    /**
     * Compares occurrence identity then binding identity.
     *
     * @param other row to compare
     * @return canonical row order
     */
    @Override
    public int compareTo(ManagedOccurrenceBinding other) {
        int order = ClosureValueSupport.comparePortableText(
                occurrenceIdentity, other.occurrenceIdentity);
        return order != 0
                ? order
                : ClosureValueSupport.comparePortableText(
                        bindingIdentity, other.bindingIdentity);
    }

    private static ScopeAddress requireEmbedded(ScopeAddress sourceAddress) {
        ScopeAddress checked = Objects.requireNonNull(
                sourceAddress, "sourceAddress");
        if (checked.isRoot()) {
            throw new IllegalArgumentException(
                    "An embedded occurrence must have a non-Root source address");
        }
        return checked;
    }
}
