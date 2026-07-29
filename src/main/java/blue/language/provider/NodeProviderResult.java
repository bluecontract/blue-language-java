package blue.language.provider;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral, immutable provider conclusion for one requested BlueId.
 *
 * <p>Found content is defensively copied on construction and every read.
 * Non-found outcomes cannot carry nodes.</p>
 */
public final class NodeProviderResult {

    private final NodeProviderOutcome outcome;
    private final List<Node> nodes;
    private final String diagnostic;

    private NodeProviderResult(NodeProviderOutcome outcome,
                               List<Node> nodes,
                               String diagnostic) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        List<Node> retained = new ArrayList<>();
        if (nodes != null) {
            for (Node node : nodes) {
                retained.add(Objects.requireNonNull(node, "provider node").clone());
            }
        }
        this.nodes = Collections.unmodifiableList(retained);
        this.diagnostic = diagnostic;
        if (outcome == NodeProviderOutcome.FOUND && retained.isEmpty()) {
            throw new IllegalArgumentException("Found provider results require content.");
        }
        if (outcome != NodeProviderOutcome.FOUND && !retained.isEmpty()) {
            throw new IllegalArgumentException(outcome + " provider results cannot carry content.");
        }
    }

    /**
     * Creates a found result containing defensively copied content.
     *
     * @param nodes non-empty candidate list
     * @return found result
     * @throws IllegalArgumentException when the list is null or empty
     */
    public static NodeProviderResult found(List<Node> nodes) {
        return new NodeProviderResult(NodeProviderOutcome.FOUND, nodes, null);
    }

    /**
     * Creates a definitive provider miss.
     *
     * @return provider-miss result
     */
    public static NodeProviderResult notFound() {
        return new NodeProviderResult(NodeProviderOutcome.NOT_FOUND, null, null);
    }

    /**
     * Creates a transiently unavailable result.
     *
     * @param diagnostic optional provider diagnostic
     * @return unavailable result
     */
    public static NodeProviderResult unavailable(String diagnostic) {
        return new NodeProviderResult(NodeProviderOutcome.UNAVAILABLE, null, diagnostic);
    }

    /**
     * Creates an invalid-evidence result.
     *
     * @param diagnostic optional verification diagnostic
     * @return invalid-evidence result
     */
    public static NodeProviderResult invalidEvidence(String diagnostic) {
        return new NodeProviderResult(NodeProviderOutcome.INVALID_EVIDENCE, null, diagnostic);
    }

    /**
     * Returns the provider's exhaustive conclusion.
     *
     * @return exhaustive provider outcome
     */
    public NodeProviderOutcome outcome() {
        return outcome;
    }

    /**
     * Returns fresh mutable copies of retained content.
     *
     * @return mutable node copies in provider order
     */
    public List<Node> nodes() {
        List<Node> copies = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            copies.add(node.clone());
        }
        return copies;
    }

    /**
     * Returns the optional provider diagnostic.
     *
     * @return provider diagnostic, if supplied
     */
    public Optional<String> diagnostic() {
        return Optional.ofNullable(diagnostic);
    }
}
