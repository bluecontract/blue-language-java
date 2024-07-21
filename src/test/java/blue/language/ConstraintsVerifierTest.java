package blue.language;

import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConstraintsVerifierTest {

    private Node node;
    private Constraints constraints;
    private BasicNodeProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        constraints = new Constraints();
        node = new Node()
                .constraints(constraints);
        mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ConstraintsPropagator(),
                        new ConstraintsVerifier()
                )
        );
        merger = new Merger(mergingProcessor, e -> null);
    }

    @Test
    public void testRequiredPositive() throws Exception {
        constraints.required(true);
        node.value("xyz"); 
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testRequiredNegative() throws Exception {
        constraints.required(true);
        node.value(null); 
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testAllowMultiplePositive() throws Exception {
        constraints.allowMultiple(true);
        node.items(Arrays.asList(new Node().name("item 1"), new Node().name("item 2")));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testAllowMultipleNegative() throws Exception {
        constraints.allowMultiple(false);
        node.items(new Node().name("item 1"), new Node().name("item 2"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinLengthPositive() throws Exception {
        constraints.minLength(3);
        node.value("xyz");
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinLengthNegative() throws Exception {
        constraints.minLength(4);
        node.value("xyz");
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaxLengthPositive() throws Exception {
        constraints.maxLength(3);
        node.value("xyz");
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMaxLengthNegative() throws Exception {
        constraints.maxLength(2);
        node.value("xyz");
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testPatternPositive() throws Exception {
        constraints.pattern("x.*");
        node.value("xyz");
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testPatternNegative() throws Exception {
        constraints.pattern("a.*");
        node.value("xyz");
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinimumPositive() throws Exception {
        constraints.minimum(new BigDecimal("1.0"));
        node.value(new BigDecimal("1.5"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinimumNegative() throws Exception {
        constraints.minimum(new BigDecimal("2.0"));
        node.value(new BigDecimal("1.5"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaximumPositive() throws Exception {
        constraints.maximum(new BigDecimal("5.0"));
        node.value(new BigDecimal("4.5"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMaximumNegative() throws Exception {
        constraints.maximum(new BigDecimal("3.0"));
        node.value(new BigDecimal("3.5"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testExclusiveMinimumPositive() throws Exception {
        constraints.exclusiveMinimum(new BigDecimal("1.0"));
        node.value(new BigDecimal("1.1"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testExclusiveMinimumNegative() throws Exception {
        constraints.exclusiveMinimum(new BigDecimal("2.0"));
        node.value(new BigDecimal("2.0"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testExclusiveMaximumPositive() throws Exception {
        constraints.exclusiveMaximum(new BigDecimal("5.0"));
        node.value(new BigDecimal("4.9"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testExclusiveMaximumNegative() throws Exception {
        constraints.exclusiveMaximum(new BigDecimal("3.0"));
        node.value(new BigDecimal("3.0"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMultipleOfPositive() throws Exception {
        constraints.multipleOf(new BigDecimal("2.0"));
        node.value(new BigDecimal("4.0"));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMultipleOfNegative() throws Exception {
        constraints.multipleOf(new BigDecimal("3.0"));
        node.value(new BigDecimal("5.0"));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMinItemsPositive() throws Exception {
        constraints.minItems(2);
        constraints.allowMultiple(true);
        node.items(Arrays.asList(new Node(), new Node()));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinItemsNegative() throws Exception {
        constraints.minItems(3);
        node.items(Arrays.asList(new Node(), new Node()));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testMaxItemsPositive() throws Exception {
        constraints.maxItems(3);
        constraints.allowMultiple(true);
        node.items(Arrays.asList(new Node(), new Node()));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMaxItemsNegative() throws Exception {
        constraints.maxItems(1);
        node.items(Arrays.asList(new Node(), new Node()));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

    @Test
    public void testUniqueItemsPositive() throws Exception {
        constraints.uniqueItems(true);
        constraints.allowMultiple(true);
        node.items(Arrays.asList(new Node().name("Name 1"), new Node().name("Name 2")));
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testUniqueItemsNegative() throws Exception {
        constraints.uniqueItems(true);
        constraints.allowMultiple(true);
        node.items(Arrays.asList(new Node().name("Name 1"), new Node().name("Name 1")));
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

}
