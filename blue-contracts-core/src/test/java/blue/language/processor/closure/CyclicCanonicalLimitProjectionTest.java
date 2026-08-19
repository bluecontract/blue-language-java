package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Schema;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact proof-selection checks independent of the portable byte projection. */
final class CyclicCanonicalLimitProjectionTest {
    private static final String MYOS_TIMELINE_CHANNEL =
            "8aohWT7jcoaC1j2siQzBxoKM8HhQ4HF13BkZDNnq5UHf";
    private static final String MYOS_TIMELINE =
            "5VAQp5thYLkzp3FbvYGmVvmdLqqu6pV5vhNgD14XJwpX";
    private static final String MYOS_PRINCIPAL =
            "CtsH9hhbLAYcQYv5jYnNhaviKxkEdrwAg8b4KoxEU5Lw";
    private static final String SEQUENTIAL_WORKFLOW_OPERATION =
            "6rnGvP1BUsBpbtvuvJ1vy6hS7psoUJVBEXXsFeRma82V";
    private static final String COMPUTE_STEP =
            "4qGDz5yJxXc9dr8bsBE1B2Tg4AWuR4qHPU9H6T29m3KZ";

    @Test
    void keepsCompactC34ShapedProofAcrossInputPermutations() {
        CyclicSetFinalization forward = finalizePair(false);
        CyclicCanonicalLimitProjection.Projection forwardProjection =
                new CyclicCanonicalLimitProjection().project(forward);

        assertExactMemberSet(forward, forwardProjection.proofMembers());
        assertNotEquals(
                wire(forward.canonicalMemberBodies()),
                wire(forwardProjection.proofMembers()));
        assertTrue(forwardProjection.canonicalBytes() > 0L);
        assertEquals(
                forwardProjection.canonicalBytes(),
                new CyclicCanonicalLimitProjection().canonicalBytesForProof(
                        forward.canonicalMemberBodies()));

        CyclicSetFinalization reversed = finalizePair(true);
        CyclicCanonicalLimitProjection.Projection reversedProjection =
                new CyclicCanonicalLimitProjection().project(reversed);

        assertEquals(
                new HashSet<String>(forward.memberBlueIdsInInputOrder()),
                new HashSet<String>(reversed.memberBlueIdsInInputOrder()));
        assertExactMemberSet(reversed, reversedProjection.proofMembers());
        assertEquals(
                forwardProjection.canonicalBytes(),
                reversedProjection.canonicalBytes());
    }

    @Test
    void resolvedCounterLikeMembersFallBackToFullProofWhileBytesStayCollapsed() {
        CyclicSetFinalization finalized = finalizeResolvedCounterPair();
        CyclicCanonicalLimitProjection projection =
                new CyclicCanonicalLimitProjection();
        CyclicCanonicalLimitProjection.Projection selected =
                projection.project(finalized);

        assertEquals(
                wire(finalized.canonicalMemberBodies()),
                wire(selected.proofMembers()));
        assertExactMemberSet(finalized, selected.proofMembers());
        assertEquals(
                selected.canonicalBytes(),
                projection.canonicalBytesForProof(
                        finalized.canonicalMemberBodies()));
        assertTrue(selected.canonicalBytes()
                < finalized.canonicalIdentityInputByteCount());
    }

    @Test
    void rejectsTamperedMasterAndDuplicateOrIncompleteMemberSets() {
        CyclicSetFinalization finalized = finalizePair(false);
        List<Node> proof = new CyclicCanonicalLimitProjection()
                .project(finalized).proofMembers();
        proof.get(0).name("tampered");

        CyclicSetFinalization tampered =
                new CircularSetIdentityCalculator().finalizeCyclicSet(proof);

        assertNotEquals(
                new HashSet<String>(finalized.memberBlueIdsInInputOrder()),
                new HashSet<String>(tampered.memberBlueIdsInInputOrder()));
        assertFalse(CyclicCanonicalLimitProjection.sameMemberIdentitySet(
                finalized, tampered));

        List<String> exact = finalized.memberBlueIdsInInputOrder();
        assertFalse(CyclicCanonicalLimitProjection.sameMemberIdentitySet(
                finalized.masterBlueId(),
                exact,
                finalized.masterBlueId(),
                Arrays.asList(exact.get(0), exact.get(0))));
        assertFalse(CyclicCanonicalLimitProjection.sameMemberIdentitySet(
                finalized.masterBlueId(),
                exact,
                finalized.masterBlueId(),
                Collections.singletonList(exact.get(0))));
        assertFalse(CyclicCanonicalLimitProjection.sameMemberIdentitySet(
                finalized.masterBlueId(),
                exact,
                hash('f'),
                exact));
    }

    private static CyclicSetFinalization finalizePair(boolean reversed) {
        Node a = member("C34 A", reversed ? 0 : 1);
        Node b = member("C34 B", reversed ? 1 : 0);
        return new CircularSetIdentityCalculator().finalizeCyclicSet(
                reversed ? Arrays.asList(b, a) : Arrays.asList(a, b));
    }

    private static Node member(String name, int targetIndex) {
        return new Node()
                .name(name)
                .properties("peer", new Node().blueId(
                        "this#" + targetIndex))
                .properties("payload", new Node()
                        .properties("mode", new Node().value("C34"))
                        .properties("enabled", new Node().value(true)))
                .contracts(new Node().properties(
                        "embedded",
                        new Node()
                                .type(new Node().blueId(
                                        RuntimeBlueIds.PROCESS_EMBEDDED))
                                .properties("paths", new Node().items(
                                        new Node().value("/peer")))));
    }

    private static CyclicSetFinalization finalizeResolvedCounterPair() {
        Node a = resolvedCounter("a", "b", 1);
        Node b = resolvedCounter("b", "a", 0);
        CyclicSetFinalization language =
                new CircularSetIdentityCalculator().finalizeCyclicSet(
                Arrays.asList(a, b));
        List<String> canonicalBlueIds = language.membersInCanonicalOrder()
                .stream()
                .map(member -> member.finalBlueId())
                .collect(Collectors.toList());
        Node bodyA = language.membersInInputOrder().get(0)
                .canonicalMemberBody();
        Node bodyB = language.membersInInputOrder().get(1)
                .canonicalMemberBody();
        materializeThis(bodyA, canonicalBlueIds);
        materializeThis(bodyB, canonicalBlueIds);
        DocumentId aId = new DocumentId("a");
        DocumentId bId = new DocumentId("b");
        List<ManagedOccurrenceBinding> bindings = Arrays.asList(
                ManagedOccurrenceBinding.derived(
                        hash('a'),
                        aId,
                        ScopeAddress.embedded("/b", 1L),
                        bId,
                        language.membersInInputOrder().get(1).finalBlueId(),
                        true,
                        null),
                ManagedOccurrenceBinding.derived(
                        hash('a'),
                        bId,
                        ScopeAddress.embedded("/a", 1L),
                        aId,
                        language.membersInInputOrder().get(0).finalBlueId(),
                        true,
                        null));
        Collections.sort(bindings);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                Arrays.asList(aId, bId), bindings);
        Map<DocumentId, Long> generations = new LinkedHashMap<DocumentId, Long>();
        generations.put(aId, Long.valueOf(1L));
        generations.put(bId, Long.valueOf(1L));
        Map<DocumentId, Node> bodies = new LinkedHashMap<DocumentId, Node>();
        bodies.put(aId, bodyA);
        bodies.put(bId, bodyB);
        return new ComponentFinalizationKernel().finalizeComponents(
                new ComponentFinalizationInput(
                        graph, generations, bodies, bindings))
                .components().get(0).cyclicFinalization();
    }

    private static Node resolvedCounter(
            String documentId,
            String targetProperty,
            int targetIndex) {
        Node timeline = new Node()
                .type(new Node().blueId(
                        MYOS_TIMELINE))
                .properties("timelineId", new Node().value("a/alice"));
        Node actor = new Node()
                .type(new Node().blueId(
                        MYOS_PRINCIPAL))
                .properties("accountId", new Node()
                        .description("MyOS account ID of the principal.")
                        .value("alice")
                        .schema(new Schema().required(true)));
        Node channel = new Node()
                .type(new Node().blueId(
                        MYOS_TIMELINE_CHANNEL))
                .properties("timeline", timeline)
                .properties("actor", actor);
        Node operation = new Node()
                .type(new Node().blueId(
                        SEQUENTIAL_WORKFLOW_OPERATION))
                .properties("channel", new Node().value("aliceChannel"))
                .properties("request", new Node().properties(
                        "amount", new Node().type(new Node().blueId(
                                RuntimeBlueIds.RUNTIME_COUNTER_ENTRY))))
                .properties("steps", new Node().items(
                        new Node().type(new Node().blueId(
                                COMPUTE_STEP))));
        return new Node()
                .properties("documentId", new Node().value(documentId))
                .name("Counter")
                .properties("counter", new Node().value(BigInteger.ZERO))
                .properties(targetProperty, new Node().blueId(
                        "this#" + targetIndex))
                .contracts(new Node()
                        .properties("aliceChannel", channel)
                        .properties("increment", operation)
                        .properties("embedded", new Node()
                                .type(new Node().blueId(
                                        RuntimeBlueIds.PROCESS_EMBEDDED))
                                .properties("paths", new Node().items(
                                        new Node().value(
                                                "/" + targetProperty)))));
    }

    private static List<Object> wire(List<Node> nodes) {
        return nodes.stream().map(NodeWireForm::get)
                .collect(Collectors.<Object>toList());
    }

    private static void materializeThis(
            Node node,
            List<String> memberBlueIds) {
        if (node == null) {
            return;
        }
        String blueId = node.getBlueId();
        if (blueId != null && blueId.startsWith("this#")) {
            node.blueId(memberBlueIds.get(
                    Integer.parseInt(blueId.substring(5))));
        }
        materializeThis(node.getType(), memberBlueIds);
        materializeThis(node.getItemType(), memberBlueIds);
        materializeThis(node.getKeyType(), memberBlueIds);
        materializeThis(node.getValueType(), memberBlueIds);
        materializeThis(node.getBlue(), memberBlueIds);
        materializeThis(node.getContracts(), memberBlueIds);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                materializeThis(item, memberBlueIds);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                materializeThis(child, memberBlueIds);
            }
        }
    }

    private static String hash(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void assertExactMemberSet(
            CyclicSetFinalization expected,
            List<Node> proofMembers) {
        CyclicSetFinalization actual = new CircularSetIdentityCalculator()
                .finalizeCyclicSet(proofMembers);
        assertEquals(expected.masterBlueId(), actual.masterBlueId());
        assertEquals(
                new HashSet<String>(expected.memberBlueIdsInInputOrder()),
                new HashSet<String>(actual.memberBlueIdsInInputOrder()));
    }
}
