package blue.language.processor.closure;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/** Stable managed-document lineage identity, distinct from every BlueId. */
public final class DocumentId implements Comparable<DocumentId>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final int MAX_UTF8_BYTES = 512;

    /** Serialized admitted identity text. */
    private final String value;

    /**
     * Admits an already NFC-normalized, non-empty platform identity.
     *
     * @param value stable lineage identity
     * @throws NullPointerException when {@code value} is null
     * @throws IllegalArgumentException when {@code value} is not portable
     */
    public DocumentId(String value) {
        String admitted = ClosureValueSupport.requireNonEmptyText(
                value, "documentId");
        if (admitted.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "documentId exceeds 512 UTF-8 bytes");
        }
        this.value = admitted;
    }

    /**
     * Returns the admitted platform identity.
     *
     * @return stable identity text
     */
    public String value() {
        return value;
    }

    /**
     * Compares identities by Unicode scalar sequence.
     *
     * @param other identity to compare
     * @return portable ordering result
     */
    @Override
    public int compareTo(DocumentId other) {
        return ClosureValueSupport.comparePortableText(
                value, other.value);
    }

    /**
     * Compares the admitted identity text.
     *
     * @param other candidate value
     * @return whether both values name the same lineage
     */
    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof DocumentId
                && value.equals(((DocumentId) other).value));
    }

    /**
     * Returns the admitted identity hash.
     *
     * @return identity hash
     */
    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Returns the admitted identity text.
     *
     * @return identity text
     */
    @Override
    public String toString() {
        return value;
    }
}
