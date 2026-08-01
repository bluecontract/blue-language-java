package blue.language.provider;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.CircularBlueIdCalculator;
import blue.language.utils.Nodes;
import blue.language.utils.Properties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

/**
 * Mutable in-memory provider for tests, local tooling, and bootstrap assembly.
 *
 * <p>Added documents are preprocessed, assigned their direct BlueIds, and
 * indexed by optional names. Multi-document cyclic sets retain complete
 * placeholder-set proof for independent verification.</p>
 */
public class BasicNodeProvider extends PreloadedNodeProvider implements CyclicAwareNodeProvider {

    private Map<String, JsonNode> blueIdToContentMap;
    private Map<String, Boolean> blueIdToMultipleDocumentsMap;
    private Map<String, CyclicSetProof> cyclicSetProofByMasterBlueId;
    private Function<Node, Node> preprocessor;

    /**
     * Creates a provider and ingests each supplied node independently.
     *
     * @param nodes exact nodes to ingest
     */
    public BasicNodeProvider(Node... nodes) {
        this(Arrays.asList(nodes));
    }

    /**
     * Creates a provider and ingests each supplied node independently.
     *
     * @param nodes exact nodes to ingest
     */
    public BasicNodeProvider(Collection<Node> nodes) {
        this.blueIdToContentMap = new HashMap<>();
        this.blueIdToMultipleDocumentsMap = new HashMap<>();
        this.cyclicSetProofByMasterBlueId = new HashMap<>();

        Preprocessor defaultPreprocessor = new Preprocessor(this);
        this.preprocessor = defaultPreprocessor::preprocess;

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
        cyclicSetProofByMasterBlueId.remove(parsedContent.blueId);
        addToNameMap(node.getName(), parsedContent.blueId);
    }

    private void processSingleNodeUnchecked(Node node) {
        Node preprocessed = preprocessor.apply(node);
        String blueId = BlueIdCalculator.calculateUncheckedBlueId(preprocessed);
        blueIdToContentMap.put(blueId, JSON_MAPPER.valueToTree(preprocessed));
        blueIdToMultipleDocumentsMap.put(blueId, false);
        cyclicSetProofByMasterBlueId.remove(blueId);
        addToNameMap(node.getName(), blueId);
    }

    private void processNodeWithItems(Node node) {
        List<Node> items = node.getItems();
        NodeContentHandler.ParsedContent parsedContent = NodeContentHandler.parseAndCalculateBlueId(items, preprocessor);
        blueIdToContentMap.put(parsedContent.blueId, parsedContent.content);
        blueIdToMultipleDocumentsMap.put(parsedContent.blueId, true);
        retainCyclicSetProof(parsedContent);

        IntStream.range(0, parsedContent.content.size()).forEach(i -> {
            JsonNode item = parsedContent.content.get(i);
            JsonNode name = item.get(Properties.OBJECT_NAME);
            if (name != null && !name.isNull()) {
                addToNameMap(
                        name.asText(),
                        BlueIds.indexedCyclicMemberBlueId(
                                parsedContent.blueId, i));
            }
        });
    }

    /**
     * Ingests the list as one content-addressed multi-document value.
     *
     * @param nodes ordered document set to ingest
     */
    public void processNodeList(List<Node> nodes) {
        NodeContentHandler.ParsedContent parsedContent = NodeContentHandler.parseAndCalculateBlueId(nodes, preprocessor);
        blueIdToContentMap.put(parsedContent.blueId, parsedContent.content);
        blueIdToMultipleDocumentsMap.put(parsedContent.blueId, true);
        retainCyclicSetProof(parsedContent);
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

    @Override
    public boolean hasVerifiedContentForBlueId(String blueId) {
        int memberSeparator =
                BlueIds.cyclicMemberSeparatorIndex(blueId);
        if (memberSeparator < 0) {
            return blueIdToContentMap.containsKey(blueId);
        }
        String baseBlueId =
                blueId.substring(0, memberSeparator);
        JsonNode content =
                blueIdToContentMap.get(baseBlueId);
        if (!Boolean.TRUE.equals(
                blueIdToMultipleDocumentsMap.get(
                        baseBlueId))
                || content == null
                || !content.isArray()) {
            return false;
        }
        final int memberIndex;
        try {
            memberIndex = Integer.parseInt(
                    blueId.substring(
                            memberSeparator + 1));
        } catch (NumberFormatException invalidIndex) {
            return false;
        }
        return memberIndex >= 0
                && memberIndex < content.size();
    }

    @Override
    public CyclicSetProofResult cyclicSetProofFor(String blueId) {
        int memberSeparator =
                BlueIds.cyclicMemberSeparatorIndex(blueId);
        if (memberSeparator < 0) {
            return CyclicSetProofResult.notFound();
        }
        CyclicSetProof proof = cyclicSetProofByMasterBlueId.get(
                blueId.substring(0, memberSeparator));
        return proof == null
                ? CyclicSetProofResult.notFound()
                : CyclicSetProofResult.found(proof);
    }

    private void retainCyclicSetProof(
            NodeContentHandler.ParsedContent parsedContent) {
        cyclicSetProofByMasterBlueId.remove(parsedContent.blueId);
        if (!parsedContent.isMultipleDocuments
                || !parsedContent.content.isArray()) {
            return;
        }
        List<Node> placeholders = new ArrayList<>(
                parsedContent.content.size());
        for (JsonNode member : parsedContent.content) {
            placeholders.add(JSON_MAPPER.convertValue(member, Node.class));
        }
        final List<String> calculatedMemberBlueIds;
        try {
            calculatedMemberBlueIds =
                    CircularBlueIdCalculator.calculateCircularSetBlueIds(
                            placeholders);
        } catch (IllegalArgumentException notACyclicSet) {
            return;
        }
        for (int index = 0; index < calculatedMemberBlueIds.size(); index++) {
            if (!BlueIds.indexedCyclicMemberBlueId(
                    parsedContent.blueId, index).equals(
                    calculatedMemberBlueIds.get(index))) {
                return;
            }
        }
        cyclicSetProofByMasterBlueId.put(
                parsedContent.blueId,
                CyclicSetProof.fromDeclaredPlaceholderSet(placeholders));
    }

    /**
     * Ingests each supplied node as an independent document.
     *
     * @param nodes exact nodes to ingest
     */
    public void addSingleNodes(Node... nodes) {
        Arrays.stream(nodes).forEach(this::processNode);
    }

    /**
     * Parses and ingests each YAML or JSON source as an independent document.
     *
     * @param docs source documents to ingest
     */
    public void addSingleDocs(String... docs) {
        Arrays.stream(docs)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .forEach(this::processNode);
    }

    /**
     * Ingests source strings using unchecked identity calculation.
     *
     * <p>This compatibility helper does not relax verification performed by a
     * wrapped runtime provider.</p>
     *
     * @param docs source documents to ingest
     */
    public void addSingleDocsUnchecked(String... docs) {
        Arrays.stream(docs)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .forEach(this::processSingleNodeUnchecked);
    }

    /**
     * Returns the first identity registered for a name.
     *
     * @param name indexed node name
     * @return first registered BlueId
     * @throws RuntimeException when the name is absent
     */
    public String getBlueIdByName(String name) {
        return nameToBlueIdsMap.get(name).get(0);
    }

    /**
     * Returns a uniquely named node.
     *
     * @param name indexed node name
     * @return uniquely named node
     * @throws IllegalArgumentException when the name is absent
     * @throws IllegalStateException when the name is ambiguous
     */
    public Node getNodeByName(String name) {
        return findNodeByName(name).orElseThrow(() -> new IllegalArgumentException("No node with name \"" + name + "\""));
    }

    /**
     * Ingests a list as a set and also indexes every item independently.
     *
     * @param list ordered documents to ingest
     */
    public void addListAndItsItems(List<Node> list) {
        processNodeList(list);
        list.forEach(this::processNode);
    }

    /**
     * Parses a source list, ingests it as a set, and indexes every item.
     *
     * @param doc YAML or JSON source containing a list node
     */
    public void addListAndItsItems(String doc) {
        Node listNode = YAML_MAPPER.readValue(doc, Node.class);
        addListAndItsItems(listNode.getItems());
    }

    /**
     * Ingests a list only as one content-addressed set.
     *
     * @param list ordered documents to ingest
     */
    public void addList(List<Node> list) {
        processNodeList(list);
    }
}
