package blue.language.processor;

import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

/** Builds identity-valid altered gas packages for fail-closed boundary tests. */
final class GasScheduleTestFixtures {

    private static final String PACKAGE_IDENTITY = "packageIdentity";

    private GasScheduleTestFixtures() {
    }

    /** Returns a valid non-release schedule with one changed portable limit. */
    @SuppressWarnings("unchecked")
    static GasSchedule withPortableLimit(
            String limitName,
            long value) {
        try (InputStream input = Objects.requireNonNull(
                GasScheduleTestFixtures.class.getClassLoader()
                        .getResourceAsStream(
                                GasSchedule.CONTRACTS_1_0_RESOURCE),
                "contracts gas manifest")) {
            Map<String, Object> manifest = UncheckedObjectMapper.YAML_MAPPER
                    .readValue(
                            input,
                            new TypeReference<Map<String, Object>>() { });
            Map<String, Object> limits = (Map<String, Object>) manifest.get(
                    GasScheduleConstants.ManifestField.PORTABLE_LIMITS);
            limits.put(limitName, value);
            manifest.put(PACKAGE_IDENTITY, packageIdentity(manifest));
            return GasSchedule.load(new ByteArrayInputStream(
                    UncheckedObjectMapper.YAML_MAPPER
                            .writeValueAsBytes(manifest)));
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Could not create altered gas schedule", failure);
        }
    }

    private static String packageIdentity(
            Map<String, Object> source) throws Exception {
        byte[] serialized = UncheckedObjectMapper.YAML_MAPPER
                .writeValueAsBytes(source);
        Map<String, Object> payload = UncheckedObjectMapper.YAML_MAPPER
                .readValue(
                        serialized,
                        new TypeReference<Map<String, Object>>() { });
        payload.put(PACKAGE_IDENTITY, null);
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        byte[] canonical = new JsonCanonicalizer(
                mapper.writeValueAsString(payload)).getEncodedUTF8();
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return "sha256:" + hex;
    }
}
