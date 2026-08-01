package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/** Immutable typed provider over verified shallow exact fragments. */
final class ExactFragmentProvider implements NodeProvider {

    private final SortedMap<String, Node> fragments;

    ExactFragmentProvider(Map<String, Node> fragments) {
        this.fragments = ExactFragmentSupport
                .immutableFragmentSnapshot(fragments);
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        NodeProviderResult result = fetchResultByBlueId(blueId);
        if (result.outcome() == NodeProviderOutcome.FOUND) {
            return result.nodes();
        }
        if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw new IllegalArgumentException(result.diagnostic().orElse(
                    "Stored exact fragment is invalid for " + blueId + "."));
        }
        if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
            throw new IllegalStateException(result.diagnostic().orElse(
                    "Exact fragment provider is unavailable for "
                            + blueId + "."));
        }
        return null;
    }

    @Override
    public NodeProviderResult fetchResultByBlueId(String blueId) {
        Node fragment = fragments.get(blueId);
        if (fragment == null) {
            return NodeProviderResult.notFound();
        }
        final String actualBlueId;
        try {
            actualBlueId = BlueIdCalculator.calculateBlueId(fragment);
        } catch (RuntimeException invalidEvidence) {
            return NodeProviderResult.invalidEvidence(
                    "Stored exact fragment is invalid for requested BlueId "
                            + blueId + ": "
                            + invalidEvidence.getMessage());
        }
        if (!blueId.equals(actualBlueId)) {
            return NodeProviderResult.invalidEvidence(
                    "Stored exact fragment calculated BlueId "
                            + actualBlueId + " instead of requested BlueId "
                            + blueId + ".");
        }
        return NodeProviderResult.found(
                Collections.singletonList(fragment));
    }
}
