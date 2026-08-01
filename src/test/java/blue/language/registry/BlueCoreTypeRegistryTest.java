package blue.language.registry;

import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueCoreTypeRegistryTest {

    @Test
    void shouldRecomputePackageIdentityAndRejectManifestTampering() throws Exception {
        // given
        Map<String, Object> manifest;
        // when
        try (InputStream input = BlueCoreTypeRegistryTest.class.getClassLoader()
                .getResourceAsStream("registry/blue-language-1.0/manifest.yaml")) {
            manifest = UncheckedObjectMapper.YAML_MAPPER.readValue(input,
                    new TypeReference<Map<String, Object>>() {
                    });
        }

        Object declaredIdentity = manifest.get("packageIdentity");
        String computedIdentity = BlueCoreTypeRegistry.computePackageIdentity(manifest);
        BlueCoreTypeRegistry.verifyPackageIdentity(manifest);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstEntry =
                (Map<String, Object>) ((List<?>) manifest.get("entries")).get(0);
        firstEntry.put("sha256",
                "0000000000000000000000000000000000000000000000000000000000000000");
        IllegalStateException tamperingFailure = captureFailure(
                () -> BlueCoreTypeRegistry.verifyPackageIdentity(manifest));

        // then
        assertEquals(declaredIdentity, computedIdentity);
        assertTrue(tamperingFailure instanceof IllegalStateException);
    }
}
