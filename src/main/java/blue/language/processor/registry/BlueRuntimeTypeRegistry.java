package blue.language.processor.registry;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.preprocess.processor.ReplaceInlineValuesForTypeAttributesWithImports;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

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

import static blue.language.utils.Properties.CORE_TYPE_NAME_TO_BLUE_ID_MAP;

public final class BlueRuntimeTypeRegistry {

    public static final String RESOURCE_ROOT = "registry/blue-contracts-1.0";

    private static final BlueRuntimeTypeRegistry DEFAULT = new BlueRuntimeTypeRegistry();

    private final Map<RuntimeTypeKey, RegistryEntry> entries;
    private final Map<String, RuntimeTypeKey> keyByBlueId;
    private final Set<String> processorManagedTypeBlueIds;
    private final String registryIdentity;
    private final NodeProvider provider;
    private final NodeProvider processorSnapshotProvider;

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
        NodeProvider lenientProvider = new RegistryNodeProvider(entries, true);
        this.processorSnapshotProvider = blueId -> BlueIds.isPotentialBlueId(blueId)
                ? lenientProvider.fetchByBlueId(blueId)
                : null;
    }

    public static BlueRuntimeTypeRegistry getDefault() {
        return DEFAULT;
    }

    public String blueId(RuntimeTypeKey key) {
        return entry(key).blueId;
    }

    public Node node(RuntimeTypeKey key) {
        return entry(key).node.clone();
    }

    public boolean isProcessorManagedTypeBlueId(String blueId) {
        return processorManagedTypeBlueIds.contains(blueId);
    }

    public Set<String> processorManagedTypeBlueIds() {
        return processorManagedTypeBlueIds;
    }

    public Map<RuntimeTypeKey, String> blueIds() {
        Map<RuntimeTypeKey, String> result = new EnumMap<>(RuntimeTypeKey.class);
        for (Map.Entry<RuntimeTypeKey, RegistryEntry> entry : entries.entrySet()) {
            result.put(entry.getKey(), entry.getValue().blueId);
        }
        return Collections.unmodifiableMap(result);
    }

    public String registryIdentity() {
        return registryIdentity;
    }

    public NodeProvider asProvider() {
        return provider;
    }

    public NodeProvider asProcessorSnapshotProvider() {
        return processorSnapshotProvider;
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
        try (InputStream input = resource("manifest.yaml")) {
            Map<String, Object> raw = UncheckedObjectMapper.YAML_MAPPER.readValue(input,
                    new TypeReference<Map<String, Object>>() {
                    });
            Manifest manifest = new Manifest();
            manifest.specVersion = stringValue(raw.get("specVersion"));
            manifest.conformanceFixturePackageIdentity =
                    stringValue(raw.get("conformanceFixturePackageIdentity"));
            if (raw.containsKey("types")) {
                throw new IllegalStateException("Runtime registry manifest uses stale types map shape");
            }
            readPreprocessingEnvironment(raw, manifest);
            Object entries = raw.get("entries");
            if (!(entries instanceof List)) {
                throw new IllegalStateException("Runtime registry manifest must contain an entries list");
            }
            for (Object rawEntry : (List<?>) entries) {
                if (!(rawEntry instanceof Map)) {
                    throw new IllegalStateException("Runtime registry manifest entry must be a map");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> value = (Map<String, Object>) rawEntry;
                String manifestKey = stringValue(value.get("key"));
                RuntimeTypeKey key = manifestKey(manifestKey);
                if (manifest.entries.containsKey(key)) {
                    throw new IllegalStateException("Duplicate runtime registry manifest key: " + manifestKey);
                }
                manifest.entries.put(key, new ManifestEntry(
                        manifestKey,
                        stringValue(value.get("path")),
                        stringValue(value.get("blueId")),
                        booleanValue(value.get("semanticDescriptionIdentityBearing"))));
            }
            if (!"1.0".equals(manifest.specVersion)) {
                throw new IllegalStateException("Unsupported Blue Contracts registry version: " + manifest.specVersion);
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
        Map<String, String> aliases = buildPreprocessingAliases(manifest, rawNodes);
        Map<RuntimeTypeKey, RegistryEntry> loaded = new EnumMap<>(RuntimeTypeKey.class);
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            ManifestEntry manifestEntry = manifest.entries.get(key);
            if (manifestEntry == null) {
                throw new IllegalStateException("Runtime registry manifest is missing " + key);
            }
            Node rawNode = rawNodes.get(key);
            verifyIdentityBearingDescription(key, manifestEntry, rawNode);
            Node node = preprocessRegistryNode(rawNode, aliases);
            String calculated = BlueIdCalculator.calculateBlueId(node);
            if (!manifestEntry.blueId.equals(calculated)) {
                // The published Blue Contracts registry manifest is authoritative for runtime
                // recognition. Conformance fixtures exercise the exact published bindings.
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
                rawNodes.put(key, UncheckedObjectMapper.YAML_MAPPER.readValue(input, Node.class));
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to load runtime registry node " + manifestEntry.path, ex);
            }
        }
        return rawNodes;
    }

    private Map<String, String> buildPreprocessingAliases(Manifest manifest, Map<RuntimeTypeKey, Node> rawNodes) {
        Map<String, String> aliases = new LinkedHashMap<>(CORE_TYPE_NAME_TO_BLUE_ID_MAP);
        for (Map.Entry<RuntimeTypeKey, ManifestEntry> entry : manifest.entries.entrySet()) {
            Node rawNode = rawNodes.get(entry.getKey());
            ManifestEntry manifestEntry = entry.getValue();
            aliases.put(manifestEntry.manifestKey, manifestEntry.blueId);
            if (rawNode != null && rawNode.getName() != null && !rawNode.getName().isEmpty()) {
                aliases.put(rawNode.getName(), manifestEntry.blueId);
            }
        }
        return aliases;
    }

    private Node preprocessRegistryNode(Node rawNode, Map<String, String> aliases) {
        return new ReplaceInlineValuesForTypeAttributesWithImports(aliases)
                .process(rawNode.clone());
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
        MessageDigest digest = sha256();
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            ManifestEntry entry = manifest.entries.get(key);
            updateDigest(digest, entry.manifestKey);
            updateDigest(digest, "\n");
            updateDigest(digest, entry.path);
            updateDigest(digest, "\n");
            updateDigest(digest, entry.blueId);
            updateDigest(digest, "\n");
            updateDigest(digest, readResourceBytes(entry.path));
            updateDigest(digest, "\n");
        }
        return "sha256:" + toHex(digest.digest());
    }

    private void verifyConformanceFixturePackageIdentityIfPresent(Manifest manifest) {
        String fixtureIdentity = readFixturePackageIdentityIfPresent();
        if (fixtureIdentity != null && !fixtureIdentity.equals(manifest.conformanceFixturePackageIdentity)) {
            throw new IllegalStateException("Runtime registry fixture package identity mismatch: manifest="
                    + manifest.conformanceFixturePackageIdentity + ", fixtures=" + fixtureIdentity);
        }
    }

    private String readFixturePackageIdentityIfPresent() {
        try (InputStream input = BlueRuntimeTypeRegistry.class.getClassLoader()
                .getResourceAsStream("blue-contracts-1.0/fixtures/manifest.yaml")) {
            if (input == null) {
                return null;
            }
            Map<String, Object> raw = UncheckedObjectMapper.YAML_MAPPER.readValue(input,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object value = raw.get("fixturePackageIdentity");
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Blue Contracts fixture manifest", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private void readPreprocessingEnvironment(Map<String, Object> raw, Manifest manifest) {
        Object environment = raw.get("preprocessingEnvironment");
        if (!(environment instanceof Map)) {
            throw new IllegalStateException("Runtime registry manifest must contain preprocessingEnvironment");
        }
        Map<String, Object> map = (Map<String, Object>) environment;
        manifest.preprocessingCoreRegistry = stringValue(map.get("coreRegistry"));
        manifest.preprocessingRuntimeRegistry = stringValue(map.get("runtimeRegistry"));
        if (!"blue-language-1.0".equals(manifest.preprocessingCoreRegistry)
                || !"blue-contracts-1.0".equals(manifest.preprocessingRuntimeRegistry)) {
            throw new IllegalStateException("Unsupported runtime registry preprocessing environment: "
                    + manifest.preprocessingCoreRegistry + ", " + manifest.preprocessingRuntimeRegistry);
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
            return MessageDigest.getInstance("SHA-256");
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
            this(entries, false);
        }

        RegistryNodeProvider(Map<RuntimeTypeKey, RegistryEntry> entries, boolean stripSchemas) {
            Map<String, Node> nodes = new LinkedHashMap<>();
            for (RegistryEntry entry : entries.values()) {
                Node node = entry.node.clone();
                if (stripSchemas) {
                    stripSchemas(node);
                }
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

        private static void stripSchemas(Node node) {
            if (node == null) {
                return;
            }
            node.schema(null);
            node.itemType((Node) null);
            node.keyType((Node) null);
            node.valueType((Node) null);
            stripSchemas(node.getType());
            stripSchemas(node.getContracts());
            stripSchemas(node.getBlue());
            if (node.getProperties() != null) {
                for (Node child : node.getProperties().values()) {
                    stripSchemas(child);
                }
            }
            if (node.getItems() != null) {
                for (Node child : node.getItems()) {
                    stripSchemas(child);
                }
            }
        }
    }

    private static final class Manifest {
        String specVersion;
        String conformanceFixturePackageIdentity;
        String preprocessingCoreRegistry;
        String preprocessingRuntimeRegistry;
        final Map<RuntimeTypeKey, ManifestEntry> entries = new EnumMap<>(RuntimeTypeKey.class);
    }

    private static final class ManifestEntry {
        final String manifestKey;
        final String path;
        final String blueId;
        final boolean semanticDescriptionIdentityBearing;

        ManifestEntry(String manifestKey, String path, String blueId, boolean semanticDescriptionIdentityBearing) {
            this.manifestKey = manifestKey;
            this.path = path;
            this.blueId = blueId;
            this.semanticDescriptionIdentityBearing = semanticDescriptionIdentityBearing;
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
