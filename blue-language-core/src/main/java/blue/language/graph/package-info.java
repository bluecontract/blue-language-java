/**
 * Exact Blue graph expansion, collapse, and specialization operations.
 *
 * <p><strong>Contents.</strong> Identity-preserving materialization and graph
 * construction belong here. Type resolution, Source canonicalization, and
 * text serialization do not.</p>
 *
 * <p><strong>Entry points.</strong> Applications use
 * {@link blue.language.graph.BlueGraph};
 * {@link blue.language.graph.StandardBlueGraph} binds the operations to a
 * verified provider and resolver.</p>
 *
 * <p><strong>Lifecycle.</strong> Graph operations return independent mutable
 * nodes and do not mutate caller input. A configured service borrows its
 * provider and follows the lifecycle and thread-safety of the owning runtime.</p>
 *
 * <p><strong>Extension.</strong> Provider behavior is extended through
 * {@code blue.language.provider.NodeProvider}, not by changing graph
 * semantics. Identity lives in {@code blue.language.identity}; authored
 * resolution lives in {@code blue.language.resolve}.</p>
 */
package blue.language.graph;
