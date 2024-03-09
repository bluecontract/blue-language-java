package blue.lang.utils;

import blue.lang.utils.Sha256Calculator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class Sha256CalculatorTest {

    @Test
    public void testObject() {

        String yaml1 = "abc:\n" +
                "  def: 1\n" +
                "  ghi:\n" +
                "    jkl: 1\n" +
                "    mno: x\n" +
                "pqr: 1";
        Map<String, Object> map1 = YAML_MAPPER.readValue(yaml1, Map.class);
        Map<String, Object> result1 = new Sha256Calculator(sha256ValueProvider()).calculate(map1);

        String yaml2 = "abc:\n" +
                "  def: 1\n" +
                "  ghi:\n" +
                "    sha256: sha256({jkl={sha256=sha256(1)}, mno={sha256=sha256(x)}})\n" +
                "pqr: 1";
        Map<String, Object> map2 = YAML_MAPPER.readValue(yaml2, Map.class);
        Map<String, Object> result2 = new Sha256Calculator(sha256ValueProvider()).calculate(map2);

        String yaml3 = "abc:\n" +
                "  sha256: sha256({def={sha256=sha256(1)}, ghi={sha256=sha256({jkl={sha256=sha256(1)}, mno={sha256=sha256(x)}})}})\n" +
                "pqr: 1";
        Map<String, Object> map3 = YAML_MAPPER.readValue(yaml3, Map.class);
        Map<String, Object> result3 = new Sha256Calculator(sha256ValueProvider()).calculate(map3);

        String yaml4 = "sha256: sha256({abc={sha256=sha256({def={sha256=sha256(1)}, ghi={sha256=sha256({jkl={sha256=sha256(1)}, mno={sha256=sha256(x)}})}})}, pqr={sha256=sha256(1)}})";
        Map<String, Object> map4 = YAML_MAPPER.readValue(yaml4, Map.class);
        Map<String, Object> result4 = new Sha256Calculator(sha256ValueProvider()).calculate(map4);

        String expectedResult = "{sha256=sha256({abc={sha256=sha256({def={sha256=sha256(1)}, ghi={sha256=sha256({jkl={sha256=sha256(1)}, mno={sha256=sha256(x)}})}})}, pqr={sha256=sha256(1)}})}";
        assertEquals(result1.toString(), expectedResult);
        assertEquals(result2.toString(), expectedResult);
        assertEquals(result3.toString(), expectedResult);
        assertEquals(result4.toString(), expectedResult);
    }

    @Test
    public void testList() {

        String list1 = "abc:\n" +
                "  - 1\n" +
                "  - 2\n" +
                "  - 3";
        Map<String, Object> map1 = YAML_MAPPER.readValue(list1, Map.class);
        Map<String, Object> result1 = new Sha256Calculator(sha256ValueProvider()).calculate(map1);

        String list2 = "abc:\n" +
                "  - sha256: sha256(sha256(1)sha256(2))\n" +
                "  - 3";
        Map<String, Object> map2 = YAML_MAPPER.readValue(list2, Map.class);
        Map<String, Object> result2 = new Sha256Calculator(sha256ValueProvider()).calculate(map2);

        String list3 = "abc:\n" +
                "  - sha256: sha256(sha256(sha256(1)sha256(2))sha256(3))";
        Map<String, Object> map3 = YAML_MAPPER.readValue(list3, Map.class);
        Map<String, Object> result3 = new Sha256Calculator(sha256ValueProvider()).calculate(map3);

        String expectedResult = "{sha256=sha256({abc=[{sha256=sha256(sha256(sha256(1)sha256(2))sha256(3))}]})}";
        assertEquals(result1.toString(), expectedResult);
        assertEquals(result2.toString(), expectedResult);
        assertEquals(result3.toString(), expectedResult);
    }

    @Test
    public void testObjectVsList() {

        String list1 = "abc: x";
        Map<String, Object> map1 = YAML_MAPPER.readValue(list1, Map.class);
        Map<String, Object> result1 = new Sha256Calculator(sha256ValueProvider()).calculate(map1);

        String list2 = "abc:\n" +
                "  - x";
        Map<String, Object> map2 = YAML_MAPPER.readValue(list2, Map.class);
        Map<String, Object> result2 = new Sha256Calculator(sha256ValueProvider()).calculate(map2);

        String expectedResult1 = "{sha256=sha256({abc={sha256=sha256(x)}})}";
        String expectedResult2 = "{sha256=sha256({abc=[{sha256=sha256(x)}]})}";
        assertEquals(result1.toString(), expectedResult1);
        assertEquals(result2.toString(), expectedResult2);
    }

    private static Function<Object, String> sha256ValueProvider() {
        return obj -> {
            if (obj instanceof Map)
                obj = new TreeMap<>((Map) obj);
            return "sha256(" + obj + ")";
        };
    }

}