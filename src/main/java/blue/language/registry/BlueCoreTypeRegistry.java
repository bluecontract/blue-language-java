package blue.language.registry;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Eager, identity-verified registry of the six Blue Language 1.0 core types.
 *
 * <p>Initialization validates the manifest package identity, exact entry set,
 * resource digests, names, and calculated BlueIds. Returned nodes are
 * defensive copies and provider lookup is independently verified.</p>
 */
public final class BlueCoreTypeRegistry {

    /** Classpath root containing the canonical registry manifest and definitions. */
    public static final String RESOURCE_ROOT = "registry/blue-language-1.0";
    private static final String MANIFEST_RESOURCE = "manifest.yaml";
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final String SHA_256_PREFIX = "sha256:";
    private static final Set<String> REQUIRED_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    BlueLanguageConstants.TEXT_TYPE,
                    BlueLanguageConstants.INTEGER_TYPE,
                    BlueLanguageConstants.DOUBLE_TYPE,
                    BlueLanguageConstants.BOOLEAN_TYPE,
                    BlueLanguageConstants.DICTIONARY_TYPE,
                    BlueLanguageConstants.LIST_TYPE)));
    /** Shared immutable verified core registry. */
    public static final BlueCoreTypeRegistry INSTANCE = new BlueCoreTypeRegistry();

    private final Map<String, RegistryEntry> entries;
    private final NodeProvider provider;
    private final String packageIdentity;
    private final String fixturePackageIdentity;

    private BlueCoreTypeRegistry() {
        Manifest manifest = loadManifest();
        this.entries = loadEntries(manifest);
        this.packageIdentity = manifest.packageIdentity;
        this.fixturePackageIdentity = manifest.fixturePackageIdentity;
        NodeProvider verifiedProvider = new VerifyingNodeProvider(new RegistryNodeProvider(entries));
        this.provider = blueId -> blueId != null
                && !BlueIds.hasCyclicMemberSeparator(blueId)
                && BlueIds.isPotentialBlueId(blueId)
                ? verifiedProvider.fetchByBlueId(blueId)
                : null;
    }

    /**
     * Returns a defensive mutable copy of a core type definition.
     *
     * @param name canonical core type name
     * @return mutable definition copy
     * @throws IllegalArgumentException when the name is unknown
     */
    public Node node(String name) {
        return entry(name).node.clone();
    }

    /**
     * Returns the exact identity of a core type.
     *
     * @param name canonical core type name
     * @return core type BlueId
     * @throws IllegalArgumentException when the name is unknown
     */
    public String blueId(String name) {
        return entry(name).blueId;
    }

    /**
     * Returns the insertion-ordered core identity catalog.
     *
     * @return unmodifiable name-to-BlueId map
     */
    public Map<String, String> blueIdsByName() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, RegistryEntry> entry : entries.entrySet()) {
            result.put(entry.getKey(), entry.getValue().blueId);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the exact registry package identity.
     *
     * @return registry package identity
     */
    public String packageIdentity() {
        return packageIdentity;
    }

    /**
     * Returns the exact fixture package identity bound by the registry.
     *
     * @return fixture package identity
     */
    public String fixturePackageIdentity() {
        return fixturePackageIdentity;
    }

    /**
     * Returns the registry's read-only identity-verifying provider.
     *
     * @return verified core registry provider
     */
    public NodeProvider verifiedProvider() {
        return provider;
    }

    private RegistryEntry entry(String name) {
        Objects.requireNonNull(name, "name");
        RegistryEntry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown Blue Language core type: " + name);
        }
        return entry;
    }

    private Manifest loadManifest() {
        try (InputStream input = resource(MANIFEST_RESOURCE)) {
            Map<String, Object> raw = UncheckedObjectMapper.YAML_MAPPER.readValue(input,
                    new TypeReference<Map<String, Object>>() {
                    });
            Manifest manifest = new Manifest();
            Object specVersion = raw.get(
                    RegistryManifestConstants
                            .FIELD_SPECIFICATION_VERSION);
            if (!RegistryManifestConstants.VERSION_1_0.equals(
                    specVersion)) {
                throw new IllegalStateException("Unsupported Blue Language core registry version: " + specVersion);
            }
            if (!RegistryManifestConstants
                    .REGISTRY_LANGUAGE_CORE
                    .equals(raw.get(
                            RegistryManifestConstants
                                    .FIELD_REGISTRY))
                    || !RegistryManifestConstants.KIND_CORE_TYPE
                    .equals(raw.get(
                            RegistryManifestConstants
                                    .FIELD_REGISTRY_KIND))) {
                throw new IllegalStateException("Unexpected Blue Language core registry identity");
            }
            verifyPackageIdentity(raw);
            manifest.packageIdentity = requiredText(
                    raw,
                    RegistryManifestConstants
                            .FIELD_PACKAGE_IDENTITY);
            manifest.fixturePackageIdentity = requiredText(
                    raw,
                    RegistryManifestConstants
                            .FIELD_FIXTURE_PACKAGE_IDENTITY);
            Object entriesObject = raw.get(
                    RegistryManifestConstants.FIELD_ENTRIES);
            if (!(entriesObject instanceof List)) {
                throw new IllegalStateException("Blue Language core registry manifest must contain an entries list");
            }
            for (Object rawEntry : (List<?>) entriesObject) {
                if (!(rawEntry instanceof Map)) {
                    throw new IllegalStateException("Blue Language core registry entry must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) rawEntry;
                String key = requiredText(
                        entry,
                        RegistryManifestConstants.FIELD_KEY);
                if (manifest.entries.containsKey(key)) {
                    throw new IllegalStateException("Duplicate Blue Language core registry key: " + key);
                }
                manifest.entries.put(key, new ManifestEntry(
                        requiredText(
                                entry,
                                RegistryManifestConstants.FIELD_PATH),
                        requiredText(
                                entry,
                                RegistryManifestConstants.FIELD_BLUE_ID),
                        requiredText(
                                entry,
                                RegistryManifestConstants.FIELD_SHA256)));
            }
            if (!manifest.entries.keySet().equals(REQUIRED_KEYS)) {
                throw new IllegalStateException("Blue Language core registry must contain exactly "
                        + REQUIRED_KEYS + " but found " + manifest.entries.keySet());
            }
            return manifest;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load Blue Language core registry manifest", ex);
        }
    }

    static void verifyPackageIdentity(Map<String, Object> raw) {
        String declared = requiredText(
                raw,
                RegistryManifestConstants.FIELD_PACKAGE_IDENTITY);
        String calculated = computePackageIdentity(raw);
        if (!declared.equals(calculated)) {
            throw new IllegalStateException("Blue Language core registry package identity mismatch: "
                    + "manifest=" + declared + ", calculated=" + calculated);
        }
    }

    static String computePackageIdentity(Map<String, Object> raw) {
        try {
            Map<String, Object> normalized = new LinkedHashMap<>(raw);
            normalized.put(
                    RegistryManifestConstants
                            .FIELD_PACKAGE_IDENTITY,
                    null);
            normalized.put(
                    RegistryManifestConstants
                            .FIELD_FIXTURE_PACKAGE_IDENTITY,
                    null);
            byte[] canonicalJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(canonicalizeJsonValue(normalized));
            return SHA_256_PREFIX + sha256Hex(canonicalJson);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to calculate Blue Language core registry package identity", ex);
        }
    }

    private static Object canonicalizeJsonValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>(String::compareTo);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                sorted.put(String.valueOf(entry.getKey()),
                        canonicalizeJsonValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> values = new ArrayList<>(((List<?>) value).size());
            for (Object element : (List<?>) value) {
                values.add(canonicalizeJsonValue(element));
            }
            return values;
        }
        return value;
    }

    private Map<String, RegistryEntry> loadEntries(Manifest manifest) {
        Map<String, RegistryEntry> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, ManifestEntry> manifestEntry : manifest.entries.entrySet()) {
            String name = manifestEntry.getKey();
            ManifestEntry entry = manifestEntry.getValue();
            String path = entry.path;
            byte[] bytes;
            Node node;
            try (InputStream input = resource(path)) {
                bytes = readAll(input);
                node = UncheckedObjectMapper.YAML_MAPPER.readValue(bytes, Node.class);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to load Blue Language core registry node " + path, ex);
            }
            String fileDigest = sha256Hex(bytes);
            if (!entry.sha256.equals(fileDigest)) {
                throw new IllegalStateException("Core registry file digest mismatch for " + name
                        + ": manifest=" + entry.sha256 + ", calculated=" + fileDigest);
            }
            if (!name.equals(node.getName())) {
                throw new IllegalStateException("Core registry node " + path + " has name " + node.getName()
                        + " instead of " + name);
            }
            String calculated = BlueIdCalculator.calculateBlueId(node);
            if (!entry.blueId.equals(calculated)) {
                throw new IllegalStateException("Core registry BlueId mismatch for " + name
                        + ": manifest=" + entry.blueId + ", calculated=" + calculated);
            }
            loaded.put(name, new RegistryEntry(path, entry.blueId, node));
        }
        return Collections.unmodifiableMap(loaded);
    }

    private static String requiredText(Map<String, Object> map, String field) {
        Object value = map.get(field);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalStateException("Blue Language core registry field must be non-empty: " + field);
        }
        return (String) value;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance(
                    SHA_256_ALGORITHM).digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static InputStream resource(String path) throws IOException {
        String fullPath = RESOURCE_ROOT + "/" + path;
        InputStream input = BlueCoreTypeRegistry.class.getClassLoader().getResourceAsStream(fullPath);
        if (input == null) {
            throw new IOException("Missing Blue Language core registry resource: " + fullPath);
        }
        return input;
    }

    private static final class RegistryNodeProvider implements NodeProvider {
        private final Map<String, Node> nodesByBlueId;

        RegistryNodeProvider(Map<String, RegistryEntry> entries) {
            Map<String, Node> nodes = new LinkedHashMap<>();
            for (RegistryEntry entry : entries.values()) {
                nodes.put(entry.blueId, entry.node.clone());
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
        final Map<String, ManifestEntry> entries = new LinkedHashMap<>();
        String packageIdentity;
        String fixturePackageIdentity;
    }

    private static final class ManifestEntry {
        final String path;
        final String blueId;
        final String sha256;

        ManifestEntry(String path, String blueId, String sha256) {
            this.path = path;
            this.blueId = blueId;
            this.sha256 = sha256;
        }
    }

    private static final class RegistryEntry {
        final String path;
        final String blueId;
        final Node node;

        RegistryEntry(String path, String blueId, Node node) {
            this.path = path;
            this.blueId = blueId;
            this.node = node;
        }
    }
}
