package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.wire.BlueLanguageConstants;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ClosureFixtureInventory {

    private static final int MAX_VERIFIED_FIXTURE_YAML_CODE_POINTS =
            16 * 1024 * 1024;

    static final String ROOT = "blue-contracts-closure-1.0/";
    static final String FIXTURE_ROOT = ROOT + "fixtures/";
    static final String MANIFEST = FIXTURE_ROOT + "manifest.yaml";
    static final String PACKAGE_IDENTITY =
            "sha256:6030839a94f98161dc35dee7665ed2a87d8b109710235990b695ccf2834094c8";
    static final int CLOSURE_FIXTURE_COUNT = 93;
    static final int EXTERNAL_PROCESS_FIXTURE_COUNT = 30;
    static final int MANAGED_REVISION_PROCESS_FIXTURE_COUNT = 5;
    static final String C_CLO_34 = "c-clo-34-separate-document-steps";

    private static final ObjectMapper YAML = verifiedYamlMapper();

    private ClosureFixtureInventory() {
    }

    private static ObjectMapper verifiedYamlMapper() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(MAX_VERIFIED_FIXTURE_YAML_CODE_POINTS);
        return new ObjectMapper(YAMLFactory.builder()
                .loaderOptions(options)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    static List<Entry> load() {
        JsonNode manifest = readYaml(MANIFEST);
        requireText(manifest, "fixturePackage", "blue-contracts-conformance");
        requireText(manifest, "specificationVersion", "1.0");
        requireText(manifest, "packageIdentity", PACKAGE_IDENTITY);
        if (requiredLong(manifest, "closureFixtureCount")
                != CLOSURE_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Unexpected closure fixture count");
        }
        JsonNode files = requiredArray(manifest, "files");
        ArrayList<Entry> entries = new ArrayList<Entry>();
        Set<String> paths = new LinkedHashSet<String>();
        Set<String> ids = new LinkedHashSet<String>();
        for (JsonNode file : files) {
            if (!"closure-fixture".equals(file.path("role").asText())) {
                continue;
            }
            String path = requiredText(file, "path");
            if (!path.startsWith("closure/")
                    || path.contains("..")
                    || !paths.add(path)) {
                throw new IllegalStateException(
                        "Invalid or duplicate closure fixture path: " + path);
            }
            byte[] bytes = normalizeLineEndings(readBytes(FIXTURE_ROOT + path));
            long declaredBytes = requiredLong(file, "bytes");
            if (declaredBytes != bytes.length) {
                throw new IllegalStateException(
                        "Closure fixture byte length mismatch: " + path);
            }
            String digest = requiredText(file, "sha256");
            if (!digest.equals(sha256Hex(bytes))) {
                throw new IllegalStateException(
                        "Closure fixture digest mismatch: " + path);
            }
            JsonNode fixture = readYaml(FIXTURE_ROOT + path);
            requireText(
                    fixture,
                    BlueLanguageConstants.OBJECT_SCHEMA,
                    "blue-contracts-closure-fixture/1.0");
            String id = requiredText(fixture, "id");
            if (!ids.add(id)) {
                throw new IllegalStateException(
                        "Duplicate closure fixture id: " + id);
            }
            List<String> vectors = textList(requiredArray(file, "vectors"));
            if (!vectors.equals(textList(requiredArray(fixture, "vectors")))) {
                throw new IllegalStateException(
                        "Closure fixture vectors disagree with manifest: " + path);
            }
            entries.add(new Entry(
                    id,
                    path,
                    requiredText(fixture, "operation"),
                    vectors,
                    digest,
                    declaredBytes));
        }
        if (entries.size() != CLOSURE_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Manifest does not inventory exactly 93 closure fixtures");
        }
        return Collections.unmodifiableList(entries);
    }

    static Entry requireById(String id) {
        for (Entry entry : load()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        throw new IllegalArgumentException("Unknown closure fixture: " + id);
    }

    /**
     * Selects the released external PROCESS_CLOSURE inventory from operation
     * and input cause only; expected result trees are never consulted.
     */
    static List<Entry> externalProcessFixtures() {
        ArrayList<Entry> result = new ArrayList<Entry>();
        for (Entry entry : load()) {
            if (!"process-closure".equals(entry.operation())) {
                continue;
            }
            JsonNode fixture = readFixture(entry);
            JsonNode input = requiredObject(fixture, "input");
            JsonNode cause = requiredObject(input, "cause");
            if ("external".equals(requiredText(cause, "kind"))) {
                result.add(entry);
            }
        }
        if (result.size() != EXTERNAL_PROCESS_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Unexpected external PROCESS_CLOSURE fixture count: "
                            + result.size());
        }
        return Collections.unmodifiableList(result);
    }

    /** Selects the five released one-receipt managed-revision invocations. */
    static List<Entry> managedRevisionProcessFixtures() {
        ArrayList<Entry> result = new ArrayList<Entry>();
        for (Entry entry : load()) {
            if (!"process-closure".equals(entry.operation())) {
                continue;
            }
            JsonNode cause = requiredObject(
                    requiredObject(readFixture(entry), "input"),
                    "cause");
            if ("managed-revision".equals(requiredText(cause, "kind"))) {
                result.add(entry);
            }
        }
        if (result.size() != MANAGED_REVISION_PROCESS_FIXTURE_COUNT) {
            throw new IllegalStateException(
                    "Unexpected managed-revision PROCESS_CLOSURE fixture "
                            + "count: " + result.size());
        }
        return Collections.unmodifiableList(result);
    }

    static JsonNode readFixture(Entry entry) {
        return readYaml(FIXTURE_ROOT + entry.path());
    }

    /** Reads one exact resource from the packaged conformance environment. */
    static JsonNode readCurrentResource(String resource) {
        return readYaml(resource);
    }

    private static JsonNode readYaml(String resource) {
        try (InputStream input = resource(resource)) {
            Object value = YAML.readValue(input, Object.class);
            return UncheckedObjectMapper.JSON_MAPPER.valueToTree(value);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to parse closure resource: " + resource,
                    exception);
        }
    }

    private static byte[] readBytes(String resource) {
        try (InputStream input = resource(resource);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read closure resource: " + resource,
                    exception);
        }
    }

    private static InputStream resource(String path) {
        InputStream input = ClosureFixtureInventory.class
                .getClassLoader().getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException(
                    "Missing closure conformance resource: " + path);
        }
        return input;
    }

    private static byte[] normalizeLineEndings(byte[] source) {
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
                result.append(String.format("%02x", Integer.valueOf(item & 0xff)));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static JsonNode requiredObject(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || !child.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return child;
    }

    static JsonNode requiredArray(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || !child.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return child;
    }

    static String requiredText(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || !child.isTextual() || child.asText().isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-empty Text");
        }
        return child.asText();
    }

    private static void requireText(
            JsonNode value,
            String field,
            String expected) {
        String actual = requiredText(value, field);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    field + " mismatch: " + actual);
        }
    }

    static long requiredLong(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || !child.isIntegralNumber() || !child.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an Integer");
        }
        return child.longValue();
    }

    static boolean requiredBoolean(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || !child.isBoolean()) {
            throw new IllegalArgumentException(field + " must be Boolean");
        }
        return child.booleanValue();
    }

    private static List<String> textList(JsonNode values) {
        ArrayList<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isEmpty()) {
                throw new IllegalArgumentException(
                        "List item must be non-empty Text");
            }
            result.add(value.asText());
        }
        return Collections.unmodifiableList(result);
    }

    static final class Entry {
        private final String id;
        private final String path;
        private final String operation;
        private final List<String> vectors;
        private final String sha256;
        private final long bytes;

        Entry(
                String id,
                String path,
                String operation,
                List<String> vectors,
                String sha256,
                long bytes) {
            this.id = id;
            this.path = path;
            this.operation = operation;
            this.vectors = Collections.unmodifiableList(
                    new ArrayList<String>(vectors));
            this.sha256 = sha256;
            this.bytes = bytes;
        }

        String id() {
            return id;
        }

        String path() {
            return path;
        }

        String operation() {
            return operation;
        }

        List<String> vectors() {
            return vectors;
        }

        String sha256() {
            return sha256;
        }

        long bytes() {
            return bytes;
        }
    }
}
