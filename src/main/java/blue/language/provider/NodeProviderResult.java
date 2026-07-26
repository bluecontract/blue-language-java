package blue.language.provider;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral provider conclusion for one requested BlueId.
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

    public static NodeProviderResult found(List<Node> nodes) {
        return new NodeProviderResult(NodeProviderOutcome.FOUND, nodes, null);
    }

    public static NodeProviderResult notFound() {
        return new NodeProviderResult(NodeProviderOutcome.NOT_FOUND, null, null);
    }

    public static NodeProviderResult unavailable(String diagnostic) {
        return new NodeProviderResult(NodeProviderOutcome.UNAVAILABLE, null, diagnostic);
    }

    public static NodeProviderResult invalidEvidence(String diagnostic) {
        return new NodeProviderResult(NodeProviderOutcome.INVALID_EVIDENCE, null, diagnostic);
    }

    public NodeProviderOutcome outcome() {
        return outcome;
    }

    public List<Node> nodes() {
        List<Node> copies = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            copies.add(node.clone());
        }
        return copies;
    }

    public Optional<String> diagnostic() {
        return Optional.ofNullable(diagnostic);
    }
}
