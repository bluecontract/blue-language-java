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
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    public void shouldAcceptRequired() throws Exception {
        // given
        schema.required(true);
        node.value("xyz");

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldUseTypedScalarIdentityForEnumAndIgnoreDeclarationMetadata() {
        // given
        Node describedText = new Node()
                .description("Declaration metadata is not scalar identity.")
                .type(new Node().blueId(TEXT_TYPE_BLUE_ID))
                .schema(new Schema().enumValues(Collections.singletonList(
                        new Node().value("catalog"))))
                .value("catalog");
        Node wrongEffectiveType = describedText.clone()
                .type(new Node().blueId(DOUBLE_TYPE_BLUE_ID))
                .value(BigDecimal.ONE);
        SchemaVerifier verifier = new SchemaVerifier();

        // when
        Throwable acceptedFailure = captureFailure(
                () -> verifier.validateCompleted(describedText, true, "/mode"));
        Throwable rejectedFailure = captureFailure(
                () -> verifier.validateCompleted(wrongEffectiveType, true, "/mode"));

        // then
        assertNull(acceptedFailure);
        assertInstanceOf(IllegalArgumentException.class, rejectedFailure);
    }

    @Test
    public void shouldRejectRequired() throws Exception {
        // given
        Node type = new Node().properties("required", new Node()
                .schema(new Schema().required(true)));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeBlueId = calculateBlueId(type);
        Merger completedValueMerger = new Merger(mergingProcessor, provider);
        Node missingRequiredValue = new Node().type(new Node().blueId(typeBlueId));

        // when
        Throwable failure = captureFailure(() -> completedValueMerger.resolve(missingRequiredValue));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAllowMultipleItemsWithoutMaxItems() throws Exception {
        // given
        node.items(Arrays.asList(new Node().name("item 1"), new Node().name("item 2")));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertNull(failure);
    }

    @Test
    public void shouldUseMaxItemsToControlSingleItemCardinality() throws Exception {
        // given
        schema.maxItems(1);
        node.items(new Node().name("item 1"), new Node().name("item 2"));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptMinLength() throws Exception {
        // given
        schema.minLength(3);
        node.value("xyz");

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMinLength() throws Exception {
        // given
        schema.minLength(4);
        node.value("xyz");

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldCountUnicodeCodePointsForMinLength() throws Exception {
        // given
        schema.minLength(2);
        node.value("\uD83D\uDE00");

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptMaxLength() throws Exception {
        // given
        schema.maxLength(3);
        node.value("xyz");

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMaxLength() throws Exception {
        // given
        schema.maxLength(2);
        node.value("xyz");

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldCountUnicodeCodePointsForMaxLength() throws Exception {
        // given
        schema.maxLength(1);
        node.value("\uD83D\uDE00");

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldAcceptMinimum() throws Exception {
        // given
        schema.minimum(new BigDecimal("1.0"));
        node.value(new BigDecimal("1.5"));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMinimum() throws Exception {
        // given
        schema.minimum(new BigDecimal("2.0"));
        node.value(new BigDecimal("1.5"));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptMaximum() throws Exception {
        // given
        schema.maximum(new BigDecimal("5.0"));
        node.value(new BigDecimal("4.5"));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMaximum() throws Exception {
        // given
        schema.maximum(new BigDecimal("3.0"));
        node.value(new BigDecimal("3.5"));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptExclusiveMinimum() throws Exception {
        // given
        schema.exclusiveMinimum(new BigDecimal("1.0"));
        node.value(new BigDecimal("1.1"));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectExclusiveMinimum() throws Exception {
        // given
        schema.exclusiveMinimum(new BigDecimal("2.0"));
        node.value(new BigDecimal("2.0"));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptExclusiveMaximum() throws Exception {
        // given
        schema.exclusiveMaximum(new BigDecimal("5.0"));
        node.value(new BigDecimal("4.9"));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectExclusiveMaximum() throws Exception {
        // given
        schema.exclusiveMaximum(new BigDecimal("3.0"));
        node.value(new BigDecimal("3.0"));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptMultipleOf() throws Exception {
        // given
        schema.multipleOf(new BigDecimal("2.0"));
        node.value(new BigDecimal("4.0"));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMultipleOf() throws Exception {
        // given
        schema.multipleOf(new BigDecimal("3.0"));
        node.value(new BigDecimal("5.0"));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldUseExactBinary64RationalArithmeticForDoubleMultipleOf() {
        // given
        Node passing = new Node()
                .schema(new Schema().multipleOf(new BigDecimal("0.5")))
                .value(new BigDecimal("1.5"));
        Node failing = new Node()
                .schema(new Schema().multipleOf(new BigDecimal("0.1")))
                .value(new BigDecimal("0.3"));

        // when
        Throwable passingFailure = captureFailure(() -> merger.resolve(passing));
        Throwable failingFailure = captureFailure(() -> merger.resolve(failing));

        // then
        assertNull(passingFailure);
        assertInstanceOf(IllegalArgumentException.class, failingFailure);
    }

    @Test
    public void shouldRejectWrongPayloadKindsForSchemaKeywords() {
        // given
        Node numericMinLengthValue = new Node()
                .schema(new Schema().minLength(1))
                .value(BigInteger.ONE);
        Node textualMinimumValue = new Node()
                .schema(new Schema().minimum(BigDecimal.ONE))
                .value("one");
        Node scalarMinItemsValue = new Node()
                .schema(new Schema().minItems(1))
                .value("not a list");
        Node objectMinItemsValue = new Node()
                .schema(new Schema().minItems(1))
                .properties("field", new Node().value("not a list"));
        Node scalarMinFieldsValue = new Node()
                .schema(new Schema().minFields(1))
                .value("not an object");
        Node listMinFieldsValue = new Node()
                .schema(new Schema().minFields(1))
                .items(new Node().value("not an object"));

        // when
        Throwable numericMinLengthFailure = captureFailure(() -> merger.resolve(numericMinLengthValue));
        Throwable textualMinimumFailure = captureFailure(() -> merger.resolve(textualMinimumValue));
        Throwable scalarMinItemsFailure = captureFailure(() -> merger.resolve(scalarMinItemsValue));
        Throwable objectMinItemsFailure = captureFailure(() -> merger.resolve(objectMinItemsValue));
        Throwable scalarMinFieldsFailure = captureFailure(() -> merger.resolve(scalarMinFieldsValue));
        Throwable listMinFieldsFailure = captureFailure(() -> merger.resolve(listMinFieldsValue));

        // then
        assertInstanceOf(IllegalArgumentException.class, numericMinLengthFailure);
        assertInstanceOf(IllegalArgumentException.class, textualMinimumFailure);
        assertInstanceOf(IllegalArgumentException.class, scalarMinItemsFailure);
        assertInstanceOf(IllegalArgumentException.class, objectMinItemsFailure);
        assertInstanceOf(IllegalArgumentException.class, scalarMinFieldsFailure);
        assertInstanceOf(IllegalArgumentException.class, listMinFieldsFailure);
    }

    @Test
    public void shouldAcceptMinItems() throws Exception {
        // given
        schema.minItems(2);
        node.items(Arrays.asList(new Node(), new Node()));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMinItems() throws Exception {
        // given
        schema.minItems(3);
        node.items(Arrays.asList(new Node(), new Node()));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptMaxItems() throws Exception {
        // given
        schema.maxItems(3);
        node.items(Arrays.asList(new Node(), new Node()));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMaxItems() throws Exception {
        // given
        schema.maxItems(1);
        node.items(Arrays.asList(new Node(), new Node()));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptUniqueItems() throws Exception {
        // given
        schema.uniqueItems(true);
        node.items(Arrays.asList(new Node().name("Name 1"), new Node().name("Name 2")));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectUniqueItems() throws Exception {
        // given
        schema.uniqueItems(true);
        node.items(Arrays.asList(new Node().name("Name 1"), new Node().name("Name 1")));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptMinFields() throws Exception {
        // given
        schema.minFields(2);
        node.properties(
                "a", new Node().value("A"),
                "b", new Node().value("B"));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMinFields() throws Exception {
        // given
        schema.minFields(2);
        node.properties("a", new Node().value("A"));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptMaxFields() throws Exception {
        // given
        schema.maxFields(2);
        node.properties(
                "a", new Node().value("A"),
                "b", new Node().value("B"));

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectMaxFields() throws Exception {
        // given
        schema.maxFields(1);
        node.properties(
                "a", new Node().value("A"),
                "b", new Node().value("B"));

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldAcceptEnum() throws Exception {
        // given
        schema.enumValues(Arrays.asList(new Node().value("red"), new Node().value("blue")));
        node.value("red");

        // when
        Node resolved = merger.resolve(node);

        // then
        assertNotNull(resolved);
    }

    @Test
    public void shouldRejectEnum() throws Exception {
        // given
        schema.enumValues(Arrays.asList(new Node().value("red"), new Node().value("blue")));
        node.value("green");

        // when
        Throwable failure = captureFailure(() -> merger.resolve(node));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldIgnorePropagatedSchemaMetadataForEnum() throws Exception {
        // given
        schema.enumValues(Arrays.asList(new Node().value("red")));
        node.value("red");

        // when
        Node resolved = merger.resolve(node);

        // then
        assertEquals("red", resolved.getValue());
    }

    @Test
    public void shouldRejectMalformedSchemaConstraints() throws Exception {
        // given
        Node negativeMinLength = new Node()
                .schema(new Schema().minLength(-1))
                .value("abc");
        Node invertedItemBounds = new Node()
                .schema(new Schema().minItems(2).maxItems(1))
                .items(new Node().value("A"));
        Node zeroMultipleOf = new Node()
                .schema(new Schema().multipleOf(BigDecimal.ZERO))
                .value(BigDecimal.ONE);

        // when
        Throwable negativeMinLengthFailure = captureFailure(() -> merger.resolve(negativeMinLength));
        Throwable invertedItemBoundsFailure = captureFailure(() -> merger.resolve(invertedItemBounds));
        Throwable zeroMultipleOfFailure = captureFailure(() -> merger.resolve(zeroMultipleOf));

        // then
        assertInstanceOf(IllegalArgumentException.class, negativeMinLengthFailure);
        assertInstanceOf(IllegalArgumentException.class, invertedItemBoundsFailure);
        assertInstanceOf(IllegalArgumentException.class, zeroMultipleOfFailure);
    }

    @Test
    public void shouldPreserveEffectiveScalarTypeWhenIntersectingEnums() {
        // given
        Node source = new Node().schema(new Schema().enumValues(Arrays.asList(
                new Node().value(BigInteger.ONE),
                new Node().value(new BigDecimal("1.0")),
                new Node().value("1"))));
        Node target = new Node().schema(new Schema().enumValues(Arrays.asList(
                new Node().value(new BigDecimal("1.0")),
                new Node().value("1"))));

        // when
        new SchemaPropagator().process(target, source, blueId -> null, null);

        // then
        assertEquals(2, target.getSchema().getEnum().size());
        assertEquals(new BigDecimal("1.0"), target.getSchema().getEnum().get(0).getValue());
        assertEquals("1", target.getSchema().getEnum().get(1).getValue());
    }

    @Test
    public void shouldMergeMinimumAndExclusiveMinimumAsExclusive() {
        // given
        Node source = new Node().schema(new Schema().minimum(new BigDecimal("5")));
        Node targetAtBound = new Node().schema(new Schema().exclusiveMinimum(new BigDecimal("5"))).value(new BigDecimal("5"));
        Node targetAboveBound = new Node().schema(new Schema().exclusiveMinimum(new BigDecimal("5"))).value(new BigDecimal("6"));

        // when
        Throwable boundFailure = captureFailure(
                () -> propagateAndVerify(targetAtBound, source));
        propagateAndVerify(targetAboveBound, source);

        // then
        assertInstanceOf(IllegalArgumentException.class, boundFailure);
        assertEquals(0, new BigDecimal("5").compareTo(targetAboveBound.getSchema().getMinimumValue()));
        assertEquals(0, new BigDecimal("5").compareTo(targetAboveBound.getSchema().getExclusiveMinimumValue()));
    }

    @Test
    public void shouldMergeMaximumAndExclusiveMaximumAsExclusive() {
        // given
        Node source = new Node().schema(new Schema().maximum(new BigDecimal("5")));
        Node targetAtBound = new Node().schema(new Schema().exclusiveMaximum(new BigDecimal("5"))).value(new BigDecimal("5"));
        Node targetBelowBound = new Node().schema(new Schema().exclusiveMaximum(new BigDecimal("5"))).value(new BigDecimal("4"));

        // when
        Throwable boundFailure = captureFailure(
                () -> propagateAndVerify(targetAtBound, source));
        propagateAndVerify(targetBelowBound, source);

        // then
        assertInstanceOf(IllegalArgumentException.class, boundFailure);
        assertEquals(0, new BigDecimal("5").compareTo(targetBelowBound.getSchema().getMaximumValue()));
        assertEquals(0, new BigDecimal("5").compareTo(targetBelowBound.getSchema().getExclusiveMaximumValue()));
    }

    @Test
    public void shouldFailWhenMinItemsExceedsMaxItems() {
        // given
        Node source = new Node().schema(new Schema().minItems(3));
        Node target = new Node()
                .schema(new Schema().maxItems(2))
                .items(new Node().value("A"), new Node().value("B"));

        // when
        Throwable failure = captureFailure(() -> propagateAndVerify(target, source));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    public void shouldUseLcmOrEquivalentWhenMergingIntegerMultipleOfConstraints() {
        // given
        Node source = new Node().schema(new Schema().multipleOf(new BigDecimal("4")));
        Node target = new Node().schema(new Schema().multipleOf(new BigDecimal("6"))).value(new BigDecimal("24"));
        Node failingTarget = new Node().schema(new Schema().multipleOf(new BigDecimal("6"))).value(new BigDecimal("18"));

        // when
        propagateAndVerify(target, source);
        Throwable failure = captureFailure(() -> propagateAndVerify(failingTarget, source));

        // then
        assertEquals(0, new BigDecimal("12").compareTo(target.getSchema().getMultipleOfValue()));
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    private void propagateAndVerify(Node target, Node source) {
        new SchemaPropagator().process(target, source, blueId -> null, null);
        SchemaVerifier verifier = new SchemaVerifier();
        verifier.postProcess(target, source, blueId -> null, null);
        verifier.validateCompleted(target, true, "/");
    }

}
