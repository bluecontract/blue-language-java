package blue.contracts.closure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One complete semantic cyclic finalization returned by the Language API. */
public final class TentativeFinalizationResult {
    private final String masterBlueId;
    private final Map<DocumentId, String> memberBlueIds;
    private final long canonicalBytes;

    public TentativeFinalizationResult(
            String masterBlueId,
            Map<DocumentId, String> memberBlueIds,
            long canonicalBytes) {
        this.masterBlueId = Objects.requireNonNull(masterBlueId, "masterBlueId");
        this.memberBlueIds = immutableCanonicalMemberBlueIds(memberBlueIds);
        this.canonicalBytes = CanonicalOrders.requireSafeInteger(
                canonicalBytes, "canonicalBytes");
        if (this.canonicalBytes == 0L) {
            throw new IllegalArgumentException("canonicalBytes");
        }
    }

    public String masterBlueId() { return masterBlueId; }
    public Map<DocumentId, String> memberBlueIds() { return memberBlueIds; }
    public long canonicalBytes() { return canonicalBytes; }

    private static Map<DocumentId, String> immutableCanonicalMemberBlueIds(
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
                        "memberBlueIds not in canonical order");
            }
            copy.put(
                    documentId,
                    Objects.requireNonNull(entry.getValue(), "memberBlueIds blueId"));
            previous = documentId;
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("memberBlueIds");
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Processor-owned evidence that places one semantic finalization at its
     * exact invocation boundary. Fixture-only oracle-stage labels do not enter
     * this production-facing shape.
     */
    public static final class ProcessorEvidence {
        private final long ordinal;
        private final Boundary boundary;
        private final TentativeFinalizationResult finalization;

        public ProcessorEvidence(
                long ordinal,
                Boundary boundary,
                TentativeFinalizationResult finalization) {
            this.ordinal = CanonicalOrders.requireSafeInteger(ordinal, "ordinal");
            this.boundary = Objects.requireNonNull(boundary, "boundary");
            this.finalization = Objects.requireNonNull(finalization, "finalization");
        }

        public long ordinal() { return ordinal; }
        public Boundary boundary() { return boundary; }
        public TentativeFinalizationResult finalization() { return finalization; }
    }

    /** Closed serialized finalization-boundary union owned by the processor. */
    public abstract static class Boundary {
        private Boundary() { }
        public abstract String kind();

        public static Boundary work(long afterWorkOrdinal) {
            return new WorkBoundary(afterWorkOrdinal);
        }

        public static Boundary initializationBatch(long afterWorkOrdinal) {
            return new InitializationBatchBoundary(afterWorkOrdinal);
        }

        public static Boundary checkpointSettlement() {
            return new CheckpointSettlementBoundary();
        }
    }

    public static final class WorkBoundary extends Boundary {
        private final long afterWorkOrdinal;

        private WorkBoundary(long afterWorkOrdinal) {
            this.afterWorkOrdinal = CanonicalOrders.requireSafeInteger(
                    afterWorkOrdinal, "afterWorkOrdinal");
        }

        @Override public String kind() { return "WORK"; }
        public long afterWorkOrdinal() { return afterWorkOrdinal; }
    }

    public static final class InitializationBatchBoundary extends Boundary {
        private final long afterWorkOrdinal;

        private InitializationBatchBoundary(long afterWorkOrdinal) {
            this.afterWorkOrdinal = CanonicalOrders.requireSafeInteger(
                    afterWorkOrdinal, "afterWorkOrdinal");
        }

        @Override public String kind() { return "INITIALIZATION_BATCH"; }
        public long afterWorkOrdinal() { return afterWorkOrdinal; }
    }

    public static final class CheckpointSettlementBoundary extends Boundary {
        private CheckpointSettlementBoundary() { }
        @Override public String kind() { return "CHECKPOINT_SETTLEMENT"; }
    }
}
