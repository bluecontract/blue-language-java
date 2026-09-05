package blue.language.processor;

import blue.language.codec.BlueFormat;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.runtime.BlueLanguage;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class RegistryCompatibilityInventoryTest {
    @Test
    void authenticateRegistryContentThroughPublicParserAndDirectIdentity() throws Exception {
        List<Map<String, Object>> inventory = new ArrayList<>();
        Set<String> production = new HashSet<>();
        Set<String> fixtures = new HashSet<>();
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            for (String name : Arrays.asList("blue-language-1.0", "blue-contracts-1.0")) {
                String root = "/registry/" + name + "/";
                Map<String, Object> manifest = UncheckedObjectMapper.YAML_MAPPER.readValue(read(root + "manifest.yaml"),
                        new TypeReference<Map<String, Object>>() {});
                String packageIdentity = (String) manifest.get("packageIdentity");
                assertEquals(name.equals("blue-language-1.0") ? BlueCoreTypeRegistry.INSTANCE.packageIdentity()
                        : BlueRuntimeTypeRegistry.getDefault().registryIdentity(), packageIdentity);
                @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) manifest.get("entries");
                for (Map<String, Object> entry : entries) {
                    String file = root + entry.get("path");
                    byte[] bytes = read(file);
                    assertEquals(entry.get("sha256"), sha256(bytes), file);
                    Node node = language.codec().parseSource(new String(bytes, StandardCharsets.UTF_8), BlueFormat.YAML);
                    assertEquals(entry.get("blueId"), language.identity().directBlueId(node), file);
                    boolean fixture = Boolean.TRUE.equals(entry.get("fixtureOnly"));
                    (fixture ? fixtures : production).add((String) entry.get("blueId"));
                    Map<String, Object> row = new LinkedHashMap<>(entry);
                    row.put("definitionFile", file);
                    row.put("packageIdentity", packageIdentity);
                    row.put("classification", fixture ? "fixture" : "production");
                    row.put("parsedContent", UncheckedObjectMapper.JSON_MAPPER.readValue(language.codec().write(node, BlueFormat.JSON), Object.class));
                    row.put("consumers", name.equals("blue-language-1.0") ? "Language; Blue JS; authored/Java catalog; BEX; Coordination; MyOS" : "Contracts; generator/Java catalog; Coordination; MyOS; fixture runners (fixture entries only)");
                    inventory.add(row);
                }
            }
        }
        assertEquals(34, inventory.size());
        assertEquals(31, production.size());
        assertEquals(3, fixtures.size());
        Set<String> all = new HashSet<>(production);
        all.addAll(fixtures);
        for (Map<String, Object> row : inventory) {
            Set<String> dependencies = new TreeSet<>();
            collectReferences(row.get("parsedContent"), dependencies);
            assertTrue(all.containsAll(dependencies), "Unknown registry dependency: " + row.get("key"));
            if (row.get("classification").equals("production")) {
                assertTrue(Collections.disjoint(dependencies, fixtures), "Production depends on fixture: " + row.get("key"));
            }
            row.put("dependencies", dependencies);
        }
        Path output = Paths.get("build/stabilization/registry-compatibility-inventory.json");
        Files.createDirectories(output.getParent());
        UncheckedObjectMapper.JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), inventory);
    }

    private byte[] read(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private void collectReferences(Object value, Set<String> result) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.get("blueId") instanceof String) result.add((String) map.get("blueId"));
            for (Object child : map.values()) collectReferences(child, result);
        } else if (value instanceof List) {
            for (Object child : (List<?>) value) collectReferences(child, result);
        }
    }
}
