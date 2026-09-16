package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExactStorageCodecsTest {
    private final ExactNodeStorageCodec nodes = new ExactNodeStorageCodec(1024 * 1024, 128);
    private final FrozenNodeStorageCodec frozen = new FrozenNodeStorageCodec(1024 * 1024, 128);

    @Test void retainsEveryNodeSchemaAndScalarFieldIncludingExactUtf16() {
        Node node = fullNode();
        Node copy = nodes.decode(nodes.encode(node));
        assertArrayEquals(nodes.encode(node), nodes.encode(copy));
        assertEquals("name\ud800", copy.getName());
        assertEquals("description\udfff", copy.getDescription());
        assertEquals(Integer.valueOf(7), copy.getPosition());
        assertArrayEquals(nodes.encode(new Node().schema(node.getSchema())), nodes.encode(new Node().schema(copy.getSchema())));
        assertTrue(copy.isInlineValue());
        assertTrue(copy.isPreprocessingTransformationConfiguration());
        assertNotSame(node.getItems(), copy.getItems());
        @SuppressWarnings("unchecked") List<Object> values = (List<Object>) copy.getRawValue();
        assertEquals(Byte.class, values.get(0).getClass());
        assertEquals(Short.class, values.get(1).getClass());
        assertEquals(Integer.class, values.get(2).getClass());
        assertEquals(Long.class, values.get(3).getClass());
        assertEquals(new BigDecimal("1.2300"), values.get(7));
        assertArrayEquals(new int[] {1, 2}, (int[]) values.get(10));
    }

    private enum HostPaymentState { RESERVED }

    @Test void hostSpecificEnumsFailClosedWithoutAddingAClassLoadingContract() {
        Node value = new Node().value(HostPaymentState.RESERVED);
        FrozenNode accepted = FrozenNode.fromResolvedNode(value);
        assertEquals(HostPaymentState.RESERVED, accepted.getValue());
        assertThrows(IllegalArgumentException.class, () -> nodes.encode(value));
        assertThrows(IllegalArgumentException.class, () -> frozen.encode(accepted));
        assertEquals(HostPaymentState.RESERVED, accepted.getValue());
    }

    @Test void retainsNullAndEmptyCollectionsAndResolvedReferenceMetadata() {
        Node absent = new Node();
        Node empty = new Node().properties(new LinkedHashMap<>()).items(new ArrayList<>());
        assertNull(nodes.decode(nodes.encode(absent)).getProperties());
        assertNotNull(nodes.decode(nodes.encode(empty)).getProperties());
        assertNotNull(nodes.decode(nodes.encode(empty)).getItems());
        Node reference = new Node().blueId(FrozenNode.fromNode(new Node().value("identity")).blueId())
                .name("retained beside reference").properties("expanded", new Node().value("metadata"));
        assertEquals("metadata", nodes.decode(nodes.encode(reference)).getAsText("/expanded"));
    }

    @Test void retainsEveryFrozenModeAndSharedMixedSubgraphs() {
        Node simple = new Node().name("mode").properties("value", new Node().value("stable"));
        for (FrozenNode value : Arrays.asList(FrozenNode.fromNode(simple), FrozenNode.fromSourceNode(simple),
                FrozenNode.fromResolvedNode(simple), FrozenNode.fromUncheckedCanonicalNode(simple))) {
            FrozenNode copy = frozen.decode(frozen.encode(value));
            assertEquals(value.resolvedStructuralKey(), copy.resolvedStructuralKey());
            assertEquals(value.resolvedStructuralKey(), copy.resolvedStructuralKey());
        }
        FrozenNode child = FrozenNode.fromNode(new Node().value("shared"));
        Map<String, FrozenNode> properties = new LinkedHashMap<>();
        properties.put("strict", child); properties.put("shared", child);
        properties.put("resolved", FrozenNode.fromResolvedNode(new Node().value("resolved")));
        FrozenNode mixed = FrozenNodeBuilder.builder().strictCanonical(false).strictBlueIdValidation(false)
                .properties(properties).build();
        FrozenNode copy = frozen.decode(frozen.encode(mixed));
        assertEquals(mixed.resolvedStructuralKey(), copy.resolvedStructuralKey());
        assertTrue(copy.property("strict").isStrictCanonical());
        assertFalse(copy.property("resolved").isStrictCanonical());
        assertSame(copy.property("strict"), copy.property("shared"));
    }

    @Test void frozenTraversalBoundDoesNotExpandPreviouslyEncodedSharedSubgraphs() {
        FrozenNode selected = FrozenNode.fromResolvedNode(new Node().value("leaf"));
        List<FrozenNode> shared = new ArrayList<>();
        shared.add(selected);
        for (int index = 0; index < 20; index++) {
            selected = FrozenNodeBuilder.builder().deferBlueId()
                    .strictCanonical(false).strictBlueIdValidation(false)
                    .properties(Collections.singletonMap("next", selected)).build();
            shared.add(selected);
        }
        FrozenNode graph = FrozenNodeBuilder.builder().deferBlueId()
                .strictCanonical(false).strictBlueIdValidation(false).items(shared).build();
        FrozenNodeStorageCodec shallow = new FrozenNodeStorageCodec(1024 * 1024, 3);
        FrozenNode restored = shallow.decode(shallow.encode(graph));
        assertEquals(graph.resolvedStructuralKey(), restored.resolvedStructuralKey());
        assertSame(restored.item(19), restored.item(20).property("next"));

        FrozenNode unsharedTraversal = selected;
        assertThrows(IllegalArgumentException.class, () -> shallow.encode(unsharedTraversal));
        assertThrows(IllegalArgumentException.class, () -> shallow.decode(frozen.encode(unsharedTraversal)));
    }

    @Test void retainsPreviousAnchorConstructionContextWhenDetachedFromItsList() {
        String id = FrozenNode.fromNode(new Node().value("previous")).blueId();
        FrozenNode list = FrozenNode.fromNode(new Node().items(Arrays.asList(
                new Node().previousBlueId(id), new Node().value("next"))));
        FrozenNode anchor = list.item(0);
        assertEquals(anchor.resolvedStructuralKey(), frozen.decode(frozen.encode(anchor)).resolvedStructuralKey());
        assertEquals(list.resolvedStructuralKey(), frozen.decode(frozen.encode(list)).resolvedStructuralKey());
    }

    @Test void rejectsDamageTruncationTrailingDataAndDeclaredOperationalBounds() {
        byte[] bytes = nodes.encode(new Node().value("intact"));
        for (int i = 0; i < bytes.length; i++) {
            byte[] damaged = bytes.clone(); damaged[i] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> nodes.decode(damaged));
        }
        assertThrows(IllegalArgumentException.class, () -> nodes.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> nodes.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        Node deep = new Node().value("leaf");
        for (int i = 0; i < 20; i++) deep = new Node().properties("nested", deep);
        Node finalDeep = deep;
        ExactNodeStorageCodec shallow = new ExactNodeStorageCodec(1024 * 1024, 8);
        assertThrows(IllegalArgumentException.class, () -> shallow.encode(finalDeep));
        assertThrows(IllegalArgumentException.class, () -> shallow.decode(nodes.encode(finalDeep)));
        assertThrows(IllegalArgumentException.class, () -> new ExactNodeStorageCodec(128, 128).encode(fullNode()));
    }

    private static Node fullNode() {
        List<Object> raw = Arrays.asList((byte) 1, (short) 2, 3, 4L, 5F, 6D,
                new BigInteger("70000000000000000000"), new BigDecimal("1.2300"), 'x', true,
                new int[] {1, 2}, new String[][] {{"a", "b"}}, Collections.singletonMap("k", Arrays.asList("v", null)));
        Node keyword = new Node().value(1);
        Schema schema = new Schema().blueId("schema-id").required(keyword).minLength(keyword).maxLength(keyword)
                .minimum(keyword).maximum(keyword).exclusiveMinimum(keyword).exclusiveMaximum(keyword).multipleOf(keyword)
                .minItems(keyword).maxItems(keyword).uniqueItems(keyword).minFields(keyword).maxFields(keyword)
                .enumValues(Arrays.asList(new Node().value("first"), new Node().value("second")));
        return new Node().name("name\ud800").description("description\udfff")
                .type(new Node().name("type")).itemType(new Node().name("item"))
                .keyType(new Node().name("key")).valueType(new Node().name("value"))
                .value(raw).items(Collections.singletonList(new Node().value("item")))
                .properties("property", new Node().value("property"))
                .contracts(new Node().value("contracts")).blueId("resolved-reference")
                .schema(schema).mergePolicy("merge-policy").previousBlueId("previous-id").position(7)
                .blue(new Node().value("blue")).inlineValue(true).preprocessingTransformationConfiguration(true);
    }
}
