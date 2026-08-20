package blue.language.provider;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Provider parity at the collapsed-proof/materialized-content boundary. */
final class CyclicProofRepresentationParityTest {

    @Test
    void shouldAcceptCollapsedAndMaterializedMembersForOneExactProof() {
        Fixture fixture = new Fixture();

        NodeProviderResult materialized = fixture.verify(
                fixture.materializedMember(0));
        NodeProviderResult collapsed = fixture.verify(
                fixture.resolvedProofMember(0));

        assertEquals(NodeProviderOutcome.FOUND, materialized.outcome());
        assertEquals(NodeProviderOutcome.FOUND, collapsed.outcome());
    }

    @Test
    void shouldRejectTamperedMaterializedOffCycleFragment() {
        Fixture fixture = new Fixture();
        Node tampered = fixture.materializedMember(0);
        tampered.getNode("/auxiliary/payload").value("tampered");

        NodeProviderResult result = fixture.verify(tampered);

        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                result.outcome());
    }

    @Test
    void shouldRejectTamperedCollapsedChildReference() {
        Fixture fixture = new Fixture();
        Node tampered = fixture.resolvedProofMember(0);
        tampered.getProperties().put(
                "auxiliary",
                new Node().blueId(DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("wrong"))));

        NodeProviderResult result = fixture.verify(tampered);

        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                result.outcome());
    }

    private static final class Fixture {
        private final CyclicSetFinalization finalization;
        private final List<Node> collapsedProofMembers;
        private final CyclicSetProof proof;
        private final List<String> memberBlueIds;

        private Fixture() {
            List<Node> fullBodies = new ArrayList<Node>();
            fullBodies.add(member("a", "A", 1));
            fullBodies.add(member("b", "B", 0));
            finalization = new CircularSetIdentityCalculator()
                    .finalizeCyclicSet(fullBodies);
            collapsedProofMembers = collapseCanonicalMembers(
                    finalization.canonicalMemberBodies());
            proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                    collapsedProofMembers);
            memberBlueIds = CircularSetIdentityCalculator
                    .calculateCircularSetBlueIds(collapsedProofMembers);
            assertEquals(finalization.masterBlueId() + "#0",
                    memberBlueIds.get(0));
            assertEquals(finalization.masterBlueId() + "#1",
                    memberBlueIds.get(1));
        }

        private NodeProviderResult verify(Node returned) {
            String requested = memberBlueIds.get(0);
            NodeProvider delegate = new ExactCyclicProvider(
                    requested, returned, proof);
            return new VerifyingNodeProvider(delegate)
                    .fetchResultByBlueId(requested);
        }

        private Node materializedMember(int index) {
            Node result = finalization.canonicalMemberBodies()
                    .get(index).clone();
            resolveThisReferences(result, memberBlueIds);
            return result;
        }

        private Node resolvedProofMember(int index) {
            return proof.resolvedMember(index, memberBlueIds);
        }

        private static Node member(
                String documentId,
                String name,
                int nextIndex) {
            return new Node()
                    .name(name)
                    .properties(
                            "documentId", new Node().value(documentId),
                            "next", new Node().blueId(
                                    "this#" + nextIndex),
                            "auxiliary", new Node().properties(
                                    "payload", new Node().value("same")));
        }

        private static List<Node> collapseCanonicalMembers(
                List<Node> canonical) {
            ArrayList<Node> result = new ArrayList<Node>();
            for (Node member : canonical) {
                LinkedHashMap<String, Node> properties =
                        new LinkedHashMap<String, Node>();
                for (Map.Entry<String, Node> entry
                        : member.getProperties().entrySet()) {
                    Node child = entry.getValue();
                    properties.put(entry.getKey(),
                            containsThisReference(child)
                                    ? child.clone()
                                    : new Node().blueId(
                                            DirectBlueIdCalculator
                                                    .calculateBlueId(child)));
                }
                result.add(new Node()
                        .name(member.getName())
                        .properties(properties));
            }
            return result;
        }

        private static boolean containsThisReference(Node node) {
            if (node.getBlueId() != null
                    && node.getBlueId().startsWith("this#")) {
                return true;
            }
            if (node.getItems() != null) {
                for (Node item : node.getItems()) {
                    if (containsThisReference(item)) {
                        return true;
                    }
                }
            }
            if (node.getProperties() != null) {
                for (Node child : node.getProperties().values()) {
                    if (containsThisReference(child)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static void resolveThisReferences(
                Node node,
                List<String> blueIds) {
            if (node.getBlueId() != null
                    && node.getBlueId().startsWith("this#")) {
                int index = Integer.parseInt(
                        node.getBlueId().substring("this#".length()));
                node.blueId(blueIds.get(index));
            }
            if (node.getItems() != null) {
                for (Node item : node.getItems()) {
                    resolveThisReferences(item, blueIds);
                }
            }
            if (node.getProperties() != null) {
                for (Node child : node.getProperties().values()) {
                    resolveThisReferences(child, blueIds);
                }
            }
        }
    }

    private static final class ExactCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String requested;
        private final Node returned;
        private final CyclicSetProof proof;

        private ExactCyclicProvider(
                String requested,
                Node returned,
                CyclicSetProof proof) {
            this.requested = requested;
            this.returned = returned;
            this.proof = proof;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return requested.equals(blueId)
                    ? Collections.singletonList(returned)
                    : null;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return requested.equals(blueId)
                    ? CyclicSetProofResult.found(proof)
                    : CyclicSetProofResult.notFound();
        }
    }
}
