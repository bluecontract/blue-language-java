package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Final stable component plus its exact state and independently verifiable proof. */
public final class ResultingComponent {
    private final String componentIdentity;
    private final String componentStateIdentity;
    private final long componentGeneration;
    private final ComponentKind kind;
    private final String masterBlueId;
    private final List<DocumentId> orderedMemberDocumentIds;
    private final List<String> orderedMemberBlueIds;
    private final Object completeCyclicProof;
    private final String cyclicProofIdentity;

    public ResultingComponent(
            String componentIdentity,
            String componentStateIdentity,
            long componentGeneration,
            ComponentKind kind,
            String masterBlueId,
            List<DocumentId> orderedMemberDocumentIds,
            List<String> orderedMemberBlueIds,
            Object completeCyclicProof,
            String cyclicProofIdentity) {
        this.componentIdentity = Objects.requireNonNull(
                componentIdentity, "componentIdentity");
        this.componentStateIdentity = Objects.requireNonNull(
                componentStateIdentity, "componentStateIdentity");
        this.componentGeneration = CanonicalOrders.requireSafeInteger(
                componentGeneration, "componentGeneration");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.masterBlueId = masterBlueId;
        this.orderedMemberDocumentIds = immutableCanonicalDocumentIds(
                orderedMemberDocumentIds);
        this.orderedMemberBlueIds = immutableUniqueBlueIds(orderedMemberBlueIds);
        if (this.orderedMemberDocumentIds.isEmpty()
                || this.orderedMemberDocumentIds.size()
                != this.orderedMemberBlueIds.size()) {
            throw new IllegalArgumentException("member state cardinality");
        }
        if (kind == ComponentKind.ACYCLIC
                && this.orderedMemberDocumentIds.size() != 1) {
            throw new IllegalArgumentException("acyclic component must be a singleton");
        }
        boolean completeProofPresent = masterBlueId != null
                && completeCyclicProof != null
                && cyclicProofIdentity != null;
        boolean proofAbsent = masterBlueId == null
                && completeCyclicProof == null
                && cyclicProofIdentity == null;
        if ((kind == ComponentKind.CYCLIC && !completeProofPresent)
                || (kind == ComponentKind.ACYCLIC && !proofAbsent)) {
            throw new IllegalArgumentException("cyclic proof evidence");
        }
        this.completeCyclicProof = completeCyclicProof;
        this.cyclicProofIdentity = cyclicProofIdentity;
    }

    public String componentIdentity() { return componentIdentity; }
    public String componentStateIdentity() { return componentStateIdentity; }
    public long componentGeneration() { return componentGeneration; }
    public ComponentKind kind() { return kind; }
    public String masterBlueId() { return masterBlueId; }
    public List<DocumentId> orderedMemberDocumentIds() {
        return orderedMemberDocumentIds;
    }
    public List<String> orderedMemberBlueIds() { return orderedMemberBlueIds; }
    public Object completeCyclicProof() { return completeCyclicProof; }
    public String cyclicProofIdentity() { return cyclicProofIdentity; }

    private static List<DocumentId> immutableCanonicalDocumentIds(
            List<DocumentId> values) {
        ArrayList<DocumentId> copy = new ArrayList<DocumentId>(
                Objects.requireNonNull(values, "orderedMemberDocumentIds"));
        for (DocumentId value : copy) {
            Objects.requireNonNull(value, "orderedMemberDocumentIds item");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException("members not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<String> immutableUniqueBlueIds(List<String> values) {
        ArrayList<String> copy = new ArrayList<String>(
                Objects.requireNonNull(values, "orderedMemberBlueIds"));
        Set<String> unique = new HashSet<String>();
        for (String value : copy) {
            String blueId = Objects.requireNonNull(
                    value, "orderedMemberBlueIds item");
            if (!unique.add(blueId)) {
                throw new IllegalArgumentException("duplicate member BlueId");
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
