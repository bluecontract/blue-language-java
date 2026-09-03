package blue.language.conformance.contracts;

import blue.language.model.wire.BlueLanguageConstants;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Exact allow-list of observable conformance projections.
 *
 * <p>The catalog is loaded once per instance from a closed packaged resource.
 * It validates assertion paths only and never reads or alters execution
 * output.</p>
 */
final class ContractsProjectionCatalog {

    /** Classpath location of the closed projection allow-list. */
    public static final String RESOURCE =
            "blue-contracts-1.0/fixtures/projection-catalog.yaml";

    private final Set<String> paths;

    /**
     * Loads and validates the bundled projection catalog.
     *
     * @throws IllegalStateException if the catalog is absent or malformed
     */
    public ContractsProjectionCatalog() {
        this.paths = Collections.unmodifiableSet(load());
    }

    /**
     * Returns declared observable projection paths.
     *
     * @return immutable paths in catalog order
     */
    public Set<String> paths() {
        return paths;
    }

    /**
     * Verifies that every actual and expected-projection path used by a
     * fixture assertion is declared in the catalog.
     *
     * @param fixture fixture whose assertion paths are checked
     * @throws IllegalArgumentException when an assertion references an
     *         undeclared path
     */
    public void validateFixtureAssertions(JsonNode fixture) {
        JsonNode assertions = fixture.path(ContractsFixtureConstants.Field.EXPECTED).path(ContractsFixtureConstants.Field.ASSERTIONS);
        if (!assertions.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode assertion : assertions) {
            String base = "$.expected.assertions[" + index++ + "]";
            String actual = assertion.path(ContractsFixtureConstants.Field.ACTUAL).asText(null);
            requireDeclared(actual, base + ".actual");
            if (assertion.has(ContractsFixtureConstants.Field.EXPECTED_PROJECTION)) {
                requireDeclared(assertion.path(ContractsFixtureConstants.Field.EXPECTED_PROJECTION).asText(null),
                        base + ".expectedProjection");
            }
        }
    }

    /**
     * Rejects a projection path that is not part of the closed allow-list.
     *
     * @param path projection path to check
     * @param source diagnostic location that declared the path
     * @throws IllegalArgumentException when {@code path} is null or undeclared
     */
    public void requireDeclared(String path, String source) {
        String declaredPath = withoutVariantPrefix(path);
        if (declaredPath == null || !paths.contains(declaredPath)) {
            throw new IllegalArgumentException(
                    source + ": undeclared Contracts conformance projection " + path);
        }
    }

    private static String withoutVariantPrefix(String path) {
        if (path == null || !path.startsWith("variants.")) {
            return path;
        }
        int nameStart = "variants.".length();
        int nameEnd = path.indexOf('.', nameStart);
        if (nameEnd <= nameStart
                || !path.substring(nameStart, nameEnd)
                .matches("[a-z0-9][a-z0-9-]*")) {
            return null;
        }
        return path.substring(nameEnd + 1);
    }

    private static Set<String> load() {
        ObjectMapper mapper = new ObjectMapper(
                YAMLFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build());
        try (InputStream input = ContractsProjectionCatalog.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing Contracts projection catalog: " + RESOURCE);
            }
            JsonNode catalog = mapper.readTree(input);
            if (!catalog.isObject()
                    || catalog.size() != 2
                    || !"blue-contracts-projection-catalog/2.0".equals(
                    catalog.path(BlueLanguageConstants.OBJECT_SCHEMA).asText())) {
                throw new IllegalStateException("Invalid Contracts projection catalog envelope");
            }
            JsonNode entries = catalog.get("entries");
            if (entries == null || !entries.isArray()) {
                throw new IllegalStateException("Contracts projection catalog entries must be a list");
            }
            Set<String> paths = new LinkedHashSet<>();
            int index = 0;
            for (JsonNode entry : entries) {
                String source = "projection-catalog.entries[" + index++ + "]";
                if (!entry.isObject()
                        || entry.size() < 2
                        || entry.size() > 3) {
                    throw new IllegalStateException(
                            source + " must contain path and definition, "
                                    + "with optional type");
                }
                Set<String> fields = new LinkedHashSet<>();
                for (Iterator<String> it = entry.fieldNames(); it.hasNext(); ) {
                    fields.add(it.next());
                }
                if (!fields.equals(
                        set("path", "definition"))
                        && !fields.equals(
                        set("path", BlueLanguageConstants.OBJECT_TYPE, "definition"))) {
                    throw new IllegalStateException(source + " has unknown fields");
                }
                String path = requiredText(entry, "path", source);
                if (entry.has(BlueLanguageConstants.OBJECT_TYPE)) {
                    String type = requiredText(
                            entry, BlueLanguageConstants.OBJECT_TYPE, source);
                    if (!set(
                            "scalar-or-node",
                            "integer",
                            "boolean",
                            BlueLanguageConstants.OBJECT_VALUE,
                            "sequence-or-value")
                            .contains(type)) {
                        throw new IllegalStateException(
                                source
                                        + " has unsupported projection type "
                                        + type);
                    }
                }
                requiredText(entry, "definition", source);
                if (!paths.add(path)) {
                    throw new IllegalStateException("Duplicate Contracts projection path: " + path);
                }
            }
            return paths;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Contracts projection catalog", ex);
        }
    }

    private static String requiredText(JsonNode object, String field, String source) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalStateException(source + "." + field + " must be non-empty text");
        }
        return value.asText();
    }

    private static Set<String> set(String... values) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return result;
    }
}
