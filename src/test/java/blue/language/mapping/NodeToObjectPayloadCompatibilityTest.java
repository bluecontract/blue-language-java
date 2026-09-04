package blue.language.mapping;

import blue.language.model.BlueDescription;
import blue.language.model.BlueName;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void shouldMapExactEmptyObjectOnlyToObjectCompatibleTargets() {
        // given
        Node emptyObject = Nodes.emptyObject();

        // when
        Map<?, ?> map = converter.convert(emptyObject, Map.class);
        EmptyPojo pojo = converter.convert(emptyObject, EmptyPojo.class);
        Object dynamic = converter.convert(emptyObject, Object.class);

        // then
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
    void shouldMapExactEmptyListSymmetricallyToListArrayAndDynamicObject() {
        // given
        Node emptyList = new Node().items(Collections.<Node>emptyList());

        // when
        List<?> list = converter.convert(emptyList, List.class);
        String[] array = converter.convert(emptyList, String[].class);
        Object dynamic = converter.convert(emptyList, Object.class);

        // then
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
    void shouldMapScalarPayloadOnlyToScalarCompatibleTargets() {
        // given
        Node text = new Node().value("READY");
        Node integer = Nodes.integerNode(BigInteger.valueOf(42L));

        // when
        Object textResult = converter.convert(text, String.class);
        Object enumResult = converter.convert(text, ExampleEnum.class);
        Object integerResult = converter.convert(integer, int.class);
        Object dynamicResult = converter.convert(integer, Object.class);

        // then
        assertEquals("READY", textResult);
        assertEquals(ExampleEnum.READY, enumResult);
        assertEquals(42, integerResult);
        assertEquals(BigInteger.valueOf(42L), dynamicResult);

        assertIncompatible(text, Map.class, "scalar payload");
        assertIncompatible(text, List.class, "scalar payload");
        assertIncompatible(text, String[].class, "scalar payload");
        assertIncompatible(text, EmptyPojo.class, "scalar payload");
    }

    @Test
    void shouldUseResolvedScalarClassForRequestedScalarSupertype() {
        // given
        TypeClassResolver resolver = new TypeClassResolver()
                .register("Registered-String", String.class);
        NodeToObjectConverter typedConverter = new NodeToObjectConverter(
                resolver);
        Node text = new Node()
                .type(new Node().blueId("Registered-String"))
                .value("value");

        // when
        CharSequence result = typedConverter.convertWithType(
                text,
                CharSequence.class,
                false);

        // then
        assertInstanceOf(String.class, result);
        assertEquals("value", result);
    }

    @Test
    void shouldRequireResolvedClassAssignableAndCompatibleWithPayloadKind() {
        // given
        TypeClassResolver resolver = new TypeClassResolver()
                .register("Wrong-Object-Type", EmptyPojo.class);
        NodeToObjectConverter typedConverter = new NodeToObjectConverter(
                resolver);
        Node scalar = new Node()
                .type(new Node().blueId("Wrong-Object-Type"))
                .value("value");

        // when
        Runnable wrongPayloadAction = () -> typedConverter.convertWithType(
                scalar,
                Object.class,
                false);
        Runnable notAssignableAction = () -> typedConverter.convertWithType(
                scalar,
                CharSequence.class,
                false);

        // then
        IllegalArgumentException wrongPayload = assertThrows(
                IllegalArgumentException.class,
                wrongPayloadAction::run);
        IllegalArgumentException notAssignable = assertThrows(
                IllegalArgumentException.class,
                notAssignableAction::run);
        assertTrue(wrongPayload.getMessage().contains("scalar payload"));
        assertTrue(wrongPayload.getMessage().contains(
                EmptyPojo.class.getName()));
        assertTrue(notAssignable.getMessage().contains("not assignable"));
        assertTrue(notAssignable.getMessage().contains(
                CharSequence.class.getName()));
    }

    @Test
    void shouldRejectUnresolvedMetadataOnlyNodeAsOpaqueObject() {
        // given
        Node metadataOnly = new Node().name("Metadata only");

        // when
        Runnable action = () -> converter.convert(metadataOnly, Object.class);

        // then
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                action::run);
        assertTrue(failure.getMessage().contains("metadata-only"));
        assertTrue(failure.getMessage().contains("no resolvable dynamic"));
    }

    @Test
    void shouldUseTargetPolicyForResolvedOptionalMetadataWithoutPayload() {
        // given
        Node metadataOnly = new Node()
                .type(new Node().blueId("Declared-Optional-Type"));

        TypeClassResolver resolver = new TypeClassResolver()
                .register("Declared-Optional-Type", EmptyPojo.class);
        NodeToObjectConverter typedConverter = new NodeToObjectConverter(
                resolver);

        // when
        Object stringValue = converter.convert(metadataOnly, String.class);
        Object primitiveValue = converter.convert(metadataOnly, int.class);
        Object listValue = converter.convert(metadataOnly, List.class);
        Object mapValue = converter.convert(metadataOnly, Map.class);
        Object enumValue = converter.convert(metadataOnly, ExampleEnum.class);
        Object pojoValue = converter.convert(metadataOnly, EmptyPojo.class);
        Object resolvedValue = typedConverter.convert(metadataOnly, Object.class);

        // then
        assertNull(stringValue);
        assertEquals(0, primitiveValue);
        assertNull(listValue);
        assertNull(mapValue);
        assertNull(enumValue);
        assertNotNull(pojoValue);
        assertInstanceOf(EmptyPojo.class, resolvedValue);
    }

    @Test
    void shouldKeepHostAbsenceAndSourceNullDistinct() {
        // given
        Node sourceNull = new Node().inlineValue(true);

        // when
        Object absent = converter.convert((Node) null, String.class);

        // then
        assertNull(absent);
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
    void shouldNeverTreatBareFieldlessBuilderAsAbsenceOrEmptyObject() {
        // given
        Node fieldless = new Node();

        // when
        // then
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
    void shouldValidateEveryNestedSemanticNodeWithItsPath() {
        // given
        Node nestedSourceNull = new Node().properties(
                "outer",
                new Node().items(
                        Nodes.emptyObject(),
                        new Node().inlineValue(true)));
        Node nestedBareBuilder = Nodes.emptyObject().schema(
                new Schema().required(new Node()));

        // when
        Runnable sourceNullAction = () ->
                converter.convert(nestedSourceNull, Node.class);
        Runnable bareBuilderAction = () ->
                converter.convert(nestedBareBuilder, Node.class);

        // then
        IllegalArgumentException sourceNullFailure = assertThrows(
                IllegalArgumentException.class,
                sourceNullAction::run);
        IllegalArgumentException bareBuilderFailure = assertThrows(
                IllegalArgumentException.class,
                bareBuilderAction::run);
        assertTrue(sourceNullFailure.getMessage().contains("/outer/1"));
        assertTrue(sourceNullFailure.getMessage().contains("Source null"));
        assertTrue(bareBuilderFailure.getMessage().contains(
                "/schema/required"));
        assertTrue(bareBuilderFailure.getMessage().contains(
                "incomplete builder"));
    }

    @Test
    void shouldValidateDeepGraphsAndRejectObjectCyclesDeterministically() {
        // given
        Node deepRoot = Nodes.emptyObject();
        Node cursor = deepRoot;
        for (int level = 0; level < 20_000; level++) {
            Node child = Nodes.emptyObject();
            cursor.properties("next", child);
            cursor = child;
        }
        Node cyclic = Nodes.emptyObject();
        cyclic.properties("self", cyclic);

        // when
        Runnable deepAction = () -> converter.convert(deepRoot, Node.class);
        Runnable cyclicAction = () -> converter.convert(cyclic, Node.class);

        // then
        assertDoesNotThrow(deepAction::run);
        IllegalArgumentException cycleFailure = assertThrows(
                IllegalArgumentException.class,
                cyclicAction::run);
        assertTrue(cycleFailure.getMessage().contains("object cycles"));
        assertTrue(cycleFailure.getMessage().contains("/self"));
    }

    @Test
    void shouldRejectInvalidNestedContentEvenWhenPojoIgnoresTheField() {
        // given
        Node ignoredSourceNull = new Node().properties(
                "known", new Node().value("kept"),
                "ignored", new Node().properties(
                        "sourceNull", new Node().inlineValue(true)));
        Node ignoredBareBuilder = new Node().properties(
                "known", new Node().value("kept"),
                "ignored", new Node().properties(
                        "bare", new Node()));
        ConverterFactory factory = new ConverterFactory(
                new TypeClassResolver());
        ComplexObjectConverter directConverter =
                new ComplexObjectConverter(
                        factory,
                        new TypeClassResolver());

        // when
        Runnable sourceNullAction = () -> converter.convert(
                ignoredSourceNull, KnownFieldPojo.class);
        Runnable bareBuilderAction = () -> converter.convert(
                ignoredBareBuilder, KnownFieldPojo.class);
        Runnable directAction = () -> directConverter.convert(
                ignoredSourceNull, KnownFieldPojo.class);

        // then
        IllegalArgumentException sourceNullFailure = assertThrows(
                IllegalArgumentException.class,
                sourceNullAction::run);
        IllegalArgumentException bareBuilderFailure = assertThrows(
                IllegalArgumentException.class,
                bareBuilderAction::run);
        IllegalArgumentException directFailure = assertThrows(
                IllegalArgumentException.class,
                directAction::run);
        assertTrue(sourceNullFailure.getMessage().contains(
                "/ignored/sourceNull"));
        assertTrue(sourceNullFailure.getMessage().contains("Source null"));
        assertTrue(bareBuilderFailure.getMessage().contains(
                "/ignored/bare"));
        assertTrue(bareBuilderFailure.getMessage().contains(
                "incomplete builder"));
        assertTrue(directFailure.getMessage().contains(
                "/ignored/sourceNull"));
    }

    @Test
    void shouldRejectRawJavaNullObjectListAndSchemaMembers() {
        // given
        Map<String, Node> rawProperties = new LinkedHashMap<>();
        rawProperties.put("present", new Node().value("value"));
        rawProperties.put("missing", null);
        Node objectWithNullMember = new Node().properties(rawProperties);

        List<Node> rawItems = new ArrayList<>();
        rawItems.add(new Node().value("value"));
        rawItems.add(null);
        Node listWithNullMember = new Node().items(rawItems);

        List<Node> rawEnum = new ArrayList<>();
        rawEnum.add(null);
        Node schemaWithNullMember = Nodes.emptyObject().schema(
                new Schema().enumValues(rawEnum));

        ConverterFactory factory = new ConverterFactory(
                new TypeClassResolver());

        // when
        Runnable objectAction = () ->
                converter.convert(objectWithNullMember, Map.class);
        Runnable listAction = () ->
                converter.convert(listWithNullMember, List.class);
        Runnable schemaAction = () ->
                converter.convert(schemaWithNullMember, EmptyPojo.class);
        Runnable directMapAction = () ->
                factory.convertMap(objectWithNullMember, Map.class);
        Runnable directListAction = () -> new CollectionConverter(
                factory,
                new TypeClassResolver())
                .convert(listWithNullMember, List.class);

        // then
        IllegalArgumentException objectFailure = assertThrows(
                IllegalArgumentException.class,
                objectAction::run);
        IllegalArgumentException listFailure = assertThrows(
                IllegalArgumentException.class,
                listAction::run);
        IllegalArgumentException schemaFailure = assertThrows(
                IllegalArgumentException.class,
                schemaAction::run);
        IllegalArgumentException directMapFailure = assertThrows(
                IllegalArgumentException.class,
                directMapAction::run);
        IllegalArgumentException directListFailure = assertThrows(
                IllegalArgumentException.class,
                directListAction::run);
        assertTrue(objectFailure.getMessage().contains("/missing"));
        assertTrue(objectFailure.getMessage().contains(
                "Java-null object member"));
        assertTrue(listFailure.getMessage().contains("/1"));
        assertTrue(listFailure.getMessage().contains(
                "Java-null list member"));
        assertTrue(schemaFailure.getMessage().contains("/schema/enum/0"));
        assertTrue(schemaFailure.getMessage().contains(
                "Java-null schema enum member"));
        assertTrue(directMapFailure.getMessage().contains("/missing"));
        assertTrue(directListFailure.getMessage().contains("/1"));
    }

    @Test
    void shouldRejectObjectPayloadsInSchemaEnumsAtEveryMappingBoundary() {
        // given
        Node objectEnum = Nodes.emptyObject().schema(
                new Schema().enumValues(Collections.singletonList(
                        Nodes.emptyObject())));
        ConverterFactory factory = new ConverterFactory(
                new TypeClassResolver());

        // when
        Runnable aggregateAction = () ->
                converter.convert(objectEnum, EmptyPojo.class);
        Runnable directAction = () -> new ComplexObjectConverter(
                factory,
                new TypeClassResolver())
                .convert(objectEnum, EmptyPojo.class);

        // then
        IllegalArgumentException aggregateFailure = assertThrows(
                IllegalArgumentException.class,
                aggregateAction::run);
        IllegalArgumentException directFailure = assertThrows(
                IllegalArgumentException.class,
                directAction::run);
        assertTrue(aggregateFailure.getMessage().contains("/schema/enum/0"));
        assertTrue(aggregateFailure.getMessage().contains(
                "schema enum member must be a scalar value"));
        assertTrue(directFailure.getMessage().contains("/schema/enum/0"));
    }

    @Test
    void shouldPreventAnnotationTargetsBypassingSemanticNodeValidation() {
        // given
        Node invalidNameTarget = new Node().properties(
                "subject",
                new Node().inlineValue(true));
        Node invalidDescriptionTarget = new Node().properties(
                "subject",
                new Node());

        // when
        Runnable nameAction = () -> converter.convert(
                invalidNameTarget,
                NameAnnotationHolder.class);
        Runnable descriptionAction = () -> converter.convert(
                invalidDescriptionTarget,
                DescriptionAnnotationHolder.class);

        // then
        IllegalArgumentException nameFailure = assertThrows(
                IllegalArgumentException.class,
                nameAction::run);
        IllegalArgumentException descriptionFailure = assertThrows(
                IllegalArgumentException.class,
                descriptionAction::run);
        assertTrue(nameFailure.getMessage().contains("/subject"));
        assertTrue(nameFailure.getMessage().contains("Source null"));
        assertTrue(descriptionFailure.getMessage().contains("/subject"));
        assertTrue(descriptionFailure.getMessage().contains(
                "incomplete builder"));
    }

    @Test
    void shouldMakeNullConverterFailClosedForEveryPresentNode() {
        // given
        NullConverter nullConverter = new NullConverter();

        // when
        Object absent = nullConverter.convert(null, Object.class);

        // then
        assertNull(absent);
        for (Node present : Arrays.asList(
                Nodes.emptyObject(),
                new Node().items(Collections.<Node>emptyList()),
                new Node().value("value"),
                new Node().inlineValue(true),
                new Node())) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> nullConverter.convert(present, Object.class));
            assertTrue(failure.getMessage().contains(
                    "accepts only a null Node"));
        }
    }

    @Test
    void shouldPreserveNestedEmptyContainersWithRecursiveObjectPolicy() {
        // given
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

        // when
        DynamicContainers converted = converter.convert(
                source,
                DynamicContainers.class);

        // then
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
    void shouldRetainFieldAndMapKeyContextForNestedFailures() {
        // given
        Node source = new Node().properties(
                "textByName",
                new Node().properties("wrong", Nodes.emptyObject()));

        // when
        Runnable action = () ->
                converter.convert(source, StringMapHolder.class);

        // then
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                action::run);
        assertTrue(failure.getMessage().contains("field 'textByName'"));
        assertTrue(failure.getMessage().contains("map value for key 'wrong'"));
        assertTrue(failure.getMessage().contains("object payload"));
        assertTrue(failure.getMessage().contains("java.lang.String"));
    }

    @Test
    void shouldRejectNestedObjectAsScalarWithoutErasingItsShape() {
        // given
        Node source = new Node().properties(
                "values",
                new Node().items(Arrays.asList(
                        new Node().value(BigInteger.ONE),
                        Nodes.emptyObject())));

        // when
        Runnable action = () ->
                converter.convert(source, IntegerListHolder.class);

        // then
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                action::run);
        assertTrue(failure.getMessage().contains("field 'values'"));
        assertTrue(failure.getMessage().contains("collection item [1]"));
        assertTrue(failure.getMessage().contains("object payload"));
        assertTrue(failure.getMessage().contains("java.lang.Integer"));
    }

    @Test
    void shouldRejectNestedSourceNullWithItsPath() {
        // given
        Node source = new Node().properties(
                "value",
                new Node().inlineValue(true));

        // when
        Runnable action = () -> converter.convert(source, ScalarHolder.class);

        // then
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                action::run);
        assertTrue(failure.getMessage().contains("/value"));
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

    public static final class KnownFieldPojo {
        public String known;

        public KnownFieldPojo() {
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

    public static final class NameAnnotationHolder {
        @BlueName("subject")
        public String subjectName;

        public NameAnnotationHolder() {
        }
    }

    public static final class DescriptionAnnotationHolder {
        @BlueDescription("subject")
        public String subjectDescription;

        public DescriptionAnnotationHolder() {
        }
    }
}
