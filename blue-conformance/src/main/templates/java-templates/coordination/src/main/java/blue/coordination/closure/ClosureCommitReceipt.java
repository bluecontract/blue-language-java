package blue.coordination.closure;

import blue.contracts.closure.CanonicalOrders;
import blue.contracts.closure.DocumentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable evidence that one complete closure publication committed. */
public final class ClosureCommitReceipt {
    private final String transactionIdentity;
    private final String planIdentity;
    private final long beforeGraphGeneration;
    private final long afterGraphGeneration;
    private final List<DocumentId> committedDocuments;
    private final String contractsCommitCompanionIdentity;

    public ClosureCommitReceipt(
            String transactionIdentity,
            String planIdentity,
            long beforeGraphGeneration,
            long afterGraphGeneration,
            List<DocumentId> committedDocuments,
            String contractsCommitCompanionIdentity) {
        this.transactionIdentity = Objects.requireNonNull(transactionIdentity, "transactionIdentity");
        this.planIdentity = Objects.requireNonNull(planIdentity, "planIdentity");
        this.beforeGraphGeneration = CanonicalOrders.requireSafeInteger(
                beforeGraphGeneration, "beforeGraphGeneration");
        this.afterGraphGeneration = CanonicalOrders.requireSafeInteger(
                afterGraphGeneration, "afterGraphGeneration");
        ArrayList<DocumentId> copy = new ArrayList<DocumentId>(
                Objects.requireNonNull(committedDocuments, "committedDocuments"));
        for (DocumentId documentId : copy) {
            Objects.requireNonNull(documentId, "committedDocuments item");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "committedDocuments not in canonical order");
            }
        }
        this.committedDocuments = Collections.unmodifiableList(copy);
        this.contractsCommitCompanionIdentity = Objects.requireNonNull(
                contractsCommitCompanionIdentity, "contractsCommitCompanionIdentity");
    }

    public String transactionIdentity() {
        return transactionIdentity;
    }

    public String planIdentity() {
        return planIdentity;
    }

    public long beforeGraphGeneration() {
        return beforeGraphGeneration;
    }

    public long afterGraphGeneration() {
        return afterGraphGeneration;
    }

    public List<DocumentId> committedDocuments() {
        return committedDocuments;
    }

    public String contractsCommitCompanionIdentity() {
        return contractsCommitCompanionIdentity;
    }
}
