package blue.contracts.closure;

import java.util.Objects;

/** Complete exact resulting document and component-state membership evidence. */
public final class ResultingDocument {
    private final DocumentId documentId;
    private final String beforeBlueId;
    private final String afterBlueId;
    private final Object document;
    private final boolean initialized;
    private final boolean terminated;
    private final boolean publicRoot;
    private final long epoch;
    private final long componentGeneration;
    private final String componentIdentity;
    private final String componentStateIdentity;
    private final Long memberIndex;

    public ResultingDocument(
            DocumentId documentId,
            String beforeBlueId,
            String afterBlueId,
            Object document,
            boolean initialized,
            boolean terminated,
            boolean publicRoot,
            long epoch,
            long componentGeneration,
            String componentIdentity,
            String componentStateIdentity,
            Long memberIndex) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.beforeBlueId = Objects.requireNonNull(beforeBlueId, "beforeBlueId");
        this.afterBlueId = Objects.requireNonNull(afterBlueId, "afterBlueId");
        this.document = Objects.requireNonNull(document, "document");
        this.initialized = initialized;
        this.terminated = terminated;
        this.publicRoot = publicRoot;
        this.epoch = CanonicalOrders.requireSafeInteger(epoch, "epoch");
        this.componentGeneration = CanonicalOrders.requireSafeInteger(
                componentGeneration, "componentGeneration");
        this.componentIdentity = Objects.requireNonNull(componentIdentity, "componentIdentity");
        this.componentStateIdentity = Objects.requireNonNull(
                componentStateIdentity, "componentStateIdentity");
        this.memberIndex = memberIndex == null ? null : Long.valueOf(
                CanonicalOrders.requireSafeInteger(memberIndex.longValue(), "memberIndex"));
        validateMemberIndexShape(this.afterBlueId, this.memberIndex);
    }

    public DocumentId documentId() { return documentId; }
    public String beforeBlueId() { return beforeBlueId; }
    public String afterBlueId() { return afterBlueId; }
    public Object document() { return document; }
    public boolean initialized() { return initialized; }
    public boolean terminated() { return terminated; }
    public boolean publicRoot() { return publicRoot; }
    public long epoch() { return epoch; }
    public long componentGeneration() { return componentGeneration; }
    public String componentIdentity() { return componentIdentity; }
    public String componentStateIdentity() { return componentStateIdentity; }
    /**
     * Exact cyclic {@code #n} suffix, or null for an acyclic result. A
     * serializer must emit this property even when its value is null.
     */
    public Long memberIndex() { return memberIndex; }

    private static void validateMemberIndexShape(String blueId, Long memberIndex) {
        int separator = blueId.lastIndexOf('#');
        if (separator < 0) {
            if (memberIndex != null) {
                throw new IllegalArgumentException("acyclic memberIndex");
            }
            return;
        }
        if (memberIndex == null) {
            throw new IllegalArgumentException("cyclic memberIndex");
        }
        String suffix = blueId.substring(separator + 1);
        try {
            if (Long.parseLong(suffix) != memberIndex.longValue()) {
                throw new IllegalArgumentException("memberIndex suffix mismatch");
            }
        } catch (NumberFormatException invalidSuffix) {
            throw new IllegalArgumentException("afterBlueId suffix", invalidSuffix);
        }
    }
}
