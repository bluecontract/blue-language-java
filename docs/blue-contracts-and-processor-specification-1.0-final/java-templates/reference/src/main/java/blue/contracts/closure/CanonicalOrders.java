package blue.contracts.closure;

import java.util.Comparator;
public final class CanonicalOrders {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private CanonicalOrders() {}
    public static long requireSafeInteger(long value,String field){if(value<0||value>MAX_SAFE_INTEGER)throw new IllegalArgumentException(field);return value;}
    public static final Comparator<DocumentId> DOCUMENT_ID = new Comparator<DocumentId>() { public int compare(DocumentId a,DocumentId b){return a.compareTo(b);} };
    public static final Comparator<DirectLogicalDelivery> DIRECT_DELIVERY = new Comparator<DirectLogicalDelivery>() { public int compare(DirectLogicalDelivery a,DirectLogicalDelivery b){return a.compareTo(b);} };
    public static final Comparator<WorkOccurrence> WORK_ORDINAL = new Comparator<WorkOccurrence>() { public int compare(WorkOccurrence a,WorkOccurrence b){return a.compareTo(b);} };
}
