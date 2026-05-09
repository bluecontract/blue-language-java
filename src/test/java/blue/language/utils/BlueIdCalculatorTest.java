package blue.language.utils;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.function.Function;

import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class BlueIdCalculatorTest {

        @Test
        public void testObject() {

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

                String expectedResult = "hash({abc={blueId=hash({def={blueId=hash({value=1})}, ghi={blueId=hash({jkl={blueId=hash({value=2})}, mno={blueId=hash({value=x})}})}})}, pqr={blueId=hash({value=1})}})";
                assertEquals(expectedResult, result1);
                assertEquals(expectedResult, result2);
                assertEquals(expectedResult, result3);
                assertEquals(expectedResult, result4);
        }

        @Test
        public void testList() {

                String list1 = "abc:\n" +
                                "  - 1\n" +
                                "  - 2\n" +
                                "  - 3";
                Map<String, Object> map1 = YAML_MAPPER.readValue(list1, Map.class);
                String result1 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map1);

                String expectedResult = "hash({abc={blueId=" + fakeListHash("hash(1)", "hash(2)", "hash(3)") + "}})";
                assertEquals(expectedResult, result1);
        }

        @Test
        public void testEmptyListIsPreserved() {
                Map<String, Object> map = YAML_MAPPER.readValue("abc: []", Map.class);

                String result = new BlueIdCalculator(fakeHashValueProvider()).calculate(map);

                assertEquals("hash({abc={blueId=hash({$list=empty})}})", result);
        }

        @Test
        public void testSingletonListIsDifferentFromScalar() {

                String list1 = "abc:\n" +
                                "  value: x";
                Map<String, Object> map1 = YAML_MAPPER.readValue(list1, Map.class);
                String result1 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map1);

                String list2 = "abc:\n" +
                                "  - value: x";
                Map<String, Object> map2 = YAML_MAPPER.readValue(list2, Map.class);
                String result2 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map2);

                assertEquals("hash({abc={blueId=hash({value=x})}})", result1);
                assertEquals("hash({abc={blueId=" + fakeListHash("hash({value=x})") + "}})", result2);
                assertNotEquals(result1, result2);
        }

        @Test
        public void testNestedListIsDifferentFromFlatList() {
                String flat = "abc:\n" +
                                "  - 1\n" +
                                "  - 2";
                String nested = "abc:\n" +
                                "  - - 1\n" +
                                "  - 2";

                String flatResult = new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(flat, Map.class));
                String nestedResult = new BlueIdCalculator(fakeHashValueProvider()).calculate(YAML_MAPPER.readValue(nested, Map.class));

                assertEquals("hash({abc={blueId=" + fakeListHash("hash(1)", "hash(2)") + "}})", flatResult);
                assertEquals("hash({abc={blueId=" + fakeListHash(fakeListHash("hash(1)"), "hash(2)") + "}})", nestedResult);
                assertNotEquals(flatResult, nestedResult);
        }

        @Test
        public void testPureReferenceShortCircuit() {
                Map<String, Object> pureReference = YAML_MAPPER.readValue("blueId: asserted-id", Map.class);
                Map<String, Object> mixedNode = YAML_MAPPER.readValue("blueId: asserted-id\nvalue: x", Map.class);

                BlueIdCalculator calculator = new BlueIdCalculator(fakeHashValueProvider());

                assertEquals("asserted-id", calculator.calculate(pureReference));
                assertNotEquals("asserted-id", calculator.calculate(mixedNode));
        }

        @Test
        public void testScalarNumbersAndStringsHashAsDifferentJsonTypes() {
                BlueIdCalculator calculator = BlueIdCalculator.INSTANCE;

                assertNotEquals(calculator.calculate(BigInteger.ONE), calculator.calculate("1"));
                assertNotEquals(calculator.calculate(true), calculator.calculate("true"));
        }

        @Test
        public void testSortingOfObjectProperties() {
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
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testLexicographicSorting() {
                Map map = JSON_MAPPER.readValue("{\"z\":1,\"aa\":65,\"q\":3,\"12\":3.5,\"a\":55,\"ab\":\"sad\"}", Map.class);
                String expectedBlueId = "hash({12={blueId=hash(3.5)}, a={blueId=hash(55)}, aa={blueId=hash(65)}, ab={blueId=hash(sad)}, q={blueId=hash(3)}, z={blueId=hash(1)}})";
                assertEquals(expectedBlueId, new BlueIdCalculator(fakeHashValueProvider()).calculate(map));
        }

        @Test
        public void testInteger() {
                String yaml = "num: 36";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + INTEGER_TYPE_BLUE_ID + "\"},\"value\":36}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testDecimal() {
                String yaml = "num: 36.55";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + DOUBLE_TYPE_BLUE_ID + "\"},\"value\":36.55}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testDoubleIntegerDecimalAndStringFormsHaveSameBlueId() {
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
                String stringBlueId = BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(stringYaml, Node.class));

                assertEquals(integerBlueId, decimalBlueId);
                assertEquals(integerBlueId, stringBlueId);
        }

        @Test
        public void testDoubleOneThirdCanonicalizesAcrossComputedAndAuthoredForms() {
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

                String computedBlueId = BlueIdCalculator.calculateBlueId(computed);

                assertEquals(computedBlueId, BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(authoredNumber, Node.class)));
                assertEquals(computedBlueId, BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(authoredString, Node.class)));
                assertEquals(computedBlueId, BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(inferredDouble, Node.class)));

                Map<String, Object> serialized = (Map<String, Object>) NodeToMapListOrValue.get(computed);
                Map<String, Object> num = (Map<String, Object>) serialized.get("num");
                assertEquals(new BigDecimal("0.3333333333333333"), num.get("value"));
        }

        @Test
        public void testBigIntegerV1() {
                String yaml = "num: 36928735469874359687345908673940586739458679548679034857690345876905238476903485769";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + INTEGER_TYPE_BLUE_ID
                                + "\"},\"value\":\"36928735469874359687345908673940586739458679548679034857690345876905238476903485769\"}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testBigIntegerV2() {
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
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testBigIntegerText() {
                String yaml = "num:\n" +
                                "  value: '36928735469874359687345908673940586739458679548679034857690345876905238476903485769'";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + TEXT_TYPE_BLUE_ID
                                + "\"},\"value\":\"36928735469874359687345908673940586739458679548679034857690345876905238476903485769\"}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testBigDecimal() {
                String yaml = "num: 36928735469874359687345908673940586739458679548679034857690345876905238476903485769.36928735469874359687345908673940586739458679548679034857690345876905238476903485769";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + DOUBLE_TYPE_BLUE_ID
                                + "\"},\"value\":3.692873546987436e+82}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testMultilineText1() {
                String yaml = "text: |\n" +
                                "  abc\n" +
                                "  def";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"text\":{\"type\":{\"blueId\":\"DLRQwz7MQeCrzjy9bohPNwtCxKEBbKaMK65KBrwjfG6K\"},\"value\":\"abc\\ndef\"}}";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testMultilineText2() {
                String yaml = "text: >\n" +
                                "  abc\n" +
                                "  def";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"text\":{\"type\":{\"blueId\":\"DLRQwz7MQeCrzjy9bohPNwtCxKEBbKaMK65KBrwjfG6K\"},\"value\":\"abc def\"}}\n";
                Node node2 = JSON_MAPPER.readValue(json, Node.class);
                String blueId2 = BlueIdCalculator.calculateBlueId(node2);

                assertEquals(blueId2, blueId);
        }

        @Test
        public void testNullAndEmptyRemoval() {
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
                String result5 = BlueIdCalculator.calculateBlueId(node5);

                assertEquals(result1, result2);
                assertEquals(result1, result3);
                assertEquals(result1, result5);
                assertNotEquals(result1, result4);
        }

        private static Function<Object, String> fakeHashValueProvider() {
                return obj -> "hash(" + obj + ")";
        }

        private static String fakeListHash(String... elementHashes) {
                String accumulator = "hash({$list=empty})";
                for (String elementHash : elementHashes) {
                        accumulator = "hash({$listCons=[{blueId=" + accumulator + "}, {blueId=" + elementHash + "}]})";
                }
                return accumulator;
        }

}
