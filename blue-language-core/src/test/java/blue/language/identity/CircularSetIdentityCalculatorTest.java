package blue.language.identity;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void shouldRejectOnlyAnExactPreliminaryDigestAndInputTie() {
        // given
        CircularSetIdentityCalculator calculator = collidingCalculator();
        List<Node> indistinguishable = Arrays.asList(
                member("same", 1),
                member("same", 0));

        // when / then
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.circularBlueIds(indistinguishable));
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
}
