package blue.language.utils;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // My additional tests

        @Test
        public void testCalculateBlueIdForBigNumbers() throws Exception {
                String yaml = "abc:\n" +
                                "  def:\n" +
                                "    value: 132452345234524739582739458723948572934875\n" +
                                "  ghi:\n" +
                                "    jkl:\n" +
                                "      value: 132452345234524739582739458723948572934875.132452345234524739582739458723948572934875";

                Map<String, Object> object = YAML_MAPPER.readValue(yaml, Map.class);

                String result1 = new BlueIdCalculator(fakeHashValueProvider()).calculate(object);
                String expectedFakeResult = "hash({abc={blueId=hash({def={blueId=hash({value=132452345234524739582739458723948572934875})}, ghi={blueId=hash({jkl={blueId=hash({value=132452345234524739582739458723948572934875.132452345234524739582739458723948572934875})}})}})}})";
                assertEquals(expectedFakeResult, result1);

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String result2 = BlueIdCalculator.calculateBlueId(node);
                assertEquals("AfcbaDxwJwMMLD9xzjjPZuks4jvxPv2jvkjrJXh8EkiA", result2);
        }

        @Test
        public void testCalculateBlueIdForObject() throws Exception {
                String yaml = "abc:\n" +
                                "  def:\n" +
                                "    value: 1\n" +
                                "  ghi:\n" +
                                "    jkl:\n" +
                                "      value: 2\n" +
                                "    mno:\n" +
                                "      value: x\n" +
                                "pqr:\n" +
                                "  value: 1";

                Node node = YAML_MAPPER.readValue(yaml, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);

                assertEquals("6MD6VFSkf4QqWHR3vwNhhsT9nM22NU1BBDrKitxH9fzD", blueId);
        }

        @Test
        public void testCalculateBlueIdForObjectWithItems() {
                Node child1Node = new Node().name("child1").value("child1Value");
                Node child2Node = new Node().name("child2").value("child2Value");
                Node child3Node = new Node().name("child3").value("child3Value");

                Node node = new Node().name("test");
                node.items(Arrays.asList(child1Node, child2Node, child3Node));

                String blueId = BlueIdCalculator.calculateBlueId(node);
                assertEquals("AbzmfN3VhcFAmWTnvfHgLZCkqhLCEW1RbGqMVTdAE2fm", blueId);
        }

        @Test
        public void testCalculateBlueIdForNodeWithSubItems() {
                Node child1Node = new Node().name("child1").value("child1Value");
                Node child2Node = new Node().name("child2").value("child2Value");
                Node child3Node = new Node().name("child3").value("child3Value");

                Node subNode = new Node();
                subNode.items(Arrays.asList(child1Node, child2Node));
                String subNodeBlueId = BlueIdCalculator.calculateBlueId(subNode);

                Node calculatedSubNode = new Node().blueId(subNodeBlueId);

                Node node = new Node();
                node.items(Arrays.asList(calculatedSubNode, child3Node));

                String blueId = BlueIdCalculator.calculateBlueId(node);
                assertEquals("9Avar4YLc5rtenpsqQppYYQuF3bSYEkq7iAougGPd1zv", blueId);
        }

        @Test
        public void testCalculateBlueIdForNodeWithSublist() {
                Node child1Node = new Node().name("child1").value("child1Value");
                Node child3Node = new Node().name("child3").value("child3Value");

                Node subListNode = new Node().items(Arrays.asList(
                                new Node().name("subChild1").value("subChild1Value"),
                                new Node().name("subChild2").value("subChild2Value")));

                Node node = new Node().name("test").items(Arrays.asList(
                                child1Node,
                                subListNode,
                                child3Node));

                String blueId = BlueIdCalculator.calculateBlueId(node);
                assertEquals("8rchyLLTuDsCgawPy7usKsgVrt7h3EaVGCFBXrahMf7D", blueId);
        }

        @Test
        public void testCalculateBlueIdWithLessCharactersThan44() throws Exception {
                String json = "{\"value\":\"18e1ca8a8de189d9759057ab4251fd97\"}";
                Node node = JSON_MAPPER.readValue(json, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);
                assertEquals("1xqjcHGX3aF3um8LtfbMAZGdRT74j3k5GXT4yqSbp8", blueId);
                assertEquals(42, blueId.length());
                assertTrue(BlueIds.isPotentialBlueId(blueId));
        }

        @Test
        public void testCalculateBlueIdFromComplexJson() throws Exception {
                String json = "{\"name\":\"New Products c1fxfa\",\"products\":{\"items\":[{\"blueId\":\"9mGcQeKTSDTrAdD9bJ1kDSxhRbPqKLudvX937Fvxm1Qs\"},[\"Sub Product 1\",\"Sub Product 2\"]]},\"property_1\":{\"name\":\"Products 5ktky8\",\"products\":{\"items\":[{\"blueId\":\"AEW8Ze5C5KZwaVX17a5ZR2fAuCrTe6uwdKMDvk7hXpQ1\"},{\"blueId\":\"BnhdJXp2FdXeksB1gUqvrMDtLm88ZjtXKPvNL4Spvptd\"}]},\"property_1_1\":{\"name\":\"New Products by5ed\",\"products\":{\"items\":[{\"blueId\":\"9mGcQeKTSDTrAdD9bJ1kDSxhRbPqKLudvX937Fvxm1Qs\"},[\"Sub Product 1\",\"Sub Product 2\"]]},\"property_1_1_1\":{\"blueId\":\"FZPSkgZWYy8x3ZEUeHJqF8BQ1epjRQeQDG89SnJhssf1\"},\"property_1_1_2\":{\"blueId\":\"FdaqU1kLfmJUpQoij9cmQJ8zTvxVbuB7uVJMwznvXdKC\"},\"property_1_1_3\":{\"blueId\":\"DapL23Dsy8XAbzThQD44RrpQT4ADEooMbu21sPXcAweo\"}},\"property_1_2\":{\"name\":\"Products ofzo8k\",\"products\":{\"items\":[{\"blueId\":\"AEW8Ze5C5KZwaVX17a5ZR2fAuCrTe6uwdKMDvk7hXpQ1\"},{\"blueId\":\"BnhdJXp2FdXeksB1gUqvrMDtLm88ZjtXKPvNL4Spvptd\"}]},\"property_1_2_1\":{\"blueId\":\"A5u5qmMjxVHdH7PyTPxoose7PwWRCZTwVXvEFBnrLFro\"},\"property_1_2_2\":{\"blueId\":\"HgzxNcR2D7ujv8rrT7yxQ6tbaighDAnezomL9vNQgd8P\"},\"property_1_2_3\":{\"blueId\":\"3kyKhhXy2jpvQb59gFAFmSMBzAzbho3HomizWUNSY7by\"}},\"property_1_3\":{\"name\":\"Name wuwdq\",\"createdAt\":{\"blueId\":\"GDjcyo4wGFv6HL4Tx6tRMqDk7N2gt8KUvFh2RTz57mWD\",\"value\":\"2024-05-15T09:11:13.136Z\"},\"createdBy\":{\"blueId\":\"5BphmBv2gKGyU2VrEmajDaSWP3KcWdCZBzhrP5fUjfH6\",\"value\":\"User 839\"},\"property_1_3_1\":{\"blueId\":\"J9t7hQqbVCoQsnACG9K44idXURutzkqLKpaAxXVZpB82\"},\"property_1_3_2\":{\"blueId\":\"DckK5rS4L15eaHFfgKWe2xatieo3RkVS4PBCDmTVp6GT\"},\"property_1_3_3\":{\"blueId\":\"E6RpVNecRSxL9veDZrzrbt9uj68BWWMhoGKbz87xhJ42\"}}},\"property_2\":{\"name\":\"Name rwjit8\",\"description\":\"Description v1upvgi\",\"property_2_1\":{\"name\":\"Name zm7kcj\",\"description\":\"Description f7wmyo\",\"property_2_1_1\":{\"blueId\":\"D4TkogpTBpbCrJstjBM7cNkWSgMZQuw4utHahP1DMiFe\"},\"property_2_1_2\":{\"blueId\":\"5otp8bVB7WsmqZUYtW2vycsi6DiD7M7w6oKTfnsG6eP1\"},\"property_2_1_3\":{\"blueId\":\"Gz8yFmjphw7SfBpt7gaBCbTmdBGvTkRfpCWbTNSdghHC\"}},\"property_2_2\":{\"blueId\":\"12nR2SBWR9QT4bt97N9w1emGdHfo97ySGvYysZXA5CEH\"}},\"property_3\":{\"blueId\":\"97i78hcHUnEiWvx1ESHiFShPBbmtXZDrL1dp5ZRrjptr\"},\"property_4\":{\"blueId\":\"DdvTnSUx3QPaA7QbKaNmxFUL2VjriQgR3LwbKDMFJm5F\"},\"property_5\":{\"blueId\":\"HM5xSf98Hq37GYe1zu5Mgy4u3tMWYsZ7EcyyenKDCRWL\"}}";

                Node node = JSON_MAPPER.readValue(json, Node.class);
                String blueId = BlueIdCalculator.calculateBlueId(node);
                assertEquals("ToF7bZomuz2gbY3ZoARG9a2QhV2L39AtRRAaQADA1NR", blueId);
        }

        private static Function<Object, String> fakeHashValueProvider() {
                return obj -> "hash(" + obj + ")";
        }
}