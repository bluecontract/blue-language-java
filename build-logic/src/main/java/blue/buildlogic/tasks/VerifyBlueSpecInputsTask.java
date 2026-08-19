package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicHashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Verifies exact canonical specification bytes and their cross-manifest bindings. */
@DisableCachingByDefault(because = "Verification must also inspect the external Git revision")
public abstract class VerifyBlueSpecInputsTask extends DefaultTask {

    private static final String LOCK_SCHEMA =
            "blue-language-java-blue-spec-input-lock/1.0";
    private static final String CONTRACTS_SPEC =
            "specifications/blue-contracts-and-processor-specification-1.0.md";
    private static final String LANGUAGE_SPEC =
            "specifications/blue-language-specification-1.0.md";
    private static final String LANGUAGE_REFERENCE =
            "reference/blue-language-specification-1.0.md";
    private static final String RELEASE_MANIFEST =
            "conformance/contracts/release-manifest.yaml";
    private static final String FIXTURE_MANIFEST =
            "conformance/contracts/fixtures/manifest.yaml";
    private static final String REGISTRY_MANIFEST =
            "conformance/contracts/registry/manifest.yaml";
    private static final String GAS_MANIFEST =
            "conformance/contracts/gas-manifest.yaml";
    private static final String IDENTITY_CONSTRUCTORS =
            "conformance/contracts/identity-constructors.yaml";
    private static final String RELEASE_CONTRACTS_SPEC_PATH =
            "../../" + CONTRACTS_SPEC;
    private static final String RELEASE_LANGUAGE_REFERENCE_PATH =
            "../../" + LANGUAGE_REFERENCE;
    private static final Set<String> REQUIRED_FILES =
            Collections.unmodifiableSet(new TreeSet<String>(Arrays.asList(
                    CONTRACTS_SPEC,
                    LANGUAGE_SPEC,
                    LANGUAGE_REFERENCE,
                    RELEASE_MANIFEST,
                    FIXTURE_MANIFEST,
                    REGISTRY_MANIFEST,
                    GAS_MANIFEST,
                    IDENTITY_CONSTRUCTORS)));
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSpecRoot();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getLockFile();

    @TaskAction
    public void verify() {
        Path root = getSpecRoot().get().getAsFile().toPath()
                .toAbsolutePath().normalize();
        Path lockPath = getLockFile().get().getAsFile().toPath();
        JsonNode lock = readJson(lockPath);
        require(LOCK_SCHEMA.equals(requiredText(lock, "schema")),
                "Unsupported Blue specification input lock schema");
        require(lock.size() == 5,
                "Blue specification input lock has unexpected root fields");

        String expectedCommit = requiredText(lock, "specCommit");
        require(expectedCommit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"),
                "specCommit is not an exact Git object identity");
        require(RELEASE_LANGUAGE_REFERENCE_PATH.equals(
                        requiredText(lock, "releaseLanguageReferencePath")),
                "The locked release Language reference path is not canonical");
        require(RELEASE_CONTRACTS_SPEC_PATH.equals(
                        requiredText(lock, "releaseContractsSpecificationPath")),
                "The locked release Contracts specification path is not canonical");

        JsonNode files = lock.path("files");
        require(files.isObject(), "files must be an object");
        Set<String> lockedPaths = new TreeSet<String>();
        files.fieldNames().forEachRemaining(lockedPaths::add);
        require(REQUIRED_FILES.equals(lockedPaths),
                "Blue specification input lock has an incomplete file set");
        for (String relativePath : REQUIRED_FILES) {
            Path input = resolveLocked(root, relativePath);
            require(Files.isRegularFile(input),
                    "Missing locked Blue specification input: " + relativePath);
            String expected = requiredText(files, relativePath);
            require(expected.matches("sha256:[0-9a-f]{64}"),
                    "Invalid locked SHA-256 identity: " + relativePath);
            String actual = DeterministicHashing.sha256(input);
            require(expected.equals(actual),
                    "Blue specification input digest mismatch: " + relativePath
                            + " (expected " + expected + ", actual " + actual + ")");
        }

        verifyGitCommit(root, expectedCommit);
        verifyManifestBindings(root, files);
    }

    private static void verifyManifestBindings(Path root, JsonNode files) {
        JsonNode release = readYaml(resolveLocked(root, RELEASE_MANIFEST));
        JsonNode fixtures = readYaml(resolveLocked(root, FIXTURE_MANIFEST));
        JsonNode registry = readYaml(resolveLocked(root, REGISTRY_MANIFEST));
        JsonNode gas = readYaml(resolveLocked(root, GAS_MANIFEST));

        require(RELEASE_CONTRACTS_SPEC_PATH.equals(requiredText(
                        release.path("specificationDocument"), "path")),
                "Release manifest selects a different Contracts specification path");
        require(bareIdentity(files, CONTRACTS_SPEC).equals(requiredText(
                        release.path("specificationDocument"), "sha256")),
                "Release manifest Contracts specification digest mismatch");

        JsonNode language = release.path("languageDependency");
        require(RELEASE_LANGUAGE_REFERENCE_PATH.equals(
                        requiredText(language, "referencePath")),
                "Release manifest selects a different Language reference path");
        require(bareIdentity(files, LANGUAGE_SPEC).equals(
                        requiredText(language, "specificationSha256")),
                "Release manifest Language specification digest mismatch");
        require(requiredText(files, LANGUAGE_SPEC).equals(
                        requiredText(files, LANGUAGE_REFERENCE)),
                "Canonical Language specification and release reference differ");

        JsonNode releaseFixtures = release.path("fixturePackage");
        JsonNode releaseRegistry = release.path("contractsRegistry");
        JsonNode releaseGas = release.path("gasManifest");
        JsonNode releaseIdentities = release.path("identityConstructors");
        require("fixtures/manifest.yaml".equals(
                        requiredText(releaseFixtures, "path")),
                "Release manifest selects a different fixture manifest");
        require("registry/manifest.yaml".equals(
                        requiredText(releaseRegistry, "path")),
                "Release manifest selects a different registry manifest");
        require("gas-manifest.yaml".equals(requiredText(releaseGas, "path")),
                "Release manifest selects a different gas manifest");
        require("identity-constructors.yaml".equals(
                        requiredText(releaseIdentities, "path")),
                "Release manifest selects different identity constructors");
        require(requiredText(fixtures, "packageIdentity").equals(
                        requiredText(releaseFixtures, "packageIdentity")),
                "Release and fixture package identities differ");
        require(requiredText(registry, "packageIdentity").equals(
                        requiredText(releaseRegistry, "packageIdentity")),
                "Release and registry package identities differ");
        require(requiredText(fixtures, "packageIdentity").equals(
                        requiredText(registry, "fixturePackageIdentity")),
                "Registry is not bound to the fixture package");
        require(requiredText(gas, "packageIdentity").equals(
                        requiredText(releaseGas, "packageIdentity")),
                "Release and gas package identities differ");
        require(bareIdentity(files, IDENTITY_CONSTRUCTORS).equals(
                        requiredText(releaseIdentities, "sha256")),
                "Release manifest identity-constructor digest mismatch");
    }

    private static void verifyGitCommit(Path root, String expectedCommit) {
        Path repository = root.getParent();
        require(repository != null, "Blue specification root has no repository parent");
        Process process;
        try {
            process = new ProcessBuilder(
                    "git", "-C", repository.toString(),
                    "rev-parse", "--verify", "HEAD^{commit}")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot inspect the Blue specification Git revision", exception);
        }
        String output;
        try (InputStream input = process.getInputStream();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
            }
            int exitCode = process.waitFor();
            output = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
            require(exitCode == 0,
                    "Cannot resolve Blue specification Git revision: " + output);
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot read the Blue specification Git revision", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GradleException(
                    "Interrupted while resolving the Blue specification Git revision",
                    exception);
        }
        require(expectedCommit.equals(output),
                "Blue specification Git revision mismatch (expected "
                        + expectedCommit + ", actual " + output + ")");
    }

    private static Path resolveLocked(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        require(resolved.startsWith(root) && !resolved.equals(root),
                "Unsafe Blue specification input path: " + relativePath);
        return resolved;
    }

    private static JsonNode readJson(Path path) {
        try {
            JsonNode value = JSON.readTree(path.toFile());
            require(value != null && value.isObject(),
                    "Blue specification input lock must be a JSON object");
            return value;
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot read Blue specification input lock: " + path, exception);
        }
    }

    private static JsonNode readYaml(Path path) {
        try {
            JsonNode value = YAML.readTree(path.toFile());
            require(value != null && value.isObject(),
                    "Blue specification manifest must be an object: " + path);
            return value;
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot read Blue specification manifest: " + path, exception);
        }
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode child = value.get(field);
        require(child != null && child.isTextual() && !child.asText().isEmpty(),
                field + " must be non-empty Text");
        return child.asText();
    }

    private static String bareIdentity(JsonNode files, String path) {
        return requiredText(files, path).substring("sha256:".length());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GradleException(message);
        }
    }
}
