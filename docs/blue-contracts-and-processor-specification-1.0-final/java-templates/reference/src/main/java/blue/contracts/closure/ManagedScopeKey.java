package blue.contracts.closure;

import java.util.Objects;

/** Complete scope occurrence key used in routing, gas, checkpoints and diagnostics. */
public final class ManagedScopeKey implements Comparable<ManagedScopeKey> {
    private final DocumentId documentId;
    private final String scopePath;
    private final long activationGeneration;
    public ManagedScopeKey(DocumentId documentId, String scopePath, long activationGeneration) {
        this.documentId=Objects.requireNonNull(documentId,"documentId");
        this.scopePath=requirePointer(scopePath);
        if (activationGeneration<0) throw new IllegalArgumentException("activationGeneration");
        this.activationGeneration=activationGeneration;
    }
    private static String requirePointer(String p){if(p==null||!p.startsWith("/"))throw new IllegalArgumentException("scopePath");return p;}
    public DocumentId documentId(){return documentId;} public String scopePath(){return scopePath;} public long activationGeneration(){return activationGeneration;}
    @Override public int compareTo(ManagedScopeKey o){int c=documentId.compareTo(o.documentId);if(c!=0)return c;c=scopePath.compareTo(o.scopePath);if(c!=0)return c;return Long.compare(activationGeneration,o.activationGeneration);} 
}
