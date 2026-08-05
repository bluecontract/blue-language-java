package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

final class DeterministicJsonTest {

    @Test
    void shouldOrderEveryMapByKeyWithoutReorderingArrays() {
        // given
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", Arrays.asList("second", "first"));
        first.put("a", Map.of("y", 2, "x", 1));
        Map<String, Object> second = new HashMap<>();
        second.put("a", Map.of("x", 1, "y", 2));
        second.put("z", Arrays.asList("second", "first"));

        // when
        String firstJson = DeterministicJson.write(first);
        String secondJson = DeterministicJson.write(second);

        // then
        assertEquals(firstJson, secondJson);
        assertEquals("{\"a\":{\"x\":1,\"y\":2},\"z\":[\"second\",\"first\"]}\n", firstJson);
    }

    @Test
    void shouldEscapeControlCharactersDeterministically() {
        // given / when
        String json = DeterministicJson.write(Map.of("value", "line\n\"quoted\""));

        // then
        assertEquals("{\"value\":\"line\\n\\\"quoted\\\"\"}\n", json);
    }

    @Test
    void shouldRejectUnsupportedEvidenceValues() {
        // given / when / then
        assertThrows(GradleException.class, () -> DeterministicJson.write(new Object()));
    }
}
