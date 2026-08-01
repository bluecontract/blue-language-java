package blue.language.resolve;

/**
 * Host-supplied policy deciding whether exact canonical provider content may
 * enter a reusable Language reference cache.
 *
 * <p>The policy changes acceleration only. Rejected content is still fetched,
 * identity-verified, and resolved for the current invocation.</p>
 */
@FunctionalInterface
public interface ReferenceCacheAdmissionPolicy {

    /** Language-only default for exact non-contextual provider content. */
    ReferenceCacheAdmissionPolicy ALLOW_ALL = blueId -> true;

    /** Conservative policy for hosts whose provider content is contextual. */
    ReferenceCacheAdmissionPolicy DENY_ALL = blueId -> false;

    /**
     * Returns whether canonical content for {@code blueId} may be retained.
     *
     * @param blueId exact verified content identity
     * @return {@code true} when the reusable cache may retain the content
     */
    boolean mayCacheCanonical(String blueId);
}
