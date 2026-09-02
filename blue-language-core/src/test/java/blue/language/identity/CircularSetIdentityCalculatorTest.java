package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CircularSetIdentityCalculatorTest {

    private static final String COLLIDING_BLUE_ID =
            "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC";

    @Test
    void shouldBreakPreliminaryDigestCollisionsByCanonicalInputBytes() {
        // given
        CircularSetIdentityCalculator calculator = collidingCalculator();
        List<Node> aThenB = Arrays.asList(
                member("A", 1),
                member("B", 0));
        List<Node> bThenA = Arrays.asList(
                member("B", 1),
                member("A", 0));

        // when
        List<String> first = calculator.circularBlueIds(aThenB);
        List<String> permuted = calculator.circularBlueIds(bThenA);

        // then
        assertEquals(COLLIDING_BLUE_ID + "#0", first.get(0));
        assertEquals(COLLIDING_BLUE_ID + "#1", first.get(1));
        assertEquals(COLLIDING_BLUE_ID + "#1", permuted.get(0));
        assertEquals(COLLIDING_BLUE_ID + "#0", permuted.get(1));
    }

    @Test
    void shouldExposeTheSameCanonicalFinalizationAcrossPermutations() {
        // given
        CircularSetIdentityCalculator calculator =
                new CircularSetIdentityCalculator();
        List<Node> aThenB = Arrays.asList(
                member("A", 1),
                member("B", 0));
        List<Node> bThenA = Arrays.asList(
                member("B", 1),
                member("A", 0));

        // when
        CyclicSetFinalization first =
                calculator.finalizeCyclicSet(aThenB);
        CyclicSetFinalization permuted =
                calculator.finalizeCyclicSet(bThenA);

        // then
        assertEquals(first.masterBlueId(), permuted.masterBlueId());
        assertEquals(
                wireForms(first.canonicalMemberBodies()),
                wireForms(permuted.canonicalMemberBodies()));
        assertArrayEquals(
                first.canonicalIdentityInputBytes(),
                permuted.canonicalIdentityInputBytes());
        assertEquals(
                memberBlueId(first, "A"),
                memberBlueId(permuted, "A"));
        assertEquals(
                memberBlueId(first, "B"),
                memberBlueId(permuted, "B"));
        assertEquals(
                calculator.circularBlueIds(aThenB),
                first.memberBlueIdsInInputOrder());
        assertEquals(
                CircularSetIdentityCalculator
                        .calculateCircularSetBlueIds(aThenB),
                CircularSetIdentityCalculator
                        .calculateCircularSetFinalization(aThenB)
                        .memberBlueIdsInInputOrder());

        for (CyclicMemberFinalization member
                : first.membersInCanonicalOrder()) {
            assertEquals(
                    first.masterBlueId() + "#"
                            + member.canonicalIndex(),
                    member.finalBlueId());
            assertEquals(
                    member,
                    first.membersInInputOrder().get(
                            member.inputIndex()));
        }
    }

    @Test
    void shouldMatchPackagedSelfCycleLanguageOracle() {
        // given
        Node document = new Node()
                .properties(
                        "documentId", scalar("self"),
                        "memberIdentity", scalar("self"),
                        "self", new Node().blueId("this#0"),
                        "contracts", new Node().properties(
                                "embedded", new Node().properties(
                                        "type", new Node().blueId(
                                                "9ftzzP6ySLmbJ43bjwTbrm6Ff79FKqsVy5xdA1zWxoQ3"),
                                        "paths", new Node().items(
                                                scalar("/self")))));

        // when
        CyclicSetFinalization finalization =
                new CircularSetIdentityCalculator()
                        .finalizeCyclicSet(
                                Arrays.asList(document));

        // then
        assertEquals(
                "AtYu1mzovKqo8KuN173RRymAHQrarZmNVLGbKEmKpLFK",
                finalization.membersInInputOrder().get(0)
                        .preliminaryBlueId());
        assertEquals(
                "2Rh775x5AkBuDgFmaTuWKfuZjFiWBpxLhh1ARRmUpVQ3",
                finalization.masterBlueId());
        assertEquals(
                finalization.masterBlueId() + "#0",
                finalization.memberBlueIdsInInputOrder().get(0));
        assertEquals(
                "this#0",
                finalization.canonicalMemberBodies().get(0)
                        .getNode("/self").getBlueId());
        assertEquals(
                finalization.canonicalIdentityInputBytes().length,
                finalization.canonicalIdentityInputByteCount());
    }

    @Test
    void shouldDefensivelyRetainAllFinalizationEvidence() {
        // given
        List<Node> input = new ArrayList<>(Arrays.asList(
                member("A", 1),
                member("B", 0)));
        CyclicSetFinalization finalization =
                new CircularSetIdentityCalculator()
                        .finalizeCyclicSet(input);
        List<Object> expectedBodies =
                wireForms(finalization.canonicalMemberBodies());
        byte[] expectedCanonicalBytes =
                finalization.canonicalIdentityInputBytes();
        CyclicMemberFinalization firstMember =
                finalization.membersInCanonicalOrder().get(0);
        byte[] expectedPreliminaryBytes =
                firstMember.preliminaryCanonicalInputBytes();

        // when
        input.get(0).name("changed after finalization");
        input.clear();
        finalization.canonicalMemberBodies().get(0)
                .name("changed returned copy");
        finalization.canonicalIdentityInputBytes()[0] ^= 1;
        firstMember.preliminaryCanonicalInputBytes()[0] ^= 1;
        firstMember.canonicalMemberBody().name("changed member copy");

        // then
        assertEquals(
                expectedBodies,
                wireForms(finalization.canonicalMemberBodies()));
        assertArrayEquals(
                expectedCanonicalBytes,
                finalization.canonicalIdentityInputBytes());
        assertArrayEquals(
                expectedPreliminaryBytes,
                firstMember.preliminaryCanonicalInputBytes());
        assertNotEquals(
                "changed member copy",
                firstMember.canonicalMemberBody().getName());
        assertThrows(
                UnsupportedOperationException.class,
                () -> finalization.membersInInputOrder().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> finalization.membersInCanonicalOrder().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> finalization.canonicalMemberBodies().clear());
        assertEquals(
                finalization,
                new CircularSetIdentityCalculator()
                        .finalizeCyclicSet(Arrays.asList(
                                member("A", 1),
                                member("B", 0))));
        assertEquals(
                finalization.hashCode(),
                new CircularSetIdentityCalculator()
                        .finalizeCyclicSet(Arrays.asList(
                                member("A", 1),
                                member("B", 0)))
                        .hashCode());
    }

    @Test
    void shouldRejectOnlyAnExactPreliminaryDigestAndInputTie() {
        // given
        CircularSetIdentityCalculator calculator = collidingCalculator();
        List<Node> indistinguishable = Arrays.asList(
                member("same", 1),
                member("same", 0));

        // when / then
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.finalizeCyclicSet(indistinguishable));
        assertEquals(
                "Indistinguishable preliminary cyclic BlueId input for members 0 and 1.",
                failure.getMessage());
    }

    private static CircularSetIdentityCalculator collidingCalculator() {
        return new CircularSetIdentityCalculator(
                new DirectBlueIdCalculator(value -> COLLIDING_BLUE_ID));
    }

    private static Node member(String name, int targetIndex) {
        return new Node()
                .name(name)
                .properties(
                        "next",
                        new Node().blueId("this#" + targetIndex));
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }

    private static List<Object> wireForms(List<Node> nodes) {
        List<Object> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(NodeWireForm.get(node));
        }
        return result;
    }

    private static String memberBlueId(
            CyclicSetFinalization finalization,
            String name) {
        for (CyclicMemberFinalization member
                : finalization.membersInCanonicalOrder()) {
            if (name.equals(member.canonicalMemberBody().getName())) {
                return member.finalBlueId();
            }
        }
        throw new AssertionError("Missing member " + name);
    }
}
