package blue.language.provider;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

public class ClasspathBasedNodeProvider extends PreloadedNodeProvider {

    private static final String BLUE_FILE_EXTENSION = ".blue";

    private Map<String, Object> blueIdToContentMap;
    private Map<String, Boolean> blueIdToMultipleDocumentsMap;

    public ClasspathBasedNodeProvider(String... classpathDirectories) throws IOException {
        this.blueIdToContentMap = new HashMap<>();
        this.blueIdToMultipleDocumentsMap = new HashMap<>();
        load(classpathDirectories);
    }

    private void load(String... classpathDirectories) throws IOException {
        for (String directory : classpathDirectories) {
            ClassLoader classLoader = getClass().getClassLoader();
            URL directoryUrl = classLoader.getResource(directory);
            if (directoryUrl == null) {
                throw new IOException("Directory not found in classpath: " + directory);
            }

            Set<String> resources = getResourcesFromDirectory(classLoader, directory);
            for (String resource : resources) {
                try (InputStream inputStream = classLoader.getResourceAsStream(resource)) {
                    if (inputStream == null) {
                        continue;
                    }
                    String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    if (resource.endsWith(BLUE_FILE_EXTENSION)) {
                        processContent(content);
                    } else {
                        String blueId = BlueIdCalculator.calculateBlueId(new Node().value(content));
                        blueIdToContentMap.put(blueId, content);
                        blueIdToMultipleDocumentsMap.put(blueId, false);
                    }
                }
            }
        }
    }

    private Set<String> getResourcesFromDirectory(ClassLoader classLoader, String directory) throws IOException {
        Set<String> resources = new HashSet<>();
        Enumeration<URL> urls = classLoader.getResources(directory);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (url.getProtocol().equals("file")) {
                try {
                    java.nio.file.Path path = java.nio.file.Paths.get(url.toURI());
                    java.nio.file.Files.walk(path)
                            .filter(java.nio.file.Files::isRegularFile)
                            .forEach(file -> resources.add(directory + "/" + path.relativize(file)));
                } catch (java.net.URISyntaxException e) {
                    throw new IOException("Failed to convert URL to URI", e);
                }
            } else if (url.getProtocol().equals("jar")) {
                String jarPath = url.getPath().substring(5, url.getPath().indexOf("!"));
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
                    Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith(directory + "/") && !name.endsWith("/")) {
                            resources.add(name);
                        }
                    }
                }
            }
        }
        return resources;
    }

    private List<String> getResourcesFromFileSystem(String directory) throws IOException {
        return java.nio.file.Files.walk(java.nio.file.Paths.get(directory))
                .filter(java.nio.file.Files::isRegularFile)
                .map(path -> directory + "/" + path.getFileName().toString())
                .collect(Collectors.toList());
    }

    private void processContent(String content) {
        NodeContentHandler.ParsedContent parsedContent = NodeContentHandler.parseAndCalculateBlueId(content);
        blueIdToContentMap.put(parsedContent.blueId, parsedContent.content);
        blueIdToMultipleDocumentsMap.put(parsedContent.blueId, parsedContent.isMultipleDocuments);

        if (parsedContent.content.isArray()) {
            processNodeList(JSON_MAPPER.convertValue(parsedContent.content, List.class));
            for (int i = 0; i < parsedContent.content.size(); i++) {
                JsonNode node = parsedContent.content.get(i);
                addNodeToNameMap(node, parsedContent.blueId + "#" + i);
            }
        } else {
            addNodeToNameMap(parsedContent.content, parsedContent.blueId);
        }
    }

    private void addNodeToNameMap(JsonNode node, String blueId) {
        JsonNode nameNode = node.get("name");
        if (nameNode != null && !nameNode.isNull()) {
            String name = nameNode.asText();
            addToNameMap(name, blueId);
        }
    }

    private void processNodeList(List<Node> nodes) {
        String listBlueId = BlueIdCalculator.calculateBlueId(nodes);
        JsonNode listContent = JSON_MAPPER.valueToTree(nodes);
        blueIdToContentMap.put(listBlueId, listContent);
        blueIdToMultipleDocumentsMap.put(listBlueId, true);
    }

    @Override
    protected JsonNode fetchContentByBlueId(String baseBlueId) {
        Object content = blueIdToContentMap.get(baseBlueId);
        Boolean isMultipleDocuments = blueIdToMultipleDocumentsMap.get(baseBlueId);
        if (content != null && isMultipleDocuments != null) {
            if (content instanceof JsonNode) {
                return NodeContentHandler.resolveThisReferences((JsonNode) content, baseBlueId, isMultipleDocuments);
            } else if (content instanceof String) {
                return JSON_MAPPER.valueToTree(content);
            }
        }
        return null;
    }

    public Map<String, Object> getBlueIdToContentMap() {
        return new HashMap<>(blueIdToContentMap);
    }
}