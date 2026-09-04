package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenNodeRetainedWeightTest {

    @Test
    void shouldGrowRetainedWeightWithContentAndIncludeSchemaWithoutComputingIdentity() throws Exception {
        // given
        FrozenNode small = FrozenNode.fromResolvedNode(new Node().value("x"));
        // when
        FrozenNode large = FrozenNode.fromResolvedNode(new Node()
                .schema(new Schema().minLength(BigInteger.valueOf(12)).enumValues(
                        java.util.Arrays.asList(new Node().value("alpha"), new Node().value("beta"))))
                .properties("left", new Node().value("a-longer-value"))
                .properties("right", new Node().items(
                        new Node().value("one"), new Node().value("two"))));

        // then
        assertNull(cachedBlueId(small));
        assertNull(cachedBlueId(large));
        assertTrue(large.approximateRetainedWeightBytes() > small.approximateRetainedWeightBytes());
        assertTrue(large.approximateShallowRetainedWeightBytes()
                > small.approximateShallowRetainedWeightBytes());
        assertTrue(large.approximateRetainedWeightBytes()
                > large.approximateShallowRetainedWeightBytes());
        assertNull(cachedBlueId(small), "weighting must not compute BlueId");
        assertNull(cachedBlueId(large), "weighting must not compute BlueId");
    }

    @Test
    void shouldDeduplicateSharedFrozenSubtreesInGraphEstimate() {
        // given
        FrozenNode child = FrozenNode.fromResolvedNode(new Node().properties(
                "payload", new Node().value("shared")));
        FrozenNode left = FrozenNode.fromResolvedNode(new Node().properties(
                "child", child.toNode()));
        FrozenNode right = left.withProperty("other", child);

        long separate = left.approximateRetainedWeightBytes()
                + right.approximateRetainedWeightBytes();
        // when
        long combined = FrozenNode.approximateRetainedWeightBytesOf(left, right);

        // then
        assertTrue(combined < separate);
    }

    @Test
    void shouldIncludeLargeDecimalMagnitudeAndOwnedSchemaGraphInWeight() {
        // given
        StringBuilder digits = new StringBuilder(20_000);
        for (int index = 0; index < 20_000; index++) {
            digits.append((char) ('1' + index % 9));
        }
        FrozenNode decimal = FrozenNode.fromResolvedNode(
                new Node().value(new BigDecimal(new BigInteger(digits.toString()), 100)));
        // when
        FrozenNode schemaDense = FrozenNode.fromResolvedNode(new Node().schema(new Schema()
                .minimum(new Node().value(new BigInteger(digits.toString())))
                .enumValues(java.util.Arrays.asList(
                        new Node().value(digits.toString()),
                        new Node().value(digits.reverse().toString())))));

        // then
        assertTrue(decimal.approximateRetainedWeightBytes() > 8_000L,
                "large decimal magnitude must participate in cache admission weight");
        assertTrue(schemaDense.approximateShallowRetainedWeightBytes() > 50_000L,
                "schema-owned nodes and structural-key schema data must be weighed");
    }

    @Test
    void shouldNotRecursivelyChargeDescendantStructuralKeysInShallowWeight() {
        // given
        FrozenNode shortChain = FrozenNode.fromResolvedNode(chain(8));
        FrozenNode deepChain = FrozenNode.fromResolvedNode(chain(256));
        shortChain.resolvedStructuralKey();
        deepChain.resolvedStructuralKey();

        long shortRootWeight = shortChain.approximateShallowRetainedWeightBytes();
        // when
        long deepRootWeight = deepChain.approximateShallowRetainedWeightBytes();

        // then
        assertTrue(deepRootWeight <= shortRootWeight + 64L,
                "a shallow entry weight must not walk and re-charge its descendant key graph");
    }

    @Test
    void shouldChargeAnExactEmptyObjectChildWithoutComputingIdentity() throws Exception {
        // given
        FrozenNode emptyObject = FrozenNode.fromResolvedNode(
                Nodes.emptyObject());
        FrozenNode containingObject = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "presentEmpty",
                        Nodes.emptyObject()));
        Object emptyBlueIdBeforeWeighting =
                cachedBlueId(emptyObject);
        Object containingBlueIdBeforeWeighting =
                cachedBlueId(containingObject);

        // when
        long emptyRetainedWeight =
                emptyObject.approximateRetainedWeightBytes();
        long containingRetainedWeight =
                containingObject.approximateRetainedWeightBytes();
        long emptyShallowWeight =
                emptyObject.approximateShallowRetainedWeightBytes();
        long containingShallowWeight =
                containingObject.approximateShallowRetainedWeightBytes();

        // then
        assertNull(emptyBlueIdBeforeWeighting);
        assertNull(containingBlueIdBeforeWeighting);
        assertTrue(containingRetainedWeight > emptyRetainedWeight,
                "a present {} child and its parent edge must contribute retained weight");
        assertTrue(containingShallowWeight > emptyShallowWeight,
                "the parent property map, key, and edge must contribute shallow weight");
        assertTrue(containingRetainedWeight > containingShallowWeight,
                "the exact empty child must remain reachable and contribute graph weight");
        assertNull(cachedBlueId(emptyObject),
                "weighting an exact empty object must not compute BlueId");
        assertNull(cachedBlueId(containingObject),
                "weighting a graph containing {} must not compute BlueId");
    }

    private static Node chain(int depth) {
        Node current = new Node().value("leaf");
        for (int index = 0; index < depth; index++) {
            current = new Node().properties("child", current);
        }
        return current;
    }

    private static Object cachedBlueId(FrozenNode node) throws Exception {
        Field field = FrozenNode.class.getDeclaredField("blueId");
        field.setAccessible(true);
        return field.get(node);
    }
}
