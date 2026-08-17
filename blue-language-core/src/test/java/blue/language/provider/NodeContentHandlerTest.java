package blue.language.provider;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeContentHandlerTest {

    private static final String COLLIDING_BLUE_ID =
            "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC";

    @Test
    void shouldCanonicalizeCollidingPreliminaryIdsAcrossParserPermutations() {
        // given
        CircularSetIdentityCalculator calculator = collidingCalculator();
        List<Node> aThenB = Arrays.asList(
                member("A", 1),
                member("B", 0));
        List<Node> bThenA = Arrays.asList(
                member("B", 1),
                member("A", 0));

        // when
        NodeContentHandler.ParsedContent first =
                NodeContentHandler.parseAndCalculateBlueId(
                        aThenB,
                        node -> node,
                        calculator);
        NodeContentHandler.ParsedContent permuted =
                NodeContentHandler.parseAndCalculateBlueId(
                        bThenA,
                        node -> node,
                        calculator);

        // then
        assertEquals(COLLIDING_BLUE_ID, first.blueId);
        assertEquals(first.blueId, permuted.blueId);
        assertEquals(first.content, permuted.content);
    }

    @Test
    void shouldRejectExactPreliminaryPairTieThroughParserPath() {
        // given
        CircularSetIdentityCalculator calculator = collidingCalculator();
        List<Node> indistinguishable = Arrays.asList(
                member("same", 1),
                member("same", 0));

        // when / then
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NodeContentHandler.parseAndCalculateBlueId(
                        indistinguishable,
                        node -> node,
                        calculator));
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
