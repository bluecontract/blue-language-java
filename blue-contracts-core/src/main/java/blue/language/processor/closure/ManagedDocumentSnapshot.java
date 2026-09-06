package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.ExecutionEvidenceUnavailableException;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/** Exact immutable state of one independently managed document. */
public final class ManagedDocumentSnapshot
        implements Comparable<ManagedDocumentSnapshot> {

    private final DocumentId documentId;
    private final String blueId;
    private final Node document;
    private final boolean initialized;
    private final boolean terminated;
    private final boolean publicRoot;
    private final long epoch;
    private final long componentGeneration;
    private final ReusableComponentAuthority reusableAuthority;
    private final RootChannelMetadata rootMetadata;

    /**
     * Creates one exact managed-document state record.
     *
     * @param documentId stable platform lineage
     * @param blueId exact current BlueId
     * @param document exact current Blue node
     * @param initialized asserted initialization state
     * @param terminated asserted termination state
     * @param publicRoot whether events may cross the public boundary
     * @param epoch durable document epoch
     * @param componentGeneration current component generation
     */
    public ManagedDocumentSnapshot(
            DocumentId documentId,
            String blueId,
            Node document,
            boolean initialized,
            boolean terminated,
            boolean publicRoot,
            long epoch,
            long componentGeneration) {
        this(documentId, blueId, Objects.requireNonNull(document, "document"), initialized,
                terminated, publicRoot, epoch, componentGeneration, null, null);
    }

    private ManagedDocumentSnapshot(DocumentId documentId, String blueId, Node document,
            boolean initialized, boolean terminated, boolean publicRoot, long epoch, long componentGeneration,
            ReusableComponentAuthority reusableAuthority, RootChannelMetadata rootMetadata) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.blueId = ClosureValueSupport.requireBlueId(
                blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        this.document = document == null ? null : document.clone();
        this.initialized = initialized;
        this.terminated = terminated;
        this.publicRoot = publicRoot;
        this.epoch = ClosureValueSupport.requireSafeInteger(epoch, "epoch");
        this.componentGeneration = ClosureValueSupport.requireSafeInteger(
                componentGeneration, "componentGeneration");
        this.reusableAuthority = reusableAuthority;
        this.rootMetadata = rootMetadata;
        if (document == null && reusableAuthority == null)
            throw new IllegalArgumentException("An absent body requires verified owning component authority");
    }

    static ManagedDocumentSnapshot fromVerifiedHeader(DocumentId documentId, String blueId, Node document,
            boolean initialized, boolean terminated, boolean publicRoot, long epoch, long generation,
            ReusableComponentAuthority authority, RootChannelMetadata metadata) {
        return new ManagedDocumentSnapshot(documentId, blueId, document, initialized, terminated,
                publicRoot, epoch, generation, Objects.requireNonNull(authority, "authority"), metadata);
    }

    /** Residency is physical acquisition state and never enters semantic identities. */
    public boolean hasResidentBody() { return document != null; }
    public Optional<Node> residentDocument() { return document == null ? Optional.<Node>empty() : Optional.of(document.clone()); }
    public Optional<RootChannelMetadata> rootMetadata() { return Optional.ofNullable(rootMetadata); }
    public Optional<ReusableComponentAuthority> reusableAuthority() { return Optional.ofNullable(reusableAuthority); }

    /** Hydrates only this exact selected view; a provider's newer head is not interchangeable. */
    public ManagedDocumentSnapshot withResidentBody(ManagedReadPin exact) {
        Objects.requireNonNull(exact, "exact");
        if (!documentId.equals(exact.documentId()) || !blueId.equals(exact.blueId()))
            throw new IllegalArgumentException("Body evidence belongs to another selected member state");
        return new ManagedDocumentSnapshot(documentId, blueId, exact.document(), initialized, terminated,
                publicRoot, epoch, componentGeneration, reusableAuthority, rootMetadata);
    }

    /**
     * Returns the stable document lineage.
     *
     * @return document identity
     */
    public DocumentId documentId() {
        return documentId;
    }

    /**
     * Returns the exact current BlueId.
     *
     * @return document BlueId
     */
    public String blueId() {
        return blueId;
    }

    /**
     * Returns a deep defensive copy of the exact document.
     *
     * @return copied document node
     */
    public Node document() {
        if (document == null) throw new ExecutionEvidenceUnavailableException(
                "Exact managed document body is not resident: " + documentId.value(), Collections.singleton(blueId));
        return document.clone();
    }

    /**
     * Returns the asserted initialization state.
     *
     * @return whether the document is initialized
     */
    public boolean initialized() {
        return initialized;
    }

    /**
     * Returns the asserted termination state.
     *
     * @return whether the document is terminated
     */
    public boolean terminated() {
        return terminated;
    }

    /**
     * Returns whether this is a public Root.
     *
     * @return public-boundary eligibility
     */
    public boolean publicRoot() {
        return publicRoot;
    }

    /**
     * Returns the durable document epoch.
     *
     * @return non-negative safe epoch
     */
    public long epoch() {
        return epoch;
    }

    /**
     * Returns the component generation asserted by the document record.
     *
     * @return non-negative component generation
     */
    public long componentGeneration() {
        return componentGeneration;
    }

    /**
     * Compares records by stable document identity.
     *
     * @param other record to compare
     * @return portable document order
     */
    @Override
    public int compareTo(ManagedDocumentSnapshot other) {
        return documentId.compareTo(other.documentId);
    }
}
