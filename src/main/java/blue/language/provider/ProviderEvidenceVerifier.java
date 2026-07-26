package blue.language.provider;

import blue.language.Blue;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Verifies provider content under an explicitly selected ingestion mode.
 */
public final class ProviderEvidenceVerifier {

    private ProviderEvidenceVerifier() {
    }

    public static Node verify(String requestedBlueId,
                              Node supplied,
                              ProviderMode mode,
                              Blue blue,
                              SourceProviderEnvironment environment) {
        Objects.requireNonNull(requestedBlueId, "requestedBlueId");
        Objects.requireNonNull(supplied, "supplied");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(blue, "blue");

        Node canonical;
        if (mode == ProviderMode.BLUE_ID_INPUT) {
            if (environment != null) {
                throw new IllegalArgumentException(
                        "BlueIdInput provider mode does not accept a Source preprocessing environment.");
            }
            canonical = supplied.clone();
        } else {
            if (environment == null) {
                throw new IllegalArgumentException(
                        "SourceDocument provider mode requires a declared language and preprocessing environment.");
            }
            if (!environment.isFullyBound()) {
                throw new IllegalArgumentException(
                        "SourceDocument provider mode requires release, canonical registry, "
                                + "and exact source-evidence identity bindings.");
            }
            if (!blue.languageVersion().equals(environment.languageVersion())) {
                throw new IllegalArgumentException(
                        "SourceDocument provider language version does not match this Blue runtime.");
            }
            if (!SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY.equals(
                    environment.languageReleaseIdentity())) {
                throw new IllegalArgumentException(
                        "SourceDocument provider release identity does not match Blue Language 1.0.");
            }
            if (!BlueCoreTypeRegistry.INSTANCE.packageIdentity().equals(
                    environment.canonicalRegistryIdentity())) {
                throw new IllegalArgumentException(
                        "SourceDocument provider canonical registry identity does not match this Blue runtime.");
            }
            if (!preprocessingEnvironmentIdentity(blue).equals(
                    environment.preprocessingEnvironmentId())) {
                throw new IllegalArgumentException(
                        "SourceDocument provider preprocessing environment identity does not match this Blue runtime.");
            }
            if (!sourceEvidenceIdentity(supplied).equals(
                    environment.sourceEvidenceIdentity())) {
                throw new IllegalArgumentException(
                        "SourceDocument provider source-evidence identity does not match the supplied snapshot.");
            }
            canonical = blue.preprocess(supplied.clone());
        }

        String actualBlueId;
        try {
            actualBlueId = BlueIdCalculator.calculateBlueId(canonical);
        } catch (RuntimeException invalidEvidence) {
            throw new IllegalArgumentException(
                    "Provider content does not verify requested BlueId "
                            + requestedBlueId + ": invalid BlueId input.",
                    invalidEvidence);
        }
        if (!requestedBlueId.equals(actualBlueId)) {
            throw new IllegalArgumentException("Provider returned content with BlueId "
                    + actualBlueId + " for requested BlueId " + requestedBlueId + ".");
        }
        return canonical;
    }

    public static String sourceEvidenceIdentity(Node supplied) {
        Objects.requireNonNull(supplied, "supplied");
        return sha256CanonicalIdentity(NodeToMapListOrValue.get(supplied));
    }

    public static String preprocessingEnvironmentIdentity(Blue blue) {
        Objects.requireNonNull(blue, "blue");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("languageReleaseIdentity",
                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY);
        payload.put("canonicalRegistryIdentity",
                BlueCoreTypeRegistry.INSTANCE.packageIdentity());
        payload.put("defaultBlueSha256", sha256Resource(
                "transformation/DefaultBlue.blue"));
        payload.put("preprocessingAliases",
                new TreeMap<>(blue.getPreprocessingAliases()));
        return sha256CanonicalIdentity(payload);
    }

    private static String sha256CanonicalIdentity(Object value) {
        try {
            byte[] json = UncheckedObjectMapper.JSON_MAPPER.writeValueAsBytes(value);
            byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
            return "sha256:" + toHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "Unable to calculate provider evidence identity.", failure);
        }
    }

    private static String sha256Resource(String resource) {
        try (InputStream input = ProviderEvidenceVerifier.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing preprocessing environment resource: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return "sha256:" + toHex(MessageDigest.getInstance("SHA-256")
                    .digest(output.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "Unable to bind preprocessing environment resource.", failure);
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
