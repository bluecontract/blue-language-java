package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.utils.CanonicalIdentityInputBuilder;
import blue.language.utils.MinimizedOverlayBuilder;
import blue.language.model.wire.BlueLanguageConstants;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class OverlayBuildersTest {

    @Test
    public void shouldMinimizeBasicResolvedOverlay() throws Exception {

        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\n" +
                   "description: Xyz\n" +
                   "x: 1\n" +
                   "y:\n" +
                   "  type: Integer\n" +
                   "z:\n" +
                   "  type: List";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "x: 1\n" +
                   "y: 2\n" +
                   "z:\n" +
                   "  type: List\n" +
                   "  itemType: Text\n" +
                   "  items:\n" +
                   "    - A\n" +
                   "    - B";
        nodeProvider.addSingleDocs(b);

        Node bNode = nodeProvider.getNodeByName("B");

        Blue blue = new Blue(nodeProvider);
        Node resolved = blue.resolve(bNode);

        MinimizedOverlayBuilder builder = new MinimizedOverlayBuilder();
        // when
        Node reversed = builder.build(resolved);

        // then
        assertFalse(reversed.getProperties().containsKey("x"));
        assertEquals(2, reversed.getAsInteger("/y/value"));
        assertEquals(BlueLanguageConstants.LIST_TYPE_BLUE_ID, reversed.getAsText("/z/type/blueId"));
        assertEquals(BlueLanguageConstants.TEXT_TYPE_BLUE_ID, reversed.getAsText("/z/itemType/blueId"));
    }

    @Test
    public void shouldMinimizeNestedResolvedTypes() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\n" +
                   "x: 5\n" +
                   "y: 10";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "z: 15";
        nodeProvider.addSingleDocs(b);

        String c = "name: C\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                   "w: 20";
        nodeProvider.addSingleDocs(c);

        Node cNode = nodeProvider.getNodeByName("C");
        Blue blue = new Blue(nodeProvider);
        Node resolved = blue.resolve(cNode);

        MinimizedOverlayBuilder builder = new MinimizedOverlayBuilder();
        // when
        Node reversed = builder.build(resolved);

        // then
        assertEquals("C", reversed.getName());
        assertEquals(nodeProvider.getBlueIdByName("B"), reversed.getType().getBlueId());
        assertEquals(20, reversed.getAsInteger("/w/value"));
        assertFalse(reversed.getProperties().containsKey("x"));
        assertFalse(reversed.getProperties().containsKey("y"));
        assertFalse(reversed.getProperties().containsKey("z"));

        assertEquals(nodeProvider.getBlueIdByName("C"), DirectBlueIdCalculator.calculateBlueId(reversed));
    }

    @Test
    public void shouldMinimizeComplexNestedProperties() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String m = "name: M\n" +
                   "a:\n" +
                   "  b:\n" +
                   "    c:\n" +
                   "      d1: 1";
        nodeProvider.addSingleDocs(m);

        String n = "name: N\n" +
                   "c:\n" +
                   "  d2: 1";
        nodeProvider.addSingleDocs(n);

        String p = "name: P\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("M") + "\n" +
                   "a:\n" +
                   "  b:\n" +
                   "    type:\n" +
                   "      blueId: " + nodeProvider.getBlueIdByName("N") + "\n" +
                   "    c:\n" +
                   "      d3: 3";
        nodeProvider.addSingleDocs(p);

        Node pNode = nodeProvider.getNodeByName("P");
        Blue blue = new Blue(nodeProvider);
        // when
        Node resolved = blue.resolve(pNode);
        MinimizedOverlayBuilder builder = new MinimizedOverlayBuilder();
        Node reversed = builder.build(resolved);

        // then
        assertEquals(1, resolved.getAsInteger("/a/b/c/d1/value"));
        assertEquals(1, resolved.getAsInteger("/a/b/c/d2/value"));
        assertEquals(3, resolved.getAsInteger("/a/b/c/d3/value"));
        assertEquals("P", reversed.getName());
        assertEquals(nodeProvider.getBlueIdByName("M"), reversed.getType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("N"), reversed.getAsNode("/a/b/type").getBlueId());
        assertEquals(3, reversed.getAsInteger("/a/b/c/d3/value"));
        assertFalse(reversed.getProperties().containsKey("d1"));
        assertFalse(reversed.getAsNode("/a/b").getProperties().containsKey("d2"));
    }

    @Test
    public void shouldMinimizeInheritedListAndMapChanges() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String base = "name: Base\n" +
                      "list:\n" +
                      "  - A\n" +
                      "  - B\n" +
                      "map:\n" +
                      "  key1: value1\n" +
                      "  key2: value2";
        nodeProvider.addSingleDocs(base);

        String derived = "name: Derived\n" +
                         "type:\n" +
                         "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                         "list:\n" +
                         "  - A\n" +
                         "  - B\n" +
                         "  - C\n" +
                         "map:\n" +
                         "  key3: value3";
        nodeProvider.addSingleDocs(derived);

        Node derivedNode = nodeProvider.getNodeByName("Derived");
        Blue blue = new Blue(nodeProvider);
        Node resolved = blue.resolve(derivedNode);

        MinimizedOverlayBuilder builder = new MinimizedOverlayBuilder();
        // when
        Node reversed = builder.build(resolved);
        Node roundTripped = blue.resolve(reversed);

        // then
        assertEquals("Derived", reversed.getName());
        assertEquals(nodeProvider.getBlueIdByName("Base"), reversed.getType().getBlueId());
        assertEquals(2, reversed.getAsNode("/list").getItems().size());
        assertNotNull(reversed.getAsNode("/list").getItems().get(0).getPreviousBlueId());
        assertEquals("C", reversed.getAsNode("/list").getItems().get(1).getValue());
        assertEquals(1, reversed.getAsNode("/map").getProperties().size());
        assertEquals("value3", reversed.getAsText("/map/key3/value"));
        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                roundTripped.getAsNode("/list").getItems().get(0).getValue(),
                roundTripped.getAsNode("/list").getItems().get(1).getValue(),
                roundTripped.getAsNode("/list").getItems().get(2).getValue()));
    }

    @Test
    public void shouldOmitUnchangedInheritedListDuringReverseMinimization() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base"));

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));
        // when
        Node reversed = new MinimizedOverlayBuilder().build(resolved);

        // then
        assertTrue(reversed.getProperties() == null || !reversed.getProperties().containsKey("list"));
    }

    @Test
    public void shouldPreserveInheritedListPositionalReplacementDuringReverseMinimization() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        Node derived = blue.yamlToNode(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 1\n" +
                "      value: C");

        Node resolved = blue.resolve(derived);
        Node reversed = new MinimizedOverlayBuilder().build(resolved);
        // when
        Node reversedList = reversed.getAsNode("/list");

        // then
        assertEquals(1, reversedList.getItems().size());
        assertNull(reversedList.getItems().get(0).getPreviousBlueId());
        assertEquals(Integer.valueOf(1), reversedList.getItems().get(0).getPosition());
        assertEquals("C", reversedList.getItems().get(0).getValue());
        assertEquals("C", blue.resolve(reversed).getAsNode("/list").getItems().get(1).getValue());
    }

    @Test
    public void shouldPreserveMultipleInheritedListReplacementsAndAppendsDuringReverseMinimization() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B\n" +
                "    - C");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        Node derived = blue.yamlToNode(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 0\n" +
                "      value: X\n" +
                "    - $pos: 2\n" +
                "      value: Z\n" +
                "    - D");

        Node reversed = new MinimizedOverlayBuilder().build(blue.resolve(derived));
        // when
        Node reversedList = reversed.getAsNode("/list");
        Node roundTripped = blue.resolve(reversed);

        // then
        assertEquals(3, reversedList.getItems().size());
        assertNull(reversedList.getItems().get(0).getPreviousBlueId());
        assertEquals(Integer.valueOf(0), reversedList.getItems().get(0).getPosition());
        assertEquals("X", reversedList.getItems().get(0).getValue());
        assertEquals(Integer.valueOf(2), reversedList.getItems().get(1).getPosition());
        assertEquals("Z", reversedList.getItems().get(1).getValue());
        assertEquals("D", reversedList.getItems().get(2).getValue());
        assertEquals(Arrays.asList("X", "B", "Z", "D"), Arrays.asList(
                roundTripped.getAsNode("/list").getItems().get(0).getValue(),
                roundTripped.getAsNode("/list").getItems().get(1).getValue(),
                roundTripped.getAsNode("/list").getItems().get(2).getValue(),
                roundTripped.getAsNode("/list").getItems().get(3).getValue()));
    }

    @Test
    public void shouldPreserveNestedInheritedListItemOverlayDuringReverseMinimization() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - name: first\n" +
                "      details:\n" +
                "        size: M\n" +
                "    - name: second");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        Node derived = blue.yamlToNode(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 0\n" +
                "      details:\n" +
                "        color: red");

        Node reversed = new MinimizedOverlayBuilder().build(blue.resolve(derived));
        // when
        Node overlay = reversed.getAsNode("/list").getItems().get(0);

        // then
        assertNull(overlay.getPreviousBlueId());
        assertEquals(Integer.valueOf(0), overlay.getPosition());
        assertEquals("red", overlay.getAsText("/details/color/value"));
        assertFalse(overlay.getProperties().containsKey("name"));
        assertFalse(overlay.getAsNode("/details").getProperties().containsKey("size"));
        assertEquals("red", blue.resolve(reversed).getAsText("/list/0/details/color/value"));
        assertEquals("M", blue.resolve(reversed).getAsText("/list/0/details/size/value"));
    }

    @Test
    public void shouldPreserveReplacementOfInheritedEmptyListPlaceholder() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $empty: true\n" +
                "    - B");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        Node derived = blue.yamlToNode(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 0\n" +
                "      value: A");

        Node reversed = new MinimizedOverlayBuilder().build(blue.resolve(derived));
        // when
        Node overlay = reversed.getAsNode("/list").getItems().get(0);

        // then
        assertNull(overlay.getPreviousBlueId());
        assertEquals(Integer.valueOf(0), overlay.getPosition());
        assertEquals("A", overlay.getValue());
        assertEquals("A", blue.resolve(reversed).getAsNode("/list").getItems().get(0).getValue());
    }

    @Test
    public void shouldNotSerializePreviousOrPositionControlsInCanonicalOverlay() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = DirectBlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        Node derived = blue.yamlToNode(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 1\n" +
                "      value: C");

        Node preprocessed = blue.preprocess(derived.clone());
        Node canonical = new CanonicalIdentityInputBuilder().build(
                blue.resolve(preprocessed.clone()), preprocessed);
        // when
        Node canonicalList = canonical.getAsNode("/list");

        // then
        assertEquals(2, canonicalList.getItems().size());
        assertEquals("A", canonicalList.getItems().get(0).getValue());
        assertEquals("C", canonicalList.getItems().get(1).getValue());
        canonicalList.getItems().forEach(item -> {
            assertNull(item.getPreviousBlueId());
            assertNull(item.getPosition());
        });
    }

    @Test
    public void shouldPreserveExplicitRootLabelsEqualToTypeLabelsInCanonicalOverlay() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Node canonicalType = new Node()
                .name("Same Label")
                .description("Same Description");
        nodeProvider.addSingleNodes(canonicalType);
        String typeBlueId = nodeProvider.getBlueIdByName(canonicalType.getName());
        Blue blue = new Blue(nodeProvider);
        Node source = new Node()
                .name(canonicalType.getName())
                .description(canonicalType.getDescription())
                .type(new Node().blueId(typeBlueId));

        Node preprocessed = blue.preprocess(source.clone());
        Node canonical = new CanonicalIdentityInputBuilder().build(
                blue.resolve(preprocessed.clone()), preprocessed);
        // when
        Node expectedCanonical = source.clone();

        // then
        assertEquals("Same Label", canonical.getName());
        assertEquals("Same Description", canonical.getDescription());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(expectedCanonical),
                blue.calculateSourceDocumentBlueId(source));
        assertNotEquals(blue.calculateSourceDocumentBlueId(
                        new Node().type(new Node().blueId(typeBlueId))),
                blue.calculateSourceDocumentBlueId(source));
    }

    @Test
    public void shouldPreserveScalarOverrideThatDiffersFromType() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "status: draft");
        Node resolved = new Blue(nodeProvider).yamlToNode(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "status: draft");
        resolved = new Blue(nodeProvider).resolve(resolved);
        resolved.getProperties().get("status").value("published");
        // when
        Node reversed = new MinimizedOverlayBuilder().build(resolved);

        // then
        assertEquals("published", reversed.getAsText("/status/value"));
    }

    @Test
    public void shouldPreserveSchemaOverrideThatDiffersFromType() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "value: abc\n" +
                "schema:\n" +
                "  minLength: 2");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "value: abc\n" +
                "schema:\n" +
                "  minLength: 3");

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));
        // when
        Node reversed = new MinimizedOverlayBuilder().build(resolved);

        // then
        assertNotNull(reversed.getSchema());
        assertEquals(BigInteger.valueOf(3), reversed.getSchema().getMinLengthExact());
    }

}
