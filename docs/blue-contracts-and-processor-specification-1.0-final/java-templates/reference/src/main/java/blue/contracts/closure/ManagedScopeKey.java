package blue.contracts.closure;

import java.util.Objects;

/** Complete scope occurrence key used in routing, gas, checkpoints and diagnostics. */
public final class ManagedScopeKey implements Comparable<ManagedScopeKey> {
    /** Recomputes the general versioned managed-scope identity constructor. */
    public interface IdentityFactory {
        String identity(ManagedScopeKey managedScopeKey);
    }

    private final DocumentId documentId;
    private final String scopePath;
    private final long activationGeneration;
    public ManagedScopeKey(DocumentId documentId, String scopePath, long activationGeneration) {
        this.documentId=Objects.requireNonNull(documentId,"documentId");
        this.scopePath=CanonicalOrders.requireRuntimePointer(
                scopePath, "scopePath");
        this.activationGeneration=CanonicalOrders.requireSafeInteger(
                activationGeneration, "activationGeneration");
        if (("/".equals(this.scopePath) && this.activationGeneration != 0L)
                || (!"/".equals(this.scopePath)
                && this.activationGeneration == 0L)) {
            throw new IllegalArgumentException(
                    "Root generation is 0; embedded generations start at 1");
        }
    }
    public static ManagedScopeKey closureRoot(DocumentId documentId) {
        return new ManagedScopeKey(documentId, "/", 0L);
    }
    public static String requireClosureRootIdentity(
            DocumentId documentId,
            String claimedIdentity,
            IdentityFactory identityFactory) {
        String recomputed = Objects.requireNonNull(
                Objects.requireNonNull(identityFactory, "identityFactory")
                        .identity(closureRoot(documentId)),
                "recomputed managed-scope identity");
        if (!recomputed.equals(Objects.requireNonNull(
                claimedIdentity, "targetManagedScopeIdentity"))) {
            throw new IllegalArgumentException(
                    "Contracts 1.0 closure target is not the Root scope identity");
        }
        return recomputed;
    }
    public boolean isClosureRoot() {
        return "/".equals(scopePath) && activationGeneration == 0L;
    }
    public DocumentId documentId(){return documentId;} public String scopePath(){return scopePath;} public long activationGeneration(){return activationGeneration;}
    @Override public int compareTo(ManagedScopeKey o){int c=documentId.compareTo(o.documentId);if(c!=0)return c;c=CanonicalOrders.compareUnicodeScalars(scopePath,o.scopePath);if(c!=0)return c;return Long.compare(activationGeneration,o.activationGeneration);}
}
