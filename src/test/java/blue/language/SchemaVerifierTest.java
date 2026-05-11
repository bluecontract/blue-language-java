package blue.language;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.processor.*;
import blue.language.model.Schema;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SchemaVerifierTest {

    private Node node;
    private Schema schema;
    private BasicNodeProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        schema = new Schema();
        node = new Node()
                .schema(schema);
        mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new SchemaPropagator(),
                        new SchemaVerifier()
                )
        );
        merger = new Merger(mergingProcessor, e -> null);
    }

    @Test
    public void testRequiredPositive() throws Exception {
        schema.required(true);
        node.value("xyz"); 
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testRequiredNegative() throws Exception {
        schema.required(true);
        node.value(null); 
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testAllowMultiplePositive() throws Exception {
        schema.allowMultiple(true);
        node.items(Arrays.asList(new Node().name("item 1"), new Node().name("item 2")));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testAllowMultipleNegative() throws Exception {
        schema.allowMultiple(false);
        node.items(new Node().name("item 1"), new Node().name("item 2"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinLengthPositive() throws Exception {
        schema.minLength(3);
        node.value("xyz");
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinLengthNegative() throws Exception {
        schema.minLength(4);
        node.value("xyz");
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinLengthCountsUnicodeCodePoints() throws Exception {
        schema.minLength(2);
        node.value("\uD83D\uDE00");
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaxLengthPositive() throws Exception {
        schema.maxLength(3);
        node.value("xyz");
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMaxLengthNegative() throws Exception {
        schema.maxLength(2);
        node.value("xyz");
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaxLengthCountsUnicodeCodePoints() throws Exception {
        schema.maxLength(1);
        node.value("\uD83D\uDE00");
        merger.resolve(node);
        // nothing should be thrown
    }

    public void testMinimumPositive() throws Exception {
        schema.minimum(new BigDecimal("1.0"));
        node.value(new BigDecimal("1.5"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinimumNegative() throws Exception {
        schema.minimum(new BigDecimal("2.0"));
        node.value(new BigDecimal("1.5"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaximumPositive() throws Exception {
        schema.maximum(new BigDecimal("5.0"));
        node.value(new BigDecimal("4.5"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMaximumNegative() throws Exception {
        schema.maximum(new BigDecimal("3.0"));
        node.value(new BigDecimal("3.5"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testExclusiveMinimumPositive() throws Exception {
        schema.exclusiveMinimum(new BigDecimal("1.0"));
        node.value(new BigDecimal("1.1"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testExclusiveMinimumNegative() throws Exception {
        schema.exclusiveMinimum(new BigDecimal("2.0"));
        node.value(new BigDecimal("2.0"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testExclusiveMaximumPositive() throws Exception {
        schema.exclusiveMaximum(new BigDecimal("5.0"));
        node.value(new BigDecimal("4.9"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testExclusiveMaximumNegative() throws Exception {
        schema.exclusiveMaximum(new BigDecimal("3.0"));
        node.value(new BigDecimal("3.0"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMultipleOfPositive() throws Exception {
        schema.multipleOf(new BigDecimal("2.0"));
        node.value(new BigDecimal("4.0"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMultipleOfNegative() throws Exception {
        schema.multipleOf(new BigDecimal("3.0"));
        node.value(new BigDecimal("5.0"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinItemsPositive() throws Exception {
        schema.minItems(2);
        schema.allowMultiple(true);
        node.items(Arrays.asList(new Node(), new Node()));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinItemsNegative() throws Exception {
        schema.minItems(3);
        node.items(Arrays.asList(new Node(), new Node()));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaxItemsPositive() throws Exception {
        schema.maxItems(3);
        schema.allowMultiple(true);
        node.items(Arrays.asList(new Node(), new Node()));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMaxItemsNegative() throws Exception {
        schema.maxItems(1);
        node.items(Arrays.asList(new Node(), new Node()));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testUniqueItemsPositive() throws Exception {
        schema.uniqueItems(true);
        schema.allowMultiple(true);
        node.items(Arrays.asList(new Node().name("Name 1"), new Node().name("Name 2")));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testUniqueItemsNegative() throws Exception {
        schema.uniqueItems(true);
        schema.allowMultiple(true);
        node.items(Arrays.asList(new Node().name("Name 1"), new Node().name("Name 1")));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinFieldsPositive() throws Exception {
        schema.minFields(2);
        node.properties(
                "a", new Node().value("A"),
                "b", new Node().value("B"));

        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinFieldsNegative() throws Exception {
        schema.minFields(2);
        node.properties("a", new Node().value("A"));

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaxFieldsPositive() throws Exception {
        schema.maxFields(2);
        node.properties(
                "a", new Node().value("A"),
                "b", new Node().value("B"));

        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMaxFieldsNegative() throws Exception {
        schema.maxFields(1);
        node.properties(
                "a", new Node().value("A"),
                "b", new Node().value("B"));

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testEnumPositive() throws Exception {
        schema.enumValues(Arrays.asList(new Node().value("red"), new Node().value("blue")));
        node.value("red");

        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testEnumNegative() throws Exception {
        schema.enumValues(Arrays.asList(new Node().value("red"), new Node().value("blue")));
        node.value("green");

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testEnumIgnoresPropagatedSchemaMetadata() throws Exception {
        schema.enumValues(Arrays.asList(new Node().value("red")));
        node.value("red");

        Node resolved = merger.resolve(node);

        assertEquals("red", resolved.getValue());
    }

    @Test
    public void testSchemaWellFormedness() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(new Node()
                .schema(new Schema().minLength(-1))
                .value("abc")));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(new Node()
                .schema(new Schema().minItems(2).maxItems(1))
                .items(new Node().value("A"))));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(new Node()
                .schema(new Schema().multipleOf(BigDecimal.ZERO))
                .value(BigDecimal.ONE)));
    }

//
//    @Test
//    public void testSchemaAndBlueIdSimpler() throws Exception {
//
//        BasicNodeProvider nodeProvider = new BasicNodeProvider();
//
//        String a = "name: A\n" +
//                   "x:\n" +
//                   "  schema:\n" +
//                   "    maxLength: 4\n" +
//                   "y:\n" +
//                   "  schema:\n" +
//                   "    maxLength: 4";
//        Node aNode = YAML_MAPPER.readValue(a, Node.class);
//        nodeProvider.addSingleNodes(aNode);
//
//        String b = "name: B\n" +
//                   "type:\n" +
//                   "  blueId: " + calculateBlueId(aNode) + "\n" +
//                   "x: asdf\n" +
//                   "y: abcd";
//        Node bNode = YAML_MAPPER.readValue(b, Node.class);
//        nodeProvider.addSingleNodes(bNode);
//
//        Blue blue = new Blue(nodeProvider);
//
////        System.out.println(blue.nodeToYaml(bNode));
//
//
//        Node result = blue.resolve(bNode);
//        System.out.println(blue.nodeToYaml(result));
//
//    }

}
