package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.codec.BlueFormat;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.provider.NodeProvider;

import java.util.Objects;

/**
 * Compact convenience facade over the focused Language and Contracts services.
 *
 * <p>The facade owns one immutable {@link BlueRuntime}. It is thread-safe when
 * its borrowed {@link NodeProvider} is thread-safe. Inputs are never mutated,
 * runtime-owned caches are bounded, and {@link #close()} releases Contracts
 * state before Language state. Applications that need advanced configuration
 * or a broader operation surface should use {@link BlueRuntime} directly.</p>
 */
public final class Blue implements AutoCloseable {

    private final BlueRuntime runtime;

    /**
     * Creates an independent runtime with bounded default caches and no
     * application content provider.
     */
    public Blue() {
        this(BlueRuntime.builder().build());
    }

    /**
     * Creates an independent runtime borrowing one exact-content provider.
     *
     * @param nodeProvider provider for externally addressed Blue content
     * @throws NullPointerException when {@code nodeProvider} is {@code null}
     */
    public Blue(NodeProvider nodeProvider) {
        this(BlueRuntime.builder()
                .nodeProvider(Objects.requireNonNull(
                        nodeProvider, "nodeProvider"))
                .build());
    }

    private Blue(BlueRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Creates an independent runtime with the supplied bounded cache policy.
     *
     * @param cachePolicy cache bounds shared by the focused runtime services
     * @return a new independently owned facade
     * @throws NullPointerException when {@code cachePolicy} is {@code null}
     */
    public static Blue withCachePolicy(BlueCachePolicy cachePolicy) {
        return new Blue(BlueRuntime.builder()
                .cachePolicy(Objects.requireNonNull(
                        cachePolicy, "cachePolicy"))
                .build());
    }

    /**
     * Parses and preprocesses one authored YAML Source Document.
     *
     * @param yaml authored YAML text
     * @return an independent validated Preprocessed Document
     * @throws NullPointerException when {@code yaml} is {@code null}
     * @throws IllegalArgumentException when the parsed Source is not valid
     *         Blue input
     * @throws IllegalStateException when this facade is closed
     */
    public Node yamlToNode(String yaml) {
        Node source = runtime.language().codec().parseSource(
                yaml, BlueFormat.YAML);
        return runtime.language().preprocessing().preprocess(source);
    }

    /**
     * Parses and preprocesses one authored JSON Source Document.
     *
     * @param json authored JSON text
     * @return an independent validated Preprocessed Document
     * @throws NullPointerException when {@code json} is {@code null}
     * @throws IllegalArgumentException when the parsed Source is not valid
     *         Blue input
     * @throws IllegalStateException when this facade is closed
     */
    public Node jsonToNode(String json) {
        Node source = runtime.language().codec().parseSource(
                json, BlueFormat.JSON);
        return runtime.language().preprocessing().preprocess(source);
    }

    /**
     * Writes one node in the normalized YAML wire form.
     *
     * @param node node to serialize without mutation
     * @return normalized YAML text
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalStateException when this facade is closed
     */
    public String nodeToYaml(Node node) {
        return runtime.language().codec().write(node, BlueFormat.YAML);
    }

    /**
     * Writes one node in the normalized JSON wire form.
     *
     * @param node node to serialize without mutation
     * @return normalized JSON text
     * @throws NullPointerException when {@code node} is {@code null}
     * @throws IllegalStateException when this facade is closed
     */
    public String nodeToJson(Node node) {
        return runtime.language().codec().write(node, BlueFormat.JSON);
    }

    /**
     * Maps one Java value to a node and applies Source preprocessing.
     *
     * @param value non-null Java value or node
     * @return an independent validated Preprocessed Document
     * @throws NullPointerException when {@code value} is {@code null}
     * @throws IllegalArgumentException when the mapped Source is not valid
     *         Blue input
     * @throws IllegalStateException when this facade is closed
     */
    public Node objectToNode(Object value) {
        Node source = runtime.mapping().toNode(value);
        return runtime.language().preprocessing().preprocess(source);
    }

    /**
     * Maps one node to a newly allocated Java value.
     *
     * @param <T> requested Java value type
     * @param node source node, or {@code null}; it is not mutated
     * @param targetClass requested Java class
     * @return newly allocated mapped value, or {@code null} when {@code node}
     *         is {@code null}
     * @throws IllegalArgumentException when a non-null node cannot be mapped
     *         to {@code targetClass}, including a null target class
     * @throws IllegalStateException when this facade is closed
     */
    public <T> T nodeToObject(Node node, Class<T> targetClass) {
        return runtime.mapping().fromNode(node, targetClass);
    }

    /**
     * Applies the configured deterministic Source preprocessing pipeline.
     *
     * @param source authored Source Document; it is not mutated
     * @return independent validated Preprocessed Document
     * @throws NullPointerException when {@code source} is {@code null}
     * @throws IllegalArgumentException when the Source or referenced
     *         preprocessing resources are invalid
     * @throws IllegalStateException when this facade is closed
     */
    public Node preprocess(Node source) {
        return runtime.language().preprocessing().preprocess(source);
    }

    /**
     * Completely resolves one authored Source Document.
     *
     * @param source authored Source Document; it is not mutated
     * @return independent fully resolved document
     * @throws NullPointerException when {@code source} is {@code null}
     * @throws IllegalArgumentException when the Source or referenced content
     *         is invalid
     * @throws IllegalStateException when this facade is closed
     */
    public Node resolve(Node source) {
        return runtime.language().resolution().resolve(source);
    }

    /**
     * Produces the strict canonical identity input for one Source Document.
     *
     * @param source authored Source Document; it is not mutated
     * @return independent strict canonical identity input
     * @throws NullPointerException when {@code source} is {@code null}
     * @throws IllegalArgumentException when the Source or referenced content
     *         is invalid
     * @throws IllegalStateException when this facade is closed
     */
    public Node canonicalize(Node source) {
        return runtime.language().identity()
                .canonicalIdentityInput(source);
    }

    /**
     * Produces a smaller authored overlay with the same resolved meaning.
     *
     * @param source authored Source Document; it is not mutated
     * @return independent minimized authored overlay
     * @throws NullPointerException when {@code source} is {@code null}
     * @throws IllegalArgumentException when the Source cannot be resolved or
     *         minimized exactly
     * @throws IllegalStateException when this facade is closed
     */
    public Node minimize(Node source) {
        return runtime.language().resolution().minimize(source);
    }

    /**
     * Reveals verified referenced content without changing node identity.
     *
     * @param source authored graph to expand; it is not mutated
     * @return independent graph with reachable exact references expanded
     * @throws IllegalArgumentException when {@code source} is {@code null} or
     *         referenced provider evidence is invalid
     * @throws IllegalStateException when this facade is closed
     */
    public Node expand(Node source) {
        return runtime.language().graph().expand(source);
    }

    /**
     * Hides exact content behind its BlueId.
     *
     * @param exactInput canonical exact content or an identity-preserving
     *                   expanded materialization; it is not mutated
     * @return a pure reference to the input's direct BlueId
     * @throws IllegalArgumentException when {@code exactInput} is
     *         {@code null} or cannot establish an exact identity
     * @throws IllegalStateException when this facade is closed
     */
    public Node collapse(Node exactInput) {
        return runtime.language().graph().collapse(exactInput);
    }

    /**
     * Creates a new authored node from a type and compatible overlay.
     *
     * @param type non-null type node or pure type reference
     * @param overlay compatible authored overlay without its own type
     * @return independent validated specialization
     * @throws NullPointerException when either argument is {@code null}
     * @throws IllegalArgumentException when the overlay declares a type or the
     *         specialization does not resolve compatibly
     * @throws IllegalStateException when this facade is closed
     */
    public Node specialize(Node type, Node overlay) {
        return runtime.language().graph().specialize(type, overlay);
    }

    /**
     * Calculates the one BlueId algorithm from exact direct input.
     *
     * @param exactInput strict direct identity input
     * @return deterministic canonical BlueId
     * @throws IllegalArgumentException when {@code exactInput} is not valid
     *         direct BlueId input
     * @throws IllegalStateException when this facade is closed
     */
    public String calculateBlueId(Node exactInput) {
        return runtime.language().identity().directBlueId(exactInput);
    }

    /**
     * Calculates a BlueId through preprocessing, resolution, and
     * canonicalization.
     *
     * @param source authored Source Document; it is not mutated
     * @return deterministic canonical Source Document BlueId
     * @throws NullPointerException when {@code source} is {@code null}
     * @throws IllegalArgumentException when the Source or referenced content
     *         is invalid
     * @throws IllegalStateException when this facade is closed
     */
    public String calculateSourceDocumentBlueId(Node source) {
        return runtime.language().identity()
                .sourceDocumentBlueId(source);
    }

    /**
     * Resolves authored Source into immutable canonical and resolved views.
     *
     * @param source authored Source Document; it is not mutated
     * @return immutable snapshot containing canonical and resolved views
     * @throws NullPointerException when {@code source} is {@code null}
     * @throws IllegalArgumentException when the Source or referenced content
     *         is invalid
     * @throws IllegalStateException when this facade is closed
     */
    public ResolvedSnapshot resolveToSnapshot(Node source) {
        return runtime.language().snapshots().resolve(source);
    }

    /**
     * Loads verified provider content addressed by one exact BlueId.
     *
     * @param blueId exact plain BlueId to load
     * @return immutable snapshot of the verified canonical and resolved content
     * @throws IllegalArgumentException when {@code blueId} is malformed,
     *         absent, ambiguous, or backed by invalid provider evidence
     * @throws IllegalStateException when this facade is closed
     */
    public ResolvedSnapshot loadSnapshot(String blueId) {
        return runtime.language().snapshots().load(blueId);
    }

    /**
     * Resolves and tests whether a candidate matches a Language type.
     * Runtime matching failures produce {@code false}.
     *
     * @param candidate authored candidate value
     * @param type authored type definition
     * @return {@code true} when the resolved candidate matches the resolved
     *         type
     * @throws IllegalStateException when this facade is closed
     */
    public boolean nodeMatchesType(Node candidate, Node type) {
        return runtime.language().matching().matches(candidate, type);
    }

    /**
     * Processes one Root and event and returns Root-scope emissions only.
     * Processing-domain failures are returned as deterministic result data.
     *
     * @param root exact initialized Root document
     * @param event exact event presented to the Contracts processor
     * @return complete deterministic processing result
     * @throws IllegalStateException when this facade is closed
     */
    public DocumentProcessingResult processDocument(
            Node root,
            Node event) {
        return runtime.contracts().process(root, event);
    }

    /**
     * Returns whether terminal shutdown has begun.
     *
     * @return {@code true} after terminal shutdown begins
     */
    public boolean isClosed() {
        return runtime.isClosed();
    }

    /**
     * Releases owned Contracts state before Language runtime state.
     * Repeated calls replay any retained close failure.
     *
     * @throws RuntimeException when an owned runtime resource fails to close
     */
    @Override
    public void close() {
        runtime.close();
    }
}
