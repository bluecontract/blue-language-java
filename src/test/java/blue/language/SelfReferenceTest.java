package blue.language;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.NodeContentHandler;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.CircularBlueIdCalculator;
import blue.language.utils.NodeExtender;
import blue.language.utils.limits.PathLimits;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class SelfReferenceTest {

    @Test
    public void testSingleDoc() throws Exception {

        String a = "name: A\n" +
                   "x:\n" +
                   "  type:\n" +
                   "    blueId: this";

        Map<String, Node> nodes = Stream.of(a)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodeProvider nodeProvider = new BasicNodeProvider(nodes.values());

        Node aNode = nodeProvider.findNodeByName("A").orElseThrow(() -> new IllegalArgumentException("No A node found"));
        String aNodeBlueId = nodeProvider.getBlueIdByName("A");
        Node extended = aNode.clone();
        assertThrows(IllegalArgumentException.class,
                () -> new NodeExtender(nodeProvider).extend(extended, PathLimits.withSinglePath("/x/x/x/x")));
        assertEquals(aNodeBlueId, aNode.getAsNode("/x/type").getBlueId());

    }

    @Test
    public void testSingleDocSelfReferenceBlueIdUsesZeroPlaceholder() throws Exception {
        String selfReferencing = "name: A\n" +
                   "x:\n" +
                   "  type:\n" +
                   "    blueId: this";
        String withPlaceholder = "name: A\n" +
                   "x:\n" +
                   "  type:\n" +
                   "    blueId: \"" + NodeContentHandler.ZERO_BLUE_ID + "\"";

        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(selfReferencing, Node.class));
        Node preprocessedPlaceholder = new Preprocessor(new BasicNodeProvider())
                .preprocessWithDefaultBlue(YAML_MAPPER.readValue(withPlaceholder, Node.class));

        assertEquals(
                BlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(preprocessedPlaceholder),
                nodeProvider.getBlueIdByName("A"));
    }

    @Test
    public void testThisTextValuesAreNotRewrittenAsReferences() {
        String doc = "name: A\n" +
                "literal: this\n" +
                "x:\n" +
                "  type:\n" +
                "    blueId: this";

        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(doc, Node.class));
        Node fetched = nodeProvider.getNodeByName("A");

        assertEquals("this", fetched.getAsText("/literal"));
        assertEquals(nodeProvider.getBlueIdByName("A"), fetched.getAsNode("/x/type").getBlueId());
    }

    @Test
    public void testTwoInterconnectedDocs() throws Exception {

        String ab = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "  aVal:\n" +
                    "    schema:\n" +
                    "      maxLength: 4\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "  bVal:\n" +
                    "    schema:\n" +
                    "      maxLength: 4\n" +
                    "  bConst: xyz";

        Node my = YAML_MAPPER.readValue(ab, Node.class);

        BasicNodeProvider nodeProvider = new BasicNodeProvider(my);

        Node aNode = nodeProvider.findNodeByName("A").orElseThrow(() -> new IllegalArgumentException("No A node found"));
        String aNodeBlueId = nodeProvider.getBlueIdByName("A");
        String bNodeBlueId = nodeProvider.getBlueIdByName("B");

        Node extendedA = aNode.clone();
        Node extendedB = nodeProvider.findNodeByName("B").orElseThrow(() -> new IllegalArgumentException("No B node found")).clone();
        new NodeExtender(nodeProvider).extend(extendedA, PathLimits.withSinglePath("/x/y/x/y"));
        new NodeExtender(nodeProvider).extend(extendedB, PathLimits.withSinglePath("/y/x/y/x"));

        assertEquals(bNodeBlueId, extendedA.getAsNode("/x/type").getBlueId());
        assertEquals("B", extendedA.getAsText("/x/type/name"));
        assertEquals(aNodeBlueId, extendedB.getAsNode("/y/type").getBlueId());
        assertEquals("A", extendedB.getAsText("/y/type/name"));
        assertEquals(aNodeBlueId, extendedA.getAsNode("/x/type/y/type").getBlueId());


        String instance = "name: Some\n" +
                          "a:\n" +
                          "  type:\n" +
                          "    blueId: " + aNodeBlueId + "\n" +
                          "  aVal: abcd\n" +
                          "  x:\n" +
                          "    bVal: abcd";

        Blue blue = new Blue(nodeProvider);
        Node result = blue.resolve(blue.preprocess(blue.yamlToNode(instance)), PathLimits.withSinglePath("/*/*/*"));
        assertEquals("xyz", result.getAsText("/a/x/bConst"));


        String errorInstance = "name: Some\n" +
                               "a:\n" +
                               "  type: \n" +
                               "    blueId: " + aNodeBlueId + "\n" +
                               "  aVal: abcd\n" +
                               "  x:\n" +
                               "    bVal: abcd\n" +
                               "    y:\n" +
                               "      aVal: TOO_LONG";

        assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(blue.preprocess(blue.yamlToNode(errorInstance)), PathLimits.withSinglePath("/*/*/*/*")));
    }

    @Test
    public void testCyclicMultiDocumentBlueIdsAreStableAcrossAuthoringOrder() {
        String ab = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "  aVal: A\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "  bVal: B";
        String ba = "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "  bVal: B\n" +
                    "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "  aVal: A";

        BasicNodeProvider providerAB = new BasicNodeProvider(YAML_MAPPER.readValue(ab, Node.class));
        BasicNodeProvider providerBA = new BasicNodeProvider(YAML_MAPPER.readValue(ba, Node.class));

        assertEquals(providerAB.getBlueIdByName("A"), providerBA.getBlueIdByName("A"));
        assertEquals(providerAB.getBlueIdByName("B"), providerBA.getBlueIdByName("B"));
        assertEquals(baseBlueId(providerAB.getBlueIdByName("A")), baseBlueId(providerBA.getBlueIdByName("A")));
    }

    @Test
    public void testCyclicMultiDocumentSuffixesFollowPreliminaryPlaceholderSort() {
        String docs = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "  aVal: A\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "  bVal: B";
        String aWithPlaceholder = "name: A\n" +
                    "x:\n" +
                    "  type:\n" +
                    "    blueId: \"" + NodeContentHandler.ZERO_BLUE_ID + "\"\n" +
                    "aVal: A";
        String bWithPlaceholder = "name: B\n" +
                    "y:\n" +
                    "  type:\n" +
                    "    blueId: \"" + NodeContentHandler.ZERO_BLUE_ID + "\"\n" +
                    "bVal: B";

        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(docs, Node.class));
        String expectedFirstName = BlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(YAML_MAPPER.readValue(aWithPlaceholder, Node.class))
                .compareTo(BlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(YAML_MAPPER.readValue(bWithPlaceholder, Node.class))) <= 0
                ? "A" : "B";
        String masterBlueId = baseBlueId(nodeProvider.getBlueIdByName("A"));
        List<Node> fetched = nodeProvider.fetchByBlueId(masterBlueId);

        assertEquals(expectedFirstName, fetched.get(0).getName());
        assertEquals(masterBlueId + "#0", nodeProvider.getBlueIdByName(fetched.get(0).getName()));
        assertEquals(masterBlueId + "#1", nodeProvider.getBlueIdByName(fetched.get(1).getName()));
    }

    @Test
    public void testCyclicMultiDocumentReferencesAreRewrittenToSortedPositions() {
        String docs = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "  aVal: A\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "  bVal: B";

        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(docs, Node.class));
        Node a = nodeProvider.getNodeByName("A");
        Node b = nodeProvider.getNodeByName("B");

        assertEquals(nodeProvider.getBlueIdByName("B"), a.getAsNode("/x/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("A"), b.getAsNode("/y/type").getBlueId());
    }

    @Test
    public void testThreeDocumentCycleIsStableAcrossPermutationsAndFetchesByFinalSuffix() {
        String abc = "- name: A\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "- name: B\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#2\n" +
                    "- name: C\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#0";
        String cab = "- name: C\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "- name: A\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#2\n" +
                    "- name: B\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#0";

        BasicNodeProvider providerABC = new BasicNodeProvider(YAML_MAPPER.readValue(abc, Node.class));
        BasicNodeProvider providerCAB = new BasicNodeProvider(YAML_MAPPER.readValue(cab, Node.class));

        assertEquals(providerABC.getBlueIdByName("A"), providerCAB.getBlueIdByName("A"));
        assertEquals(providerABC.getBlueIdByName("B"), providerCAB.getBlueIdByName("B"));
        assertEquals(providerABC.getBlueIdByName("C"), providerCAB.getBlueIdByName("C"));

        Node a = providerABC.getNodeByName("A");
        Node b = providerABC.getNodeByName("B");
        Node c = providerABC.getNodeByName("C");
        assertEquals(providerABC.getBlueIdByName("B"), a.getAsNode("/next/type").getBlueId());
        assertEquals(providerABC.getBlueIdByName("C"), b.getAsNode("/next/type").getBlueId());
        assertEquals(providerABC.getBlueIdByName("A"), c.getAsNode("/next/type").getBlueId());

        String masterBlueId = baseBlueId(providerABC.getBlueIdByName("A"));
        List<Node> fetched = providerABC.fetchByBlueId(masterBlueId);
        assertEquals(3, fetched.size());
        IntStream.range(0, fetched.size()).forEach(i ->
                assertEquals(masterBlueId + "#" + i, providerABC.getBlueIdByName(fetched.get(i).getName())));
    }

    @Test
    public void circularSetCalculatorReturnsFinalMemberIdsInOriginalOrder() {
        String docs = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "  aVal: A\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "  bVal: B";
        List<Node> nodes = YAML_MAPPER.readValue(docs, Node.class).getItems();
        BasicNodeProvider provider = new BasicNodeProvider(YAML_MAPPER.readValue(docs, Node.class));

        List<String> ids = CircularBlueIdCalculator.calculateCircularSetBlueIds(nodes);

        assertEquals(provider.getBlueIdByName("A"), ids.get(0));
        assertEquals(provider.getBlueIdByName("B"), ids.get(1));
        assertEquals(baseBlueId(ids.get(0)), baseBlueId(ids.get(1)));
    }

    @Test
    public void circularSetCalculatorIsStableAcrossPermutations() {
        String abc = "- name: A\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "- name: B\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#2\n" +
                    "- name: C\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#0";
        String cab = "- name: C\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "- name: A\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#2\n" +
                    "- name: B\n" +
                    "  next:\n" +
                    "    type:\n" +
                    "      blueId: this#0";

        Map<String, String> abcIds = idsByName(YAML_MAPPER.readValue(abc, Node.class).getItems());
        Map<String, String> cabIds = idsByName(YAML_MAPPER.readValue(cab, Node.class).getItems());

        assertEquals(abcIds.get("A"), cabIds.get("A"));
        assertEquals(abcIds.get("B"), cabIds.get("B"));
        assertEquals(abcIds.get("C"), cabIds.get("C"));
    }

    @Test
    public void zeroPlaceholderIsRejectedInFinalBlueIdInput() {
        assertThrows(RuntimeException.class,
                () -> BlueIdCalculator.calculateBlueId(new Node().blueId(NodeContentHandler.ZERO_BLUE_ID)));
    }

    @Test
    public void circularSetWithoutInternalThisReferencesRejected() {
        List<Node> nodes = Arrays.asList(new Node().value("same"), new Node().value("same"));

        assertThrows(IllegalArgumentException.class, () -> CircularBlueIdCalculator.calculateCircularSetBlueIds(nodes));
    }

    @Test
    public void singleDocumentCycleUsesThisHashZero() {
        Node node = YAML_MAPPER.readValue("next:\n  blueId: this#0", Node.class);

        List<String> ids = CircularBlueIdCalculator.calculateCircularSetBlueIds(Arrays.asList(node));

        assertEquals(1, ids.size());
        assertTrue(ids.get(0).endsWith("#0"));
    }

    @Test
    public void bareThisRejectedInCircularApi() {
        Node node = YAML_MAPPER.readValue("next:\n  blueId: this", Node.class);

        assertThrows(IllegalArgumentException.class,
                () -> CircularBlueIdCalculator.calculateCircularSetBlueIds(Arrays.asList(node)));
    }

    @Test
    public void bareThisRejectedOutsideCircularApi() {
        assertThrows(RuntimeException.class, () -> BlueIdCalculator.calculateBlueId(new Node().blueId("this")));
        assertThrows(RuntimeException.class, () -> new Blue().parseBlueIdInputYaml("blueId: this"));
    }

    @Test
    public void duplicatePreliminaryIdsWithActualCycleUseOriginalIndexTieBreak() {
        List<Node> nodes = YAML_MAPPER.readValue(
                "- next:\n" +
                "    blueId: this#1\n" +
                "- next:\n" +
                "    blueId: this#0", Node.class).getItems();

        List<String> ids = CircularBlueIdCalculator.calculateCircularSetBlueIds(nodes);

        assertEquals(baseBlueId(ids.get(0)), baseBlueId(ids.get(1)));
        assertTrue(ids.get(0).endsWith("#0"));
        assertTrue(ids.get(1).endsWith("#1"));
    }

    @Test
    public void testThisReferencesAreRewrittenInTypeMetadata() {
        String docs = "- name: A\n" +
                    "  type:\n" +
                    "    blueId: this#1\n" +
                    "  itemType:\n" +
                    "    blueId: this#2\n" +
                    "  keyType:\n" +
                    "    blueId: this#1\n" +
                    "  valueType:\n" +
                    "    blueId: this#2\n" +
                    "- name: B\n" +
                    "  peer:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "- name: C\n" +
                    "  peer:\n" +
                    "    type:\n" +
                    "      blueId: this#0";

        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(docs, Node.class));
        Node a = nodeProvider.getNodeByName("A");

        assertEquals(nodeProvider.getBlueIdByName("B"), a.getType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("C"), a.getItemType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("B"), a.getKeyType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("C"), a.getValueType().getBlueId());
    }

    @Test
    public void testParsedCyclicSetStoresSortedDocumentsWithThisReferencesBeforeFetchTimeResolution() {
        String docs = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0";

        NodeContentHandler.ParsedContent parsed = NodeContentHandler.parseAndCalculateBlueId(docs, node -> node);
        List<Node> stored = Arrays.asList(JSON_MAPPER.treeToValue(parsed.content, Node[].class));
        assertEquals(BlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(stored), parsed.blueId);

        Map<String, Integer> nameToStoredIndex = IntStream.range(0, stored.size())
                .boxed()
                .collect(Collectors.toMap(i -> stored.get(i).getName(), i -> i));

        for (int i = 0; i < stored.size(); i++) {
            Node node = stored.get(i);
            if ("A".equals(node.getName())) {
                assertEquals("this#" + nameToStoredIndex.get("B"), node.getAsNode("/x/type").getBlueId());
            } else if ("B".equals(node.getName())) {
                assertEquals("this#" + nameToStoredIndex.get("A"), node.getAsNode("/y/type").getBlueId());
            } else {
                fail("Unexpected stored cyclic document: " + node.getName());
            }
        }
    }

    @Test
    public void testInvalidCyclicMultiDocumentReferencesAreRejectedAtIngestion() {
        String missingIndex = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this\n" +
                    "- name: B";
        String outOfRange = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#2\n" +
                    "- name: B";

        assertThrows(IllegalArgumentException.class,
                () -> new BasicNodeProvider(YAML_MAPPER.readValue(missingIndex, Node.class)));
        assertThrows(IllegalArgumentException.class,
                () -> new BasicNodeProvider(YAML_MAPPER.readValue(outOfRange, Node.class)));
    }

    @Test
    public void testInvalidSingleDocumentIndexedSelfReferenceIsRejectedAtIngestion() {
        String indexedSelf = "name: A\n" +
                    "x:\n" +
                    "  type:\n" +
                    "    blueId: this#0";

        assertThrows(IllegalArgumentException.class,
                () -> new BasicNodeProvider(YAML_MAPPER.readValue(indexedSelf, Node.class)));
    }

    private String baseBlueId(String blueId) {
        return blueId.split("#")[0];
    }

    private Map<String, String> idsByName(List<Node> nodes) {
        List<String> ids = CircularBlueIdCalculator.calculateCircularSetBlueIds(nodes);
        return IntStream.range(0, nodes.size())
                .boxed()
                .collect(Collectors.toMap(i -> nodes.get(i).getName(), ids::get));
    }

}
