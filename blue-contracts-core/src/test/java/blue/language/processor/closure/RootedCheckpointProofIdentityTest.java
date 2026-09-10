package blue.language.processor.closure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent closed-envelope checks; authentic publication is tested through the SDK. */
final class RootedCheckpointProofIdentityTest {
    @Test void matchesIndependentProofAndPositionVectorsAndRejectsMalformedOperands() throws Exception {
        Map<String, Object> data;
        try (InputStream in = getClass().getResourceAsStream("/rooted/checkpoint-reference-identities.json")) {
            assertNotNull(in);
            data = new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() { });
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> vectors = (List<Map<String, Object>>) data.get("vectors");
        assertEquals(2, vectors.size());
        for (Map<String, Object> vector : vectors) {
            ClosureIdentityService.Constructor constructor = ClosureIdentityService.Constructor.valueOf((String) vector.get("constructor"));
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) vector.get("value");
            assertEquals(vector.get("expected"), ClosureIdentityService.INSTANCE.identity(constructor, value));
            for (String field : value.keySet()) {
                Map<String, Object> missing = new LinkedHashMap<>(value); missing.remove(field);
                assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(constructor, missing), field);
                Map<String, Object> wrongType = new LinkedHashMap<>(value); wrongType.put(field, Boolean.TRUE);
                assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(constructor, wrongType), field);
            }
            Map<String, Object> extra = new LinkedHashMap<>(value); extra.put("verified", true);
            assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(constructor, extra));
            Map<String, Object> changed = new LinkedHashMap<>(value); changed.put("afterBlueId", value.get("beforeBlueId"));
            assertNotEquals(vector.get("expected"), ClosureIdentityService.INSTANCE.identity(constructor, changed));
        }
    }
}
