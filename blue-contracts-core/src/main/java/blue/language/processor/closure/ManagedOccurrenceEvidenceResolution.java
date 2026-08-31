package blue.language.processor.closure;

import java.util.Objects;

/**
 * Exact host resolution of one managed-occurrence evidence demand.
 *
 * <p>The resolution selects only a durable target lineage and one historical
 * source epoch. Contracts derives and verifies the resulting occurrence row
 * at the exact post-patch demand boundary; callers cannot supply occurrence
 * identities, activation generations, binding policies, or source paths.</p>
 */
public final class ManagedOccurrenceEvidenceResolution
        implements Comparable<ManagedOccurrenceEvidenceResolution> {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private final String resolutionIdentity;
    private final ManagedOccurrenceEvidenceDemand demand;
    private final DocumentId targetDocumentId;
    private final long pendingHistoricalEpoch;

    /**
     * Creates and verifies one exact demand resolution.
     *
     * @param resolutionIdentity asserted resolution identity
     * @param demand exact demand returned by a prior noncommitting attempt
     * @param targetDocumentId selected durable target lineage
     * @param pendingHistoricalEpoch selected historical epoch; {@code -1}
     *     denotes the authored pre-initialization value
     */
    public ManagedOccurrenceEvidenceResolution(
            String resolutionIdentity,
            ManagedOccurrenceEvidenceDemand demand,
            DocumentId targetDocumentId,
            long pendingHistoricalEpoch) {
        this.resolutionIdentity = ClosureValueSupport.requireSha256Identity(
                resolutionIdentity, "resolutionIdentity");
        this.demand = Objects.requireNonNull(demand, "demand");
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.pendingHistoricalEpoch =
                ClosureValueSupport.requireManagedEpochCursor(
                        pendingHistoricalEpoch,
                        "pendingHistoricalEpoch");
        String exact = IDENTITIES.managedOccurrenceResolutionIdentity(
                this.demand.demandIdentity(),
                this.targetDocumentId,
                this.pendingHistoricalEpoch);
        if (!this.resolutionIdentity.equals(exact)) {
            throw new IllegalArgumentException(
                    "resolutionIdentity does not identify this exact "
                            + "managed-occurrence resolution");
        }
    }

    /**
     * Derives one exact managed-occurrence resolution.
     *
     * @param demand exact demand returned by a prior noncommitting attempt
     * @param targetDocumentId selected durable target lineage
     * @param pendingHistoricalEpoch selected historical epoch; {@code -1}
     *     denotes the authored pre-initialization value
     * @return verified resolution of the exact demand
     */
    public static ManagedOccurrenceEvidenceResolution derived(
            ManagedOccurrenceEvidenceDemand demand,
            DocumentId targetDocumentId,
            long pendingHistoricalEpoch) {
        ManagedOccurrenceEvidenceDemand selected = Objects.requireNonNull(
                demand, "demand");
        DocumentId target = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        long epoch = ClosureValueSupport.requireManagedEpochCursor(
                pendingHistoricalEpoch, "pendingHistoricalEpoch");
        return new ManagedOccurrenceEvidenceResolution(
                IDENTITIES.managedOccurrenceResolutionIdentity(
                        selected.demandIdentity(), target, epoch),
                selected,
                target,
                epoch);
    }

    /**
     * Returns the exact resolution identity.
     *
     * @return exact resolution identity
     */
    public String resolutionIdentity() {
        return resolutionIdentity;
    }

    /**
     * Returns the exact demand resolved by this value.
     *
     * @return exact resolved demand
     */
    public ManagedOccurrenceEvidenceDemand demand() {
        return demand;
    }

    /**
     * Returns the selected durable target lineage.
     *
     * @return selected target document identity
     */
    public DocumentId targetDocumentId() {
        return targetDocumentId;
    }

    /**
     * Returns the selected historical source epoch.
     *
     * @return selected epoch, or {@code -1} for the authored initial value
     */
    public long pendingHistoricalEpoch() {
        return pendingHistoricalEpoch;
    }

    @Override
    public int compareTo(ManagedOccurrenceEvidenceResolution other) {
        ManagedOccurrenceEvidenceResolution selected = Objects.requireNonNull(
                other, "other");
        return ClosureValueSupport.comparePortableText(
                resolutionIdentity, selected.resolutionIdentity);
    }
}
