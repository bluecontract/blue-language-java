package blue.language.processor.closure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent closed vectors preserve every absent-carrier identity. */
final class ManagedRevisionSuccessorIdentityTest {
    @Test void matchesLegacyAndSuccessorVectorsAndRejectsUnboundOperands() throws Exception {
        Map<String, Object> data;
        try (InputStream in = getClass().getResourceAsStream("/rooted/revision-successor-identities.json")) {
            assertNotNull(in);
            data = new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() { });
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> vectors = (List<Map<String, Object>>) data.get("vectors");
        assertEquals(4, vectors.size());
        for (Map<String, Object> vector : vectors) {
            ClosureIdentityService.Constructor constructor = ClosureIdentityService.Constructor.valueOf((String) vector.get("constructor"));
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) vector.get("value");
            assertEquals(vector.get("expected"), ClosureIdentityService.INSTANCE.identity(constructor, value));
            for (String field : value.keySet()) {
                Map<String, Object> missing = new LinkedHashMap<>(value); missing.remove(field);
                assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(constructor, missing), field);
                Map<String, Object> wrong = new LinkedHashMap<>(value); wrong.put(field, Boolean.TRUE);
                assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(constructor, wrong), field);
            }
            Map<String, Object> extra = new LinkedHashMap<>(value); extra.put("verified", true);
            assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(constructor, extra));
            Map<String, Object> badEpoch = new LinkedHashMap<>(value); badEpoch.put("toEpoch", ((Number) value.get("toEpoch")).longValue() + 1L);
            assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(constructor, badEpoch));
            if (value.containsKey("successorRepresentationCauseIdentity")) {
                Map<String, Object> changed = new LinkedHashMap<>(value);
                changed.put("successorRepresentationCauseIdentity", "sha256:" + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
                assertNotEquals(vector.get("expected"), ClosureIdentityService.INSTANCE.identity(constructor, changed));
                assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(
                        ClosureIdentityService.Constructor.MANAGED_REVISION_CAUSE, value));
            } else {
                assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(
                        ClosureIdentityService.Constructor.MANAGED_REVISION_CAUSE_WITH_REPRESENTATION_SUCCESSOR, value));
            }
        }
    }
}
