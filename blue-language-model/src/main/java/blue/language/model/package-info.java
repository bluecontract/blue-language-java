/**
 * Defines mutable authoring values at the Blue Language boundary.
 *
 * <p><strong>Contents.</strong> This package contains Blue nodes and schemas,
 * serialization adapters, identity-provider hooks, graph-copy support, and
 * structural path editing. Resolution, provider access, canonical hashing,
 * Contracts processing, and runtime caching do not belong in the model.</p>
 *
 * <p><strong>Entry points.</strong> Authors construct
 * {@link blue.language.model.Node} and {@link blue.language.model.Schema};
 * {@link blue.language.model.NodePathEditor} provides explicit structural path
 * reads, writes, and pattern selection. {@link blue.language.model.Nodes}
 * supplies narrow shape predicates and canonical scalar factories.</p>
 *
 * <p><strong>Lifecycle.</strong> Nodes and schemas are mutable DTOs and are not
 * thread-safe. Callers own the graphs they construct or receive unless an API
 * explicitly returns an immutable snapshot; use deep cloning or snapshot
 * conversion before sharing mutable graphs.</p>
 *
 * <p><strong>Extension.</strong> Add fields only when the Language wire model
 * specifies them, and keep model code free of provider or runtime dependencies.
 * Wire constants and pointer syntax live in
 * {@link blue.language.model.wire.JsonPointer}; immutable runtime values live
 * in the core snapshot layer.</p>
 */
package blue.language.model;
