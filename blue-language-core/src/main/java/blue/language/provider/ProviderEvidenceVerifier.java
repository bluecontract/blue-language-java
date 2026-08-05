package blue.language.provider;

import blue.language.model.wire.SchemaPropertyConstants;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodeWireForm;
import blue.language.codec.jackson.UncheckedObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static blue.language.model.wire.SchemaPropertyConstants.KEY_ENUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAX_FIELDS;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAX_ITEMS;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAX_LENGTH;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MAXIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MIN_FIELDS;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MIN_ITEMS;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MIN_LENGTH;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MINIMUM;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_MULTIPLE_OF;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_REQUIRED;
import static blue.language.model.wire.SchemaPropertyConstants.KEY_UNIQUE_ITEMS;

/**
 * Verifies provider content under an explicitly selected ingestion mode.
 *
 * <p>Direct BlueId input is hashed as supplied. Source-document input is
 * accepted only when its release, registry, preprocessing configuration, and
 * exact source evidence all match the active runtime.</p>
 */
public final class ProviderEvidenceVerifier {

    private static final String FIELD_LANGUAGE_RELEASE_IDENTITY =
            "languageReleaseIdentity";
    private static final String FIELD_LANGUAGE_VERSION =
            "languageVersion";
    private static final String FIELD_CANONICAL_REGISTRY_IDENTITY =
            "canonicalRegistryIdentity";
    private static final String FIELD_PREPROCESSING_ENVIRONMENT_IDENTITY =
            "preprocessingEnvironmentIdentity";
    private static final String FIELD_PREPROCESSING_ALIASES =
            "preprocessingAliases";
    private static final String FIELD_ENVIRONMENT_IMPORTS =
            "environmentImports";
    private static final String FIELD_PROVIDER_DOMAIN_IDENTITY =
            "providerDomainIdentity";
    private static final String FIELD_PROVIDER_MODE =
            "providerMode";
    private static final String FIELD_SOURCE_CONTENT_STRATEGY =
            "sourceContentStrategyIdentity";
    private static final String FIELD_SOURCE_EVIDENCE_IDENTITY =
            "sourceEvidenceIdentity";
    private static final String FIELD_SOURCE_CONTENT =
            "sourceContent";
    private static final String FIELD_INLINE_VALUE_PATHS =
            "inlineValuePaths";
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final String SHA_256_PREFIX = "sha256:";

    private ProviderEvidenceVerifier() {
    }

    /**
     * Returns canonical verified content for the requested identity.
     *
     * @param requestedBlueId identity the supplied content must establish
     * @param supplied provider-returned node
     * @param mode ingestion mode
     * @param runtime exact source-content verification runtime
     * @param environment source environment binding, required only for
     *                    {@link ProviderMode#SOURCE_DOCUMENT}
     * @return canonical verified content
     * @throws IllegalArgumentException when bindings or calculated identity do
     *                                  not match
     */
    public static Node verify(String requestedBlueId,
                              Node supplied,
                              ProviderMode mode,
                              SourceContentVerificationRuntime runtime,
                              SourceProviderEnvironment environment) {
        Objects.requireNonNull(requestedBlueId, "requestedBlueId");
        Objects.requireNonNull(supplied, "supplied");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(runtime, "runtime");

        Node canonical;
        if (mode == ProviderMode.DIRECT_NODE) {
            if (environment != null) {
                throw new IllegalArgumentException(
                        "BlueIdInput provider mode does not accept a Source preprocessing environment.");
            }
            canonical = candidateWithoutRootIdentity(
                    supplied, requestedBlueId,
                    "Direct provider candidate");
        } else {
            Node source = candidateWithoutRootIdentity(
                    supplied, requestedBlueId,
                    "Bound source provider candidate");
            validateSourceEnvironment(
                    runtime, environment, sourceEvidenceIdentity(source));
            canonical = canonicalizeSource(source, runtime);
        }

        String actualBlueId;
        try {
            actualBlueId = DirectBlueIdCalculator.calculateBlueId(canonical);
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

    /**
     * Verifies a multi-node authored source value under one fully bound
     * environment.
     *
     * <p>This overload is for ordinary list-shaped Content BlueIds.</p>
     *
     * @param requestedBlueId identity the complete supplied value must establish
     * @param supplied complete ordered source-node value
     * @param runtime exact source-content verification runtime
     * @param environment immutable source verification environment
     * @return unmodifiable preprocessed node copies
     */
    public static List<Node> verifySourceContent(
            String requestedBlueId,
            List<Node> supplied,
            SourceContentVerificationRuntime runtime,
            SourceProviderEnvironment environment) {
        Objects.requireNonNull(requestedBlueId, "requestedBlueId");
        Objects.requireNonNull(supplied, "supplied");
        Objects.requireNonNull(runtime, "runtime");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bound source provider content must not be empty.");
        }
        List<Node> source = candidatesWithoutRootIdentity(
                supplied, requestedBlueId,
                "Bound source provider candidate");
        validateSourceEnvironment(
                runtime, environment, sourceEvidenceIdentity(source));

        List<Node> canonical = canonicalizeSource(source, runtime);
        String actualBlueId;
        try {
            actualBlueId = canonical.size() == 1
                    ? DirectBlueIdCalculator.calculateBlueId(canonical.get(0))
                    : DirectBlueIdCalculator.calculateBlueId(canonical);
        } catch (RuntimeException invalidEvidence) {
            throw new IllegalArgumentException(
                    "Provider content does not verify requested BlueId "
                            + requestedBlueId
                            + ": invalid bound source input.",
                    invalidEvidence);
        }
        if (!requestedBlueId.equals(actualBlueId)) {
            throw new IllegalArgumentException(
                    "Provider returned bound source content with BlueId "
                            + actualBlueId + " for requested BlueId "
                            + requestedBlueId + ".");
        }
        return immutableNodeCopies(canonical);
    }

    /**
     * Calculates the canonical SHA-256 identity of authored source evidence.
     *
     * @param supplied exact authored source node
     * @return lowercase hexadecimal identity prefixed with {@code sha256:}
     */
    public static String sourceEvidenceIdentity(Node supplied) {
        Objects.requireNonNull(supplied, "supplied");
        return sha256CanonicalIdentity(
                sourceEvidenceValue(supplied));
    }

    /**
     * Calculates the canonical SHA-256 identity of a complete ordered source
     * value.
     *
     * @param supplied exact authored source nodes
     * @return lowercase hexadecimal identity prefixed with {@code sha256:}
     */
    public static String sourceEvidenceIdentity(List<Node> supplied) {
        Objects.requireNonNull(supplied, "supplied");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source evidence list must not be empty.");
        }
        return sha256CanonicalIdentity(
                sourceEvidenceValue(supplied));
    }

    /**
     * Compares every wire-visible source field and all preprocessing-sensitive
     * inline-value provenance.
     *
     * @param first first exact source node
     * @param second second exact source node
     * @return whether both nodes are identical source evidence
     */
    public static boolean sameSourceEvidence(
            Node first,
            Node second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        try {
            return Arrays.equals(
                    canonicalIdentityBytes(
                            sourceEvidenceValue(first)),
                    canonicalIdentityBytes(
                            sourceEvidenceValue(second)));
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to compare provider source evidence.",
                    failure);
        }
    }

    /**
     * Calculates imported source evidence after validating and removing only
     * matching informational root identity metadata.
     *
     * @param requestedBlueId exact requested identity
     * @param supplied provider-returned source candidates
     * @return canonical source-evidence identity
     */
    public static String normalizedSourceEvidenceIdentity(
            String requestedBlueId,
            List<Node> supplied) {
        Objects.requireNonNull(requestedBlueId, "requestedBlueId");
        Objects.requireNonNull(supplied, "supplied");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source evidence list must not be empty.");
        }
        return sourceEvidenceIdentity(candidatesWithoutRootIdentity(
                supplied, requestedBlueId,
                "Bound source provider candidate"));
    }

    /**
     * Binds the Language release, canonical registry, and configured directive
     * aliases that define the active preprocessing environment.
     *
     * @param runtime exact source-content verification runtime
     * @return lowercase hexadecimal environment identity prefixed with
     *         {@code sha256:}
     */
    public static String preprocessingEnvironmentIdentity(
            SourceContentVerificationRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_LANGUAGE_RELEASE_IDENTITY,
                SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY);
        payload.put(FIELD_CANONICAL_REGISTRY_IDENTITY,
                runtime.canonicalRegistryIdentity());
        payload.put(FIELD_PREPROCESSING_ALIASES,
                new TreeMap<>(runtime.preprocessingAliases()));
        if (!runtime.environmentImports().isEmpty()) {
            payload.put(FIELD_ENVIRONMENT_IMPORTS,
                    new TreeMap<>(runtime.environmentImports()));
        }
        return sha256CanonicalIdentity(payload);
    }

    /**
     * Calculates one cache-safe identity over every immutable source-provider
     * environment field.
     *
     * @param environment fully bound source-provider environment
     * @return canonical environment identity
     */
    public static String sourceEnvironmentIdentity(
            SourceProviderEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        if (!environment.isFullyBound()) {
            throw new IllegalArgumentException(
                    "Cannot identify an incomplete source-provider "
                            + "environment.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(FIELD_LANGUAGE_RELEASE_IDENTITY,
                environment.languageReleaseIdentity());
        payload.put(FIELD_LANGUAGE_VERSION,
                environment.languageVersion());
        payload.put(FIELD_PREPROCESSING_ENVIRONMENT_IDENTITY,
                environment.preprocessingEnvironmentId());
        payload.put(FIELD_CANONICAL_REGISTRY_IDENTITY,
                environment.canonicalRegistryIdentity());
        payload.put(FIELD_PROVIDER_DOMAIN_IDENTITY,
                environment.providerDomainIdentity());
        payload.put(FIELD_PROVIDER_MODE,
                environment.providerMode().evidenceLabel());
        payload.put(FIELD_SOURCE_CONTENT_STRATEGY,
                environment.sourceContentStrategyIdentity());
        payload.put(FIELD_SOURCE_EVIDENCE_IDENTITY,
                environment.sourceEvidenceIdentity());
        return sha256CanonicalIdentity(payload);
    }

    private static void validateSourceEnvironment(
            SourceContentVerificationRuntime runtime,
            SourceProviderEnvironment environment,
            String actualSourceEvidenceIdentity) {
        if (environment == null) {
            throw new IllegalArgumentException(
                    "Bound source provider mode requires a declared language and preprocessing environment.");
        }
        if (!environment.isFullyBound()) {
            throw new IllegalArgumentException(
                    "Bound source provider mode requires release, preprocessing, "
                            + "canonical registry, provider domain, mode, and "
                            + "exact imported source-evidence identity bindings.");
        }
        if (environment.providerMode()
                != ProviderMode.BOUND_SOURCE_CONTENT) {
            throw new IllegalArgumentException(
                    "Source provider environment does not declare BOUND_SOURCE_CONTENT mode.");
        }
        if (!runtime.languageVersion().equals(
                environment.languageVersion())) {
            throw new IllegalArgumentException(
                    "Bound source provider language version does not match this Blue runtime.");
        }
        if (!SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY.equals(
                environment.languageReleaseIdentity())) {
            throw new IllegalArgumentException(
                    "Bound source provider release identity does not match Blue Language 1.0.");
        }
        if (!runtime.canonicalRegistryIdentity().equals(
                environment.canonicalRegistryIdentity())) {
            throw new IllegalArgumentException(
                    "Bound source provider canonical registry identity does not match this Blue runtime.");
        }
        if (!preprocessingEnvironmentIdentity(runtime).equals(
                environment.preprocessingEnvironmentId())) {
            throw new IllegalArgumentException(
                    "Bound source provider preprocessing environment identity does not match this Blue runtime.");
        }
        if (!actualSourceEvidenceIdentity.equals(
                environment.sourceEvidenceIdentity())) {
            throw new IllegalArgumentException(
                    "Bound source provider imported source-evidence identity "
                            + "does not match the supplied snapshot.");
        }
        String strategy =
                environment.sourceContentStrategyIdentity();
        if (!SourceProviderEnvironment
                .LANGUAGE_CONTENT_STRATEGY_IDENTITY
                .equals(strategy)) {
            throw new IllegalArgumentException(
                    "Bound source provider declares an unsupported "
                            + "Content BlueId strategy: "
                            + strategy + ".");
        }
    }

    private static Node canonicalizeSource(
            Node source,
            SourceContentVerificationRuntime runtime) {
        return runtime.canonicalizeSourceContent(source);
    }

    private static List<Node> canonicalizeSource(
            List<Node> source,
            SourceContentVerificationRuntime runtime) {
        List<Node> canonical = new ArrayList<>(source.size());
        for (Node node : source) {
            canonical.add(canonicalizeSource(node, runtime));
        }
        return canonical;
    }

    private static Node candidateWithoutRootIdentity(
            Node supplied,
            String requestedBlueId,
            String source) {
        Objects.requireNonNull(supplied, "provider candidate");
        Node canonical = supplied.clone();
        if (canonical.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    source + " is a pure reference and supplies no content evidence.");
        }
        String rootBlueId = canonical.getBlueId();
        if (rootBlueId == null) {
            return canonical;
        }
        if (!requestedBlueId.equals(rootBlueId)) {
            throw new IllegalArgumentException(
                    source + " has root BlueId " + rootBlueId
                            + " instead of requested BlueId "
                            + requestedBlueId + ".");
        }
        canonical.blueId(null);
        return canonical;
    }

    private static List<Node> candidatesWithoutRootIdentity(
            List<Node> supplied,
            String requestedBlueId,
            String source) {
        List<Node> result = new ArrayList<>(supplied.size());
        for (Node node : supplied) {
            if (node == null) {
                throw new NullPointerException("provider candidate");
            }
            Node canonical = node.clone();
            if (canonical.isReferenceOnly()) {
                if (supplied.size() == 1) {
                    throw new IllegalArgumentException(
                            source + " is a pure reference and supplies no content evidence.");
                }
                result.add(canonical);
                continue;
            }
            String rootBlueId = canonical.getBlueId();
            if (rootBlueId != null) {
                if (supplied.size() != 1
                        || !requestedBlueId.equals(rootBlueId)) {
                    throw new IllegalArgumentException(
                            source + " has unverified root BlueId "
                                    + rootBlueId + ".");
                }
                canonical.blueId(null);
            }
            result.add(canonical);
        }
        return result;
    }

    private static List<Node> immutableNodeCopies(List<Node> nodes) {
        List<Node> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(node.clone());
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> sourceEvidenceValue(
            Node supplied) {
        Map<String, Object> evidence =
                new LinkedHashMap<>();
        evidence.put(FIELD_SOURCE_CONTENT,
                NodeWireForm.get(supplied));
        List<String> inlinePaths = new ArrayList<>();
        collectInlineValuePaths(
                supplied, JsonPointer.ROOT, inlinePaths);
        evidence.put(FIELD_INLINE_VALUE_PATHS,
                inlinePaths);
        return evidence;
    }

    private static Map<String, Object> sourceEvidenceValue(
            List<Node> supplied) {
        List<Object> content =
                new ArrayList<>(supplied.size());
        List<String> inlinePaths =
                new ArrayList<>();
        for (int index = 0;
             index < supplied.size();
             index++) {
            Node node = Objects.requireNonNull(
                    supplied.get(index),
                    "source evidence node");
            content.add(NodeWireForm.get(node));
            collectInlineValuePaths(
                    node,
                    JsonPointer.append(
                            JsonPointer.ROOT,
                            Integer.toString(index)),
                    inlinePaths);
        }
        Map<String, Object> evidence =
                new LinkedHashMap<>();
        evidence.put(FIELD_SOURCE_CONTENT, content);
        evidence.put(FIELD_INLINE_VALUE_PATHS,
                inlinePaths);
        return evidence;
    }

    private static void collectInlineValuePaths(
            Node node,
            String path,
            List<String> paths) {
        if (node == null) {
            return;
        }
        if (node.isInlineValue()) {
            paths.add(path);
        }
        collectInlineValuePaths(
                node.getType(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_TYPE),
                paths);
        collectInlineValuePaths(
                node.getItemType(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_ITEM_TYPE),
                paths);
        collectInlineValuePaths(
                node.getKeyType(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_KEY_TYPE),
                paths);
        collectInlineValuePaths(
                node.getValueType(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_VALUE_TYPE),
                paths);
        collectInlineValuePaths(
                node.getBlue(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_BLUE),
                paths);
        collectInlineValuePaths(
                node.getContracts(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_CONTRACTS),
                paths);
        collectInlineValuePaths(
                node.getSchema(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_SCHEMA),
                paths);
        if (node.getItems() != null) {
            String itemsPath = JsonPointer.append(
                    path, BlueLanguageConstants.OBJECT_ITEMS);
            for (int index = 0;
                 index < node.getItems().size();
                 index++) {
                collectInlineValuePaths(
                        node.getItems().get(index),
                        JsonPointer.append(
                                itemsPath,
                                Integer.toString(index)),
                        paths);
            }
        }
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> property :
                    new TreeMap<>(node.getProperties()).entrySet()) {
                collectInlineValuePaths(
                        property.getValue(),
                        JsonPointer.append(
                                path, property.getKey()),
                        paths);
            }
        }
    }

    private static void collectInlineValuePaths(
            Schema schema,
            String path,
            List<String> paths) {
        if (schema == null) {
            return;
        }
        collectInlineValuePaths(
                schema.getRequired(),
                JsonPointer.append(path, KEY_REQUIRED),
                paths);
        collectInlineValuePaths(
                schema.getMinLength(),
                JsonPointer.append(path, KEY_MIN_LENGTH),
                paths);
        collectInlineValuePaths(
                schema.getMaxLength(),
                JsonPointer.append(path, KEY_MAX_LENGTH),
                paths);
        collectInlineValuePaths(
                schema.getMinimum(),
                JsonPointer.append(path, KEY_MINIMUM),
                paths);
        collectInlineValuePaths(
                schema.getMaximum(),
                JsonPointer.append(path, KEY_MAXIMUM),
                paths);
        collectInlineValuePaths(
                schema.getExclusiveMinimum(),
                JsonPointer.append(
                        path, KEY_EXCLUSIVE_MINIMUM),
                paths);
        collectInlineValuePaths(
                schema.getExclusiveMaximum(),
                JsonPointer.append(
                        path, KEY_EXCLUSIVE_MAXIMUM),
                paths);
        collectInlineValuePaths(
                schema.getMultipleOf(),
                JsonPointer.append(path, KEY_MULTIPLE_OF),
                paths);
        collectInlineValuePaths(
                schema.getMinItems(),
                JsonPointer.append(path, KEY_MIN_ITEMS),
                paths);
        collectInlineValuePaths(
                schema.getMaxItems(),
                JsonPointer.append(path, KEY_MAX_ITEMS),
                paths);
        collectInlineValuePaths(
                schema.getUniqueItems(),
                JsonPointer.append(path, KEY_UNIQUE_ITEMS),
                paths);
        collectInlineValuePaths(
                schema.getMinFields(),
                JsonPointer.append(path, KEY_MIN_FIELDS),
                paths);
        collectInlineValuePaths(
                schema.getMaxFields(),
                JsonPointer.append(path, KEY_MAX_FIELDS),
                paths);
        if (schema.getEnum() != null) {
            String enumPath =
                    JsonPointer.append(path, KEY_ENUM);
            for (int index = 0;
                 index < schema.getEnum().size();
                 index++) {
                collectInlineValuePaths(
                        schema.getEnum().get(index),
                        JsonPointer.append(
                                enumPath,
                                Integer.toString(index)),
                        paths);
            }
        }
    }

    private static String sha256CanonicalIdentity(Object value) {
        try {
            return SHA_256_PREFIX + toHex(
                    MessageDigest.getInstance(
                            SHA_256_ALGORITHM).digest(
                            canonicalIdentityBytes(value)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "Unable to calculate provider evidence identity.", failure);
        }
    }

    private static byte[] canonicalIdentityBytes(
            Object value) throws IOException {
        byte[] json = UncheckedObjectMapper.JSON_MAPPER
                .writeValueAsBytes(value);
        return new JsonCanonicalizer(json)
                .getEncodedUTF8();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
