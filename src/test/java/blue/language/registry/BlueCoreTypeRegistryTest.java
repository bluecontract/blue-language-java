package blue.language.registry;

import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueCoreTypeRegistryTest {

    @Test
    void packageIdentityIsRecomputedAndRejectsManifestTampering() throws Exception {
        Map<String, Object> manifest;
        try (InputStream input = BlueCoreTypeRegistryTest.class.getClassLoader()
                .getResourceAsStream("registry/blue-language-1.0/manifest.yaml")) {
            manifest = UncheckedObjectMapper.YAML_MAPPER.readValue(input,
                    new TypeReference<Map<String, Object>>() {
                    });
        }

        assertEquals(manifest.get("packageIdentity"),
                BlueCoreTypeRegistry.computePackageIdentity(manifest));
        assertDoesNotThrow(() -> BlueCoreTypeRegistry.verifyPackageIdentity(manifest));

        @SuppressWarnings("unchecked")
        Map<String, Object> firstEntry =
                (Map<String, Object>) ((List<?>) manifest.get("entries")).get(0);
        firstEntry.put("sha256",
                "0000000000000000000000000000000000000000000000000000000000000000");

        assertThrows(IllegalStateException.class,
                () -> BlueCoreTypeRegistry.verifyPackageIdentity(manifest));
    }
}
