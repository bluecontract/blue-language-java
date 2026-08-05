/**
 * Complete resolution machinery and immutable resolved/canonical snapshots.
 *
 * <p><strong>Contents.</strong> Node resolution contracts, merge orchestration,
 * verified-reference provenance, and snapshot pairs belong here. Authored
 * preprocessing, transport codecs, and runtime-specific Contracts state do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.merge.NodeResolver} and
 * {@link blue.language.merge.Merger} perform resolution;
 * {@link blue.language.merge.ResolvedSnapshot} exposes immutable canonical and
 * resolved lanes through {@link blue.language.merge.BlueSnapshots}.</p>
 *
 * <p><strong>Lifecycle.</strong> Resolved snapshots are immutable and
 * thread-safe. Configured resolvers borrow providers and may participate in an
 * owning runtime's bounded caches; callers close the runtime, not snapshots.</p>
 *
 * <p><strong>Extension.</strong> Custom merge stages implement
 * {@link blue.language.merge.MergingProcessor} only when defining Language
 * semantics. Application providers belong in {@code blue.language.provider};
 * persistent edits belong in {@code blue.language.snapshot}.</p>
 */
package blue.language.merge;
