package blue.language.identity;

import blue.language.model.Node;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Default immutable composition of direct, Source Document, and cyclic-set
 * identity operations.
 *
 * <p>The canonicalizer is supplied by the resolution composition root. This
 * keeps identity independent of provider policy and makes it impossible for
 * Source Document identity to invoke minimization.</p>
 */
public final class StandardBlueIdentity implements BlueIdentity {

    private final DirectBlueIdCalculator directCalculator;
    private final SourceDocumentBlueIdCalculator sourceCalculator;
    private final CircularSetIdentityCalculator circularCalculator;

    /**
     * Creates an identity service using the normative direct calculator.
     *
     * @param canonicalIdentityInput function implementing preprocess, complete
     *                               resolution, and canonicalization
     */
    public StandardBlueIdentity(Function<Node, Node> canonicalIdentityInput) {
        this(new DirectBlueIdCalculator(), canonicalIdentityInput);
    }

    /**
     * Creates an identity service with an explicit direct calculator.
     *
     * @param directCalculator direct BlueId implementation
     * @param canonicalIdentityInput Source-to-canonical function
     */
    public StandardBlueIdentity(
            DirectBlueIdCalculator directCalculator,
            Function<Node, Node> canonicalIdentityInput) {
        this.directCalculator = Objects.requireNonNull(
                directCalculator,
                "directCalculator");
        this.sourceCalculator = new SourceDocumentBlueIdCalculator(
                Objects.requireNonNull(
                        canonicalIdentityInput,
                        "canonicalIdentityInput"),
                directCalculator);
        this.circularCalculator = new CircularSetIdentityCalculator(
                directCalculator);
    }

    @Override
    public String directBlueId(Node blueIdInput) {
        return directCalculator.directBlueId(blueIdInput);
    }

    @Override
    public String sourceDocumentBlueId(Node sourceDocument) {
        return sourceCalculator.sourceDocumentBlueId(sourceDocument);
    }

    @Override
    public Node canonicalIdentityInput(Node sourceDocument) {
        return sourceCalculator.canonicalIdentityInput(sourceDocument);
    }

    @Override
    public List<String> circularBlueIds(List<Node> documents) {
        return circularCalculator.circularBlueIds(documents);
    }
}
