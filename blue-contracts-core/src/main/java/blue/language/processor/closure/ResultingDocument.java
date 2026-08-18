package blue.language.processor.closure;

import blue.language.model.Node;

import java.util.Objects;

/**
 * Complete exact resulting state of one independently managed document.
 *
 * <p>The document body is always copied on ingress and egress.  Component
 * membership is repeated deliberately so a result can be validated without
 * consulting hidden processor state.</p>
 */
public final class ResultingDocument
        implements Comparable<ResultingDocument> {

    private final DocumentId documentId;
    private final String beforeBlueId;
    private final String afterBlueId;
    private final Node document;
    private final boolean initialized;
    private final boolean terminated;
    private final boolean publicRoot;
    private final long epoch;
    private final long componentGeneration;
    private final String componentIdentity;
    private final String componentStateIdentity;
    private final Long memberIndex;

    /**
     * Creates one closed resulting-document record.
     *
     * @param documentId stable managed lineage
     * @param beforeBlueId exact predecessor identity
     * @param afterBlueId exact resulting identity
     * @param document exact resulting Blue value
     * @param initialized exact initialization marker assertion
     * @param terminated exact termination marker assertion
     * @param publicRoot whether events may cross the public boundary
     * @param epoch durable per-document epoch
     * @param componentGeneration resulting component generation
     * @param componentIdentity stable component lineage identity
     * @param componentStateIdentity exact resulting component-state identity
     * @param memberIndex cyclic suffix, or {@code null} for acyclic state
     */
    public ResultingDocument(
            DocumentId documentId,
            String beforeBlueId,
            String afterBlueId,
            Node document,
            boolean initialized,
            boolean terminated,
            boolean publicRoot,
            long epoch,
            long componentGeneration,
            String componentIdentity,
            String componentStateIdentity,
            Long memberIndex) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.beforeBlueId = ClosureValueSupport.requireBlueId(
                beforeBlueId, "beforeBlueId");
        this.afterBlueId = ClosureValueSupport.requireBlueId(
                afterBlueId, "afterBlueId");
        this.document = Objects.requireNonNull(document, "document").clone();
        this.initialized = initialized;
        this.terminated = terminated;
        this.publicRoot = publicRoot;
        this.epoch = ClosureValueSupport.requireSafeInteger(epoch, "epoch");
        this.componentGeneration = ClosureValueSupport.requireSafeInteger(
                componentGeneration, "componentGeneration");
        this.componentIdentity = ClosureValueSupport.requireSha256Identity(
                componentIdentity, "componentIdentity");
        this.componentStateIdentity =
                ClosureValueSupport.requireSha256Identity(
                        componentStateIdentity, "componentStateIdentity");
        this.memberIndex = memberIndex == null
                ? null
                : Long.valueOf(ClosureValueSupport.requireSafeInteger(
                        memberIndex.longValue(), "memberIndex"));
        validateMemberIndex();
    }

    /**
     * Returns stable managed lineage.
     *
     * @return stable managed lineage
     */
    public DocumentId documentId() {
        return documentId;
    }

    /**
     * Returns exact predecessor BlueId.
     *
     * @return exact predecessor BlueId
     */
    public String beforeBlueId() {
        return beforeBlueId;
    }

    /**
     * Returns exact resulting BlueId.
     *
     * @return exact resulting BlueId
     */
    public String afterBlueId() {
        return afterBlueId;
    }

    /**
     * Returns a deep defensive copy of the resulting document.
     *
     * @return a deep defensive copy of the resulting document
     */
    public Node document() {
        return document.clone();
    }

    /**
     * Returns exact initialization-state assertion.
     *
     * @return exact initialization-state assertion
     */
    public boolean initialized() {
        return initialized;
    }

    /**
     * Returns exact termination-state assertion.
     *
     * @return exact termination-state assertion
     */
    public boolean terminated() {
        return terminated;
    }

    /**
     * Returns whether this document is a public Root.
     *
     * @return whether this document is a public Root
     */
    public boolean publicRoot() {
        return publicRoot;
    }

    /**
     * Returns durable non-negative safe epoch.
     *
     * @return durable non-negative safe epoch
     */
    public long epoch() {
        return epoch;
    }

    /**
     * Returns resulting component generation.
     *
     * @return resulting component generation
     */
    public long componentGeneration() {
        return componentGeneration;
    }

    /**
     * Returns stable component lineage identity.
     *
     * @return stable component lineage identity
     */
    public String componentIdentity() {
        return componentIdentity;
    }

    /**
     * Returns exact resulting component-state identity.
     *
     * @return exact resulting component-state identity
     */
    public String componentStateIdentity() {
        return componentStateIdentity;
    }

    /**
     * Returns the exact numeric {@code #n} suffix.
     *
     * @return cyclic member index, or {@code null} for acyclic state
     */
    public Long memberIndex() {
        return memberIndex;
    }

    /** {@inheritDoc} */
    @Override
    public int compareTo(ResultingDocument other) {
        return documentId.compareTo(other.documentId);
    }

    ManagedDocumentSnapshot asSnapshot() {
        return new ManagedDocumentSnapshot(
                documentId,
                afterBlueId,
                document,
                initialized,
                terminated,
                publicRoot,
                epoch,
                componentGeneration);
    }

    private void validateMemberIndex() {
        int separator = afterBlueId.lastIndexOf('#');
        if (separator < 0) {
            if (memberIndex != null) {
                throw new IllegalArgumentException(
                        "Acyclic afterBlueId requires null memberIndex");
            }
            return;
        }
        if (memberIndex == null) {
            throw new IllegalArgumentException(
                    "Cyclic afterBlueId requires memberIndex");
        }
        String suffix = afterBlueId.substring(separator + 1);
        String expected = Long.toString(memberIndex.longValue());
        if (!expected.equals(suffix)) {
            throw new IllegalArgumentException(
                    "memberIndex does not match the afterBlueId suffix");
        }
    }
}
