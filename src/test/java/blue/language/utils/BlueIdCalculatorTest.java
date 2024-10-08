package blue.language.utils;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

                String list2 = "abc:\n" +
                                "  - blueId: hash([{blueId=hash(1)}, {blueId=hash(2)}])\n" +
                                "  - 3";
                Map<String, Object> map2 = YAML_MAPPER.readValue(list2, Map.class);
                String result2 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map2);

                String list3 = "abc:\n" +
                                "  - blueId: hash([{blueId=hash([{blueId=hash(1)}, {blueId=hash(2)}])}, {blueId=hash(3)}])";
                Map<String, Object> map3 = YAML_MAPPER.readValue(list3, Map.class);
                String result3 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map3);

                String expectedResult = "hash({abc={blueId=hash([{blueId=hash([{blueId=hash(1)}, {blueId=hash(2)}])}, {blueId=hash(3)}])}})";
                assertEquals(expectedResult, result1);
                assertEquals(expectedResult, result2);
                assertEquals(expectedResult, result3);
        }

        @Test
        public void testObjectVsList() {

                String list1 = "abc:\n" +
                                "  value: x";
                Map<String, Object> map1 = YAML_MAPPER.readValue(list1, Map.class);
                String result1 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map1);

                String list2 = "abc:\n" +
                                "  - value: x";
                Map<String, Object> map2 = YAML_MAPPER.readValue(list2, Map.class);
                String result2 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map2);

                String expectedResult = "hash({abc={blueId=hash({value=x})}})";
                assertEquals(expectedResult, result1);
                assertEquals(expectedResult, result2);
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
        public void testBigIntegerV1() {
                String yaml = "num: 36928735469874359687345908673940586739458679548679034857690345876905238476903485769";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                String json = "{\"num\":{\"type\":{\"blueId\":\"" + INTEGER_TYPE_BLUE_ID
                                + "\"},\"value\":\"9007199254740991\"}}";
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

                String json = "{\"text\":{\"type\":{\"blueId\":\"F92yo19rCcbBoBSpUA5LRxpfDejJDAaP1PRxxbWAraVP\"},\"value\":\"abc\\ndef\"}}";
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

                String json = "{\"text\":{\"type\":{\"blueId\":\"F92yo19rCcbBoBSpUA5LRxpfDejJDAaP1PRxxbWAraVP\"},\"value\":\"abc def\"}}\n";
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
                assertEquals(result1, result4);
                assertEquals(result1, result5);
        }

        private static Function<Object, String> fakeHashValueProvider() {
                return obj -> "hash(" + obj + ")";
        }

}