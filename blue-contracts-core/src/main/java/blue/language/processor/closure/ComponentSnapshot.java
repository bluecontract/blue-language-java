package blue.language.processor.closure;

import blue.language.provider.CyclicSetProof;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Stable component lineage plus complete independently verifiable state. */
public final class ComponentSnapshot implements Comparable<ComponentSnapshot> {

    private final String componentIdentity;
    private final String componentStateIdentity;
    private final long componentGeneration;
    private final ComponentKind kind;
    private final List<DocumentId> orderedMemberDocumentIds;
    private final List<String> orderedMemberBlueIds;
    private final String masterBlueId;
    private final CyclicSetProof completeCyclicProof;
    private final String cyclicProofIdentity;

    /**
     * Creates one exact component-state record.
     *
     * @param componentIdentity stable component lineage identity
     * @param componentStateIdentity identity of this exact state
     * @param componentGeneration component lineage generation
     * @param kind acyclic or cyclic shape
     * @param orderedMemberDocumentIds canonical member lineages
     * @param orderedMemberBlueIds parallel exact member BlueIds
     * @param masterBlueId cyclic master BlueId, otherwise {@code null}
     * @param completeCyclicProof complete cyclic proof, otherwise {@code null}
     * @param cyclicProofIdentity proof identity, otherwise {@code null}
     */
    public ComponentSnapshot(
            String componentIdentity,
            String componentStateIdentity,
            long componentGeneration,
            ComponentKind kind,
            List<DocumentId> orderedMemberDocumentIds,
            List<String> orderedMemberBlueIds,
            String masterBlueId,
            CyclicSetProof completeCyclicProof,
            String cyclicProofIdentity) {
        this.componentIdentity = ClosureValueSupport.requireSha256Identity(
                componentIdentity, "componentIdentity");
        this.componentStateIdentity = ClosureValueSupport.requireSha256Identity(
                componentStateIdentity, "componentStateIdentity");
        this.componentGeneration = ClosureValueSupport.requireSafeInteger(
                componentGeneration, "componentGeneration");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.orderedMemberDocumentIds = immutableCanonicalDocumentIds(
                orderedMemberDocumentIds);
        this.orderedMemberBlueIds = immutableUniqueBlueIds(
                orderedMemberBlueIds);
        if (this.orderedMemberDocumentIds.size()
                != this.orderedMemberBlueIds.size()) {
            throw new IllegalArgumentException(
                    "Component member identities have different cardinalities");
        }
        if (kind == ComponentKind.ACYCLIC
                && this.orderedMemberDocumentIds.size() != 1) {
            throw new IllegalArgumentException(
                    "An acyclic component must contain one document");
        }
        boolean completeCyclicEvidence = masterBlueId != null
                && completeCyclicProof != null
                && cyclicProofIdentity != null;
        boolean noCyclicEvidence = masterBlueId == null
                && completeCyclicProof == null
                && cyclicProofIdentity == null;
        if ((kind == ComponentKind.CYCLIC && !completeCyclicEvidence)
                || (kind == ComponentKind.ACYCLIC && !noCyclicEvidence)) {
            throw new IllegalArgumentException(
                    "Cyclic evidence does not match component kind");
        }
        if (kind == ComponentKind.CYCLIC) {
            this.masterBlueId = ClosureValueSupport.requireBlueId(
                    masterBlueId, "masterBlueId");
            this.cyclicProofIdentity = ClosureValueSupport.requireSha256Identity(
                    cyclicProofIdentity, "cyclicProofIdentity");
            this.completeCyclicProof = copyProof(completeCyclicProof);
            if (this.completeCyclicProof.declaredPlaceholderSet().size()
                    != this.orderedMemberDocumentIds.size()) {
                throw new IllegalArgumentException(
                        "Cyclic proof does not cover every component member");
            }
            requireCyclicSuffixMapping();
        } else {
            this.masterBlueId = null;
            this.cyclicProofIdentity = null;
            this.completeCyclicProof = null;
        }
    }

    /**
     * Returns the documented value.
     *
     * @return stable component lineage identity
     */
    public String componentIdentity() {
        return componentIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return identity of this exact component state
     */
    public String componentStateIdentity() {
        return componentStateIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return non-negative component generation
     */
    public long componentGeneration() {
        return componentGeneration;
    }

    /**
     * Returns the documented value.
     *
     * @return structural component kind
     */
    public ComponentKind kind() {
        return kind;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical member lineages
     */
    public List<DocumentId> orderedMemberDocumentIds() {
        return orderedMemberDocumentIds;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable parallel exact member BlueIds
     */
    public List<String> orderedMemberBlueIds() {
        return orderedMemberBlueIds;
    }

    /**
     * Returns the documented value.
     *
     * @return cyclic master BlueId, or {@code null} for acyclic state
     */
    public String masterBlueId() {
        return masterBlueId;
    }

    /**
     * Returns an independent proof container.
     *
     * @return proof copy, or {@code null} for acyclic state
     */
    public CyclicSetProof completeCyclicProof() {
        return completeCyclicProof == null ? null : copyProof(completeCyclicProof);
    }

    /**
     * Returns the documented value.
     *
     * @return cyclic proof identity, or {@code null} for acyclic state
     */
    public String cyclicProofIdentity() {
        return cyclicProofIdentity;
    }

    /**
     * Compares records by stable component identity.
     *
     * @param other component to compare
     * @return canonical component order
     */
    @Override
    public int compareTo(ComponentSnapshot other) {
        return ClosureValueSupport.comparePortableText(
                componentIdentity, other.componentIdentity);
    }

    private void requireCyclicSuffixMapping() {
        for (int index = 0; index < orderedMemberBlueIds.size(); index++) {
            String expected = masterBlueId + "#" + index;
            if (!expected.equals(orderedMemberBlueIds.get(index))) {
                throw new IllegalArgumentException(
                        "Cyclic member BlueId does not match MASTER#index order");
            }
        }
    }

    private static CyclicSetProof copyProof(CyclicSetProof proof) {
        return CyclicSetProof.fromDeclaredPlaceholderSet(
                Objects.requireNonNull(proof, "completeCyclicProof")
                        .declaredPlaceholderSet());
    }

    private static List<DocumentId> immutableCanonicalDocumentIds(
            List<DocumentId> values) {
        ArrayList<DocumentId> copy = new ArrayList<DocumentId>(
                Objects.requireNonNull(values, "orderedMemberDocumentIds"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Component members must not be empty");
        }
        for (int index = 0; index < copy.size(); index++) {
            Objects.requireNonNull(copy.get(index), "component member documentId");
            if (index > 0
                    && copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "Component DocumentIds are not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<String> immutableUniqueBlueIds(List<String> values) {
        ArrayList<String> copy = new ArrayList<String>(
                Objects.requireNonNull(values, "orderedMemberBlueIds"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Component BlueIds must not be empty");
        }
        Set<String> unique = new HashSet<String>();
        for (int index = 0; index < copy.size(); index++) {
            String blueId = ClosureValueSupport.requireBlueId(
                    copy.get(index), "component member BlueId");
            if (!unique.add(blueId)) {
                throw new IllegalArgumentException("Duplicate component member BlueId");
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
