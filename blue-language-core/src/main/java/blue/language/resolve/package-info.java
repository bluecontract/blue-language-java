/**
 * Establishes complete type-derived meaning and author-facing minimization.
 *
 * <p><strong>Contents.</strong> This package contains the focused resolution
 * service, minimized-overlay construction, traversal-limit contract, and its
 * package-private policy implementations. Canonical identity reconstruction,
 * graph transport, Contracts semantics, and application persistence do not
 * belong here.</p>
 *
 * <p><strong>Entry points.</strong> Applications resolve and minimize through
 * {@link blue.language.resolve.BlueResolution}. Advanced traversal code uses
 * {@link blue.language.resolve.ResolutionLimits} factories and its builder;
 * {@link blue.language.resolve.MinimizedOverlayBuilder} is the explicit
 * low-level minimization boundary.</p>
 *
 * <p><strong>Lifecycle.</strong> Resolution services are configured and owned
 * by a runtime. Most {@code ResolutionLimits} instances track a balanced
 * traversal path and therefore belong to one invocation and one thread; only
 * {@link blue.language.resolve.ResolutionLimits#NO_LIMITS} is stateless and
 * freely shareable.</p>
 *
 * <p><strong>Extension.</strong> Compose limits through public factories rather
 * than depending on concrete policies. Limited resolution must preserve
 * incomplete versus absent, and minimization must never become an identity
 * algorithm. Identity belongs in {@link blue.language.identity.BlueIdentity};
 * merge orchestration lives in {@link blue.language.merge.Merger}.</p>
 */
package blue.language.resolve;
