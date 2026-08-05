package blue.language.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Shared deterministic readers used by semantic-baseline capture and
 * verification.
 *
 * <p>The helpers deliberately operate on public reports and exact fixture
 * files. They do not call processor internals or reinterpret gas and locality
 * evidence.</p>
 */
final class SemanticBaselineSupport {

    static final ObjectMapper JSON = new ObjectMapper();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final String BASELINE_SCHEMA =
            "blue-language-java-semantic-baseline/1.0";
    static final String VERIFICATION_SCHEMA =
            "blue-language-java-semantic-baseline-verification/1.0";
    static final String RELEASE_CONFORMANCE_SCHEMA =
            "blue-language-java-release-conformance-report/1.0";
    static final String RELEASE_EVIDENCE_SCHEMA =
            "blue-language-java-release-evidence/1.4";
    static final String API_INVENTORY_SCHEMA =
            "blue-language-java-api-inventory/1.0";
    static final String LOCALITY_EVIDENCE_SCHEMA =
            "blue-language-locality-evidence/1.0";
    static final String SHA_256_PREFIX = "sha256:";
    static final int LANGUAGE_FIXTURE_COUNT = 153;
    static final int CONTRACTS_FIXTURE_COUNT = 154;
    static final int GAS_FIXTURE_COUNT = 58;
    static final int RELEASE_FIXTURE_COUNT =
            LANGUAGE_FIXTURE_COUNT + CONTRACTS_FIXTURE_COUNT;
    static final List<String> ARTIFACT_KEYS = Collections.unmodifiableList(
            Arrays.asList(
                    "jar",
                    "sourcesJar",
                    "javadocJar",
                    "sourceRelease"));
    static final Set<String> LOCALITY_EVIDENCE_FILES =
            Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
                    "deep-graph-matrix.json",
                    "fragmented-matrix.json",
                    "root-only-event.json")));
    static final Set<String> BASELINE_LOCALITY_TEST_METHODS =
            Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
                    "shouldVerifyExactRootAndEventFragmentsHaveIdenticalSemanticsAcrossMatrix",
                    "shouldVerifyDeepGraphHasSemanticParityAndPhysicalLocalityAcrossRepresentationsAndProviders",
                    "shouldSplitOnlySelectedCutsAndTheirAncestorSpine",
                    "shouldVerifySelectedBodyUnavailableSuspendsWithoutPortableGasAndRetryMatches")));

    private SemanticBaselineSupport() {
    }

    /** Reads one required JSON document. */
    static JsonNode readJson(Path path) throws IOException {
        requireRegularFile(path, "JSON document");
        JsonNode value = JSON.readTree(path.toFile());
        if (value == null) {
            throw new IllegalStateException(
                    "Required JSON document is empty: " + path);
        }
        return value;
    }

    /** Reads one required YAML fixture as an exact JSON-compatible tree. */
    static JsonNode readYaml(Path path) throws IOException {
        requireRegularFile(path, "YAML fixture");
        JsonNode value = YAML.readTree(path.toFile());
        if (value == null) {
            throw new IllegalStateException(
                    "Required YAML fixture is empty: " + path);
        }
        return value;
    }

    /** Returns one non-null value at an RFC 6901 pointer. */
    static JsonNode required(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalStateException(
                    "Missing required JSON value: " + pointer);
        }
        return value;
    }

    /** Returns one non-empty textual value at an RFC 6901 pointer. */
    static String text(JsonNode node, String pointer) {
        String value = required(node, pointer).asText();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "Empty required JSON text: " + pointer);
        }
        return value;
    }

    /** Returns one exact integer value at an RFC 6901 pointer. */
    static int intValue(JsonNode node, String pointer) {
        JsonNode value = required(node, pointer);
        if (!value.canConvertToInt()) {
            throw new IllegalStateException(
                    "Expected integer JSON value: " + pointer);
        }
        return value.asInt();
    }

    /** Calculates the lowercase SHA-256 identity of one exact file. */
    static String sha256(Path path) throws IOException {
        requireRegularFile(path, "identity input");
        return SHA_256_PREFIX + sha256Hex(Files.readAllBytes(path));
    }

    /** Calculates the bare lowercase SHA-256 digest used by spec reports. */
    static String sha256Digest(Path path) throws IOException {
        requireRegularFile(path, "digest input");
        return sha256Hex(Files.readAllBytes(path));
    }

    /** Requires one lowercase SHA-256 identity. */
    static String requireIdentity(String value, String label) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    label + " is not a SHA-256 identity");
        }
        return value;
    }

    /** Requires one exact lowercase Git object identity. */
    static String requireSourceRevision(String value, String label) {
        if (value == null
                || !value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalStateException(
                    label + " is not a Git source revision");
        }
        return value;
    }

    /** Requires exact object or array equality. */
    static void requireEquals(
            String label,
            Object expected,
            Object actual) {
        if (expected instanceof byte[] && actual instanceof byte[]) {
            if (!Arrays.equals((byte[]) expected, (byte[]) actual)) {
                throw new IllegalStateException(label + " differs");
            }
            return;
        }
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(label + " differs: expected="
                    + expected + ", actual=" + actual);
        }
    }

    /**
     * Projects all gas-role fixtures named by release conformance into an
     * ordered oracle containing their exact {@code expected} subtrees.
     */
    static ArrayNode gasFixtureOracle(
            JsonNode conformance,
            Path fixtureRoot) throws IOException {
        Path normalizedRoot = fixtureRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IllegalStateException(
                    "Contracts fixture root is not a directory: "
                            + fixtureRoot);
        }

        List<GasFixture> fixtures = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        Set<String> paths = new TreeSet<>();
        JsonNode results = required(conformance, "/fixtures");
        if (!results.isArray()) {
            throw new IllegalStateException(
                    "Release conformance fixtures must be an array");
        }
        for (JsonNode result : results) {
            if (!"contracts".equals(result.path("suite").asText())
                    || !"gas-fixture".equals(
                    result.path("role").asText())) {
                continue;
            }
            requireEquals(
                    "gas fixture status " + result.path("id").asText(),
                    "PASS",
                    result.path("status").asText());
            String id = requiredText(result, "id");
            String relativePath = requiredText(result, "path");
            if (!ids.add(id)) {
                throw new IllegalStateException(
                        "Duplicate gas fixture id: " + id);
            }
            if (!paths.add(relativePath)) {
                throw new IllegalStateException(
                        "Duplicate gas fixture path: " + relativePath);
            }
            Path fixturePath = normalizedRoot.resolve(relativePath)
                    .normalize();
            if (!fixturePath.startsWith(normalizedRoot)) {
                throw new IllegalStateException(
                        "Gas fixture escapes its fixture root: "
                                + relativePath);
            }
            JsonNode fixture = readYaml(fixturePath);
            requireEquals(
                    "gas fixture id " + relativePath,
                    id,
                    requiredText(fixture, "id"));
            JsonNode expected = fixture.path("expected");
            if (expected.isMissingNode() || expected.isNull()) {
                throw new IllegalStateException(
                        "Gas fixture has no expected subtree: "
                                + relativePath);
            }
            fixtures.add(new GasFixture(
                    result,
                    relativePath,
                    expected));
        }
        requireEquals(
                "Contracts gas fixture count",
                GAS_FIXTURE_COUNT,
                fixtures.size());
        Collections.sort(fixtures, Comparator.comparing(
                GasFixture::path));

        ArrayNode oracle = JSON.createArrayNode();
        for (GasFixture fixture : fixtures) {
            oracle.add(fixture.toJson());
        }
        return oracle;
    }

    /**
     * Reads exact locality JSON payloads from files or recursive directories.
     * Paths and payloads are returned in deterministic path order.
     */
    static ArrayNode localityPayloads(List<Path> inputs)
            throws IOException {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalStateException(
                    "At least one locality JSON file or directory is required");
        }
        Map<String, Path> files = new TreeMap<>();
        for (Path input : inputs) {
            collectLocalityFiles(input, files);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException(
                    "No locality JSON payloads were supplied");
        }
        Set<String> fileNames = new TreeSet<>();
        for (Path path : files.values()) {
            fileNames.add(path.getFileName().toString());
        }
        requireEquals(
                "complete locality evidence file set",
                LOCALITY_EVIDENCE_FILES,
                fileNames);

        ArrayNode payloads = JSON.createArrayNode();
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            JsonNode localityPayload = readJson(entry.getValue());
            requireEquals(
                    "locality evidence schema " + entry.getKey(),
                    LOCALITY_EVIDENCE_SCHEMA,
                    text(localityPayload, "/schema"));
            ObjectNode payload = JSON.createObjectNode();
            payload.put("path", entry.getKey());
            payload.put("identity", sha256(entry.getValue()));
            payload.set("payload", localityPayload);
            payloads.add(payload);
        }
        return payloads;
    }

    /** Returns exact artifact identities from fragmented release evidence. */
    static ObjectNode artifactIdentities(JsonNode evidence) {
        ObjectNode identities = JSON.createObjectNode();
        for (String key : ARTIFACT_KEYS) {
            String identity = text(
                    evidence,
                    "/artifacts/" + key + "/identity");
            identities.put(
                    key,
                    requireIdentity(identity, "artifact " + key));
        }
        return identities;
    }

    /** Returns exact source revision and source-input identity evidence. */
    static ObjectNode sourceIdentities(JsonNode evidence) {
        String sourceCommit = requireSourceRevision(
                text(evidence, "/source/commit"),
                "fragmented evidence source commit");
        String cleanBuildCommit = requireSourceRevision(
                text(evidence, "/execution/cleanBuild/sourceCommit"),
                "clean-build source commit");
        requireEquals(
                "fragmented evidence source commit",
                sourceCommit,
                cleanBuildCommit);
        String sourceInputIdentity = text(
                evidence,
                "/execution/cleanBuild/sourceInputIdentity");
        ObjectNode source = JSON.createObjectNode();
        source.put("commit", sourceCommit);
        source.put(
                "sourceInputIdentity",
                requireIdentity(
                        sourceInputIdentity,
                        "source input identity"));
        return source;
    }

    /** Returns exact locality source identities from fragmented evidence. */
    static JsonNode localitySourceFiles(JsonNode evidence) {
        JsonNode sourceFiles = required(
                evidence,
                "/representationAndLocality/sourceFiles");
        if (!sourceFiles.isArray() || sourceFiles.size() == 0) {
            throw new IllegalStateException(
                    "Fragmented evidence has no locality source identities");
        }
        Set<String> paths = new TreeSet<>();
        for (JsonNode sourceFile : sourceFiles) {
            String path = requiredText(sourceFile, "path");
            if (!paths.add(path)) {
                throw new IllegalStateException(
                        "Duplicate locality source path: " + path);
            }
            requireIdentity(
                    requiredText(sourceFile, "identity"),
                    "locality source " + path);
        }
        return sourceFiles.deepCopy();
    }

    /** Returns exact required locality test records from fragmented evidence. */
    static JsonNode localityRequiredTests(JsonNode evidence) {
        JsonNode requiredTests = required(
                evidence,
                "/representationAndLocality/requiredTestCases");
        if (!requiredTests.isArray() || requiredTests.size() == 0) {
            throw new IllegalStateException(
                    "Fragmented evidence has no required locality tests");
        }
        Set<String> identities = new TreeSet<>();
        ArrayNode baselineTests = JSON.createArrayNode();
        for (JsonNode requiredTest : requiredTests) {
            String identity = requiredTestIdentity(requiredTest);
            if (!identities.add(identity)) {
                throw new IllegalStateException(
                        "Duplicate required locality test: " + identity);
            }
            if (!requiredTest.path("executed").asBoolean()
                    || !requiredTest.path("passed").asBoolean()) {
                throw new IllegalStateException(
                        "Required locality test did not pass: " + identity);
            }
            if (BASELINE_LOCALITY_TEST_METHODS.contains(identity)) {
                baselineTests.add(requiredTest.deepCopy());
            }
        }
        Set<String> baselineIdentities = new TreeSet<>();
        for (JsonNode baselineTest : baselineTests) {
            baselineIdentities.add(requiredTestIdentity(baselineTest));
        }
        requireEquals(
                "complete baseline locality test set",
                BASELINE_LOCALITY_TEST_METHODS,
                baselineIdentities);
        return baselineTests;
    }

    /** Converts trailing CLI arguments into normalized locality input paths. */
    static List<Path> localityArguments(String[] args, int offset) {
        List<Path> paths = new ArrayList<>();
        for (int index = offset; index < args.length; index++) {
            paths.add(Paths.get(args[index]));
        }
        return paths;
    }

    /** Writes one JSON document with deterministic indentation and newline. */
    static void writeJson(Path path, JsonNode value) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] content = JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(value);
        Files.write(path, appendNewline(content));
    }

    private static void collectLocalityFiles(
            Path input,
            Map<String, Path> files) throws IOException {
        Path normalized = input.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) {
            requireJsonFile(normalized);
            addLocalityFile(normalized, files);
            return;
        }
        if (!Files.isDirectory(normalized)) {
            throw new IllegalStateException(
                    "Locality input does not exist: " + input);
        }
        try (Stream<Path> stream = Files.walk(normalized)) {
            for (Path candidate : (Iterable<Path>) stream
                    .filter(Files::isRegularFile)
                    .filter(SemanticBaselineSupport::isJsonFile)
                    ::iterator) {
                addLocalityFile(candidate.toAbsolutePath().normalize(), files);
            }
        }
    }

    private static void addLocalityFile(
            Path path,
            Map<String, Path> files) {
        String logicalPath = logicalPath(path);
        Path previous = files.put(logicalPath, path);
        if (previous != null) {
            throw new IllegalStateException(
                    "Duplicate locality payload path: " + logicalPath);
        }
    }

    private static String logicalPath(Path path) {
        Path workingDirectory = Paths.get("")
                .toAbsolutePath()
                .normalize();
        Path normalized = path.toAbsolutePath().normalize();
        Path logical = normalized.startsWith(workingDirectory)
                ? workingDirectory.relativize(normalized)
                : normalized;
        return logical.toString().replace('\\', '/');
    }

    private static boolean isJsonFile(Path path) {
        return path.getFileName().toString()
                .toLowerCase(Locale.ROOT)
                .endsWith(".json");
    }

    private static void requireJsonFile(Path path) {
        if (!isJsonFile(path)) {
            throw new IllegalStateException(
                    "Locality evidence must be JSON: " + path);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalStateException(
                    "Missing required JSON text field: " + field);
        }
        return value.asText();
    }

    private static String requiredTestIdentity(JsonNode requiredTest) {
        String testMethod = requiredTest.path("testMethod").asText();
        if (!testMethod.isEmpty()) {
            return testMethod;
        }
        String className = requiredTest.path("className").asText();
        String methodName = requiredTest.path("methodName").asText();
        if (!className.isEmpty() && !methodName.isEmpty()) {
            return className + "#" + methodName;
        }
        String name = requiredTest.path("name").asText();
        if (!name.isEmpty()) {
            return name;
        }
        throw new IllegalStateException(
                "Required locality test has no stable identity: "
                        + requiredTest);
    }

    private static void requireRegularFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "Missing required " + label + ": " + path);
        }
    }

    private static String sha256Hex(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] appendNewline(byte[] content) {
        byte[] terminated = Arrays.copyOf(content, content.length + 1);
        terminated[content.length] = (byte) '\n';
        return terminated;
    }

    /** Exact conformance metadata and expected subtree for one gas fixture. */
    private static final class GasFixture {
        private final JsonNode result;
        private final String path;
        private final JsonNode expected;

        private GasFixture(
                JsonNode result,
                String path,
                JsonNode expected) {
            this.result = result;
            this.path = path;
            this.expected = expected;
        }

        private String path() {
            return path;
        }

        private ObjectNode toJson() {
            ObjectNode fixture = JSON.createObjectNode();
            copyRequired(fixture, result, "resultKey");
            copyRequired(fixture, result, "id");
            fixture.put("path", path);
            copyRequired(fixture, result, "role");
            copyRequired(fixture, result, "category");
            copyRequired(fixture, result, "operation");
            copyRequired(fixture, result, "vectors");
            fixture.set("expected", expected.deepCopy());
            return fixture;
        }

        private static void copyRequired(
                ObjectNode target,
                JsonNode source,
                String field) {
            JsonNode value = source.path(field);
            if (value.isMissingNode() || value.isNull()) {
                throw new IllegalStateException(
                        "Gas fixture result is missing " + field);
            }
            target.set(field, value.deepCopy());
        }
    }
}
