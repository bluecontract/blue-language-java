package blue.contracts.closure;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;

/** Stable platform identity; deliberately not a BlueId or Language primitive. */
public final class DocumentId implements Comparable<DocumentId>, java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final String value;
    public DocumentId(String value) {
        Objects.requireNonNull(value, "value");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0 || normalized.getBytes(StandardCharsets.UTF_8).length > 512) {
            throw new IllegalArgumentException("invalid DocumentId");
        }
        this.value = normalized;
    }
    public String value() { return value; }
    @Override public int compareTo(DocumentId other) { return compareCodePoints(value, other.value); }
    private static int compareCodePoints(String a, String b) {
        int ia=0, ib=0;
        while (ia<a.length() && ib<b.length()) {
            int ca=a.codePointAt(ia), cb=b.codePointAt(ib);
            if (ca!=cb) return Integer.compare(ca,cb);
            ia+=Character.charCount(ca); ib+=Character.charCount(cb);
        }
        return Integer.compare(a.codePointCount(0,a.length()), b.codePointCount(0,b.length()));
    }
    @Override public boolean equals(Object o){return o instanceof DocumentId && value.equals(((DocumentId)o).value);} 
    @Override public int hashCode(){return value.hashCode();}
    @Override public String toString(){return value;}
}
