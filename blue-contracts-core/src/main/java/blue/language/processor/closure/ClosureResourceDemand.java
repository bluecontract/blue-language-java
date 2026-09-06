package blue.language.processor.closure;

import java.util.Objects;

/**
 * Closed Java-8-compatible hierarchy of exact resources required by a
 * noncommitting closure attempt.
 *
 * <p>Construction is package-controlled. The only supported concrete forms
 * are {@link ExactNodeDemand}, {@link ManagedOccurrenceEvidenceDemand}, and
 * {@link SourceInitializationDemand}; all are final.</p>
 */
public abstract class ClosureResourceDemand
        implements Comparable<ClosureResourceDemand> {

    /** Closed demand discriminator. */
    public enum Kind {
        /** An exact Blue node must first become available. */
        EXACT_NODE,
        /** Exact managed-occurrence lineage evidence must be supplied. */
        MANAGED_OCCURRENCE_EVIDENCE,
        /** A canonical source initialization must be prepared or retrieved, not executed by the consumer. */
        CANONICAL_INITIALIZATION
    }

    private final Kind kind;
    private final String demandIdentity;
    private final DocumentId sourceDocumentId;
    private final String sourcePath;
    private final String suppliedValueBlueId;
    private final long canonicalOrdinal;

    ClosureResourceDemand(
            Kind kind,
            String demandIdentity,
            DocumentId sourceDocumentId,
            String sourcePath,
            String suppliedValueBlueId,
            long canonicalOrdinal) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.demandIdentity = ClosureValueSupport.requireSha256Identity(
                demandIdentity, "demandIdentity");
        this.sourceDocumentId = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        this.sourcePath = ClosureValueSupport.requireAbsolutePointer(
                sourcePath, "sourcePath");
        this.suppliedValueBlueId = ClosureValueSupport.requireBlueId(
                suppliedValueBlueId, "suppliedValueBlueId");
        this.canonicalOrdinal = ClosureValueSupport.requireSafeInteger(
                canonicalOrdinal, "canonicalOrdinal");
    }

    /**
     * Returns the closed demand discriminator.
     *
     * @return closed demand discriminator
     */
    public final Kind kind() {
        return kind;
    }

    /**
     * Returns the canonical domain-separated demand identity.
     *
     * @return canonical domain-separated demand identity
     */
    public final String demandIdentity() {
        return demandIdentity;
    }

    /**
     * Returns the source managed-document identity.
     *
     * @return source managed-document identity
     */
    public final DocumentId sourceDocumentId() {
        return sourceDocumentId;
    }

    /**
     * Returns the absolute source occurrence path.
     *
     * @return absolute source occurrence path
     */
    public final String sourcePath() {
        return sourcePath;
    }

    /**
     * Returns the exact supplied-value BlueId.
     *
     * @return exact supplied-value BlueId
     */
    public final String suppliedValueBlueId() {
        return suppliedValueBlueId;
    }

    /**
     * Orders demands by source DocumentId, source path, supplied value BlueId,
     * demand ordinal, and finally demand identity.
     *
     * <p>Kind is intentionally not an ordering prefix. It is already bound by
     * the final identity tie-break and cannot override source order.</p>
     *
     * @param other demand to compare
     * @return canonical demand-order result
     */
    @Override
    public final int compareTo(ClosureResourceDemand other) {
        ClosureResourceDemand selected = Objects.requireNonNull(
                other, "other");
        int order = sourceDocumentId.compareTo(selected.sourceDocumentId);
        if (order == 0) {
            order = ClosureValueSupport.comparePortableText(
                    sourcePath, selected.sourcePath);
        }
        if (order == 0) {
            order = ClosureValueSupport.comparePortableText(
                    suppliedValueBlueId, selected.suppliedValueBlueId);
        }
        if (order == 0) {
            order = Long.compare(canonicalOrdinal,
                    selected.canonicalOrdinal);
        }
        return order != 0
                ? order
                : ClosureValueSupport.comparePortableText(
                        demandIdentity, selected.demandIdentity);
    }

    /** {@inheritDoc} */
    @Override
    public final boolean equals(Object other) {
        return this == other
                || (other instanceof ClosureResourceDemand
                && demandIdentity.equals(
                        ((ClosureResourceDemand) other).demandIdentity));
    }

    /** {@inheritDoc} */
    @Override
    public final int hashCode() {
        return demandIdentity.hashCode();
    }
}
