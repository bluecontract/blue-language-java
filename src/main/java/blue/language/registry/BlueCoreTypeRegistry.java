package blue.language.registry;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BlueCoreTypeRegistry {

    public static final String RESOURCE_ROOT = "registry/blue-language-1.0";
    public static final BlueCoreTypeRegistry INSTANCE = new BlueCoreTypeRegistry();

    private final Map<String, RegistryEntry> entries;
    private final NodeProvider provider;

    private BlueCoreTypeRegistry() {
        Manifest manifest = loadManifest();
        this.entries = loadEntries(manifest);
        NodeProvider verifiedProvider = new VerifyingNodeProvider(new RegistryNodeProvider(entries));
        this.provider = blueId -> blueId != null
                && blueId.indexOf('#') < 0
                && BlueIds.isPotentialBlueId(blueId)
                ? verifiedProvider.fetchByBlueId(blueId)
                : null;
    }

    public Node node(String name) {
        return entry(name).node.clone();
    }

    public String blueId(String name) {
        return entry(name).blueId;
    }

    public Map<String, String> blueIdsByName() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, RegistryEntry> entry : entries.entrySet()) {
            result.put(entry.getKey(), entry.getValue().blueId);
        }
        return Collections.unmodifiableMap(result);
    }

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
        try (InputStream input = resource("manifest.yaml")) {
            Map<String, Object> raw = UncheckedObjectMapper.YAML_MAPPER.readValue(input,
                    new TypeReference<Map<String, Object>>() {
                    });
            Manifest manifest = new Manifest();
            Object specVersion = raw.get("specVersion");
            if (!"1.0".equals(specVersion)) {
                throw new IllegalStateException("Unsupported Blue Language core registry version: " + specVersion);
            }
            Object entriesObject = raw.get("entries");
            if (!(entriesObject instanceof Map)) {
                throw new IllegalStateException("Blue Language core registry manifest must contain an entries map");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entryMap = (Map<String, Object>) entriesObject;
            for (Map.Entry<String, Object> entry : entryMap.entrySet()) {
                if (!(entry.getValue() instanceof String) || ((String) entry.getValue()).isEmpty()) {
                    throw new IllegalStateException("Core registry BlueId must be a non-empty string: " + entry.getKey());
                }
                manifest.entries.put(entry.getKey(), (String) entry.getValue());
            }
            return manifest;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load Blue Language core registry manifest", ex);
        }
    }

    private Map<String, RegistryEntry> loadEntries(Manifest manifest) {
        Map<String, RegistryEntry> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, String> manifestEntry : manifest.entries.entrySet()) {
            String name = manifestEntry.getKey();
            String path = name + ".blue";
            Node node;
            try (InputStream input = resource(path)) {
                node = UncheckedObjectMapper.YAML_MAPPER.readValue(input, Node.class);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to load Blue Language core registry node " + path, ex);
            }
            if (!name.equals(node.getName())) {
                throw new IllegalStateException("Core registry node " + path + " has name " + node.getName()
                        + " instead of " + name);
            }
            String calculated = BlueIdCalculator.calculateBlueId(node);
            if (!manifestEntry.getValue().equals(calculated)) {
                throw new IllegalStateException("Core registry BlueId mismatch for " + name
                        + ": manifest=" + manifestEntry.getValue() + ", calculated=" + calculated);
            }
            loaded.put(name, new RegistryEntry(path, manifestEntry.getValue(), node));
        }
        return Collections.unmodifiableMap(loaded);
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
        final Map<String, String> entries = new LinkedHashMap<>();
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
