package blue.language.processor.closure;

import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.VerifyingNodeProvider;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Independent Language verification for a retained cyclic successor body. */
final class ManagedRevisionCyclicEvidenceVerifier {

    private ManagedRevisionCyclicEvidenceVerifier() {
    }

    /** Verifies the claimed member identity, complete proof, and exact body. */
    static void verify(
            String expectedBlueId,
            Node afterDocument,
            CyclicSetProof afterCyclicProof) {
        String expected = Objects.requireNonNull(
                expectedBlueId, "expectedBlueId");
        Node body = Objects.requireNonNull(
                afterDocument, "afterDocument").clone();
        CyclicSetProof proof = copyProof(Objects.requireNonNull(
                afterCyclicProof, "afterCyclicProof"));
        NodeProviderResult verified = new VerifyingNodeProvider(
                new SingleMemberEvidence(expected, body, proof))
                .fetchResultByBlueId(expected);
        if (verified.outcome() != NodeProviderOutcome.FOUND) {
            throw new IllegalArgumentException(
                    verified.diagnostic().orElse(
                            "Cyclic managed-revision successor evidence is invalid"));
        }
    }

    private static CyclicSetProof copyProof(CyclicSetProof proof) {
        return CyclicSetProof.fromDeclaredPlaceholderSet(
                proof.declaredPlaceholderSet());
    }

    /** One invocation-owned proof-bearing provider, verified independently. */
    private static final class SingleMemberEvidence
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String expectedBlueId;
        private final Node body;
        private final CyclicSetProof proof;

        private SingleMemberEvidence(
                String expectedBlueId,
                Node body,
                CyclicSetProof proof) {
            this.expectedBlueId = expectedBlueId;
            this.body = body.clone();
            this.proof = copyProof(proof);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return expectedBlueId.equals(blueId)
                    ? Collections.singletonList(body.clone())
                    : Collections.<Node>emptyList();
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return expectedBlueId.equals(blueId)
                    ? CyclicSetProofResult.found(copyProof(proof))
                    : CyclicSetProofResult.notFound();
        }
    }
}
