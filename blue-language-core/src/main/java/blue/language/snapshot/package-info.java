/**
 * Immutable Blue node representation and persistent canonical patch mechanics.
 *
 * <p><strong>Contents.</strong> Frozen nodes, structural navigation and keys,
 * immutable patch values, and canonical persistent edits belong here. Runtime
 * cache policy, authored preprocessing, and Contracts commit policy do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.snapshot.FrozenNode} is the immutable graph value;
 * {@link blue.language.snapshot.ImmutableBluePatch} and
 * {@link blue.language.snapshot.CanonicalOverlayPatchEngine} perform persistent
 * edits.</p>
 *
 * <p><strong>Lifecycle.</strong> Frozen nodes and immutable patches are
 * thread-safe and freely shareable. Builders are mutable construction scopes
 * and must not be shared concurrently. No snapshot value requires closing.</p>
 *
 * <p><strong>Extension.</strong> Patch operation semantics and canonical
 * identity projection are closed Language behavior. Resolved/canonical pairs
 * live in {@code blue.language.merge}; focused patch application lives in
 * {@code blue.language.patching}.</p>
 */
package blue.language.snapshot;
