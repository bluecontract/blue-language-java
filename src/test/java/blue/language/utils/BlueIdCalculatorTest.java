package blue.language.utils;

import blue.language.model.NodeWireForm;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BlueIdCalculatorTest {

        @Test
        public void shouldCalculateSameBlueIdAcrossObjectRepresentations() {

                // given
                String yaml1 = "abc:\n" +
                                "  def:\n" +
                                "    value: 1\n" +
                                "  ghi:\n" +
                                "    jkl:\n" +
                                "      value: 2\n" +
                                "    mno:\n" +
                                "      value: x\n" +
                                "pqr:\n" +
                                "  value: 1";
                Map<String, Object> map1 = YAML_MAPPER.readValue(yaml1, Map.class);
                String result1 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map1);

                String yaml2 = "abc:\n" +
                                "  def:\n" +
                                "    value: 1\n" +
                                "  ghi:\n" +
                                "    blueId: hash({jkl={blueId=hash({value=2})}, mno={blueId=hash({value=x})}})\n" +
                                "pqr:\n" +
                                "  value: 1";
                Map<String, Object> map2 = YAML_MAPPER.readValue(yaml2, Map.class);
                String result2 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map2);

                String yaml3 = "abc:\n" +
                                "  blueId: hash({def={blueId=hash({value=1})}, ghi={blueId=hash({jkl={blueId=hash({value=2})}, mno={blueId=hash({value=x})}})}})\n"
                                +
                                "pqr:\n" +
                                "  value: 1";
                Map<String, Object> map3 = YAML_MAPPER.readValue(yaml3, Map.class);
                String result3 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map3);

                String yaml4 = "blueId: hash({abc={blueId=hash({def={blueId=hash({value=1})}, ghi={blueId=hash({jkl={blueId=hash({value=2})}, mno={blueId=hash({value=x})}})}})}, pqr={blueId=hash({value=1})}})";
                Map<String, Object> map4 = YAML_MAPPER.readValue(yaml4, Map.class);
                String result4 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map4);

                // when
                String expectedResult = "hash({abc={blueId=hash({def={blueId=hash({value=1})}, ghi={blueId=hash({jkl={blueId=hash({value=2})}, mno={blueId=hash({value=x})}})}})}, pqr={blueId=hash({value=1})}})";
                // then
                assertEquals(expectedResult, result1);
                assertEquals(expectedResult, result2);
                assertEquals(expectedResult, result3);
                assertEquals(expectedResult, result4);
        }

        @Test
        public void shouldCalculateBlueIdForListContent() {

                // given
                String list1 = "abc:\n" +
                                "  - 1\n" +
                                "  - 2\n" +
                                "  - 3";
                Map<String, Object> map1 = YAML_MAPPER.readValue(list1, Map.class);
                String result1 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map1);

                // when
                String expectedResult = "hash({abc={blueId=" + fakeListHash(
                                fakeScalarHash(INTEGER_TYPE_BLUE_ID, 1),
                                fakeScalarHash(INTEGER_TYPE_BLUE_ID, 2),
                                fakeScalarHash(INTEGER_TYPE_BLUE_ID, 3)) + "}})";
                // then
                assertEquals(expectedResult, result1);
        }

        @Test
        public void shouldPreserveEmptyList() {
                // given
                Map<String, Object> map = YAML_MAPPER.readValue("abc: []", Map.class);

                // when
                String result = new BlueIdCalculator(fakeHashValueProvider()).calculate(map);

                // then
                assertEquals("hash({abc={blueId=hash({$list=empty})}})", result);
        }

        @Test
        public void shouldDistinguishSingletonListFromScalar() {

                // given
                String list1 = "abc:\n" +
                                "  value: x";
                Map<String, Object> map1 = YAML_MAPPER.readValue(list1, Map.class);
                String result1 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map1);

                String list2 = "abc:\n" +
                                "  - value: x";
                Map<String, Object> map2 = YAML_MAPPER.readValue(list2, Map.class);
                // when
                String result2 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map2);

                // then
                assertEquals("hash({abc={blueId=hash({value=x})}})", result1);
                assertEquals("hash({abc={blueId=" + fakeListHash("hash({value=x})") + "}})", result2);
                assertNotEquals(result1, result2);
        }

        @Test
        public void shouldDistinguishNestedListFromFlatList() {
                // given
                String flat = "abc:\n" +
                                "  - 1\n" +
                                "  - 2";
                String nested = "abc:\n" +
                                "  - - 1\n" +
                                "  - 2";

                String flatResult = new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(flat, Map.class));
                // when
                String nestedResult = new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(nested, Map.class));

                // then
                assertEquals("hash({abc={blueId=" + fakeListHash(
                                fakeScalarHash(INTEGER_TYPE_BLUE_ID, 1),
                                fakeScalarHash(INTEGER_TYPE_BLUE_ID, 2)) + "}})", flatResult);
                assertEquals("hash({abc={blueId=" + fakeListHash(
                                fakeListHash(fakeScalarHash(INTEGER_TYPE_BLUE_ID, 1)),
                                fakeScalarHash(INTEGER_TYPE_BLUE_ID, 2)) + "}})", nestedResult);
                assertNotEquals(flatResult, nestedResult);
        }

        @Test
        public void shouldSeedListHashFromPreviousListAnchor() {
                // given
                String anchored = "abc:\n" +
                                "  - $previous:\n" +
                                "      blueId: prevHash\n" +
                                "  - value: x";

                // when
                String result = new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(anchored, Map.class));

                // then
                assertEquals("hash({abc={blueId=hash({$listCons={elem={blueId=hash({value=x})}, prev={blueId=prevHash}}})}})", result);
        }

        @Test
        public void shouldReturnPreviousBlueIdWhenPreviousListAnchorHasNoAppends() {
                // given
                String anchored = "abc:\n" +
                                "  - $previous:\n" +
                                "      blueId: prevHash";

                // when
                String result = new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(anchored, Map.class));

                // then
                assertEquals("hash({abc={blueId=prevHash}})", result);
        }

        @Test
        public void shouldRejectPositionOverlayForDirectBlueId() {
                // given
                String withPosition = "abc:\n" +
                                "  - $pos: 0\n" +
                                "    value: A\n" +
                                "  - value: B";

                // when
                IllegalArgumentException failure = captureFailure(
                                () -> new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(withPosition, Map.class)));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldRejectReplaceOverlayForDirectBlueId() {
                // given
                String withReplace = "abc:\n" +
                                "  - $replace: true\n" +
                                "    value: A";

                // when
                IllegalArgumentException failure = captureFailure(
                                () -> new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(withReplace, Map.class)));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldRejectInvalidListControlsDuringHashing() {
                // given
                BlueIdCalculator calculator = new BlueIdCalculator(fakeHashValueProvider());

                // when
                IllegalArgumentException[] failures = {
                        captureFailure(() -> calculator.calculate(YAML_MAPPER.readValue(
                                "abc:\n" +
                                "  - value: A\n" +
                                "  - $previous:\n" +
                                "      blueId: prevHash", Map.class))),
                        captureFailure(() -> calculator.calculate(YAML_MAPPER.readValue(
                                "abc:\n" +
                                "  - $pos: 0\n" +
                                "    value: A\n" +
                                "  - $pos: 0\n" +
                                "    value: B", Map.class))),
                        captureFailure(() -> calculator.calculate(YAML_MAPPER.readValue(
                                "abc:\n" +
                                "  - $pos: 1.5\n" +
                                "    value: A", Map.class))),
                        captureFailure(() -> calculator.calculate(YAML_MAPPER.readValue(
                                "abc:\n" +
                                "  - $pos: 2147483648\n" +
                                "    value: A", Map.class))),
                        captureFailure(() -> calculator.calculate(YAML_MAPPER.readValue(
                                "abc:\n" +
                                "  - $pos: 0", Map.class))),
                        captureFailure(() -> calculator.calculate(YAML_MAPPER.readValue(
                                "abc:\n" +
                                "  - $previous:\n" +
                                "      blueId: 123", Map.class))),
                        captureFailure(() -> calculator.calculate(YAML_MAPPER.readValue(
                                "abc:\n" +
                                "  - $previous:\n" +
                                "      blueId: prevHash\n" +
                                "      extra: value", Map.class)))
                };

                // then
                for (IllegalArgumentException failure : failures) {
                        assertTrue(failure instanceof IllegalArgumentException);
                }
        }

        @Test
        public void shouldHashEmptyPlaceholderAsContent() {
                // given
                String placeholder = "abc:\n" +
                                "  - $empty: true";
                String empty = "abc: []";

                String placeholderResult = new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(placeholder, Map.class));
                // when
                String emptyResult = new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(empty, Map.class));

                // then
                assertNotEquals(emptyResult, placeholderResult);
        }

        @Test
        public void shouldShortCircuitPureReference() {
                // given
                Map<String, Object> pureReference = YAML_MAPPER.readValue("blueId: asserted-id", Map.class);
                Map<String, Object> mixedNode = YAML_MAPPER.readValue("blueId: asserted-id\nvalue: x", Map.class);

                // when
                BlueIdCalculator calculator = new BlueIdCalculator(fakeHashValueProvider());

                // then
                assertEquals("asserted-id", calculator.calculate(pureReference));
                assertNotEquals("asserted-id", calculator.calculate(mixedNode));
        }

        @Test
        public void shouldHashScalarNumbersAndStringsAsDifferentJsonTypes() {
                // given
                BlueIdCalculator calculator = BlueIdCalculator.INSTANCE;
                BigInteger integerValue = BigInteger.ONE;
                String integerText = "1";
                boolean booleanValue = true;
                String booleanText = "true";

                // when
                String integerBlueId = calculator.calculate(integerValue);
                String integerTextBlueId = calculator.calculate(integerText);
                String booleanBlueId = calculator.calculate(booleanValue);
                String booleanTextBlueId = calculator.calculate(booleanText);

                // then
                assertNotEquals(integerBlueId, integerTextBlueId);
                assertNotEquals(booleanBlueId, booleanTextBlueId);
        }

        @Test
        public void shouldSortObjectProperties() {
                // given
                String yaml = "€: Euro Sign\n" +
                                "\\r: Carriage Return\n" +
                                "\\n: Newline\n" +
                                "\"1\": One\n" +
                                "\uD83D\uDE02: Smiley\n" +
                                "ö: Latin Small Letter O With Diaeresis\n" +
                                "דּ: Hebrew Letter Dalet With Dagesh\n" +
                                "</script>: Browser Challenge";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"1\":\"One\",\"</script>\":\"Browser Challenge\",\"\\\\n\":\"Newline\",\"\\\\r\":\"Carriage Return\",\"ö\":\"Latin Small Letter O With Diaeresis\",\"דּ\":\"Hebrew Letter Dalet With Dagesh\",\"€\":\"Euro Sign\",\"\uD83D\uDE02\":\"Smiley\"}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                // when
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                // then
                assertEquals(blueId2, blueId);
        }

        @Test
        public void shouldSortLexicographically() {
                // given
                Map map = JSON_MAPPER.readValue("{\"z\":1,\"aa\":65,\"q\":3,\"12\":3.5,\"a\":55,\"ab\":\"sad\"}", Map.class);
                // when
                String expectedBlueId = "hash({12={blueId="
                                + fakeScalarHash(DOUBLE_TYPE_BLUE_ID, new BigDecimal("3.5"))
                                + "}, a={blueId=" + fakeScalarHash(INTEGER_TYPE_BLUE_ID, 55)
                                + "}, aa={blueId=" + fakeScalarHash(INTEGER_TYPE_BLUE_ID, 65)
                                + "}, ab={blueId=" + fakeScalarHash(TEXT_TYPE_BLUE_ID, "sad")
                                + "}, q={blueId=" + fakeScalarHash(INTEGER_TYPE_BLUE_ID, 3)
                                + "}, z={blueId=" + fakeScalarHash(INTEGER_TYPE_BLUE_ID, 1) + "}})";
                // then
                assertEquals(expectedBlueId, new BlueIdCalculator(fakeHashValueProvider()).calculate(map));
        }

        @Test
        public void shouldCalculateSameBlueIdForIntegerYamlAndJson() {
                // given
                String yaml = "num: 36";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + INTEGER_TYPE_BLUE_ID + "\"},\"value\":36}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                // when
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                // then
                assertEquals(blueId2, blueId);
        }

        @Test
        public void shouldCalculateSameBlueIdForDecimalYamlAndJson() {
                // given
                String yaml = "num: 36.55";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + DOUBLE_TYPE_BLUE_ID + "\"},\"value\":36.55}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                // when
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                // then
                assertEquals(blueId2, blueId);
        }

        @Test
        public void shouldCalculateSameBlueIdForDoubleIntegerDecimalAndStringForms() {
                // given
                String integerYaml = "num:\n" +
                                "  type:\n" +
                                "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                                "  value: 1";
                String decimalYaml = "num:\n" +
                                "  type:\n" +
                                "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                                "  value: 1.0";
                String stringYaml = "num:\n" +
                                "  type:\n" +
                                "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                                "  value: \"1\"";

                String integerBlueId = BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(integerYaml, Node.class));
                String decimalBlueId = BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(decimalYaml, Node.class));
                // when
                String stringBlueId = BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(stringYaml, Node.class));

                // then
                assertEquals(integerBlueId, decimalBlueId);
                assertEquals(integerBlueId, stringBlueId);
        }

        @Test
        public void shouldCanonicalizeDoubleOneThirdAcrossComputedAndAuthoredForms() {
                // given
                Node computed = new Node().properties(
                                "num", new Node()
                                                .type(new Node().blueId(DOUBLE_TYPE_BLUE_ID))
                                                .value(1.0 / 3.0)
                );
                String authoredNumber = "num:\n" +
                                "  type:\n" +
                                "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                                "  value: 0.3333333333333333";
                String authoredString = "num:\n" +
                                "  type:\n" +
                                "    blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                                "  value: \"0.333333333333333333333333333333\"";
                String inferredDouble = "num: 0.333333333333333333333333333333";

                // when
                String computedBlueId = BlueIdCalculator.calculateBlueId(computed);
                String authoredNumberBlueId = BlueIdCalculator.calculateBlueId(
                                YAML_MAPPER.readValue(authoredNumber, Node.class));
                String authoredStringBlueId = BlueIdCalculator.calculateBlueId(
                                YAML_MAPPER.readValue(authoredString, Node.class));
                String inferredDoubleBlueId = BlueIdCalculator.calculateBlueId(
                                YAML_MAPPER.readValue(inferredDouble, Node.class));
                Map<String, Object> serialized =
                                (Map<String, Object>) NodeWireForm.get(computed);
                Map<String, Object> num =
                                (Map<String, Object>) serialized.get("num");

                // then
                assertEquals(computedBlueId, authoredNumberBlueId);
                assertEquals(computedBlueId, authoredStringBlueId);
                assertEquals(computedBlueId, inferredDoubleBlueId);
                assertEquals(new BigDecimal("0.3333333333333333"), num.get("value"));
        }

        @Test
        public void shouldRejectUnquotedOutOfRangeInteger() {
                // given
                String yaml = "num: 36928735469874359687345908673940586739458679548679034857690345876905238476903485769";

                // when
                RuntimeException failure =
                        captureFailure(() -> YAML_MAPPER.readValue(yaml, Node.class));

                // then
                assertTrue(failure instanceof RuntimeException);
        }

        @Test
        public void shouldCalculateSameBlueIdForQuotedLargeIntegerAcrossYamlAndJson() {
                // given
                String yaml = "num:\n" +
                                "  value: '36928735469874359687345908673940586739458679548679034857690345876905238476903485769'\n"
                                +
                                "  type:\n" +
                                "    blueId: " + INTEGER_TYPE_BLUE_ID;

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + INTEGER_TYPE_BLUE_ID
                                + "\"},\"value\":\"36928735469874359687345908673940586739458679548679034857690345876905238476903485769\"}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                // when
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                // then
                assertEquals(blueId2, blueId);
        }

        @Test
        public void shouldCalculateSameBlueIdForLargeNumericTextAcrossYamlAndJson() {
                // given
                String yaml = "num:\n" +
                                "  value: '36928735469874359687345908673940586739458679548679034857690345876905238476903485769'";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + TEXT_TYPE_BLUE_ID
                                + "\"},\"value\":\"36928735469874359687345908673940586739458679548679034857690345876905238476903485769\"}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                // when
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                // then
                assertEquals(blueId2, blueId);
        }

        @Test
        public void shouldCalculateSameBlueIdForLargeDecimalAcrossYamlAndJson() {
                // given
                String yaml = "num: 36928735469874359687345908673940586739458679548679034857690345876905238476903485769.36928735469874359687345908673940586739458679548679034857690345876905238476903485769";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + DOUBLE_TYPE_BLUE_ID
                                + "\"},\"value\":3.692873546987436e+82}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                // when
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                // then
                assertEquals(blueId2, blueId);
        }

        @Test
        public void shouldCalculateSameBlueIdForLiteralMultilineTextAcrossYamlAndJson() {
                // given
                String yaml = "text: |\n" +
                                "  abc\n" +
                                "  def";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"text\":{\"type\":{\"blueId\":\""
                                + TEXT_TYPE_BLUE_ID
                                + "\"},\"value\":\"abc\\ndef\"}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                // when
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                // then
                assertEquals(blueId2, blueId);
        }

        @Test
        public void shouldCalculateSameBlueIdForFoldedMultilineTextAcrossYamlAndJson() {
                // given
                String yaml = "text: >\n" +
                                "  abc\n" +
                                "  def";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"text\":{\"type\":{\"blueId\":\""
                                + TEXT_TYPE_BLUE_ID
                                + "\"},\"value\":\"abc def\"}}\n";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                // when
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                // then
                assertEquals(blueId2, blueId);
        }

        @Test
        public void shouldRemoveNullAndEmptyValues() {
                // given
                String yaml1 = "a: 1\n" +
                                "b: null";
                String yaml2 = "a: 1";
                String yaml3 = "a: 1\n" +
                                "b: null\n" +
                                "c: null";
                String yaml4 = "a: 1\n" +
                                "b: null\n" +
                                "c: []\n" +
                                "d: null";
                String yaml5 = "a: 1\n" +
                                "d: {}";

                Node node1 = YAML_MAPPER.readValue(yaml1, Node.class);
                Node node2 = YAML_MAPPER.readValue(yaml2, Node.class);
                Node node3 = YAML_MAPPER.readValue(yaml3, Node.class);
                Node node4 = YAML_MAPPER.readValue(yaml4, Node.class);
                Node node5 = YAML_MAPPER.readValue(yaml5, Node.class);

                String result1 = BlueIdCalculator.calculateBlueId(node1);
                String result2 = BlueIdCalculator.calculateBlueId(node2);
                String result3 = BlueIdCalculator.calculateBlueId(node3);
                String result4 = BlueIdCalculator.calculateBlueId(node4);
                // when
                String result5 = BlueIdCalculator.calculateBlueId(node5);

                // then
                assertEquals(result1, result2);
                assertEquals(result1, result3);
                assertEquals(result1, result5);
                assertNotEquals(result1, result4);
        }

        @Test
        public void shouldRejectBlueDirectiveForDirectBlueId() {
                // given
                Node node = YAML_MAPPER.readValue(
                                "blue:\n" +
                                "  items: []\n" +
                                "value: hello", Node.class);

                // when
                IllegalArgumentException exception = captureFailure(
                                () -> BlueIdCalculator.calculateBlueId(node));

                // then
                assertTrue(exception instanceof IllegalArgumentException);
                assertTrue(exception.getMessage().contains("\"blue\" is a preprocessing directive"));
        }

        @Test
        public void shouldRejectBlueDirectiveForFacadeDirectBlueId() {
                // given
                Node node = YAML_MAPPER.readValue(
                                "blue:\n" +
                                "  items: []\n" +
                                "value: hello", Node.class);

                // when
                IllegalArgumentException failure =
                        captureFailure(() -> new Blue().calculateBlueId(node));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldRequireCanonicalBlueIdsDuringExplicitBlueIdInputParsing() {
                // given
                Blue blue = new Blue();
                String validBlueId = BlueIdCalculator.calculateBlueId(new Node().value("x"));

                // when
                blue.parseBlueIdInputYaml("blueId: " + validBlueId);
                blue.parseBlueIdInputYaml("blueId: " + validBlueId + "#0");
                RuntimeException[] failures = {
                        captureFailure(() -> blue.parseBlueIdInputYaml("blueId: abc")),
                        captureFailure(() -> blue.parseBlueIdInputYaml(
                                "blueId: " + validBlueId + "#01")),
                        captureFailure(() -> blue.parseBlueIdInputYaml("blueId: this#0")),
                        captureFailure(() -> blue.parseBlueIdInputYaml(
                                "items:\n" +
                                "  - $previous:\n" +
                                "      blueId: prevHash\n" +
                                "  - value: x"))
                };

                // then
                for (RuntimeException failure : failures) {
                        assertTrue(failure instanceof RuntimeException);
                }
        }

        @Test
        public void shouldRejectInvalidReferenceBlueIdsInStaticCalculator() {
                // given
                Node malformedPrevious = YAML_MAPPER.readValue(
                                "items:\n" +
                                "  - $previous:\n" +
                                "      blueId: not-a-real-blueid\n" +
                                "  - value: x", Node.class);

                // when
                IllegalArgumentException[] failures = {
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(
                                new Node().blueId("not-a-real-blueid"))),
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(
                                new Node().blueId("this#0"))),
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(
                                malformedPrevious))
                };

                // then
                for (IllegalArgumentException failure : failures) {
                        assertTrue(failure instanceof IllegalArgumentException);
                }
        }

        @Test
        public void shouldRejectUnresolvedTypeAliasesForDirectBlueId() {
                // given
                Node typeAlias = YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class);
                Node itemTypeAlias =
                        YAML_MAPPER.readValue("itemType: Text\nitems: []", Node.class);
                Node mapTypeAliases =
                        YAML_MAPPER.readValue("keyType: Text\nvalueType: Integer", Node.class);

                // when
                RuntimeException[] failures = {
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(typeAlias)),
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(itemTypeAlias)),
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(mapTypeAliases)),
                        captureFailure(() -> new Blue().parseBlueIdInputYaml(
                                "type: Integer\nvalue: 1"))
                };

                // then
                for (RuntimeException failure : failures) {
                        assertTrue(failure instanceof RuntimeException);
                }
        }

        @Test
        public void shouldRejectTypeAliasForDirectBlueId() {
                // given
                Node node = YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class);

                // when
                IllegalArgumentException failure =
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(node));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldRejectItemTypeAliasForDirectBlueId() {
                // given
                Node node = YAML_MAPPER.readValue("itemType: Text\nitems: []", Node.class);

                // when
                IllegalArgumentException failure =
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(node));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldRejectKeyTypeAliasForDirectBlueId() {
                // given
                Node node = YAML_MAPPER.readValue("keyType: Text\n", Node.class);

                // when
                IllegalArgumentException failure =
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(node));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldRejectValueTypeAliasForDirectBlueId() {
                // given
                Node node = YAML_MAPPER.readValue("valueType: Integer\n", Node.class);

                // when
                IllegalArgumentException failure =
                        captureFailure(() -> BlueIdCalculator.calculateBlueId(node));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldRejectTypeAliasWhenParsingBlueIdInput() {
                // given
                Blue blue = new Blue();

                // when
                RuntimeException failure = captureFailure(
                                () -> blue.parseBlueIdInputYaml("type: Integer\nvalue: 1"));

                // then
                assertTrue(failure instanceof RuntimeException);
        }

        @Test
        public void shouldRejectLegacyBlueItemsForSourceDocumentBlueId() {
                // given
                Node node = YAML_MAPPER.readValue(
                                "blue:\n" +
                                "  items: []\n" +
                                "value: hello", Node.class);

                // when
                IllegalArgumentException failure = captureFailure(
                                () -> new Blue().calculateSourceDocumentBlueId(node));

                // then
                assertTrue(failure.getMessage().contains(
                                "invalid portable shape"));
        }

        @Test
        public void shouldAcceptSourceAliasesAndRemoveThemFromCanonicalOverlay() {
                // given
                Blue blue = new Blue();
                Node source = YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class);

                // when
                String sourceDocumentBlueId =
                        blue.calculateSourceDocumentBlueId(source);
                Node canonical = blue.canonicalize(source);
                String directBlueId = BlueIdCalculator.calculateBlueId(canonical);

                // then
                assertTrue(sourceDocumentBlueId != null);
                assertEquals(INTEGER_TYPE_BLUE_ID, canonical.getType().getBlueId());
                assertTrue(directBlueId != null);
        }

        @Test
        public void shouldUsePreviousAsListSeedForDirectBlueId() {
                // given
                String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());
                Node node = YAML_MAPPER.readValue(
                                "items:\n" +
                                "  - $previous:\n" +
                                "      blueId: " + previousBlueId + "\n" +
                                "  - value: C", Node.class);

                // when
                String blueId = BlueIdCalculator.calculateBlueId(node);

                // then
                assertTrue(blueId != null);
        }

        @Test
        public void shouldNormalizeNullSourceListToEmptyPlaceholder() {
                // given
                Blue blue = new Blue();
                Node withNull = blue.yamlToNode(
                                "items:\n" +
                                "  - A\n" +
                                "  - null\n" +
                                "  - B");
                Node withPlaceholder = blue.yamlToNode(
                                "items:\n" +
                                "  - A\n" +
                                "  - $empty: true\n" +
                                "  - B");
                // when
                Node compact = blue.yamlToNode(
                                "items:\n" +
                                "  - A\n" +
                                "  - B");

                // then
                assertEquals(BlueIdCalculator.calculateBlueId(withPlaceholder), BlueIdCalculator.calculateBlueId(withNull));
                assertNotEquals(BlueIdCalculator.calculateBlueId(compact), BlueIdCalculator.calculateBlueId(withNull));
        }

        @Test
        public void shouldNormalizeEmptyObjectSourceListToEmptyPlaceholder() {
                // given
                Blue blue = new Blue();
                Node withEmptyObject = blue.yamlToNode(
                                "items:\n" +
                                "  - A\n" +
                                "  - {}\n" +
                                "  - B");
                Node withPlaceholder = blue.yamlToNode(
                                "items:\n" +
                                "  - A\n" +
                                "  - $empty: true\n" +
                                "  - B");
                // when
                Node compact = blue.yamlToNode(
                                "items:\n" +
                                "  - A\n" +
                                "  - B");

                // then
                assertEquals(BlueIdCalculator.calculateBlueId(withPlaceholder), BlueIdCalculator.calculateBlueId(withEmptyObject));
                assertNotEquals(BlueIdCalculator.calculateBlueId(compact), BlueIdCalculator.calculateBlueId(withEmptyObject));
        }

        @Test
        public void shouldRejectEmptyObjectListElementForDirectBlueId() {
                // given
                Node withEmptyObject = YAML_MAPPER.readValue(
                                "items:\n" +
                                "  - {}", Node.class);

                // when
                IllegalArgumentException failure = captureFailure(
                                () -> BlueIdCalculator.calculateBlueId(withEmptyObject));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldRejectNullListElementForDirectBlueId() {
                // given
                Node withNull = YAML_MAPPER.readValue(
                                "items:\n" +
                                "  - null", Node.class);

                // when
                IllegalArgumentException failure = captureFailure(
                                () -> BlueIdCalculator.calculateBlueId(withNull));

                // then
                assertTrue(failure instanceof IllegalArgumentException);
        }

        @Test
        public void shouldUseTypedScalarIdentityForNestedBareSchemaScalar() {
                // given
                Node withBareSchemaScalar = YAML_MAPPER.readValue(
                                "schema:\n" +
                                "  required: true", Node.class);

                Schema explicitSchema = new Schema()
                                .required(new Node()
                                                .type(new Node().blueId(BOOLEAN_TYPE_BLUE_ID))
                                                .value(true));
                Node withExplicitTypedScalar = new Node().schema(explicitSchema);

                // when
                String explicitBlueId =
                        BlueIdCalculator.calculateBlueId(withExplicitTypedScalar);
                String bareBlueId =
                        BlueIdCalculator.calculateBlueId(withBareSchemaScalar);

                // then
                assertEquals(explicitBlueId, bareBlueId);
        }

        @Test
        public void shouldCanonicalizeSchemaEnumOrderAndDuplicates() {
                // given
                Node first = new Node()
                                .schema(new Schema().enumValues(Arrays.asList(
                                                new Node().value("B"),
                                                new Node().value("A"),
                                                new Node().value("B"))))
                                .value("A");
                Node second = new Node()
                                .schema(new Schema().enumValues(Arrays.asList(
                                                new Node().value("A"),
                                                new Node().value("B"))))
                                .value("A");

                // when
                String firstBlueId = BlueIdCalculator.calculateBlueId(first);
                String secondBlueId = BlueIdCalculator.calculateBlueId(second);

                // then
                assertEquals(secondBlueId, firstBlueId);
                assertEquals(
                                "4Q8KMTFv6BboSsKpd6WK6GDonEPhXY9LSHu7cmV1ZtFr",
                                firstBlueId);
                assertEquals("B", first.getSchema().getEnum().get(0).getValue());
                assertEquals(3, first.getSchema().getEnum().size());
        }

        @Test
        public void shouldMatchPublishedContracts10IdentityForCheckpointEntry() throws Exception {
                // given
                String expected = RuntimeBlueIds.CHECKPOINT_ENTRY;

                // when
                boolean resourcePresent;
                String actual = null;
                try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                                "registry/blue-contracts-1.0/CheckpointEntry.blue")) {
                        resourcePresent = input != null;
                        if (resourcePresent) {
                                Node checkpointEntry = YAML_MAPPER.readValue(input, Node.class);
                                actual = BlueIdCalculator.calculateBlueId(checkpointEntry);
                        }
                }

                // then
                assertTrue(resourcePresent);
                assertEquals(expected, actual);
        }

        private static Function<Object, String> fakeHashValueProvider() {
                return obj -> "hash(" + obj + ")";
        }

        private static String fakeListHash(String... elementHashes) {
                String accumulator = "hash({$list=empty})";
                for (String elementHash : elementHashes) {
                        accumulator = "hash({$listCons={elem={blueId=" + elementHash + "}, prev={blueId=" + accumulator + "}}})";
                }
                return accumulator;
        }

        private static String fakeScalarHash(String typeBlueId, Object value) {
                return "hash({type={blueId=" + typeBlueId + "}, value=" + value + "})";
        }

}
