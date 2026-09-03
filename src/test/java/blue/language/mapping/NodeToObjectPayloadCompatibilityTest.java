package blue.language.mapping;

import blue.language.model.Node;
import blue.language.model.Nodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves payload-kind parity at root and recursive Java mapping boundaries. */
final class NodeToObjectPayloadCompatibilityTest {

    private NodeToObjectConverter converter;

    @BeforeEach
    void setUp() {
        converter = new NodeToObjectConverter(new TypeClassResolver());
    }

    @Test
    void exactEmptyObjectMapsOnlyToObjectCompatibleTargets() {
        Node emptyObject = Nodes.emptyObject();

        Map<?, ?> map = converter.convert(emptyObject, Map.class);
        EmptyPojo pojo = converter.convert(emptyObject, EmptyPojo.class);
        Object dynamic = converter.convert(emptyObject, Object.class);

        assertNotNull(map);
        assertTrue(map.isEmpty());
        assertNotNull(pojo);
        assertInstanceOf(Map.class, dynamic);
        assertTrue(((Map<?, ?>) dynamic).isEmpty());

        assertIncompatible(emptyObject, String.class, "object payload");
        assertIncompatible(emptyObject, int.class, "object payload");
        assertIncompatible(emptyObject, ExampleEnum.class, "object payload");
        assertIncompatible(emptyObject, List.class, "object payload");
        assertIncompatible(emptyObject, Object[].class, "object payload");
    }

    @Test
    void exactEmptyListMapsSymmetricallyToListArrayAndDynamicObject() {
        Node emptyList = new Node().items(Collections.<Node>emptyList());

        List<?> list = converter.convert(emptyList, List.class);
        String[] array = converter.convert(emptyList, String[].class);
        Object dynamic = converter.convert(emptyList, Object.class);

        assertNotNull(list);
        assertTrue(list.isEmpty());
        assertNotNull(array);
        assertEquals(0, array.length);
        assertInstanceOf(List.class, dynamic);
        assertTrue(((List<?>) dynamic).isEmpty());

        assertIncompatible(emptyList, Map.class, "list payload");
        assertIncompatible(emptyList, String.class, "list payload");
        assertIncompatible(emptyList, EmptyPojo.class, "list payload");
    }

    @Test
    void scalarPayloadMapsOnlyToScalarCompatibleTargets() {
        Node text = new Node().value("READY");
        Node integer = Nodes.integerNode(BigInteger.valueOf(42L));

        assertEquals("READY", converter.convert(text, String.class));
        assertEquals(ExampleEnum.READY,
                converter.convert(text, ExampleEnum.class));
        assertEquals(42, converter.convert(integer, int.class));
        assertEquals(BigInteger.valueOf(42L),
                converter.convert(integer, Object.class));

        assertIncompatible(text, Map.class, "scalar payload");
        assertIncompatible(text, List.class, "scalar payload");
        assertIncompatible(text, String[].class, "scalar payload");
        assertIncompatible(text, EmptyPojo.class, "scalar payload");
    }

    @Test
    void unresolvedMetadataOnlyNodeDoesNotDisappearIntoOpaqueObject() {
        Node metadataOnly = new Node().name("Metadata only");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(metadataOnly, Object.class));

        assertTrue(failure.getMessage().contains("metadata-only"));
        assertTrue(failure.getMessage().contains("no resolvable dynamic"));
    }

    @Test
    void hostAbsenceAndSourceNullRemainDistinct() {
        assertNull(converter.convert((Node) null, String.class));

        Node sourceNull = new Node().inlineValue(true);
        for (Class<?> target : Arrays.<Class<?>>asList(
                Object.class,
                Map.class,
                List.class,
                String.class,
                EmptyPojo.class)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> converter.convert(sourceNull, target));
            assertTrue(failure.getMessage().contains("Source null"));
            assertTrue(failure.getMessage().contains("removed before"));
        }
    }

    @Test
    void bareFieldlessBuilderIsNeverHostAbsenceOrEmptyObject() {
        Node fieldless = new Node();

        for (Class<?> target : Arrays.<Class<?>>asList(
                Object.class,
                Node.class,
                Map.class,
                List.class,
                String.class,
                EmptyPojo.class)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> converter.convert(fieldless, target));
            assertTrue(failure.getMessage().contains("incomplete builder"));
            assertTrue(failure.getMessage().contains("Nodes.emptyObject()"));
        }
    }

    @Test
    void recursiveObjectPolicyPreservesNestedEmptyContainers() {
        Node emptyObject = Nodes.emptyObject();
        Node emptyList = new Node().items(Collections.<Node>emptyList());
        Node source = new Node().properties(
                "valuesByName",
                new Node().properties(
                        "object",
                        emptyObject.clone(),
                        "list",
                        emptyList.clone()),
                "values",
                new Node().items(Arrays.asList(
                        emptyObject.clone(),
                        emptyList.clone(),
                        new Node().value("value"))));

        DynamicContainers converted = converter.convert(
                source,
                DynamicContainers.class);

        assertInstanceOf(Map.class, converted.valuesByName.get("object"));
        assertTrue(((Map<?, ?>) converted.valuesByName.get("object"))
                .isEmpty());
        assertInstanceOf(List.class, converted.valuesByName.get("list"));
        assertTrue(((List<?>) converted.valuesByName.get("list")).isEmpty());
        assertInstanceOf(Map.class, converted.values.get(0));
        assertTrue(((Map<?, ?>) converted.values.get(0)).isEmpty());
        assertInstanceOf(List.class, converted.values.get(1));
        assertTrue(((List<?>) converted.values.get(1)).isEmpty());
        assertEquals("value", converted.values.get(2));
    }

    @Test
    void nestedFailuresRetainFieldAndMapKeyContext() {
        Node source = new Node().properties(
                "textByName",
                new Node().properties("wrong", Nodes.emptyObject()));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(source, StringMapHolder.class));

        assertTrue(failure.getMessage().contains("field 'textByName'"));
        assertTrue(failure.getMessage().contains("map value for key 'wrong'"));
        assertTrue(failure.getMessage().contains("object payload"));
        assertTrue(failure.getMessage().contains("java.lang.String"));
    }

    @Test
    void nestedCollectionRejectsObjectAsScalarWithoutErasingItsShape() {
        Node source = new Node().properties(
                "values",
                new Node().items(Arrays.asList(
                        new Node().value(BigInteger.ONE),
                        Nodes.emptyObject())));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(source, IntegerListHolder.class));

        assertTrue(failure.getMessage().contains("field 'values'"));
        assertTrue(failure.getMessage().contains("collection item [1]"));
        assertTrue(failure.getMessage().contains("object payload"));
        assertTrue(failure.getMessage().contains("java.lang.Integer"));
    }

    @Test
    void nestedSourceNullIsRejectedWithFieldContext() {
        Node source = new Node().properties(
                "value",
                new Node().inlineValue(true));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(source, ScalarHolder.class));

        assertTrue(failure.getMessage().contains("field 'value'"));
        assertTrue(failure.getMessage().contains("Source null"));
    }

    private void assertIncompatible(
            Node node,
            Class<?> target,
            String expectedPayload) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> converter.convert(node, target));
        assertTrue(failure.getMessage().contains(expectedPayload));
        assertTrue(failure.getMessage().contains(target.getTypeName()));
    }

    enum ExampleEnum {
        READY
    }

    public static final class EmptyPojo {
        public EmptyPojo() {
        }
    }

    public static final class DynamicContainers {
        public Map<String, Object> valuesByName;
        public List<Object> values;

        public DynamicContainers() {
        }
    }

    public static final class StringMapHolder {
        public Map<String, String> textByName;

        public StringMapHolder() {
        }
    }

    public static final class ScalarHolder {
        public String value;

        public ScalarHolder() {
        }
    }

    public static final class IntegerListHolder {
        public List<Integer> values;

        public IntegerListHolder() {
        }
    }
}
