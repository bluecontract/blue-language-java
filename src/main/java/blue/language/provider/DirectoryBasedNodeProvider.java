package blue.language.provider;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.utils.BlueIdCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

public class DirectoryBasedNodeProvider extends PreloadedNodeProvider {

    private static final String BLUE_FILE_EXTENSION = ".blue";

    private Map<String, Object> blueIdToContentMap = new HashMap<>();
    private Map<String, Boolean> blueIdToMultipleDocumentsMap = new HashMap<>();
    private Function<Node, Node> preprocessor;

    public DirectoryBasedNodeProvider(String... directories) throws IOException {
        Preprocessor defaultPreprocessor = new Preprocessor(this);
        this.preprocessor = defaultPreprocessor::preprocessWithDefaultBlue;
        load(directories);
    }

    public DirectoryBasedNodeProvider(Function<Node, Node> preprocessor, String... directories) throws IOException {
        this.preprocessor = preprocessor;
        load(directories);
    }

    private void load(String... directories) throws IOException {
        for (String directory : directories) {
            Path path = Paths.get(directory);
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                throw new IOException("Directory does not exist or is not a directory: " + directory);
            }
            try (Stream<Path> paths = Files.walk(path)) {
                List<Path> pathList = paths
                        .filter(Files::isRegularFile)
                        .collect(Collectors.toList());
                for (Path p : pathList) {
                    String content = new String(Files.readAllBytes(p));
                    if (p.toString().endsWith(BLUE_FILE_EXTENSION)) {
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

    private void processContent(String content) {
        NodeContentHandler.ParsedContent parsedContent = NodeContentHandler.parseAndCalculateBlueId(content, preprocessor);
        blueIdToContentMap.put(parsedContent.blueId, parsedContent.content);
        blueIdToMultipleDocumentsMap.put(parsedContent.blueId, parsedContent.isMultipleDocuments);

        if (parsedContent.content.isArray()) {
            List<Node> nodeList = new ArrayList<>();
            for (JsonNode element : parsedContent.content) {
                nodeList.add(JSON_MAPPER.treeToValue(element, Node.class));
            }
            IntStream.range(0, parsedContent.content.size()).forEach(i -> {
                JsonNode node = parsedContent.content.get(i);
                addNodeToNameMap(node, parsedContent.blueId + "#" + i);
            });
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