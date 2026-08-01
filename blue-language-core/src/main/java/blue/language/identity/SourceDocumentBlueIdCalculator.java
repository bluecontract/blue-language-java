package blue.language.identity;

import blue.language.model.Node;

import java.util.Objects;
import java.util.function.Function;

/**
 * Calculates Source Document identity through canonical identity input.
 *
 * <p>The injected function must implement preprocessing, complete resolution,
 * and canonicalization in that order. Minimization is intentionally not a
 * dependency and therefore cannot enter this path.</p>
 */
public final class SourceDocumentBlueIdCalculator {

    private final Function<Node, Node> canonicalIdentityInput;
    private final DirectBlueIdCalculator directCalculator;

    /**
     * Creates a Source Document calculator.
     *
     * @param canonicalIdentityInput Source-to-canonical function
     * @param directCalculator normative direct calculator
     */
    public SourceDocumentBlueIdCalculator(
            Function<Node, Node> canonicalIdentityInput,
            DirectBlueIdCalculator directCalculator) {
        this.canonicalIdentityInput = Objects.requireNonNull(
                canonicalIdentityInput,
                "canonicalIdentityInput");
        this.directCalculator = Objects.requireNonNull(
                directCalculator,
                "directCalculator");
    }

    /**
     * Produces the canonical direct identity input for an authored Source.
     *
     * @param sourceDocument authored Source Document
     * @return canonical identity input
     */
    public Node canonicalIdentityInput(Node sourceDocument) {
        return Objects.requireNonNull(
                canonicalIdentityInput.apply(Objects.requireNonNull(
                        sourceDocument,
                        "sourceDocument").clone()),
                "canonicalIdentityInput result");
    }

    /**
     * Calculates Source identity by passing canonical input to the one direct
     * calculator.
     *
     * @param sourceDocument authored Source Document
     * @return canonical BlueId
     */
    public String sourceDocumentBlueId(Node sourceDocument) {
        return directCalculator.directBlueId(
                canonicalIdentityInput(sourceDocument));
    }
}
