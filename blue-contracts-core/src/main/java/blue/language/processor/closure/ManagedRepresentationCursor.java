package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact progress and frozen target for one historical representation chain. */
public final class ManagedRepresentationCursor {
    private final String anchorReceiptIdentity;
    private final String positionIdentity;
    private final String targetPositionIdentity;
    private final String nextRevisionReceiptIdentity;
    public ManagedRepresentationCursor(String anchorReceiptIdentity, String positionIdentity,
            String targetPositionIdentity, String nextRevisionReceiptIdentity) {
        this.anchorReceiptIdentity = ClosureValueSupport.requireSha256Identity(anchorReceiptIdentity, "anchorReceiptIdentity");
        this.positionIdentity = ClosureValueSupport.requireSha256Identity(positionIdentity, "positionIdentity");
        this.targetPositionIdentity = ClosureValueSupport.requireSha256Identity(targetPositionIdentity, "targetPositionIdentity");
        this.nextRevisionReceiptIdentity = nextRevisionReceiptIdentity == null ? null
                : ClosureValueSupport.requireSha256Identity(nextRevisionReceiptIdentity, "nextRevisionReceiptIdentity");
    }
    public String anchorReceiptIdentity() { return anchorReceiptIdentity; }
    public String positionIdentity() { return positionIdentity; }
    public String targetPositionIdentity() { return targetPositionIdentity; }
    public String nextRevisionReceiptIdentity() { return nextRevisionReceiptIdentity; }
    public Map<String, Object> identityValue() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("anchorReceiptIdentity", anchorReceiptIdentity);
        value.put("positionIdentity", positionIdentity);
        value.put("targetPositionIdentity", targetPositionIdentity);
        value.put("nextRevisionReceiptIdentity", nextRevisionReceiptIdentity);
        return value;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ManagedRepresentationCursor)) return false;
        ManagedRepresentationCursor that = (ManagedRepresentationCursor) other;
        return anchorReceiptIdentity.equals(that.anchorReceiptIdentity)
                && positionIdentity.equals(that.positionIdentity)
                && targetPositionIdentity.equals(that.targetPositionIdentity)
                && Objects.equals(nextRevisionReceiptIdentity, that.nextRevisionReceiptIdentity);
    }
    @Override public int hashCode() { return Objects.hash(anchorReceiptIdentity, positionIdentity, targetPositionIdentity, nextRevisionReceiptIdentity); }
}
