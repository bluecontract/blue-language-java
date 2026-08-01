package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.NodeContentHandler;
import blue.language.utils.BlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.graph.NodeExpander;
import blue.language.utils.limits.PathLimits;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class SelfReferenceTest {

    private static final String INTERCONNECTED_CONSTANT_VALUE = "xyz";
    private static final String INTERCONNECTED_DOCUMENTS =
            "- name: A\n" +
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
            "  bConst: " + INTERCONNECTED_CONSTANT_VALUE;

    @Test
    public void shouldResolveSingleSelfReferentialDocument() throws Exception {

        // given
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
        Node expanded = aNode.clone();

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new NodeExpander(nodeProvider).expand(
                        expanded, PathLimits.withSinglePath("/x/x/x/x")));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(aNodeBlueId, aNode.getAsNode("/x/type").getBlueId());

    }

    @Test
    public void shouldUseZeroPlaceholderForSingleDocumentSelfReferenceBlueId() throws Exception {
        // given
        String selfReferencing = "name: A\n" +
                   "x:\n" +
                   "  type:\n" +
                   "    blueId: this";
        String withPlaceholder = "name: A\n" +
                   "x:\n" +
                   "  type:\n" +
                   "    blueId: \"" + BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER + "\"";

        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(selfReferencing, Node.class));
        // when
        Node preprocessedPlaceholder = new Preprocessor(new BasicNodeProvider())
                .preprocess(YAML_MAPPER.readValue(withPlaceholder, Node.class));

        // then
        assertEquals(
                DirectBlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(preprocessedPlaceholder),
                nodeProvider.getBlueIdByName("A"));
    }

    @Test
    public void shouldNotRewriteThisTextValuesAsReferences() {
        // given
        String doc = "name: A\n" +
                "literal: this\n" +
                "x:\n" +
                "  type:\n" +
                "    blueId: this";

        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(doc, Node.class));
        // when
        Node fetched = nodeProvider.getNodeByName("A");

        // then
        assertEquals("this", fetched.getAsText("/literal"));
        assertEquals(nodeProvider.getBlueIdByName("A"), fetched.getAsNode("/x/type").getBlueId());
    }

    @Test
    public void shouldExpandTwoInterconnectedDocumentsAcrossFinitePaths() {
        // given
        InterconnectedFixture fixture = new InterconnectedFixture();
        Node expandedA = fixture.documentA().clone();
        Node expandedB = fixture.documentB().clone();

        // when
        new NodeExpander(fixture.provider).expand(
                expandedA,
                PathLimits.withSinglePath("/x/y/x/y"));
        new NodeExpander(fixture.provider).expand(
                expandedB,
                PathLimits.withSinglePath("/y/x/y/x"));

        // then
        assertEquals(fixture.bBlueId, expandedA.getAsNode("/x/type").getBlueId());
        assertEquals("B", expandedA.getAsText("/x/type/name"));
        assertEquals(fixture.aBlueId, expandedB.getAsNode("/y/type").getBlueId());
        assertEquals("A", expandedB.getAsText("/y/type/name"));
        assertEquals(fixture.aBlueId, expandedA.getAsNode("/x/type/y/type").getBlueId());
    }

    @Test
    public void shouldResolveInheritedValuesAcrossInterconnectedDocuments() {
        // given
        InterconnectedFixture fixture = new InterconnectedFixture();
        String instance = "name: Some\n" +
                          "a:\n" +
                          "  type:\n" +
                          "    blueId: " + fixture.aBlueId + "\n" +
                          "  aVal: abcd\n" +
                          "  x:\n" +
                          "    bVal: abcd";

        // when
        Node result = fixture.blue.resolve(
                fixture.blue.preprocess(fixture.blue.yamlToNode(instance)),
                PathLimits.withSinglePath("/*/*/*"));

        // then
        assertEquals(INTERCONNECTED_CONSTANT_VALUE, result.getAsText("/a/x/bConst"));
    }

    @Test
    public void shouldRejectInvalidNestedValueAcrossInterconnectedDocuments() {
        // given
        InterconnectedFixture fixture = new InterconnectedFixture();
        String errorInstance = "name: Some\n" +
                               "a:\n" +
                               "  type: \n" +
                               "    blueId: " + fixture.aBlueId + "\n" +
                               "  aVal: abcd\n" +
                               "  x:\n" +
                               "    bVal: abcd\n" +
                               "    y:\n" +
                               "      aVal: TOO_LONG";

        // when
        IllegalArgumentException failure = captureFailure(
                () -> fixture.blue.resolve(
                        fixture.blue.preprocess(fixture.blue.yamlToNode(errorInstance)),
                        PathLimits.withSinglePath("/*/*/*/*")));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    public void shouldKeepCyclicMultiDocumentBlueIdsStableAcrossAuthoringOrder() {
        // given
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

        // when
        BasicNodeProvider providerAB = new BasicNodeProvider(YAML_MAPPER.readValue(ab, Node.class));
        BasicNodeProvider providerBA = new BasicNodeProvider(YAML_MAPPER.readValue(ba, Node.class));

        // then
        assertEquals(providerAB.getBlueIdByName("A"), providerBA.getBlueIdByName("A"));
        assertEquals(providerAB.getBlueIdByName("B"), providerBA.getBlueIdByName("B"));
        assertEquals(baseBlueId(providerAB.getBlueIdByName("A")), baseBlueId(providerBA.getBlueIdByName("A")));
    }

    @Test
    public void shouldAssignCyclicMultiDocumentSuffixesByPreliminaryPlaceholderSort() {
        // given
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
                    "    blueId: \"" + BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER + "\"\n" +
                    "aVal: A";
        String bWithPlaceholder = "name: B\n" +
                    "y:\n" +
                    "  type:\n" +
                    "    blueId: \"" + BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER + "\"\n" +
                    "bVal: B";

        // when
        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(docs, Node.class));
        String expectedFirstName = DirectBlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(YAML_MAPPER.readValue(aWithPlaceholder, Node.class))
                .compareTo(DirectBlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(YAML_MAPPER.readValue(bWithPlaceholder, Node.class))) <= 0
                ? "A" : "B";
        String masterBlueId = baseBlueId(nodeProvider.getBlueIdByName("A"));
        List<Node> fetched = nodeProvider.fetchByBlueId(masterBlueId);

        // then
        assertEquals(expectedFirstName, fetched.get(0).getName());
        assertEquals(masterBlueId + "#0", nodeProvider.getBlueIdByName(fetched.get(0).getName()));
        assertEquals(masterBlueId + "#1", nodeProvider.getBlueIdByName(fetched.get(1).getName()));
    }

    @Test
    public void shouldRewriteCyclicMultiDocumentReferencesToSortedPositions() {
        // given
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

        // when
        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(docs, Node.class));
        Node a = nodeProvider.getNodeByName("A");
        Node b = nodeProvider.getNodeByName("B");

        // then
        assertEquals(nodeProvider.getBlueIdByName("B"), a.getAsNode("/x/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("A"), b.getAsNode("/y/type").getBlueId());
    }

    @Test
    public void shouldKeepThreeDocumentCycleStableAcrossPermutationsAndFetchByFinalSuffix() {
        // given
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

        // when
        BasicNodeProvider providerABC = new BasicNodeProvider(YAML_MAPPER.readValue(abc, Node.class));
        BasicNodeProvider providerCAB = new BasicNodeProvider(YAML_MAPPER.readValue(cab, Node.class));
        Node a = providerABC.getNodeByName("A");
        Node b = providerABC.getNodeByName("B");
        Node c = providerABC.getNodeByName("C");
        String masterBlueId = baseBlueId(providerABC.getBlueIdByName("A"));
        List<Node> fetched = providerABC.fetchByBlueId(masterBlueId);
        List<String> fetchedIds = IntStream.range(0, fetched.size())
                .mapToObj(i -> providerABC.getBlueIdByName(fetched.get(i).getName()))
                .collect(Collectors.toList());

        // then
        assertEquals(providerABC.getBlueIdByName("A"), providerCAB.getBlueIdByName("A"));
        assertEquals(providerABC.getBlueIdByName("B"), providerCAB.getBlueIdByName("B"));
        assertEquals(providerABC.getBlueIdByName("C"), providerCAB.getBlueIdByName("C"));
        assertEquals(providerABC.getBlueIdByName("B"), a.getAsNode("/next/type").getBlueId());
        assertEquals(providerABC.getBlueIdByName("C"), b.getAsNode("/next/type").getBlueId());
        assertEquals(providerABC.getBlueIdByName("A"), c.getAsNode("/next/type").getBlueId());
        assertEquals(3, fetched.size());
        assertEquals(
                Arrays.asList(masterBlueId + "#0", masterBlueId + "#1", masterBlueId + "#2"),
                fetchedIds);
    }

    @Test
    public void shouldReturnFinalMemberIdsInOriginalOrderFromCircularSetCalculator() {
        // given
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

        // when
        List<String> ids = CircularSetIdentityCalculator.calculateCircularSetBlueIds(nodes);

        // then
        assertEquals(provider.getBlueIdByName("A"), ids.get(0));
        assertEquals(provider.getBlueIdByName("B"), ids.get(1));
        assertEquals(baseBlueId(ids.get(0)), baseBlueId(ids.get(1)));
    }

    @Test
    public void shouldKeepCircularSetCalculationStableAcrossPermutations() {
        // given
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

        // when
        Map<String, String> abcIds = idsByName(YAML_MAPPER.readValue(abc, Node.class).getItems());
        Map<String, String> cabIds = idsByName(YAML_MAPPER.readValue(cab, Node.class).getItems());

        // then
        assertEquals(abcIds.get("A"), cabIds.get("A"));
        assertEquals(abcIds.get("B"), cabIds.get("B"));
        assertEquals(abcIds.get("C"), cabIds.get("C"));
    }

    @Test
    public void shouldRejectZeroPlaceholderInFinalBlueIdInput() {
        // given
        Node placeholderReference = new Node().blueId(
                BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER);

        // when
        RuntimeException failure = captureFailure(
                () -> DirectBlueIdCalculator.calculateBlueId(placeholderReference));

        // then
        assertTrue(failure instanceof RuntimeException);
    }

    @Test
    public void shouldRejectCircularSetWithoutInternalThisReferences() {
        // given
        List<Node> nodes = Arrays.asList(new Node().value("same"), new Node().value("same"));

        // when
        IllegalArgumentException failure = captureFailure(
                () -> CircularSetIdentityCalculator.calculateCircularSetBlueIds(nodes));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    public void shouldUseThisHashZeroForSingleDocumentCycle() {
        // given
        Node node = YAML_MAPPER.readValue("next:\n  blueId: this#0", Node.class);

        // when
        List<String> ids = CircularSetIdentityCalculator.calculateCircularSetBlueIds(Arrays.asList(node));

        // then
        assertEquals(1, ids.size());
        assertTrue(ids.get(0).endsWith("#0"));
    }

    @Test
    public void shouldRejectBareThisInCircularApi() {
        // given
        Node node = YAML_MAPPER.readValue("next:\n  blueId: this", Node.class);

        // when
        IllegalArgumentException failure = captureFailure(
                () -> CircularSetIdentityCalculator.calculateCircularSetBlueIds(Arrays.asList(node)));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    public void shouldRejectBareThisOutsideCircularApi() {
        // given
        Node bareThisReference = new Node().blueId("this");
        Blue blue = new Blue();

        // when
        RuntimeException calculationFailure = captureFailure(
                () -> DirectBlueIdCalculator.calculateBlueId(bareThisReference));
        RuntimeException parsingFailure = captureFailure(
                () -> blue.parseBlueIdInputYaml("blueId: this"));

        // then
        assertTrue(calculationFailure instanceof RuntimeException);
        assertTrue(parsingFailure instanceof RuntimeException);
    }

    @Test
    public void shouldRejectActualCycleWithDuplicatePreliminaryIds() {
        // given
        List<Node> nodes = YAML_MAPPER.readValue(
                "- next:\n" +
                "    blueId: this#1\n" +
                "- next:\n" +
                "    blueId: this#0", Node.class).getItems();

        // when
        IllegalArgumentException failure = captureFailure(
                () -> CircularSetIdentityCalculator.calculateCircularSetBlueIds(nodes));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    public void shouldRewriteThisReferencesInTypeMetadata() {
        // given
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

        // when
        BasicNodeProvider nodeProvider = new BasicNodeProvider(YAML_MAPPER.readValue(docs, Node.class));
        Node a = nodeProvider.getNodeByName("A");

        // then
        assertEquals(nodeProvider.getBlueIdByName("B"), a.getType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("C"), a.getItemType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("B"), a.getKeyType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("C"), a.getValueType().getBlueId());
    }

    @Test
    public void shouldStoreSortedParsedCyclicDocumentsWithThisReferencesBeforeFetchTimeResolution() {
        // given
        String docs = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0";

        // when
        NodeContentHandler.ParsedContent parsed = NodeContentHandler.parseAndCalculateBlueId(docs, node -> node);
        List<Node> stored = Arrays.asList(JSON_MAPPER.treeToValue(parsed.content, Node[].class));
        String storedBlueId = DirectBlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(stored);
        Map<String, Integer> nameToStoredIndex = IntStream.range(0, stored.size())
                .boxed()
                .collect(Collectors.toMap(i -> stored.get(i).getName(), i -> i));
        Map<String, Node> storedByName = stored.stream()
                .collect(Collectors.toMap(Node::getName, node -> node));

        // then
        assertEquals(storedBlueId, parsed.blueId);
        assertEquals(2, storedByName.size());
        assertTrue(storedByName.containsKey("A"));
        assertTrue(storedByName.containsKey("B"));
        assertEquals(
                "this#" + nameToStoredIndex.get("B"),
                storedByName.get("A").getAsNode("/x/type").getBlueId());
        assertEquals(
                "this#" + nameToStoredIndex.get("A"),
                storedByName.get("B").getAsNode("/y/type").getBlueId());
    }

    @Test
    public void shouldRejectInvalidCyclicMultiDocumentReferencesAtIngestion() {
        // given
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

        // when
        IllegalArgumentException missingIndexFailure = captureFailure(
                () -> new BasicNodeProvider(YAML_MAPPER.readValue(missingIndex, Node.class)));
        IllegalArgumentException outOfRangeFailure = captureFailure(
                () -> new BasicNodeProvider(YAML_MAPPER.readValue(outOfRange, Node.class)));

        // then
        assertTrue(missingIndexFailure instanceof IllegalArgumentException);
        assertTrue(outOfRangeFailure instanceof IllegalArgumentException);
    }

    @Test
    public void shouldRejectInvalidSingleDocumentIndexedSelfReferenceAtIngestion() {
        // given
        String indexedSelf = "name: A\n" +
                    "x:\n" +
                    "  type:\n" +
                    "    blueId: this#0";

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new BasicNodeProvider(YAML_MAPPER.readValue(indexedSelf, Node.class)));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    private String baseBlueId(String blueId) {
        return blueId.split("#")[0];
    }

    private Map<String, String> idsByName(List<Node> nodes) {
        List<String> ids = CircularSetIdentityCalculator.calculateCircularSetBlueIds(nodes);
        return IntStream.range(0, nodes.size())
                .boxed()
                .collect(Collectors.toMap(i -> nodes.get(i).getName(), ids::get));
    }

    private static final class InterconnectedFixture {
        private final BasicNodeProvider provider;
        private final String aBlueId;
        private final String bBlueId;
        private final Blue blue;

        private InterconnectedFixture() {
            provider = new BasicNodeProvider(
                    YAML_MAPPER.readValue(INTERCONNECTED_DOCUMENTS, Node.class));
            aBlueId = provider.getBlueIdByName("A");
            bBlueId = provider.getBlueIdByName("B");
            blue = new Blue(provider);
        }

        private Node documentA() {
            return provider.findNodeByName("A")
                    .orElseThrow(() -> new IllegalArgumentException("No A node found"));
        }

        private Node documentB() {
            return provider.findNodeByName("B")
                    .orElseThrow(() -> new IllegalArgumentException("No B node found"));
        }
    }

}
