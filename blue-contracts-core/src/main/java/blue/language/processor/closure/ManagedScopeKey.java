package blue.language.processor.closure;

import java.util.Objects;

/** Complete identity of a managed scope occurrence. */
public final class ManagedScopeKey implements Comparable<ManagedScopeKey> {

    private final DocumentId documentId;
    private final ScopeAddress address;

    /**
     * Creates a complete managed-scope key.
     *
     * @param documentId stable document lineage
     * @param address scope occurrence within that document
     */
    public ManagedScopeKey(DocumentId documentId, ScopeAddress address) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.address = Objects.requireNonNull(address, "address");
    }

    /**
     * Returns the Contracts 1.0 execution key for one independent document.
     *
     * @param documentId stable document lineage
     * @return Root-scoped key
     */
    public static ManagedScopeKey root(DocumentId documentId) {
        return new ManagedScopeKey(documentId, ScopeAddress.root());
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
     * Returns the scope occurrence address.
     *
     * @return scope address
     */
    public ScopeAddress address() {
        return address;
    }

    /**
     * Reports whether this key selects the document Root.
     *
     * @return whether the address is Root
     */
    public boolean isRoot() {
        return address.isRoot();
    }

    /**
     * Compares document identity then scope address.
     *
     * @param other key to compare
     * @return portable ordering result
     */
    @Override
    public int compareTo(ManagedScopeKey other) {
        int order = documentId.compareTo(other.documentId);
        return order != 0 ? order : address.compareTo(other.address);
    }

    /**
     * Compares complete scope-key values.
     *
     * @param other candidate key
     * @return whether the values identify the same managed scope
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManagedScopeKey)) {
            return false;
        }
        ManagedScopeKey key = (ManagedScopeKey) other;
        return documentId.equals(key.documentId)
                && address.equals(key.address);
    }

    /**
     * Returns a value hash.
     *
     * @return key hash
     */
    @Override
    public int hashCode() {
        return Objects.hash(documentId, address);
    }

    /**
     * Returns a diagnostic document/scope form.
     *
     * @return diagnostic text
     */
    @Override
    public String toString() {
        return documentId + ":" + address;
    }
}
