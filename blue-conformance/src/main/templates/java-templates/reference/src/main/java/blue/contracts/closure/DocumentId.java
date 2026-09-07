package blue.contracts.closure;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Stable platform identity; deliberately not a BlueId or Language primitive. */
public final class DocumentId implements Comparable<DocumentId>, java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final String value;
    public DocumentId(String value) {
        Objects.requireNonNull(value, "value");
        String admitted = CanonicalOrders.requireNfc(value, "DocumentId");
        if (admitted.isEmpty() || admitted.indexOf('\0') >= 0 || admitted.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("invalid DocumentId");
        }
        this.value = admitted;
    }
    public String value() { return value; }
    @Override public int compareTo(DocumentId other) { return CanonicalOrders.compareUnicodeScalars(value, other.value); }
    @Override public boolean equals(Object o){return o instanceof DocumentId && value.equals(((DocumentId)o).value);} 
    @Override public int hashCode(){return value.hashCode();}
    @Override public String toString(){return value;}
}
