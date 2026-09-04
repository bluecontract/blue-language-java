package blue.language.provider;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.registry.BlueCoreTypeRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Exact identity contract for the acyclic Source preprocessing baseline. */
final class SourcePreprocessingBaselineIdentityTest {

    private static final String RESOURCE =
            "/provider/blue-language-source-preprocessing-environment-1.0.yaml";
    private static final String SPECIFICATION_RESOURCE =
            "/specifications/blue-language-specification-1.0.md";
    private static final String DOMAIN =
            "blue-language-source-preprocessing-environment/1.0";
    private static final String IDENTITY_PREFIX =
            "blue-language-source-preprocessing-environment-1.0@sha256:";
    private static final String DEFECTIVE_AGGREGATE_PREFIX =
            "blue-language-contracts-embedded-modules-collection-paths@";

    @Test
    void shouldRecomputeBaselineFromDeclaredCanonicalPayload()
            throws Exception {
        Map<String, Object> payload = baselinePayload();
        Map<String, Object> value = value(payload);

        assertEquals(DOMAIN, payload.get("domain"));
        assertEquals("1.0", value.get("languageVersion"));
        assertEquals(sha256Resource(SPECIFICATION_RESOURCE),
                value.get("languageSpecificationSha256"));
        assertEquals(BlueCoreTypeRegistry.INSTANCE.packageIdentity(),
                value.get("canonicalRegistryIdentity"));
        assertEquals(
                SourceProviderEnvironment.LANGUAGE_CONTENT_STRATEGY_IDENTITY,
                value.get("sourceContentStrategyIdentity"));
        assertEquals(
                SourceProviderEnvironment.EXPLICIT_VERIFIER_DOMAIN_IDENTITY,
                value.get("providerEvidenceVerifierDomainIdentity"));
        assertEquals(SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY,
                identity(payload));
    }

    @Test
    void shouldChangeWhenEverySemanticInputChanges() throws Exception {
        Map<String, Object> payload = baselinePayload();
        String expected = identity(payload);

        Map<String, Object> changedDomain =
                new LinkedHashMap<String, Object>(payload);
        changedDomain.put("domain", DOMAIN + "-changed");
        assertNotEquals(expected, identity(changedDomain), "domain");

        for (String field : Arrays.asList(
                "languageVersion",
                "languageSpecificationSha256",
                "canonicalRegistryIdentity",
                "sourceContentStrategyIdentity",
                "providerEvidenceVerifierDomainIdentity")) {
            Map<String, Object> changed =
                    new LinkedHashMap<String, Object>(payload);
            Map<String, Object> changedValue =
                    new LinkedHashMap<String, Object>(value(payload));
            changedValue.put(field, changedValue.get(field) + "-changed");
            changed.put("value", changedValue);
            assertNotEquals(expected, identity(changed), field);
        }
    }

    @Test
    void shouldRemainIndependentOfAggregateAndGeneratedState()
            throws Exception {
        Map<String, Object> payload = baselinePayload();
        Map<String, Object> value = value(payload);

        assertEquals(Arrays.asList("domain", "value"),
                new ArrayList<String>(payload.keySet()));
        assertEquals(Arrays.asList(
                        "languageVersion",
                        "languageSpecificationSha256",
                        "canonicalRegistryIdentity",
                        "sourceContentStrategyIdentity",
                        "providerEvidenceVerifierDomainIdentity"),
                new ArrayList<String>(value.keySet()));
        String canonical = new String(
                new JsonCanonicalizer(
                        UncheckedObjectMapper.JSON_MAPPER
                                .writeValueAsBytes(payload))
                        .getEncodedUTF8(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(canonical.contains(DEFECTIVE_AGGREGATE_PREFIX));
        assertFalse(canonical.contains("sourceCommit"));
        assertFalse(canonical.contains("sourceTree"));
        assertFalse(canonical.contains("closureRelease"));
        assertFalse(canonical.contains("generatedArtifact"));
        assertFalse(SourceProviderEnvironment
                .LANGUAGE_1_0_RELEASE_IDENTITY
                .startsWith(DEFECTIVE_AGGREGATE_PREFIX));
    }

    private static Map<String, Object> baselinePayload() {
        InputStream input = SourcePreprocessingBaselineIdentityTest.class
                .getResourceAsStream(RESOURCE);
        assertNotNull(input, "Missing Source preprocessing baseline resource");
        try {
            return UncheckedObjectMapper.YAML_MAPPER.readValue(
                    input,
                    new TypeReference<Map<String, Object>>() { });
        } finally {
            try {
                input.close();
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(
                        "Unable to close baseline resource", failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("value");
    }

    private static String identity(Map<String, Object> payload)
            throws Exception {
        byte[] json = UncheckedObjectMapper.JSON_MAPPER
                .writeValueAsBytes(payload);
        byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical);
        return IDENTITY_PREFIX + toHex(digest);
    }

    private static String sha256Resource(String resource) throws Exception {
        InputStream input = SourcePreprocessingBaselineIdentityTest.class
                .getResourceAsStream(resource);
        assertNotNull(input, "Missing resource " + resource);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } finally {
            input.close();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
