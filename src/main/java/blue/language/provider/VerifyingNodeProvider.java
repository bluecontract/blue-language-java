package blue.language.provider;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.CircularBlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

/**
 * Provider boundary that independently verifies returned content against the
 * requested plain or cyclic-member BlueId.
 *
 * <p>Plain content is rehashed. Cyclic members require a
 * {@link CyclicAwareNodeProvider} and a complete {@link CyclicSetProof};
 * typed proof-acquisition failures are preserved, and verified proof
 * calculations are retained in a small bounded cache.</p>
 */
public class VerifyingNodeProvider implements NodeProvider {

    private static final int CYCLIC_PROOF_CACHE_LIMIT = 128;

    private final NodeProvider delegate;
    private final Map<String, VerifiedCyclicSet> verifiedCyclicSets =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, VerifiedCyclicSet>(
                            16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, VerifiedCyclicSet> eldest) {
                            return size() > CYCLIC_PROOF_CACHE_LIMIT;
                        }
                    });

    /**
     * Wraps a delegate whose evidence will be verified on every lookup.
     *
     * @param delegate provider whose returned evidence must be verified
     */
    public VerifyingNodeProvider(NodeProvider delegate) {
        this.delegate = java.util.Objects.requireNonNull(
                delegate, "delegate");
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
            throw new ProviderUnavailableException(
                    result.diagnostic().orElse(
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
            if (BlueIds.hasCyclicMemberSeparator(requestedBlueId)) {
                CyclicSetProofResult proofResult =
                        acquireCyclicSetProof(requestedBlueId);
                if (proofResult.outcome()
                        == NodeProviderOutcome.UNAVAILABLE) {
                    return NodeProviderResult.unavailable(
                            proofResult.diagnostic().orElse(
                                    "Cyclic-set proof is temporarily unavailable for "
                                            + requestedBlueId + "."));
                }
                if (proofResult.outcome()
                        == NodeProviderOutcome.INVALID_EVIDENCE) {
                    return NodeProviderResult.invalidEvidence(
                            proofResult.diagnostic().orElse(
                                    "Provider supplied invalid cyclic-set evidence for "
                                            + requestedBlueId + "."));
                }
                if (proofResult.outcome()
                        == NodeProviderOutcome.NOT_FOUND) {
                    return NodeProviderResult.invalidEvidence(
                            "Provider returned cyclic member content without "
                                    + "a complete cyclic-set proof for "
                                    + requestedBlueId + ".");
                }
                verifyCyclicContent(
                        requestedBlueId,
                        nodes,
                        proofResult.proof().orElseThrow(
                                () -> new IllegalArgumentException(
                                        "Found cyclic-set proof result omitted proof for "
                                                + requestedBlueId + ".")));
            } else {
                verifyPlainContent(requestedBlueId, nodes);
            }
            return NodeProviderResult.found(nodes);
        } catch (ProviderUnavailableException unavailable) {
            return NodeProviderResult.unavailable(
                    unavailable.getMessage());
        } catch (RuntimeException invalidEvidence) {
            return NodeProviderResult.invalidEvidence(invalidEvidence.getMessage());
        }
    }

    private CyclicSetProofResult acquireCyclicSetProof(
            String requestedBlueId) {
        if (!(delegate instanceof CyclicAwareNodeProvider)) {
            throw new UnsupportedOperationException(
                    "Provider verification for cyclic member BlueIds requires a cyclic-set-aware verifier: "
                            + requestedBlueId);
        }
        CyclicSetProofResult proofResult =
                ((CyclicAwareNodeProvider) delegate).cyclicSetProofFor(
                        requestedBlueId);
        if (proofResult == null) {
            throw new IllegalArgumentException(
                    "Cyclic-set-aware provider returned no typed proof result for "
                            + requestedBlueId + ".");
        }
        return proofResult;
    }

    private void verifyCyclicContent(
            String requestedBlueId,
            List<Node> returnedNodes,
            CyclicSetProof proof) {
        VerifiedCyclicSet verifiedSet =
                verifiedCyclicSet(requestedBlueId, proof);
        Integer proofMemberIndex =
                verifiedSet.memberIndexByBlueId.get(requestedBlueId);
        if (proofMemberIndex == null) {
            throw new IllegalArgumentException(
                    "Cyclic-set proof does not calculate requested BlueId "
                            + requestedBlueId + ".");
        }
        if (returnedNodes.size() != 1) {
            throw new IllegalArgumentException(
                    "Provider returned " + returnedNodes.size()
                            + " members for requested cyclic BlueId "
                            + requestedBlueId + ".");
        }

        Node expected = proof.resolvedMember(
                proofMemberIndex,
                verifiedSet.calculatedMemberBlueIds);
        Node actual = returnedNodes.get(0).clone();
        removeMatchingRootIdentity(
                expected, requestedBlueId, "Cyclic-set proof member");
        removeMatchingRootIdentity(
                actual, requestedBlueId, "Provider-returned cyclic member");
        if (!JSON_MAPPER.valueToTree(expected).equals(
                JSON_MAPPER.valueToTree(actual))) {
            throw new IllegalArgumentException(
                    "Provider returned cyclic member content that does not match "
                            + "the independently verified complete set for "
                            + requestedBlueId + ".");
        }
    }

    private VerifiedCyclicSet verifiedCyclicSet(
            String requestedBlueId,
            CyclicSetProof proof) {
        String masterBlueId =
                BlueIds.cyclicSetMasterBlueId(requestedBlueId);
        synchronized (verifiedCyclicSets) {
            VerifiedCyclicSet retained =
                    verifiedCyclicSets.get(masterBlueId);
            if (retained != null && retained.proof == proof) {
                return retained;
            }
            List<String> calculatedMemberBlueIds =
                    Collections.unmodifiableList(new ArrayList<>(
                            CircularBlueIdCalculator
                                    .calculateCircularSetBlueIds(
                                            proof.declaredPlaceholderSet())));
            Map<String, Integer> memberIndexByBlueId =
                    new LinkedHashMap<>();
            for (int index = 0;
                 index < calculatedMemberBlueIds.size();
                 index++) {
                memberIndexByBlueId.put(
                        calculatedMemberBlueIds.get(index), index);
            }
            VerifiedCyclicSet verified = new VerifiedCyclicSet(
                    proof,
                    calculatedMemberBlueIds,
                    Collections.unmodifiableMap(memberIndexByBlueId));
            verifiedCyclicSets.put(masterBlueId, verified);
            return verified;
        }
    }

    private void removeMatchingRootIdentity(
            Node node,
            String requestedBlueId,
            String source) {
        String rootBlueId = node.getBlueId();
        if (rootBlueId == null) {
            return;
        }
        if (!requestedBlueId.equals(rootBlueId)) {
            throw new IllegalArgumentException(
                    source + " has root BlueId " + rootBlueId
                            + " instead of requested BlueId "
                            + requestedBlueId + ".");
        }
        node.blueId(null);
    }

    private void verifyPlainContent(String requestedBlueId, List<Node> nodes) {
        String actualBlueId = nodes.size() == 1
                ? BlueIdCalculator.calculateBlueId(
                contentWithoutRootIdentity(
                        nodes.get(0), requestedBlueId))
                : BlueIdCalculator.calculateBlueId(
                contentWithoutRootIdentity(
                        nodes, requestedBlueId));
        if (requestedBlueId.equals(actualBlueId)) {
            return;
        }

        throw new IllegalArgumentException("Provider returned content with BlueId " + actualBlueId
                + " for requested BlueId " + requestedBlueId + ".");
    }

    private Node contentWithoutRootIdentity(
            Node node,
            String requestedBlueId) {
        Node canonical = node.clone();
        if (canonical.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Provider returned a pure reference instead of direct "
                            + "content evidence for "
                            + requestedBlueId + ".");
        }
        if (canonical.getBlueId() != null) {
            if (!requestedBlueId.equals(
                    canonical.getBlueId())) {
                throw new IllegalArgumentException(
                        "Provider-returned content has root BlueId "
                                + canonical.getBlueId()
                                + " instead of requested BlueId "
                                + requestedBlueId + ".");
            }
            canonical.blueId(null);
        }
        return canonical;
    }

    private List<Node> contentWithoutRootIdentity(
            List<Node> nodes,
            String requestedBlueId) {
        List<Node> canonical = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            Node member = node.clone();
            if (!member.isReferenceOnly()
                    && member.getBlueId() != null) {
                throw new IllegalArgumentException(
                        "Provider-returned multi-node content carries "
                                + "unverified root BlueId "
                                + member.getBlueId() + ".");
            }
            canonical.add(member);
        }
        return canonical;
    }

    private static final class VerifiedCyclicSet {
        private final CyclicSetProof proof;
        private final List<String> calculatedMemberBlueIds;
        private final Map<String, Integer> memberIndexByBlueId;

        private VerifiedCyclicSet(
                CyclicSetProof proof,
                List<String> calculatedMemberBlueIds,
                Map<String, Integer> memberIndexByBlueId) {
            this.proof = proof;
            this.calculatedMemberBlueIds = calculatedMemberBlueIds;
            this.memberIndexByBlueId = memberIndexByBlueId;
        }
    }
}
