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

    /** Creates an independent runtime with bounded default caches. */
    public Blue() {
        this(BlueRuntime.builder().build());
    }

    /**
     * Creates an independent runtime borrowing one exact-content provider.
     *
     * @param nodeProvider provider for externally addressed Blue content
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

    /** Creates an independent runtime with the supplied bounded cache policy. */
    public static Blue withCachePolicy(BlueCachePolicy cachePolicy) {
        return new Blue(BlueRuntime.builder()
                .cachePolicy(Objects.requireNonNull(
                        cachePolicy, "cachePolicy"))
                .build());
    }

    /** Parses and preprocesses one authored YAML Source Document. */
    public Node yamlToNode(String yaml) {
        Node source = runtime.language().codec().parseSource(
                yaml, BlueFormat.YAML);
        return runtime.language().preprocessing().preprocess(source);
    }

    /** Parses and preprocesses one authored JSON Source Document. */
    public Node jsonToNode(String json) {
        Node source = runtime.language().codec().parseSource(
                json, BlueFormat.JSON);
        return runtime.language().preprocessing().preprocess(source);
    }

    /** Writes one node in the normalized YAML wire form. */
    public String nodeToYaml(Node node) {
        return runtime.language().codec().write(node, BlueFormat.YAML);
    }

    /** Writes one node in the normalized JSON wire form. */
    public String nodeToJson(Node node) {
        return runtime.language().codec().write(node, BlueFormat.JSON);
    }

    /** Maps one Java value to a node and applies Source preprocessing. */
    public Node objectToNode(Object value) {
        Node source = runtime.mapping().toNode(value);
        return runtime.language().preprocessing().preprocess(source);
    }

    /** Maps one node to a newly allocated Java value. */
    public <T> T nodeToObject(Node node, Class<T> targetClass) {
        return runtime.mapping().fromNode(node, targetClass);
    }

    /** Applies the configured deterministic Source preprocessing pipeline. */
    public Node preprocess(Node source) {
        return runtime.language().preprocessing().preprocess(source);
    }

    /** Completely resolves one authored Source Document. */
    public Node resolve(Node source) {
        return runtime.language().resolution().resolve(source);
    }

    /** Produces the strict canonical identity input for one Source Document. */
    public Node canonicalize(Node source) {
        return runtime.language().identity()
                .canonicalIdentityInput(source);
    }

    /** Produces a smaller authored overlay with the same resolved meaning. */
    public Node minimize(Node source) {
        return runtime.language().resolution().minimize(source);
    }

    /** Reveals verified referenced content without changing node identity. */
    public Node expand(Node source) {
        return runtime.language().graph().expand(source);
    }

    /** Hides exact canonical content behind its direct BlueId. */
    public Node collapse(Node exactInput) {
        return runtime.language().graph().collapse(exactInput);
    }

    /** Creates a new authored node from a type and compatible overlay. */
    public Node specialize(Node type, Node overlay) {
        return runtime.language().graph().specialize(type, overlay);
    }

    /** Calculates the one BlueId algorithm from exact direct input. */
    public String calculateBlueId(Node exactInput) {
        return runtime.language().identity().directBlueId(exactInput);
    }

    /** Calculates a BlueId through preprocessing, resolution, and canonicalization. */
    public String calculateSourceDocumentBlueId(Node source) {
        return runtime.language().identity()
                .sourceDocumentBlueId(source);
    }

    /** Resolves authored Source into immutable canonical and resolved views. */
    public ResolvedSnapshot resolveToSnapshot(Node source) {
        return runtime.language().snapshots().resolve(source);
    }

    /** Loads verified provider content addressed by one exact BlueId. */
    public ResolvedSnapshot loadSnapshot(String blueId) {
        return runtime.language().snapshots().load(blueId);
    }

    /** Resolves and tests whether a candidate matches a Language type. */
    public boolean nodeMatchesType(Node candidate, Node type) {
        return runtime.language().matching().matches(candidate, type);
    }

    /** Processes one Root and event and returns Root emissions only. */
    public DocumentProcessingResult processDocument(
            Node root,
            Node event) {
        return runtime.contracts().process(root, event);
    }

    /** Returns whether terminal shutdown has begun. */
    public boolean isClosed() {
        return runtime.isClosed();
    }

    /** Releases owned Contracts and Language runtime state. */
    @Override
    public void close() {
        runtime.close();
    }
}
