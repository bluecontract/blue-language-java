package blue.language.provider;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.Nodes;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class BasicNodeProvider extends PreloadedNodeProvider {

    private Map<String, JsonNode> blueIdToContentMap;
    private Map<String, Boolean> blueIdToMultipleDocumentsMap;
    private Function<Node, Node> preprocessor;

    public BasicNodeProvider(Node... nodes) {
        this(Arrays.asList(nodes));
    }

    public BasicNodeProvider(Collection<Node> nodes) {
        this.blueIdToContentMap = new HashMap<>();
        this.blueIdToMultipleDocumentsMap = new HashMap<>();

        Preprocessor defaultPreprocessor = new Preprocessor(this);
        this.preprocessor = defaultPreprocessor::preprocessWithDefaultBlue;

        nodes.forEach(this::processNode);
    }

    private void processNode(Node node) {
        if (Nodes.hasItemsOnly(node)) {
            processNodeWithItems(node);
        } else {
            processSingleNode(node);
        }
    }

    private void processSingleNode(Node node) {
        NodeContentHandler.ParsedContent parsedContent = NodeContentHandler.parseAndCalculateBlueId(node, preprocessor);
        blueIdToContentMap.put(parsedContent.blueId, parsedContent.content);
        blueIdToMultipleDocumentsMap.put(parsedContent.blueId, parsedContent.isMultipleDocuments);
        addToNameMap(node.getName(), parsedContent.blueId);
    }

    private void processNodeWithItems(Node node) {
        List<Node> items = node.getItems();
        processNodeList(items);

        NodeContentHandler.ParsedContent parsedContent = NodeContentHandler.parseAndCalculateBlueId(items, preprocessor);
        blueIdToContentMap.put(parsedContent.blueId, parsedContent.content);
        blueIdToMultipleDocumentsMap.put(parsedContent.blueId, true);

        IntStream.range(0, items.size()).forEach(i -> {
            Node item = items.get(i);
            addToNameMap(item.getName(), parsedContent.blueId + "#" + i);
        });
    }

    public void processNodeList(List<Node> nodes) {
        String listBlueId = BlueIdCalculator.calculateBlueId(nodes);
        JsonNode listContent = JSON_MAPPER.valueToTree(nodes);
        blueIdToContentMap.put(listBlueId, listContent);
        blueIdToMultipleDocumentsMap.put(listBlueId, true);
    }

    @Override
    protected JsonNode fetchContentByBlueId(String baseBlueId) {
        JsonNode content = blueIdToContentMap.get(baseBlueId);
        Boolean isMultipleDocuments = blueIdToMultipleDocumentsMap.get(baseBlueId);
        if (content != null && isMultipleDocuments != null) {
            return NodeContentHandler.resolveThisReferences(content, baseBlueId, isMultipleDocuments);
        }
        return null;
    }

    public void addSingleNodes(Node... nodes) {
        Arrays.stream(nodes).forEach(this::processNode);
    }

    public void addSingleDocs(String... docs) {
        Arrays.stream(docs)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .forEach(this::processNode);
    }

    public String getBlueIdByName(String name) {
        return nameToBlueIdsMap.get(name).get(0);
    }

    public Node getNodeByName(String name) {
        return findNodeByName(name).orElseThrow(() -> new IllegalArgumentException("No node with name \"" + name + "\""));
    }

    public void addListAndItsItems(List<Node> list) {
        processNodeList(list);
        list.forEach(this::processNode);
    }

    public void addListAndItsItems(String doc) {
        Node listNode = YAML_MAPPER.readValue(doc, Node.class);
        addListAndItsItems(listNode.getItems());
    }

    public void addList(List<Node> list) {
        processNodeList(list);
    }
}