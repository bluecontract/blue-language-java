package blue.language.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonCanonicalizerTest {

    @Test
    public void testSortingOfObjectProperties() throws Exception {
        // https://www.rfc-editor.org/rfc/rfc8785#name-sorting-of-object-propertie
        String a = "{\n" +
                "  \"\\u20ac\": \"Euro Sign\",\n" +
                "  \"\\r\": \"Carriage Return\",\n" +
                "  \"\\u000a\": \"Newline\",\n" +
                "  \"1\": \"One\",\n" +
                "  \"\\u0080\": \"Control\\u007f\",\n" +
                "  \"\\ud83d\\ude02\": \"Smiley\",\n" +
                "  \"\\u00f6\": \"Latin Small Letter O With Diaeresis\",\n" +
                "  \"\\ufb33\": \"Hebrew Letter Dalet With Dagesh\",\n" +
                "  \"</script>\": \"Browser Challenge\"\n" +
                "}";
        Map map = JSON_MAPPER.readValue(a, Map.class);

        String expectedResult = "{\"\\n\":\"Newline\",\"\\r\":\"Carriage Return\",\"1\":\"One\",\"</script>\":\"Browser Challenge\"," +
                "\"\u0080\":\"Control\u007F\",\"ö\":\"Latin Small Letter O With Diaeresis\",\"€\":\"Euro Sign\"," +
                "\"\uD83D\uDE02\":\"Smiley\",\"דּ\":\"Hebrew Letter Dalet With Dagesh\"}";
        assertEquals(expectedResult, JsonCanonicalizer.canonicalize(map));
    }

    @Test
    public void testFrench() throws Exception {
        String a = "{\n" +
                "  \"peach\": \"This sorting order\",\n" +
                "  \"péché\": \"is wrong according to French\",\n" +
                "  \"pêche\": \"but canonicalization MUST\",\n" +
                "  \"sin\":   \"ignore locale\"\n" +
                "}";
        Map map = JSON_MAPPER.readValue(a, Map.class);

        String expectedResult = "{\"peach\":\"This sorting order\",\"péché\":\"is wrong according to French\"," +
                "\"pêche\":\"but canonicalization MUST\",\"sin\":\"ignore locale\"}";
        assertEquals(expectedResult, JsonCanonicalizer.canonicalize(map));
    }

    @Test
    public void testBigNumbers() throws Exception {
        String a = "{\n" +
                "  \"num\": 203984578234652384758236485726348576238947562839746582739465902736450892736480592039845782346523847582364857263485762389475628397465827394659027364508927364805920398457823465238475823648572634857623894756283974658273946590273645089273648059,\n" +
                "  \"num2\": 20398457823465238475823648572634857623894756283974658273946590273645089273648059.8692457869345769345769345769345769345\n" +
                "}";
        Map map = JSON_MAPPER.readValue(a, Map.class);

        String expectedResult = "{\"num\":203984578234652384758236485726348576238947562839746582739465902736450892736480592039845782346523847582364857263485762389475628397465827394659027364508927364805920398457823465238475823648572634857623894756283974658273946590273645089273648059,\"num2\":20398457823465238475823648572634857623894756283974658273946590273645089273648059.8692457869345769345769345769345769345}";
        assertEquals(expectedResult, JsonCanonicalizer.canonicalize(map));
    }

}
