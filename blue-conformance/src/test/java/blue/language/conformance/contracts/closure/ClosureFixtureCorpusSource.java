package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.GasSchedule;
import blue.language.processor.registry.RuntimeBlueIds;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict test source for either released resources or one complete staged package. */
final class ClosureFixtureCorpusSource {

    static final String PACKAGE_ROOT_PROPERTY =
            "blue.contracts.closurePackageRoot";
    static final String PACKAGE_ROOT_ENVIRONMENT =
            "BLUE_CONTRACTS_CLOSURE_PACKAGE_ROOT";
    private static final String CONTRACTS_SPECIFICATION_PATH =
            "../../specifications/blue-contracts-and-processor-specification-1.0.md";
    private static final String LANGUAGE_REFERENCE_PATH =
            "../../reference/blue-language-specification-1.0.md";

    private static final int MAX_STAGED_YAML_CODE_POINTS = 16 * 1024 * 1024;
    private static final ObjectMapper YAML = stagedYamlMapper();

    private final Path contractsRoot;
    private final List<ClosureFixtureInventory.Entry> entries;

    private ClosureFixtureCorpusSource(
            Path contractsRoot,
            List<ClosureFixtureInventory.Entry> entries) {
        this.contractsRoot = contractsRoot;
        this.entries = Collections.unmodifiableList(
                new ArrayList<ClosureFixtureInventory.Entry>(entries));
    }

    private static ObjectMapper stagedYamlMapper() {
        LoaderOptions options = new LoaderOptions();
        // The released loop trace is intentionally larger than SnakeYAML's
        // generic 3 MiB default.  This loader is test-only and every staged
        // byte length and digest is verified against the closed fixture
        // manifest before an external trace is parsed.
        options.setCodePointLimit(MAX_STAGED_YAML_CODE_POINTS);
        return new ObjectMapper(YAMLFactory.builder()
                .loaderOptions(options)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    static ClosureFixtureCorpusSource open() {
        String packageRoot = configured(
                PACKAGE_ROOT_PROPERTY, PACKAGE_ROOT_ENVIRONMENT);
        if (packageRoot != null) {
            return openCompletePackage(packageRoot);
        }
        return new ClosureFixtureCorpusSource(
                null, ClosureFixtureInventory.load());
    }

    private static ClosureFixtureCorpusSource openCompletePackage(
            String configured) {
        Path root = Paths.get(configured).toAbsolutePath().normalize();
        Path contractsRoot = root.resolve("conformance/contracts");
        require(Files.isRegularFile(root.resolve("package-manifest.yaml")),
                "staged root lacks package-manifest.yaml");
        require(Files.isRegularFile(root.resolve("MANIFEST.sha256")),
                "staged root lacks MANIFEST.sha256");
        require(Files.isDirectory(contractsRoot),
                "staged root lacks conformance/contracts");
        return new ClosureFixtureCorpusSource(
                contractsRoot, verifyCompleteContractsPackage(contractsRoot));
    }

    private static String configured(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isEmpty()) {
            value = System.getenv(environment);
        }
        return value == null || value.isEmpty() ? null : value;
    }

    List<ClosureFixtureInventory.Entry> entries() {
        return entries;
    }

    JsonNode executionFixture(ClosureFixtureInventory.Entry entry) {
        ObjectNode fixture = (ObjectNode) readFixture(entry).deepCopy();
        fixture.remove("expected");
        return fixture;
    }

    JsonNode expectedAfterExecution(
            ClosureFixtureInventory.Entry entry) {
        JsonNode fixture = readFixture(entry);
        ObjectNode expected = (ObjectNode) ClosureFixtureInventory
                .requiredObject(fixture, "expected").deepCopy();
        JsonNode traceFile = expected.get("gasTraceFile");
        if (traceFile != null && !traceFile.isNull()) {
            String path = ClosureFixtureInventory.requiredText(
                    expected, "gasTraceFile");
            JsonNode trace = readRelativeToClosure(path);
            require(entry.id().equals(
                            ClosureFixtureInventory.requiredText(
                                    trace, "fixture")),
                    "external gas trace fixture mismatch: " + entry.id());
            expected.set("gasTrace", ClosureFixtureInventory.requiredArray(
                    trace, "entries").deepCopy());
        }
        return expected;
    }

    private JsonNode readFixture(ClosureFixtureInventory.Entry entry) {
        if (contractsRoot == null) {
            return ClosureFixtureInventory.readFixture(entry);
        }
        return readYaml(contractsRoot.resolve("fixtures")
                .resolve(entry.path()));
    }

    private JsonNode readRelativeToClosure(String path) {
        if (contractsRoot == null) {
            return ClosureFixtureInventory.readCurrentResource(
                    ClosureFixtureInventory.FIXTURE_ROOT
                            + "closure/" + path);
        }
        return readYaml(contractsRoot.resolve("fixtures/closure")
                .resolve(path));
    }

    private static List<ClosureFixtureInventory.Entry>
            verifyCompleteContractsPackage(Path root) {
        JsonNode fixtures = readYaml(root.resolve("fixtures/manifest.yaml"));
        JsonNode registry = readYaml(root.resolve("registry/manifest.yaml"));
        JsonNode gas = readYaml(root.resolve("gas-manifest.yaml"));
        JsonNode release = readYaml(root.resolve("release-manifest.yaml"));

        String fixtureIdentity = ClosureFixtureInventory.requiredText(
                fixtures, "packageIdentity");
        require(RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY.equals(
                        ClosureFixtureInventory.requiredText(
                                registry, "packageIdentity")),
                "staged registry identity is not the production registry");
        require(GasSchedule.CONTRACTS_1_0_PACKAGE_IDENTITY.equals(
                        ClosureFixtureInventory.requiredText(
                                gas, "packageIdentity")),
                "staged gas identity is not the production gas schedule");
        require(fixtureIdentity.equals(ClosureFixtureInventory.requiredText(
                        registry, "fixturePackageIdentity")),
                "staged registry is not bound to the fixture package");

        JsonNode releaseRegistry = ClosureFixtureInventory.requiredObject(
                release, "contractsRegistry");
        JsonNode releaseGas = ClosureFixtureInventory.requiredObject(
                release, "gasManifest");
        JsonNode releaseFixtures = ClosureFixtureInventory.requiredObject(
                release, "fixturePackage");
        require(RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY.equals(
                        ClosureFixtureInventory.requiredText(
                                releaseRegistry, "packageIdentity")),
                "release manifest registry identity mismatch");
        require(GasSchedule.CONTRACTS_1_0_PACKAGE_IDENTITY.equals(
                        ClosureFixtureInventory.requiredText(
                                releaseGas, "packageIdentity")),
                "release manifest gas identity mismatch");
        require(fixtureIdentity.equals(ClosureFixtureInventory.requiredText(
                        releaseFixtures, "packageIdentity")),
                "release manifest fixture identity mismatch");

        verifyRegistryFiles(root.resolve("registry"), registry);
        List<ClosureFixtureInventory.Entry> result = verifyFixtureFiles(
                root.resolve("fixtures"), fixtures);
        require(result.size() == ClosureFixtureInventory.CLOSURE_FIXTURE_COUNT,
                "staged package does not contain exactly 93 closure fixtures");

        JsonNode specificationDocument = ClosureFixtureInventory.requiredObject(
                release, "specificationDocument");
        require(CONTRACTS_SPECIFICATION_PATH.equals(
                        ClosureFixtureInventory.requiredText(
                                specificationDocument, "path")),
                "staged release selects a different Contracts specification");
        String specificationIdentity = "sha256:" + sha256Hex(normalized(
                readBytes(root.resolve(CONTRACTS_SPECIFICATION_PATH))));
        require(specificationIdentity.equals("sha256:"
                        + ClosureFixtureInventory.requiredText(
                                specificationDocument, "sha256")),
                "staged Contracts specification digest mismatch");
        String gasManifestIdentity = "sha256:" + sha256Hex(normalized(
                readBytes(root.resolve("gas-manifest.yaml"))));
        JsonNode language = ClosureFixtureInventory.requiredObject(
                release, "languageDependency");
        String languageIdentity = "sha256:"
                + ClosureFixtureInventory.requiredText(
                        language, "specificationSha256");
        require(LANGUAGE_REFERENCE_PATH.equals(
                        ClosureFixtureInventory.requiredText(
                                language, "referencePath")),
                "staged release selects a different Language reference");
        Path languageReference = root.resolve(LANGUAGE_REFERENCE_PATH);
        String stagedLanguageIdentity = "sha256:"
                + sha256Hex(normalized(readBytes(languageReference)));
        require(languageIdentity.equals(stagedLanguageIdentity),
                "staged Language specification digest mismatch");
        String cyclicFinalizerIdentity =
                ClosureFixtureInventory.requiredText(
                        language, "cyclicSetFinalizerBaselineIdentity");
        String cyclicProofVerifierIdentity =
                ClosureFixtureInventory.requiredText(
                        language, "cyclicSetProofVerifierBaselineIdentity");
        require(ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY.equals(
                        cyclicFinalizerIdentity),
                "staged cyclic finalizer is not the production identity");
        require(ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY.equals(
                        cyclicProofVerifierIdentity),
                "staged cyclic proof verifier is not the production identity");
        int executableEnvironments = 0;
        for (ClosureFixtureInventory.Entry entry : result) {
            if ("limit-micro".equals(entry.operation())) {
                continue;
            }
            executableEnvironments++;
            JsonNode environment = ClosureFixtureInventory.requiredObject(
                    ClosureFixtureInventory.requiredObject(
                            readYaml(root.resolve("fixtures")
                                    .resolve(entry.path())), "input"),
                    "environment");
            require(languageIdentity.equals(
                            ClosureFixtureInventory.requiredText(
                                    environment,
                                    "blueLanguageSpecificationIdentity")),
                    entry.id() + " language identity mismatch");
            require(specificationIdentity.equals(
                            ClosureFixtureInventory.requiredText(
                                    environment,
                                    "contractsSpecificationIdentity")),
                    entry.id() + " Contracts identity mismatch");
            require(RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY.equals(
                            ClosureFixtureInventory.requiredText(
                                    environment, "runtimeRegistryIdentity")),
                    entry.id() + " registry identity mismatch");
            require(gasManifestIdentity.equals(
                            ClosureFixtureInventory.requiredText(
                                    environment, "gasManifestIdentity")),
                    entry.id() + " gas manifest digest mismatch");
            require(cyclicFinalizerIdentity.equals(
                            ClosureFixtureInventory.requiredText(
                                    environment, "cyclicFinalizerIdentity")),
                    entry.id() + " cyclic finalizer identity mismatch");
            require(cyclicProofVerifierIdentity.equals(
                            ClosureFixtureInventory.requiredText(
                                    environment,
                                    "cyclicProofVerifierIdentity")),
                    entry.id() + " cyclic proof verifier identity mismatch");
        }
        require(executableEnvironments == 62,
                "staged executable closure fixture count mismatch");
        return result;
    }

    private static List<ClosureFixtureInventory.Entry> verifyFixtureFiles(
            Path root,
            JsonNode manifest) {
        require(ClosureFixtureInventory.requiredLong(
                        manifest, "closureFixtureCount")
                        == ClosureFixtureInventory.CLOSURE_FIXTURE_COUNT,
                "staged closure fixture count mismatch");
        ArrayList<ClosureFixtureInventory.Entry> result =
                new ArrayList<ClosureFixtureInventory.Entry>();
        Set<String> ids = new LinkedHashSet<String>();
        for (JsonNode item : ClosureFixtureInventory.requiredArray(
                manifest, "files")) {
            String path = ClosureFixtureInventory.requiredText(item, "path");
            verifyManifestFile(root, item, path);
            if (!"closure-fixture".equals(item.path("role").asText())) {
                continue;
            }
            JsonNode fixture = readYaml(root.resolve(path));
            String id = ClosureFixtureInventory.requiredText(fixture, "id");
            require(ids.add(id), "duplicate staged fixture id: " + id);
            result.add(new ClosureFixtureInventory.Entry(
                    id,
                    path,
                    ClosureFixtureInventory.requiredText(
                            fixture, "operation"),
                    textList(ClosureFixtureInventory.requiredArray(
                            fixture, "vectors")),
                    ClosureFixtureInventory.requiredText(item, "sha256"),
                    ClosureFixtureInventory.requiredLong(item, "bytes")));
        }
        return result;
    }

    private static void verifyRegistryFiles(Path root, JsonNode manifest) {
        for (JsonNode item : ClosureFixtureInventory.requiredArray(
                manifest, "entries")) {
            String path = ClosureFixtureInventory.requiredText(item, "path");
            byte[] bytes = normalized(readBytes(root.resolve(path)));
            require(ClosureFixtureInventory.requiredText(item, "sha256")
                            .equals(sha256Hex(bytes)),
                    "staged registry digest mismatch: " + path);
        }
    }

    private static void verifyManifestFile(
            Path root,
            JsonNode item,
            String path) {
        require(!path.contains(".."), "unsafe staged manifest path: " + path);
        byte[] bytes = normalized(readBytes(root.resolve(path)));
        require(ClosureFixtureInventory.requiredLong(item, "bytes")
                        == bytes.length,
                "staged fixture byte length mismatch: " + path);
        require(ClosureFixtureInventory.requiredText(item, "sha256")
                        .equals(sha256Hex(bytes)),
                "staged fixture digest mismatch: " + path);
    }

    private static List<String> textList(JsonNode values) {
        ArrayList<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            require(value.isTextual() && !value.asText().isEmpty(),
                    "fixture vector must be non-empty Text");
            result.add(value.asText());
        }
        return result;
    }

    private static JsonNode readYaml(Path path) {
        try {
            Object value = YAML.readValue(path.toFile(), Object.class);
            return UncheckedObjectMapper.JSON_MAPPER.valueToTree(value);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read staged YAML: " + path, exception);
        }
    }

    private static byte[] readBytes(Path path) {
        try (InputStream input = Files.newInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read staged file: " + path, exception);
        }
    }

    private static byte[] normalized(byte[] source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
        for (int index = 0; index < source.length; index++) {
            int value = source[index] & 0xff;
            if (value == '\r') {
                if (index + 1 < source.length
                        && (source[index + 1] & 0xff) == '\n') {
                    index++;
                }
                output.write('\n');
            } else {
                output.write(value);
            }
        }
        return output.toByteArray();
    }

    private static String sha256Hex(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format(
                        "%02x", Integer.valueOf(item & 0xff)));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
