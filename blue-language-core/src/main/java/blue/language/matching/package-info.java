/**
 * Deterministic structural, schema, and declared-type matching.
 *
 * <p><strong>Contents.</strong> Focused matching APIs, verified-reference
 * materialization boundaries, and matcher-owned bounded plans belong here.
 * General resolution, provider transport, and Contracts handler selection do
 * not.</p>
 *
 * <p><strong>Entry points.</strong> Use
 * {@link blue.language.matching.BlueMatching} for runtime composition and
 * {@link blue.language.matching.FrozenTypeMatcher} for immutable resolved
 * values. {@link blue.language.matching.MatchingRuntime} is the narrow host
 * boundary.</p>
 *
 * <p><strong>Lifecycle.</strong> Frozen nodes are immutable and shareable;
 * matcher instances own bounded synchronized caches and may be reused and
 * explicitly cleared. Borrowed runtimes retain their own close lifecycle.</p>
 *
 * <p><strong>Extension.</strong> Hosts may implement {@code MatchingRuntime}
 * with verified exact materialization. New schema keywords or subtype rules
 * are Language changes, not application extensions. Resolution neighbors this
 * package in {@code blue.language.merge} and {@code blue.language.resolve}.</p>
 */
package blue.language.matching;
