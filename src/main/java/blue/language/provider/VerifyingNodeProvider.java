package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;

import java.util.List;

public class VerifyingNodeProvider implements NodeProvider {

    private final NodeProvider delegate;

    public VerifyingNodeProvider(NodeProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        NodeProviderResult result = fetchResultByBlueId(blueId);
        if (result.outcome() == NodeProviderOutcome.FOUND) {
            return result.nodes();
        }
        if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw new IllegalArgumentException(result.diagnostic().orElse(
                    "Provider returned invalid evidence for requested BlueId " + blueId + "."));
        }
        if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
            throw new IllegalStateException(result.diagnostic().orElse(
                    "Provider unavailable for requested BlueId " + blueId + "."));
        }
        return null;
    }

    @Override
    public NodeProviderResult fetchResultByBlueId(String blueId) {
        String requestedBlueId = BlueIds.requireBlueIdOrCyclicMember(blueId, "provider.fetchByBlueId");
        NodeProviderResult result = delegate.fetchResultByBlueId(blueId);
        if (result.outcome() != NodeProviderOutcome.FOUND) {
            return result;
        }
        List<Node> nodes = result.nodes();

        try {
            if (requestedBlueId.contains("#")) {
                requireCyclicVerification(requestedBlueId);
            } else {
                verifyPlainContent(requestedBlueId, nodes);
            }
            return NodeProviderResult.found(nodes);
        } catch (RuntimeException invalidEvidence) {
            return NodeProviderResult.invalidEvidence(invalidEvidence.getMessage());
        }
    }

    private void requireCyclicVerification(String requestedBlueId) {
        if (!(delegate instanceof CyclicAwareNodeProvider)) {
            throw new UnsupportedOperationException(
                    "Provider verification for cyclic member BlueIds requires a cyclic-set-aware verifier: "
                            + requestedBlueId);
        }
        if (!((CyclicAwareNodeProvider) delegate).hasVerifiedContentForBlueId(requestedBlueId)) {
            throw new UnsupportedOperationException(
                    "Provider verification for cyclic member BlueIds requires verified cyclic-set content: "
                            + requestedBlueId);
        }
    }

    private void verifyPlainContent(String requestedBlueId, List<Node> nodes) {
        String actualBlueId = nodes.size() == 1
                ? BlueIdCalculator.calculateBlueId(contentWithoutRootIdentity(nodes.get(0)))
                : BlueIdCalculator.calculateBlueId(contentWithoutRootIdentity(nodes));
        if (requestedBlueId.equals(actualBlueId)) {
            return;
        }

        throw new IllegalArgumentException("Provider returned content with BlueId " + actualBlueId
                + " for requested BlueId " + requestedBlueId + ".");
    }

    private Node contentWithoutRootIdentity(Node node) {
        Node canonical = node.clone();
        if (canonical.getBlueId() != null && !canonical.isReferenceOnly()) {
            canonical.blueId(null);
        }
        return canonical;
    }

    private List<Node> contentWithoutRootIdentity(List<Node> nodes) {
        List<Node> canonical = new java.util.ArrayList<>(nodes.size());
        for (Node node : nodes) {
            canonical.add(contentWithoutRootIdentity(node));
        }
        return canonical;
    }
}
