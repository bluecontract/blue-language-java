package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;

import java.util.Objects;

/**
 * Immutable semantic finalization evidence for one managed document Root.
 *
 * <p>The value contains only the document's own exact body and component
 * membership. It deliberately contains no collection of documents that
 * reference or contain this document.</p>
 */
public final class FinalizedDocumentEvidence
        implements Comparable<FinalizedDocumentEvidence> {

    private final DocumentId documentId;
    private final String blueId;
    private final Node document;
    private final ComponentKind componentKind;
    private final long componentGeneration;
    private final String componentIdentity;
    private final String componentStateIdentity;
    private final Integer cyclicMemberIndex;
    private final String preliminaryBlueId;
    private final ReusableComponentAuthority reusableAuthority;

    FinalizedDocumentEvidence(
            DocumentId documentId,
            String blueId,
            Node document,
            ComponentSnapshot component,
            Integer cyclicMemberIndex,
            String preliminaryBlueId) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.blueId = ClosureValueSupport.requireBlueId(
                blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        this.document = Objects.requireNonNull(document, "document").clone();
        ComponentSnapshot selected = Objects.requireNonNull(
                component, "component");
        this.componentKind = selected.kind();
        this.componentGeneration = selected.componentGeneration();
        this.componentIdentity = selected.componentIdentity();
        this.componentStateIdentity = selected.componentStateIdentity();
        this.cyclicMemberIndex = cyclicMemberIndex;
        this.preliminaryBlueId = preliminaryBlueId;
        this.reusableAuthority = null;
        validateCyclicEvidence();
    }

    private FinalizedDocumentEvidence(ManagedDocumentSnapshot header, ReusableComponentAuthority authority, Node residentBody) {
        this.documentId = header.documentId(); this.blueId = header.blueId();
        this.document = residentBody == null ? null : residentBody.clone();
        ComponentSnapshot component = authority.component();
        this.componentKind = component.kind(); this.componentGeneration = component.componentGeneration();
        this.componentIdentity = component.componentIdentity(); this.componentStateIdentity = component.componentStateIdentity();
        this.cyclicMemberIndex = authority.canonicalMemberIndexes().get(documentId);
        this.preliminaryBlueId = authority.preliminaryBlueIds().get(documentId);
        this.reusableAuthority = authority;
        validateCyclicEvidence();
    }

    static FinalizedDocumentEvidence fromReusable(ReusableComponentAuthority authority, DocumentId member, Node residentBody) {
        return new FinalizedDocumentEvidence(authority.memberHeader(member), authority, residentBody);
    }

    /**
     * Returns the stable managed-document lineage.
     *
     * @return document identity
     */
    public DocumentId documentId() {
        return documentId;
    }

    /**
     * Returns the exact finalized BlueId.
     *
     * @return direct BlueId or cyclic member BlueId
     */
    public String blueId() {
        return blueId;
    }

    /**
     * Returns a deep defensive copy of the exact finalized body.
     *
     * @return independent document body
     */
    public Node document() {
        if (document == null) throw new blue.language.processor.ExecutionEvidenceUnavailableException(
                "Exact finalized component body is not resident", java.util.Collections.singletonList(blueId));
        return document.clone();
    }

    public boolean hasResidentBody() { return document != null; }
    public java.util.Optional<ReusableComponentAuthority> reusableAuthority() { return java.util.Optional.ofNullable(reusableAuthority); }

    /**
     * Returns the resulting component shape.
     *
     * @return acyclic or cyclic kind
     */
    public ComponentKind componentKind() {
        return componentKind;
    }

    /**
     * Returns the generation assigned by the graph transition.
     *
     * @return non-negative component generation
     */
    public long componentGeneration() {
        return componentGeneration;
    }

    /**
     * Returns the stable component-lineage identity.
     *
     * @return component identity
     */
    public String componentIdentity() {
        return componentIdentity;
    }

    /**
     * Returns the identity of the exact finalized component state.
     *
     * @return component-state identity
     */
    public String componentStateIdentity() {
        return componentStateIdentity;
    }

    /**
     * Returns the Language-canonical cyclic suffix index.
     *
     * @return member index, or {@code null} for an acyclic document
     */
    public Integer cyclicMemberIndex() {
        return cyclicMemberIndex;
    }

    /**
     * Returns the preliminary ZERO-form identity used for cyclic ordering.
     *
     * @return preliminary BlueId, or {@code null} for an acyclic document
     */
    public String preliminaryBlueId() {
        return preliminaryBlueId;
    }

    /** {@inheritDoc} */
    @Override
    public int compareTo(FinalizedDocumentEvidence other) {
        return documentId.compareTo(Objects.requireNonNull(
                other, "other").documentId);
    }

    private void validateCyclicEvidence() {
        if (componentKind == ComponentKind.ACYCLIC) {
            if (cyclicMemberIndex != null || preliminaryBlueId != null) {
                throw new IllegalArgumentException(
                        "Acyclic document cannot retain cyclic mapping evidence");
            }
            return;
        }
        if (cyclicMemberIndex == null || cyclicMemberIndex.intValue() < 0
                || preliminaryBlueId == null) {
            throw new IllegalArgumentException(
                    "Cyclic document requires complete member mapping evidence");
        }
        String suffix = "#" + cyclicMemberIndex;
        if (!blueId.endsWith(suffix)) {
            throw new IllegalArgumentException(
                    "Cyclic member index does not match the finalized BlueId");
        }
    }
}
