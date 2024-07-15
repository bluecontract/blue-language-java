package blue.language.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

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
                "    blueId: hash({jkl=hash({value=2}), mno=hash({value=x})})\n" +
                "pqr:\n" +
                "  value: 1";
        Map<String, Object> map2 = YAML_MAPPER.readValue(yaml2, Map.class);
        String result2 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map2);

        String yaml3 = "abc:\n" +
                "  blueId: hash({def=hash({value=1}), ghi=hash({jkl=hash({value=2}), mno=hash({value=x})})})\n" +
                "pqr:\n" +
                "  value: 1";
        Map<String, Object> map3 = YAML_MAPPER.readValue(yaml3, Map.class);
        String result3 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map3);

        String yaml4 = "blueId: hash({abc=hash({def=hash({value=1}), ghi=hash({jkl=hash({value=2}), mno=hash({value=x})})}), pqr=hash({value=1})})";
        Map<String, Object> map4 = YAML_MAPPER.readValue(yaml4, Map.class);
        String result4 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map4);

        String expectedResult = "hash({abc=hash({def=hash({value=1}), ghi=hash({jkl=hash({value=2}), mno=hash({value=x})})}), pqr=hash({value=1})})";
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
                "  - blueId: hash([hash(1), hash(2)])\n" +
                "  - 3";
        Map<String, Object> map2 = YAML_MAPPER.readValue(list2, Map.class);
        String result2 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map2);

        String list3 = "abc:\n" +
                "  - blueId: hash([hash([hash(1), hash(2)]), hash(3)])";
        Map<String, Object> map3 = YAML_MAPPER.readValue(list3, Map.class);
        String result3 = new BlueIdCalculator(fakeHashValueProvider()).calculate(map3);

        String expectedResult = "hash({abc=hash([hash([hash(1), hash(2)]), hash(3)])})";
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

        String expectedResult = "hash({abc=hash({value=x})})";
        assertEquals(expectedResult, result1);
        assertEquals(expectedResult, result2);
    }

    private static Function<Object, String> fakeHashValueProvider() {
        return obj -> "hash(" + obj + ")";
    }

}