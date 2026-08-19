package blue.language.processor.closure;

import blue.language.identity.BlueIds;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One accepted whole-component tentative cyclic finalization.
 *
 * <p>The receipt is processor evidence.  Fixture-only oracle stage labels are
 * intentionally absent.</p>
 */
public final class TentativeFinalization {

    private final long ordinal;
    private final Boundary boundary;
    private final String masterBlueId;
    private final Map<DocumentId, String> memberBlueIds;
    private final long canonicalBytes;

    /**
     * Creates one finalization receipt.
     *
     * @param ordinal invocation-global contiguous finalization ordinal
     * @param boundary exact processor boundary
     * @param masterBlueId exact cyclic MASTER identity
     * @param memberBlueIds canonical DocumentId-to-member-BlueId map
     * @param canonicalBytes positive canonical cyclic byte count
     */
    public TentativeFinalization(
            long ordinal,
            Boundary boundary,
            String masterBlueId,
            Map<DocumentId, String> memberBlueIds,
            long canonicalBytes) {
        this.ordinal = ClosureValueSupport.requireSafeInteger(
                ordinal, "ordinal");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.masterBlueId = ClosureValueSupport.requireBlueId(
                masterBlueId, "masterBlueId");
        if (this.masterBlueId.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "masterBlueId must not contain a member suffix");
        }
        this.memberBlueIds = immutableCanonicalMembers(memberBlueIds);
        this.canonicalBytes = ClosureValueSupport.requirePositiveSafeInteger(
                canonicalBytes, "canonicalBytes");
        validateMemberBlueIds();
    }

    /**
     * Returns invocation-global finalization ordinal.
     *
     * @return invocation-global finalization ordinal
     */
    public long ordinal() {
        return ordinal;
    }

    /**
     * Returns exact closed finalization boundary.
     *
     * @return exact closed finalization boundary
     */
    public Boundary boundary() {
        return boundary;
    }

    /**
     * Returns exact cyclic MASTER identity.
     *
     * @return exact cyclic MASTER identity
     */
    public String masterBlueId() {
        return masterBlueId;
    }

    /**
     * Returns immutable canonical member identity map.
     *
     * @return immutable canonical member identity map
     */
    public Map<DocumentId, String> memberBlueIds() {
        return memberBlueIds;
    }

    /**
     * Returns positive canonical cyclic byte count.
     *
     * @return positive canonical cyclic byte count
     */
    public long canonicalBytes() {
        return canonicalBytes;
    }

    private void validateMemberBlueIds() {
        Set<Long> suffixes = new HashSet<Long>();
        for (String memberBlueId : memberBlueIds.values()) {
            int separator = BlueIds.cyclicMemberSeparatorIndex(
                    memberBlueId);
            if (separator != masterBlueId.length()
                    || !masterBlueId.equals(
                    BlueIds.cyclicSetMasterBlueId(memberBlueId))) {
                throw new IllegalArgumentException(
                        "memberBlueId does not belong to masterBlueId");
            }
            String suffix = memberBlueId.substring(
                    separator
                            + BlueIds.CYCLIC_MEMBER_SEPARATOR.length());
            long index;
            try {
                index = Long.parseLong(suffix);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "memberBlueId has an invalid numeric suffix",
                        exception);
            }
            ClosureValueSupport.requireSafeInteger(index, "memberBlueId suffix");
            if (!Long.toString(index).equals(suffix)
                    || index >= memberBlueIds.size()
                    || !suffixes.add(Long.valueOf(index))) {
                throw new IllegalArgumentException(
                        "memberBlueId suffixes must be a complete canonical range");
            }
        }
    }

    private static Map<DocumentId, String> immutableCanonicalMembers(
            Map<DocumentId, String> values) {
        LinkedHashMap<DocumentId, String> copy =
                new LinkedHashMap<DocumentId, String>();
        DocumentId previous = null;
        for (Map.Entry<DocumentId, String> entry
                : Objects.requireNonNull(values, "memberBlueIds").entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "memberBlueIds documentId");
            if (previous != null && previous.compareTo(documentId) >= 0) {
                throw new IllegalArgumentException(
                        "memberBlueIds are not in canonical DocumentId order");
            }
            copy.put(documentId, ClosureValueSupport.requireBlueId(
                    entry.getValue(), "memberBlueIds BlueId"));
            previous = documentId;
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "memberBlueIds must not be empty");
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Closed finalization-boundary union. */
    public abstract static class Boundary {

        /** Stable boundary kinds. */
        public enum Kind {
            /** Finalization immediately after one accepted work item. */
            WORK,
            /** Finalization after the complete initialization batch. */
            INITIALIZATION_BATCH,
            /** Finalization after checkpoint settlement. */
            CHECKPOINT_SETTLEMENT
        }

        private final Kind kind;
        private final Long afterWorkOrdinal;

        private Boundary(Kind kind, Long afterWorkOrdinal) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.afterWorkOrdinal = afterWorkOrdinal;
        }

        /**
         * Creates a work boundary.
         *
         * @param afterWorkOrdinal accepted work ordinal
         * @return closed work branch
         */
        public static Boundary work(long afterWorkOrdinal) {
            return new WorkBoundary(afterWorkOrdinal);
        }

        /**
         * Creates an initialization-batch boundary.
         *
         * @param afterWorkOrdinal last accepted batch work ordinal
         * @return closed initialization branch
         */
        public static Boundary initializationBatch(long afterWorkOrdinal) {
            return new InitializationBatchBoundary(afterWorkOrdinal);
        }

        /**
         * Returns closed checkpoint-settlement branch.
         *
         * @return closed checkpoint-settlement branch
         */
        public static Boundary checkpointSettlement() {
            return CheckpointSettlementBoundary.INSTANCE;
        }

        /**
         * Returns stable boundary kind.
         *
         * @return stable boundary kind
         */
        public final Kind kind() {
            return kind;
        }

        /**
         * Returns the causal work ordinal for the two work-bound branches.
         *
         * @return work ordinal, or {@code null} for checkpoint settlement
         */
        public final Long afterWorkOrdinal() {
            return afterWorkOrdinal;
        }
    }

    /** Closed WORK branch. */
    public static final class WorkBoundary extends Boundary {
        private WorkBoundary(long afterWorkOrdinal) {
            super(Kind.WORK, Long.valueOf(
                    ClosureValueSupport.requireSafeInteger(
                            afterWorkOrdinal, "afterWorkOrdinal")));
        }
    }

    /** Closed INITIALIZATION_BATCH branch. */
    public static final class InitializationBatchBoundary extends Boundary {
        private InitializationBatchBoundary(long afterWorkOrdinal) {
            super(Kind.INITIALIZATION_BATCH, Long.valueOf(
                    ClosureValueSupport.requireSafeInteger(
                            afterWorkOrdinal, "afterWorkOrdinal")));
        }
    }

    /** Closed CHECKPOINT_SETTLEMENT branch. */
    public static final class CheckpointSettlementBoundary extends Boundary {
        private static final CheckpointSettlementBoundary INSTANCE =
                new CheckpointSettlementBoundary();

        private CheckpointSettlementBoundary() {
            super(Kind.CHECKPOINT_SETTLEMENT, null);
        }
    }
}
