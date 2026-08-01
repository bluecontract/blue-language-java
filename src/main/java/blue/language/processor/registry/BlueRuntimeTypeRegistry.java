package blue.language.processor.registry;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.registry.RegistryManifestConstants;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed registry of the runtime types published by Blue Contracts 1.0.
 *
 * <p>Construction eagerly loads every bundled type, verifies the manifest and
 * resource digests, recalculates each BlueId and the package identity, and
 * rejects any mismatch. Returned {@link Node} instances are defensive clones;
 * the provider performs the same cloning at its boundary.</p>
 */
public final class BlueRuntimeTypeRegistry {

    /** Classpath directory containing the verified registry manifest and types. */
    public static final String RESOURCE_ROOT = "registry/blue-contracts-1.0";
    private static final String MANIFEST_RESOURCE = "manifest.yaml";
    private static final String FIXTURE_MANIFEST_RESOURCE =
            "blue-contracts-1.0/fixtures/manifest.yaml";
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final String SHA_256_PREFIX = "sha256:";

    private static final BlueRuntimeTypeRegistry DEFAULT = new BlueRuntimeTypeRegistry();

    private final Map<RuntimeTypeKey, RegistryEntry> entries;
    private final Map<String, RuntimeTypeKey> keyByBlueId;
    private final Set<String> processorManagedTypeBlueIds;
    private final String registryIdentity;
    private final NodeProvider provider;

    /**
     * Loads and verifies the complete bundled registry.
     *
     * @throws IllegalStateException when any required artifact is absent,
     *         malformed, or inconsistent with its published identity
     */
    public BlueRuntimeTypeRegistry() {
        Manifest manifest = loadManifest();
        this.entries = loadEntries(manifest);
        this.keyByBlueId = buildKeyByBlueId(entries);
        this.processorManagedTypeBlueIds = buildProcessorManagedTypeBlueIds(entries);
        this.registryIdentity = calculateRegistryIdentity(manifest);
        verifyConformanceFixturePackageIdentityIfPresent(manifest);
        NodeProvider verifiedProvider = new RegistryNodeProvider(entries);
        this.provider = blueId -> BlueIds.isPotentialBlueId(blueId)
                ? verifiedProvider.fetchByBlueId(blueId)
                : null;
    }

    /**
     * Returns the process-wide, eagerly verified registry instance.
     *
     * @return shared immutable registry
     */
    public static BlueRuntimeTypeRegistry getDefault() {
        return DEFAULT;
    }

    /**
     * Returns the published BlueId for the requested runtime type.
     *
     * @param key stable runtime type key
     * @return verified published BlueId
     */
    public String blueId(RuntimeTypeKey key) {
        return entry(key).blueId;
    }

    /**
     * Returns a defensive copy of the canonical node for the requested type.
     *
     * @param key stable runtime type key
     * @return detached canonical registry node
     */
    public Node node(RuntimeTypeKey key) {
        return entry(key).node.clone();
    }

    /**
     * Returns whether the BlueId identifies a processor-managed runtime type.
     *
     * @param blueId exact type identity to classify
     * @return {@code true} for a processor-owned type
     */
    public boolean isProcessorManagedTypeBlueId(String blueId) {
        return processorManagedTypeBlueIds.contains(blueId);
    }

    /**
     * Returns the immutable set of processor-managed runtime type BlueIds.
     *
     * @return immutable verified identity set
     */
    public Set<String> processorManagedTypeBlueIds() {
        return processorManagedTypeBlueIds;
    }

    /**
     * Returns an immutable snapshot of every runtime key-to-BlueId mapping.
     *
     * @return immutable mapping in registry-key order
     */
    public Map<RuntimeTypeKey, String> blueIds() {
        Map<RuntimeTypeKey, String> result = new EnumMap<>(RuntimeTypeKey.class);
        for (Map.Entry<RuntimeTypeKey, RegistryEntry> entry : entries.entrySet()) {
            result.put(entry.getKey(), entry.getValue().blueId);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns whether a registered runtime type has the requested registered
     * supertype in its canonical type ancestry.
     *
     * <p>This query is intentionally limited to the verified runtime registry.
     * Application-defined types are classified from their resolved contract
     * snapshots and processor registrations instead.</p>
     *
     * @param candidateBlueId exact registered candidate identity
     * @param supertype stable registered supertype key
     * @return {@code true} when the verified registry ancestry contains the
     *         requested supertype
     * @throws IllegalStateException if the verified registry contains cyclic
     *         type ancestry
     */
    public boolean isRegisteredSubtype(
            String candidateBlueId,
            RuntimeTypeKey supertype) {
        Objects.requireNonNull(supertype, "supertype");
        if (candidateBlueId == null || candidateBlueId.isEmpty()) {
            return false;
        }
        String expectedBlueId = blueId(supertype);
        String currentBlueId = candidateBlueId;
        Set<String> visited = new LinkedHashSet<>();
        while (visited.add(currentBlueId)) {
            if (expectedBlueId.equals(currentBlueId)) {
                return true;
            }
            RuntimeTypeKey currentKey = keyByBlueId.get(currentBlueId);
            if (currentKey == null) {
                return false;
            }
            Node declaredType = entry(currentKey).node.getType();
            if (declaredType == null) {
                return false;
            }
            currentBlueId = declaredType.getBlueId() != null
                    ? declaredType.getBlueId()
                    : DirectBlueIdCalculator.calculateBlueId(declaredType);
        }
        throw new IllegalStateException(
                "Cyclic runtime registry type ancestry at "
                        + currentBlueId);
    }

    /**
     * Returns the verified SHA-256 identity of the registry package.
     *
     * @return lowercase hexadecimal package digest
     */
    public String registryIdentity() {
        return registryIdentity;
    }

    /**
     * Returns a provider that accepts published BlueIds and supplies defensive
     * copies of their canonical registry nodes.
     *
     * @return immutable verified registry provider
     */
    public NodeProvider asProvider() {
        return provider;
    }

    /**
     * Returns the verified provider used to resolve processor-owned snapshots.
     *
     * <p>This intent-revealing alias currently has the same behavior as
     * {@link #asProvider()}.</p>
     *
     * @return immutable verified processor-snapshot provider
     */
    public NodeProvider asProcessorSnapshotProvider() {
        return provider;
    }

    private RegistryEntry entry(RuntimeTypeKey key) {
        Objects.requireNonNull(key, "key");
        RegistryEntry entry = entries.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown runtime type key: " + key);
        }
        return entry;
    }

    private Manifest loadManifest() {
        try (InputStream input = resource(MANIFEST_RESOURCE)) {
            Map<String, Object> raw = UncheckedObjectMapper.YAML_MAPPER.readValue(input,
                    new TypeReference<Map<String, Object>>() {
                    });
            Manifest manifest = new Manifest();
            manifest.raw = raw;
            manifest.registry = stringValue(raw.get(
                    RegistryManifestConstants.FIELD_REGISTRY));
            manifest.registryKind = stringValue(raw.get(
                    RegistryManifestConstants.FIELD_REGISTRY_KIND));
            manifest.specVersion = stringValue(raw.get(
                    RegistryManifestConstants
                            .FIELD_SPECIFICATION_VERSION));
            manifest.languageVersion = stringValue(raw.get(
                    RegistryManifestConstants.FIELD_LANGUAGE_VERSION));
            manifest.fixturePackageIdentity =
                    stringValue(raw.get(
                            RegistryManifestConstants
                                    .FIELD_FIXTURE_PACKAGE_IDENTITY));
            manifest.packageIdentity = stringValue(raw.get(
                    RegistryManifestConstants.FIELD_PACKAGE_IDENTITY));
            if (raw.containsKey(
                    RegistryManifestConstants.FIELD_LEGACY_TYPES)) {
                throw new IllegalStateException("Runtime registry manifest uses stale types map shape");
            }
            Object entries = raw.get(
                    RegistryManifestConstants.FIELD_ENTRIES);
            if (!(entries instanceof List)) {
                throw new IllegalStateException("Runtime registry manifest must contain an entries list");
            }
            for (Object rawEntry : (List<?>) entries) {
                if (!(rawEntry instanceof Map)) {
                    throw new IllegalStateException("Runtime registry manifest entry must be a map");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> value = (Map<String, Object>) rawEntry;
                String manifestKey = stringValue(value.get(
                        RegistryManifestConstants.FIELD_KEY));
                RuntimeTypeKey key = manifestKey(manifestKey);
                if (manifest.entries.containsKey(key)) {
                    throw new IllegalStateException("Duplicate runtime registry manifest key: " + manifestKey);
                }
                manifest.entries.put(key, new ManifestEntry(
                        manifestKey,
                        stringValue(value.get(
                                RegistryManifestConstants.FIELD_PATH)),
                        stringValue(value.get(
                                RegistryManifestConstants.FIELD_BLUE_ID)),
                        stringValue(value.get(
                                RegistryManifestConstants.FIELD_SHA256)),
                        booleanValue(value.get(
                                RegistryManifestConstants
                                        .FIELD_SEMANTIC_DESCRIPTION_IDENTITY_BEARING)),
                        booleanValue(value.get(
                                RegistryManifestConstants
                                        .FIELD_FIXTURE_ONLY))));
            }
            if (!RegistryManifestConstants
                    .REGISTRY_CONTRACTS_RUNTIME
                    .equals(manifest.registry)
                    || !RegistryManifestConstants.KIND_RUNTIME_TYPE
                    .equals(manifest.registryKind)
                    || !RegistryManifestConstants.VERSION_1_0
                    .equals(manifest.specVersion)
                    || !RegistryManifestConstants.VERSION_1_0
                    .equals(manifest.languageVersion)) {
                throw new IllegalStateException("Unsupported Blue Contracts registry version: " + manifest.specVersion);
            }
            if (!RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY.equals(manifest.packageIdentity)) {
                throw new IllegalStateException("Runtime registry package identity mismatch: "
                        + manifest.packageIdentity);
            }
            if (manifest.entries.size() != RuntimeTypeKey.values().length) {
                throw new IllegalStateException("Runtime registry manifest contains " + manifest.entries.size()
                        + " entries, expected " + RuntimeTypeKey.values().length);
            }
            return manifest;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load Blue runtime type registry manifest", ex);
        }
    }

    private Map<RuntimeTypeKey, RegistryEntry> loadEntries(Manifest manifest) {
        Map<RuntimeTypeKey, Node> rawNodes = loadRawNodes(manifest);
        Map<RuntimeTypeKey, RegistryEntry> loaded = new EnumMap<>(RuntimeTypeKey.class);
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            ManifestEntry manifestEntry = manifest.entries.get(key);
            if (manifestEntry == null) {
                throw new IllegalStateException("Runtime registry manifest is missing " + key);
            }
            Node rawNode = rawNodes.get(key);
            verifyIdentityBearingDescription(key, manifestEntry, rawNode);
            /*
             * Registry artifacts are already canonical BlueId Input: every
             * type reference is an exact published BlueId.  Running Source
             * alias preprocessing here would infer extra structure inside
             * schema values and change the published identity.
             */
            Node node = rawNode.clone();
            String calculatedBlueId = DirectBlueIdCalculator.calculateBlueId(node);
            if (!manifestEntry.blueId.equals(calculatedBlueId)) {
                throw new IllegalStateException("Runtime registry BlueId mismatch for " + key
                        + ": calculated=" + calculatedBlueId
                        + ", manifest=" + manifestEntry.blueId);
            }
            if (!RuntimeBlueIds.blueId(key).equals(manifestEntry.blueId)) {
                throw new IllegalStateException("RuntimeBlueIds constant mismatch for " + key
                        + ": constant=" + RuntimeBlueIds.blueId(key) + ", manifest=" + manifestEntry.blueId);
            }
            loaded.put(key, new RegistryEntry(key, manifestEntry.path, manifestEntry.blueId, node));
        }
        return Collections.unmodifiableMap(loaded);
    }

    private Map<RuntimeTypeKey, Node> loadRawNodes(Manifest manifest) {
        Map<RuntimeTypeKey, Node> rawNodes = new EnumMap<>(RuntimeTypeKey.class);
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            ManifestEntry manifestEntry = manifest.entries.get(key);
            if (manifestEntry == null) {
                throw new IllegalStateException("Runtime registry manifest is missing " + key);
            }
            try (InputStream input = resource(manifestEntry.path)) {
                byte[] bytes = readResourceBytes(manifestEntry.path);
                String sha256 = toHex(sha256().digest(bytes));
                if (!manifestEntry.sha256.equals(sha256)) {
                    throw new IllegalStateException("Runtime registry resource digest mismatch for "
                            + manifestEntry.path);
                }
                rawNodes.put(key, UncheckedObjectMapper.YAML_MAPPER.readValue(
                        new java.io.ByteArrayInputStream(bytes), Node.class));
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to load runtime registry node " + manifestEntry.path, ex);
            }
        }
        return rawNodes;
    }

    private void verifyIdentityBearingDescription(RuntimeTypeKey key, ManifestEntry entry, Node node) {
        if (!entry.semanticDescriptionIdentityBearing) {
            return;
        }
        String description = node != null ? node.getDescription() : null;
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalStateException("Runtime registry entry " + key
                    + " declares semanticDescriptionIdentityBearing but has no description");
        }
    }

    private String calculateRegistryIdentity(Manifest manifest) {
        Map<String, Object> payload = deepCopyMap(manifest.raw);
        payload.put(
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY,
                null);
        payload.put(
                RegistryManifestConstants
                        .FIELD_FIXTURE_PACKAGE_IDENTITY,
                null);
        try {
            ObjectMapper identityMapper = new ObjectMapper();
            identityMapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
            String json = identityMapper.writeValueAsString(payload);
            byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
            String calculated = SHA_256_PREFIX
                    + toHex(sha256().digest(canonical));
            if (!manifest.packageIdentity.equals(calculated)) {
                throw new IllegalStateException(
                        "Runtime registry package identity mismatch: calculated="
                                + calculated + ", manifest=" + manifest.packageIdentity);
            }
            return calculated;
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to canonicalize runtime registry manifest", ex);
        }
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        return UncheckedObjectMapper.JSON_MAPPER.convertValue(
                source, new TypeReference<Map<String, Object>>() {
                });
    }

    private void verifyConformanceFixturePackageIdentityIfPresent(Manifest manifest) {
        String fixtureIdentity = readFixturePackageIdentityIfPresent();
        if (fixtureIdentity != null && !fixtureIdentity.equals(manifest.fixturePackageIdentity)) {
            throw new IllegalStateException("Runtime registry fixture package identity mismatch: manifest="
                    + manifest.fixturePackageIdentity + ", fixtures=" + fixtureIdentity);
        }
    }

    private String readFixturePackageIdentityIfPresent() {
        try (InputStream input = BlueRuntimeTypeRegistry.class.getClassLoader()
                .getResourceAsStream(FIXTURE_MANIFEST_RESOURCE)) {
            if (input == null) {
                return null;
            }
            Map<String, Object> raw = UncheckedObjectMapper.YAML_MAPPER.readValue(input,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object value = raw.get(
                    RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Blue Contracts fixture manifest", ex);
        }
    }

    private static Map<String, RuntimeTypeKey> buildKeyByBlueId(Map<RuntimeTypeKey, RegistryEntry> entries) {
        Map<String, RuntimeTypeKey> result = new LinkedHashMap<>();
        for (Map.Entry<RuntimeTypeKey, RegistryEntry> entry : entries.entrySet()) {
            result.put(entry.getValue().blueId, entry.getKey());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> buildProcessorManagedTypeBlueIds(Map<RuntimeTypeKey, RegistryEntry> entries) {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<RuntimeTypeKey, RegistryEntry> entry : entries.entrySet()) {
            result.add(entry.getValue().blueId);
        }
        return Collections.unmodifiableSet(result);
    }

    private static RuntimeTypeKey manifestKey(String key) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(ch));
        }
        return RuntimeTypeKey.valueOf(result.toString());
    }

    private static String stringValue(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalStateException("Expected non-empty string in runtime registry manifest");
        }
        return (String) value;
    }

    private static boolean booleanValue(Object value) {
        if (!(value instanceof Boolean)) {
            throw new IllegalStateException("Expected boolean in runtime registry manifest");
        }
        return (Boolean) value;
    }

    private static InputStream resource(String path) throws IOException {
        String fullPath = RESOURCE_ROOT + "/" + path;
        InputStream input = BlueRuntimeTypeRegistry.class.getClassLoader().getResourceAsStream(fullPath);
        if (input == null) {
            throw new IOException("Missing runtime registry resource: " + fullPath);
        }
        return input;
    }

    private static byte[] readResourceBytes(String path) {
        try (InputStream input = resource(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read runtime registry resource " + path, ex);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(
                    SHA_256_ALGORITHM);
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError("SHA-256 is unavailable", ex);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateDigest(MessageDigest digest, byte[] value) {
        digest.update(value);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static final class RegistryNodeProvider implements NodeProvider {
        private final Map<String, Node> nodesByBlueId;

        RegistryNodeProvider(Map<RuntimeTypeKey, RegistryEntry> entries) {
            Map<String, Node> nodes = new LinkedHashMap<>();
            for (RegistryEntry entry : entries.values()) {
                Node node = entry.node.clone();
                nodes.put(entry.blueId, node);
            }
            this.nodesByBlueId = Collections.unmodifiableMap(nodes);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node node = nodesByBlueId.get(blueId);
            if (node == null) {
                return null;
            }
            List<Node> result = new ArrayList<>(1);
            result.add(node.clone());
            return result;
        }

    }

    private static final class Manifest {
        Map<String, Object> raw;
        String registry;
        String registryKind;
        String specVersion;
        String languageVersion;
        String fixturePackageIdentity;
        String packageIdentity;
        final Map<RuntimeTypeKey, ManifestEntry> entries = new EnumMap<>(RuntimeTypeKey.class);
    }

    private static final class ManifestEntry {
        final String manifestKey;
        final String path;
        final String blueId;
        final String sha256;
        final boolean semanticDescriptionIdentityBearing;
        final boolean fixtureOnly;

        ManifestEntry(String manifestKey,
                      String path,
                      String blueId,
                      String sha256,
                      boolean semanticDescriptionIdentityBearing,
                      boolean fixtureOnly) {
            this.manifestKey = manifestKey;
            this.path = path;
            this.blueId = blueId;
            this.sha256 = sha256;
            this.semanticDescriptionIdentityBearing = semanticDescriptionIdentityBearing;
            this.fixtureOnly = fixtureOnly;
        }
    }

    private static final class RegistryEntry {
        final RuntimeTypeKey key;
        final String path;
        final String blueId;
        final Node node;

        RegistryEntry(RuntimeTypeKey key, String path, String blueId, Node node) {
            this.key = key;
            this.path = path;
            this.blueId = blueId;
            this.node = node;
        }
    }
}
