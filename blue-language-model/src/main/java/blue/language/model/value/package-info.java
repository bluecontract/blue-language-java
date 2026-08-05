/**
 * Canonical scalar conversion and comparison rules for the Blue data model.
 *
 * <p><strong>Contents.</strong> This package contains exact numeric and scalar
 * value helpers used by model serialization and semantic validation. Graph
 * traversal, identity hashing, provider access, and runtime state do not
 * belong here.</p>
 *
 * <p><strong>Entry points.</strong> {@link blue.language.model.value.BlueNumbers}
 * owns canonical numeric conversion, while
 * {@link blue.language.model.value.ScalarValues} reads schema scalar values.</p>
 *
 * <p><strong>Lifecycle.</strong> The helpers are stateless, thread-safe, and
 * reusable. They own no resources and require no close operation.</p>
 *
 * <p><strong>Extension.</strong> Applications should contribute new domain
 * types through mapping or runtime SPIs, not by extending the closed Language
 * scalar rules. Neighboring {@code blue.language.model} owns nodes and schema;
 * {@code blue.language.model.wire} owns protocol spellings.</p>
 */
package blue.language.model.value;
