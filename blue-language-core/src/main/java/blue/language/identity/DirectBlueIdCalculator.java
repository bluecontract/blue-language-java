package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Orchestrates the strict direct BlueId path over normalized identity input.
 *
 * <p>Map, list, and scalar formulas live in dedicated collaborators. Every
 * direct call, including compatibility and cyclic-set calls, reaches this one
 * recursive implementation.</p>
 */
public final class DirectBlueIdCalculator {

    /** Shared calculator using the normative canonical JSON hash. */
    public static final DirectBlueIdCalculator INSTANCE =
            new DirectBlueIdCalculator();

    private final BlueIdInputNormalizer normalizer;
    private final ScalarIdentityEncoder scalarEncoder;
    private final ObjectBlueIdHasher objectHasher;
    private final ListBlueIdFold listFold;

    /** Creates a calculator using the normative canonical JSON hasher. */
    public DirectBlueIdCalculator() {
        this(new CanonicalJsonHasher());
    }

    /**
     * Creates a calculator with an explicit deterministic hash function.
     *
     * <p>This constructor supports formula-level tests and compatibility
     * tooling. Production callers should normally use the no-argument
     * constructor.</p>
     *
     * @param hashProvider canonical-value hash function
     */
    public DirectBlueIdCalculator(Function<Object, String> hashProvider) {
        Function<Object, String> checkedHashProvider = Objects.requireNonNull(
                hashProvider,
                "hashProvider");
        this.normalizer = new BlueIdInputNormalizer();
        this.scalarEncoder = new ScalarIdentityEncoder();
        this.objectHasher = new ObjectBlueIdHasher(checkedHashProvider);
        this.listFold = new ListBlueIdFold(checkedHashProvider);
    }

    /** Calculates a strict direct BlueId with the shared calculator. */
    public static String calculateBlueId(Node node) {
        return INSTANCE.directBlueId(node);
    }

    /** Calculates a strict ordered-list BlueId with the shared calculator. */
    public static String calculateBlueId(List<Node> nodes) {
        return INSTANCE.directBlueId(nodes);
    }

    /** Calculates unchecked structural identity with the shared calculator. */
    public static String calculateUncheckedBlueId(Node node) {
        return INSTANCE.uncheckedBlueId(node);
    }

    /** Calculates unchecked ordered-list identity with the shared calculator. */
    public static String calculateUncheckedBlueId(List<Node> nodes) {
        return INSTANCE.uncheckedBlueId(nodes);
    }

    /** Calculates direct identity while accepting cyclic placeholders. */
    public static String calculateBlueIdAllowingCyclicPlaceholders(
            Node node) {
        return INSTANCE.directBlueIdAllowingCyclicPlaceholders(node);
    }

    /** Calculates ordered identity while accepting cyclic placeholders. */
    public static String calculateBlueIdAllowingCyclicPlaceholders(
            List<Node> nodes) {
        return INSTANCE.directBlueIdAllowingCyclicPlaceholders(nodes);
    }

    /**
     * Calculates a strict direct BlueId for one exact node.
     *
     * @param node strict direct identity input
     * @return canonical BlueId
     */
    public String directBlueId(Node node) {
        return calculateNormalized(normalizer.normalize(node));
    }

    /**
     * Calculates a strict direct BlueId for an ordered list of exact nodes.
     *
     * @param nodes ordered list elements
     * @return canonical list BlueId
     */
    public String directBlueId(List<Node> nodes) {
        return calculateNormalized(normalizer.normalizeElements(nodes));
    }

    /**
     * Calculates identity for an already projected map/list/scalar value.
     *
     * @param canonicalInput projected identity input
     * @return canonical BlueId
     */
    public String directBlueIdFromCanonicalInput(Object canonicalInput) {
        return calculateNormalized(
                normalizer.normalizeCanonicalInput(canonicalInput));
    }

    /**
     * Calculates legacy unchecked structural identity for one node.
     *
     * @param node source node
     * @return unchecked structural BlueId
     */
    public String uncheckedBlueId(Node node) {
        return directBlueIdFromCanonicalInput(NodeWireForm.get(node));
    }

    /**
     * Calculates legacy unchecked structural identity for a node list.
     *
     * @param nodes ordered source elements
     * @return unchecked structural list BlueId
     */
    public String uncheckedBlueId(List<Node> nodes) {
        java.util.ArrayList<Object> values = new java.util.ArrayList<>(
                nodes.size());
        for (Node node : nodes) {
            values.add(NodeWireForm.get(node));
        }
        return directBlueIdFromCanonicalInput(values);
    }

    /**
     * Calculates direct identity while accepting invocation-local cyclic
     * placeholders. Ordinary callers should use {@link #directBlueId(Node)}.
     *
     * @param node cyclic calculation input
     * @return preliminary or master BlueId
     */
    public String directBlueIdAllowingCyclicPlaceholders(Node node) {
        return calculateNormalized(
                normalizer.normalizeAllowingCyclicPlaceholders(node));
    }

    /**
     * Calculates ordered identity while accepting invocation-local cyclic
     * placeholders.
     *
     * @param nodes cyclic calculation members
     * @return cyclic-set master BlueId
     */
    public String directBlueIdAllowingCyclicPlaceholders(List<Node> nodes) {
        return calculateNormalized(
                normalizer.normalizeElementsAllowingCyclicPlaceholders(nodes));
    }

    @SuppressWarnings("unchecked")
    private String calculateNormalized(Object normalized) {
        if (normalized instanceof String
                || normalized instanceof Number
                || normalized instanceof Boolean) {
            return objectHasher.hash(
                    scalarEncoder.encode(normalized),
                    this::calculateNormalized);
        }
        if (normalized instanceof Map) {
            return objectHasher.hash(
                    (Map<String, Object>) normalized,
                    this::calculateNormalized);
        }
        if (normalized instanceof List) {
            return listFold.fold(
                    (List<Object>) normalized,
                    this::calculateNormalized);
        }
        throw new IllegalArgumentException(
                "Object must be a String, Number, Boolean, List or Map - found "
                        + normalized.getClass());
    }
}
