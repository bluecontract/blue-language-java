package blue.language.processor.closure;

import blue.language.identity.CyclicMemberFinalization;
import blue.language.identity.CyclicSetFinalization;
import blue.language.model.NodeWireForm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact immutable evidence produced for one finalized graph component.
 *
 * <p>Cyclic evidence retains the unchanged Language finalizer result,
 * including preliminary order inputs, the input-to-canonical mapping, and the
 * canonical member bodies used for Language identity.  The component proof is
 * their representation-neutral Contracts cyclic-limit projection.</p>
 */
public final class FinalizedComponentEvidence {

    private final ComponentSnapshot component;
    private final CyclicSetFinalization cyclicFinalization;
    private final Map<DocumentId, Integer> canonicalMemberIndexes;
    private final Map<DocumentId, String> preliminaryBlueIds;
    private final ReusableComponentAuthority reusableAuthority;

    FinalizedComponentEvidence(
            ComponentSnapshot component,
            CyclicSetFinalization cyclicFinalization) {
        this.component = Objects.requireNonNull(component, "component");
        this.reusableAuthority = null;
        this.cyclicFinalization = cyclicFinalization;
        this.canonicalMemberIndexes = canonicalIndexes(
                this.component, cyclicFinalization);
        this.preliminaryBlueIds = preliminaryBlueIds(
                this.component, cyclicFinalization);
        validateProofBodies();
    }

    private FinalizedComponentEvidence(ReusableComponentAuthority authority) {
        this.reusableAuthority = Objects.requireNonNull(authority, "authority");
        this.component = authority.component(); this.cyclicFinalization = null;
        this.canonicalMemberIndexes = authority.canonicalMemberIndexes();
        this.preliminaryBlueIds = authority.preliminaryBlueIds();
    }

    static FinalizedComponentEvidence fromReusable(ReusableComponentAuthority authority) {
        return new FinalizedComponentEvidence(authority);
    }

    public java.util.Optional<ReusableComponentAuthority> reusableAuthority() { return java.util.Optional.ofNullable(reusableAuthority); }

    /**
     * Returns the exact Contracts component-state evidence.
     *
     * @return immutable component snapshot
     */
    public ComponentSnapshot component() {
        return component;
    }

    /**
     * Returns complete unchanged Language cyclic-finalization evidence.
     *
     * @return cyclic evidence, or {@code null} for an acyclic component
     */
    public CyclicSetFinalization cyclicFinalization() {
        return cyclicFinalization;
    }

    /**
     * Returns each lineage's Language-canonical {@code MASTER#index} suffix.
     *
     * @return immutable map, empty for an acyclic component
     */
    public Map<DocumentId, Integer> canonicalMemberIndexes() {
        return canonicalMemberIndexes;
    }

    /**
     * Returns each lineage's preliminary ZERO-form BlueId.
     *
     * @return immutable map, empty for an acyclic component
     */
    public Map<DocumentId, String> preliminaryBlueIds() {
        return preliminaryBlueIds;
    }

    private static Map<DocumentId, Integer> canonicalIndexes(
            ComponentSnapshot component,
            CyclicSetFinalization finalization) {
        LinkedHashMap<DocumentId, Integer> result =
                new LinkedHashMap<DocumentId, Integer>();
        if (component.kind() == ComponentKind.ACYCLIC) {
            if (finalization != null) {
                throw new IllegalArgumentException(
                        "Acyclic component cannot retain cyclic finalization");
            }
            return Collections.unmodifiableMap(result);
        }
        requireCompleteFinalization(component, finalization);
        List<CyclicMemberFinalization> members =
                finalization.membersInInputOrder();
        for (int index = 0; index < members.size(); index++) {
            CyclicMemberFinalization member = members.get(index);
            String expectedBlueId = component.orderedMemberBlueIds().get(index);
            if (member.inputIndex() != index
                    || !expectedBlueId.equals(member.finalBlueId())) {
                throw new IllegalArgumentException(
                        "Cyclic input-to-canonical member mapping is inconsistent");
            }
            result.put(component.orderedMemberDocumentIds().get(index),
                    Integer.valueOf(member.canonicalIndex()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<DocumentId, String> preliminaryBlueIds(
            ComponentSnapshot component,
            CyclicSetFinalization finalization) {
        LinkedHashMap<DocumentId, String> result =
                new LinkedHashMap<DocumentId, String>();
        if (finalization == null) {
            return Collections.unmodifiableMap(result);
        }
        List<CyclicMemberFinalization> members =
                finalization.membersInInputOrder();
        for (int index = 0; index < members.size(); index++) {
            result.put(component.orderedMemberDocumentIds().get(index),
                    members.get(index).preliminaryBlueId());
        }
        return Collections.unmodifiableMap(result);
    }

    private void validateProofBodies() {
        if (cyclicFinalization == null) {
            return;
        }
        List<blue.language.model.Node> proofBodies = component
                .completeCyclicProof().declaredPlaceholderSet();
        List<blue.language.model.Node> expectedProofBodies =
                new CyclicCanonicalLimitProjection()
                        .project(cyclicFinalization)
                        .proofMembers();
        if (proofBodies.size() != expectedProofBodies.size()) {
            throw new IllegalArgumentException(
                    "Cyclic proof does not cover the finalization set");
        }
        for (int index = 0; index < proofBodies.size(); index++) {
            if (!NodeWireForm.get(
                            proofBodies.get(index),
                            NodeWireForm.Strategy.SIMPLE)
                    .equals(NodeWireForm.get(
                            expectedProofBodies.get(index),
                            NodeWireForm.Strategy.SIMPLE))) {
                throw new IllegalArgumentException(
                        "Cyclic proof body differs from Contracts projection");
            }
        }
    }

    private static void requireCompleteFinalization(
            ComponentSnapshot component,
            CyclicSetFinalization finalization) {
        CyclicSetFinalization selected = Objects.requireNonNull(
                finalization, "cyclicFinalization");
        if (!selected.masterBlueId().equals(component.masterBlueId())
                || selected.membersInInputOrder().size()
                != component.orderedMemberDocumentIds().size()) {
            throw new IllegalArgumentException(
                    "Language finalization does not match component evidence");
        }
    }
}
