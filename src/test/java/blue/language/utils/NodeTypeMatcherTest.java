package blue.language.utils;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.NodeContentHandler;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.language.utils.Properties.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NodeTypeMatcherTest {

    @Test
    void matchesBasicTypeValueAndShapeCases() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: A\nvalue: AAA");
        nodeProvider.addSingleDocs(
                "name: B\n" +
                "x:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("A"));
        nodeProvider.addSingleDocs("name: C");
        nodeProvider.addSingleDocs(
                "name: B Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                "x: AAA");

        Blue blue = new Blue(nodeProvider);
        Node node = nodeProvider.getNodeByName("B Instance");

        assertTrue(blue.nodeMatchesType(node, blue.yamlToNode("x:\n  type:\n    blueId: " + nodeProvider.getBlueIdByName("A"))));
        assertTrue(blue.nodeMatchesType(node, blue.yamlToNode("x: AAA")));
        assertFalse(blue.nodeMatchesType(node, blue.yamlToNode("x:\n  type:\n    blueId: " + nodeProvider.getBlueIdByName("C"))));
        assertFalse(blue.nodeMatchesType(node, blue.yamlToNode(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("C") + "\n" +
                "x: AAA")));
        assertFalse(blue.nodeMatchesType(node, blue.yamlToNode(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                "x: AAA\n" +
                "y: d")));
    }

    @Test
    void doesNotTreatSameNamedTypesWithDifferentDefinitionsAsTheSameType() {
        Blue blue = new Blue(new BasicNodeProvider());
        Node node = blue.yamlToNode(
                "type:\n" +
                "  name: Shared Type\n" +
                "  description: Candidate description\n" +
                "  value: active\n" +
                "value: active");
        Node target = blue.yamlToNode(
                "type:\n" +
                "  name: Shared Type\n" +
                "  description: Target description\n" +
                "  value: inactive");

        assertFalse(blue.nodeMatchesType(node, target));
    }

    @Test
    void ignoresNameAndDescriptionForMatcherAndTypeCompatibility() {
        Blue blue = new Blue(new BasicNodeProvider());
        Node node = blue.yamlToNode(
                "name: Candidate label\n" +
                "description: Candidate description\n" +
                "type:\n" +
                "  name: Candidate type label\n" +
                "  description: Candidate type description\n" +
                "  score:\n" +
                "    type: Integer\n" +
                "score: 7");
        Node target = blue.yamlToNode(
                "name: Target label ignored\n" +
                "description: Target description ignored\n" +
                "type:\n" +
                "  name: Target type label ignored\n" +
                "  description: Target type description ignored\n" +
                "  score:\n" +
                "    type: Integer\n" +
                "score:\n" +
                "  type: Integer");

        assertTrue(blue.nodeMatchesType(node, target));
    }

    @Test
    void targetLabelsDoNotConstrainPresenceOrMatching() {
        Blue blue = new Blue(new BasicNodeProvider());
        Node node = blue.yamlToNode("x: 1");
        Node target = blue.yamlToNode(
                "name: Root label ignored\n" +
                "description: Root description ignored\n" +
                "x:\n" +
                "  name: Field label ignored\n" +
                "  description: Field description ignored\n" +
                "y:\n" +
                "  name: Missing field label ignored\n" +
                "  description: Missing field description ignored");

        assertTrue(blue.nodeMatchesType(node, target));
    }

    @Test
    void providerBackedTypeCompatibilityIgnoresNameDescriptionAndSchemaLabels() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Provider Request\n" +
                "description: Provider type description\n" +
                "payload:\n" +
                "  type: Integer\n" +
                "  schema:\n" +
                "    minimum:\n" +
                "      name: Provider minimum label\n" +
                "      description: Provider minimum description\n" +
                "      value: 1");
        Blue blue = new Blue(nodeProvider);

        Node providerTypedNode = blue.yamlToNode(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Provider Request") + "\n" +
                "payload: 5");
        Node inlineEquivalentTarget = blue.yamlToNode(
                "type:\n" +
                "  name: Inline Request\n" +
                "  description: Inline type description\n" +
                "  payload:\n" +
                "    type: Integer\n" +
                "    schema:\n" +
                "      minimum:\n" +
                "        name: Inline minimum label\n" +
                "        description: Inline minimum description\n" +
                "        value: 1\n" +
                "payload:\n" +
                "  type: Integer");
        Node inlineTypedNode = blue.yamlToNode(
                "type:\n" +
                "  name: Inline Request\n" +
                "  description: Inline type description\n" +
                "  payload:\n" +
                "    type: Integer\n" +
                "    schema:\n" +
                "      minimum:\n" +
                "        name: Inline minimum label\n" +
                "        description: Inline minimum description\n" +
                "        value: 1\n" +
                "payload: 5");
        Node providerReferenceTarget = blue.yamlToNode(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Provider Request"));

        assertTrue(blue.nodeMatchesType(providerTypedNode, inlineEquivalentTarget));
        assertTrue(blue.nodeMatchesType(inlineTypedNode, providerReferenceTarget));
    }

    @Test
    void pureBlueIdReferencesStillRequireExactIdentityIncludingLabels() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Exact State\n" +
                "description: Exact description\n" +
                "value: active");
        Blue blue = new Blue(nodeProvider);

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode(
                        "state:\n" +
                        "  name: Different label\n" +
                        "  description: Different description\n" +
                        "  value: active"),
                blue.yamlToNode(
                        "state:\n" +
                        "  blueId: " + nodeProvider.getBlueIdByName("Exact State"))));
    }

    @Test
    void appliesInheritedFixedValuesFromReferencedTargetTypes() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Activation State\n" +
                "value: active");
        nodeProvider.addSingleDocs(
                "name: Wrong State\n" +
                "value: wrong");
        Blue blue = new Blue(nodeProvider);

        Node matching = new Node().properties("state",
                new Node()
                        .blueId(nodeProvider.getBlueIdByName("Activation State"))
                        .type(new Node().blueId(nodeProvider.getBlueIdByName("Activation State")))
                        .value("active"));
        Node mismatched = new Node().properties("state",
                new Node()
                        .blueId(nodeProvider.getBlueIdByName("Wrong State"))
                        .type(new Node().blueId(nodeProvider.getBlueIdByName("Wrong State")))
                        .value("wrong"));
        Node target = blue.yamlToNode(
                "state:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Activation State"));

        assertTrue(blue.nodeMatchesType(matching, target));
        assertFalse(blue.nodeMatchesType(mismatched, target));
    }

    @Test
    void honorsOptionalAndRequiredSchemaPropertiesWithoutResolvingTargetAsDocument() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Blue blue = new Blue(nodeProvider);
        Node node = blue.yamlToNode("x: ABC");

        assertTrue(blue.nodeMatchesType(node, blue.yamlToNode(
                "x:\n" +
                "  schema:\n" +
                "    minLength: 3\n" +
                "y:\n" +
                "  schema:\n" +
                "    minLength: 5")));
        assertFalse(blue.nodeMatchesType(node, blue.yamlToNode(
                "x:\n" +
                "  schema:\n" +
                "    minLength: 4")));
        assertFalse(blue.nodeMatchesType(node, blue.yamlToNode(
                "x:\n" +
                "  schema:\n" +
                "    minLength: 3\n" +
                "y:\n" +
                "  schema:\n" +
                "    required: true")));
    }

    @Test
    void enforcesRequiredProviderBackedTypeDefinitionsWithoutTreatingThemAsInstances() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Required Request\n" +
                "payload:\n" +
                "  type: Integer\n" +
                "  schema:\n" +
                "    required: true");
        Blue blue = new Blue(nodeProvider);
        Node target = blue.yamlToNode(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Required Request"));

        assertTrue(blue.nodeMatchesType(blue.yamlToNode("payload: 5"), target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("payload: five"), target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("other: 5"), target));
    }

    @Test
    void verifiesSchemaKeywordsOnFrozenNodes() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Blue blue = new Blue(nodeProvider);

        Node valid = blue.yamlToNode(
                "score: 12\n" +
                "code: AB12\n" +
                "values:\n" +
                "  - A\n" +
                "  - B\n" +
                "flags:\n" +
                "  enabled: true");
        Node target = blue.yamlToNode(
                "score:\n" +
                "  type: Integer\n" +
                "  schema:\n" +
                "    minimum: 10\n" +
                "    maximum: 20\n" +
                "    multipleOf: 2\n" +
                "code:\n" +
                "  type: Text\n" +
                "  schema:\n" +
                "    minLength: 4\n" +
                "    maxLength: 4\n" +
                "values:\n" +
                "  type: List\n" +
                "  schema:\n" +
                "    allowMultiple: true\n" +
                "    minItems: 2\n" +
                "    maxItems: 3\n" +
                "    uniqueItems: true\n" +
                "flags:\n" +
                "  type: Dictionary\n" +
                "  schema:\n" +
                "    minFields: 1\n" +
                "    maxFields: 2");

        assertTrue(blue.nodeMatchesType(valid, target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("score: 11"), blue.yamlToNode(
                "score:\n" +
                "  type: Integer\n" +
                "  schema:\n" +
                "    multipleOf: 2")));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("values:\n  - A\n  - A"), blue.yamlToNode(
                "values:\n" +
                "  type: List\n" +
                "  schema:\n" +
                "    uniqueItems: true")));
    }

    @Test
    void verifiesEnumByCanonicalNodeIdentityIgnoringCandidateSchema() {
        Blue blue = new Blue(new BasicNodeProvider());
        Node node = blue.yamlToNode(
                "status:\n" +
                "  value: active\n" +
                "  schema:\n" +
                "    minLength: 1");
        Node target = blue.yamlToNode(
                "status:\n" +
                "  schema:\n" +
                "    enum:\n" +
                "      - active\n" +
                "      - paused");
        Node wrongTarget = blue.yamlToNode(
                "status:\n" +
                "  schema:\n" +
                "    enum:\n" +
                "      - disabled");

        assertTrue(blue.nodeMatchesType(node, target));
        assertFalse(blue.nodeMatchesType(node, wrongTarget));
    }

    @Test
    void supportsNestedListAndPropertyShapes() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: Item\nvalue: 1");
        nodeProvider.addSingleDocs("name: Item2\nvalue: 2");
        nodeProvider.addSingleDocs(
                "name: ListOwner\n" +
                "items:\n" +
                "  - blueId: " + nodeProvider.getBlueIdByName("Item"));
        nodeProvider.addSingleDocs(
                "name: Container\n" +
                "list:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("ListOwner"));
        Blue blue = new Blue(nodeProvider);
        Node container = nodeProvider.getNodeByName("Container");

        assertTrue(blue.nodeMatchesType(container, blue.yamlToNode(
                "list:\n" +
                "  items:\n" +
                "    - blueId: " + nodeProvider.getBlueIdByName("Item"))));
        assertFalse(blue.nodeMatchesType(container, blue.yamlToNode(
                "list:\n" +
                "  items:\n" +
                "    - blueId: " + nodeProvider.getBlueIdByName("Item2"))));
        assertFalse(blue.nodeMatchesType(container, blue.yamlToNode(
                "list:\n" +
                "  items:\n" +
                "    - extra: something")));
    }

    @Test
    void matchesExactBlueIdReferencesAgainstNodeOrNodeTypeIdentity() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: Alpha");
        nodeProvider.addSingleDocs("name: Beta");
        Blue blue = new Blue(nodeProvider);
        Node directReference = new Node().properties("x", new Node().blueId(nodeProvider.getBlueIdByName("Alpha")));
        Node typedReference = new Node().properties("x", new Node().type(new Node().blueId(nodeProvider.getBlueIdByName("Alpha"))));

        Node ok = blue.yamlToNode("x:\n  blueId: " + nodeProvider.getBlueIdByName("Alpha"));
        Node fail = blue.yamlToNode("x:\n  blueId: " + nodeProvider.getBlueIdByName("Beta"));

        assertTrue(blue.nodeMatchesType(directReference, ok));
        assertTrue(blue.nodeMatchesType(typedReference, ok));
        assertFalse(blue.nodeMatchesType(directReference, fail));
        assertFalse(blue.nodeMatchesType(typedReference, fail));
    }

    @Test
    void pureReferencePatternDoesNotExpandCandidateReferenceLeaf() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Expected\nvalue: expected");
        delegate.addSingleDocs(
                "name: Huge Candidate\n" +
                "a: 1\n" +
                "b: 2\n" +
                "c:\n" +
                "  d:\n" +
                "    e: ignored");
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode("x:\n  blueId: " + delegate.getBlueIdByName("Huge Candidate")),
                blue.yamlToNode("x:\n  blueId: " + delegate.getBlueIdByName("Expected"))));
        assertEquals(0, provider.fetches);
    }

    @Test
    void nestedPatternExpandsOnlyRequiredPrefixAndKeepsReferenceLeavesUnexpanded() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Expected Z\nvalue: z");
        delegate.addSingleDocs("name: Unchecked Huge\nvalue: huge");
        delegate.addSingleDocs(
                "name: Checked X\n" +
                "y: 1\n" +
                "z:\n" +
                "  blueId: " + delegate.getBlueIdByName("Expected Z") + "\n" +
                "ignored:\n" +
                "  blueId: " + delegate.getBlueIdByName("Unchecked Huge"));
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode("x:\n  blueId: " + delegate.getBlueIdByName("Checked X")),
                blue.yamlToNode(
                        "x:\n" +
                        "  y: 1\n" +
                        "  z:\n" +
                        "    blueId: " + delegate.getBlueIdByName("Expected Z"))));
        assertEquals(1, provider.fetches);
    }

    @Test
    void callerGlobalLimitsStillBoundTargetPatternMatching() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs(
                "name: Branch\n" +
                "y: 1\n" +
                "ignored:\n" +
                "  deeply:\n" +
                "    nested: true");
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);
        Node candidate = blue.yamlToNode("x:\n  blueId: " + delegate.getBlueIdByName("Branch"));
        Node pattern = blue.yamlToNode("x:\n  y: 1");
        NodeTypeMatcher matcher = new NodeTypeMatcher(blue);

        assertFalse(matcher.matchesType(candidate, pattern, PathLimits.withSinglePath("/other")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Branch")));

        assertTrue(matcher.matchesType(candidate, pattern, PathLimits.withSinglePath("/x/y")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Branch")));
    }

    @Test
    void targetBoundedMatchingUsesLiteralPathSegmentsForKeysContainingSlash() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Expected Slash Value\nvalue: slash");
        delegate.addSingleDocs("name: Unchecked Slash Huge\nvalue: huge");
        delegate.addSingleDocs(
                "name: Slash X\n" +
                "'a/b':\n" +
                "  blueId: " + delegate.getBlueIdByName("Expected Slash Value") + "\n" +
                "ignored:\n" +
                "  blueId: " + delegate.getBlueIdByName("Unchecked Slash Huge"));
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode("x:\n  blueId: " + delegate.getBlueIdByName("Slash X")),
                blue.yamlToNode(
                        "x:\n" +
                        "  'a/b':\n" +
                        "    blueId: " + delegate.getBlueIdByName("Expected Slash Value"))));
        assertEquals(1, provider.fetches);
    }

    @Test
    void globalPathLimitsUseJsonPointerEscapesForKeysContainingSlashOrTilde() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Unchecked Escaped Huge\nvalue: huge");
        delegate.addSingleDocs(
                "name: Escaped Branch\n" +
                "'a/b':\n" +
                "  'c~d': 7\n" +
                "ignored:\n" +
                "  blueId: " + delegate.getBlueIdByName("Unchecked Escaped Huge"));
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);
        Node candidate = blue.yamlToNode("x:\n  blueId: " + delegate.getBlueIdByName("Escaped Branch"));
        Node pattern = blue.yamlToNode(
                "x:\n" +
                "  'a/b':\n" +
                "    'c~d': 7");
        NodeTypeMatcher matcher = new NodeTypeMatcher(blue);

        assertTrue(matcher.matchesType(candidate, pattern, PathLimits.withSinglePath("/x/a~1b/c~0d")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Escaped Branch")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Unchecked Escaped Huge")));
    }

    @Test
    void listSchemaCardinalityMergesItemsWithoutExpandingItemReferences() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Huge One\nvalue: one");
        delegate.addSingleDocs("name: Huge Two\nvalue: two");
        delegate.addSingleDocs(
                "name: List Candidate\n" +
                "type: List\n" +
                "items:\n" +
                "  - blueId: " + delegate.getBlueIdByName("Huge One") + "\n" +
                "  - blueId: " + delegate.getBlueIdByName("Huge Two"));
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode("values:\n  blueId: " + delegate.getBlueIdByName("List Candidate")),
                blue.yamlToNode(
                        "values:\n" +
                        "  type: List\n" +
                        "  schema:\n" +
                        "    allowMultiple: true\n" +
                        "    minItems: 2\n" +
                        "    maxItems: 2")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("List Candidate")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Huge One")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Huge Two")));
    }

    @Test
    void explicitThreeItemListPatternAgainstListReferenceExpandsOnlyRequiredItems() {
        BasicNodeProvider delegate = explicitListProvider(false, false);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode("values:\n  blueId: " + delegate.getBlueIdByName("Candidate List")),
                explicitThreeItemListPattern(blue, delegate)));

        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Candidate List")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("First Exact")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Middle Score")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Last Details")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Active Status")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Unchecked Huge")));
    }

    @Test
    void explicitThreeItemListPatternAgainstInlineReferenceEdgesExpandsOnlyNonExactItems() {
        BasicNodeProvider delegate = explicitListProvider(false, false);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode(
                        "values:\n" +
                        "  type: List\n" +
                        "  items:\n" +
                        "    - blueId: " + delegate.getBlueIdByName("First Exact") + "\n" +
                        "    - score: 12\n" +
                        "    - blueId: " + delegate.getBlueIdByName("Last Details")),
                explicitThreeItemListPattern(blue, delegate)));

        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Candidate List")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("First Exact")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Middle Score")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Last Details")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Active Status")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Unchecked Huge")));
    }

    @Test
    void explicitListPatternRequiresPureReferenceItemsToBePresent() {
        BasicNodeProvider delegate = explicitListProvider(false, false);
        Blue blue = new Blue(delegate);

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode(
                        "values:\n" +
                        "  type: List\n" +
                        "  items:\n" +
                        "    - score: 12"),
                explicitThreeItemListPattern(blue, delegate)));
    }

    @Test
    void explicitListPatternRejectsNestedReferenceMismatchWithoutFetchingReferenceLeaves() {
        BasicNodeProvider delegate = explicitListProvider(true, false);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode("values:\n  blueId: " + delegate.getBlueIdByName("Candidate List")),
                explicitThreeItemListPattern(blue, delegate)));

        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Candidate List")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("First Exact")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Middle Score")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Last Details")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Active Status")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Wrong Status")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Unchecked Huge")));
    }

    @Test
    void explicitListPatternAllowsExtraItemsUnlessCardinalityConstrainsThem() {
        BasicNodeProvider delegate = explicitListProvider(false, true);
        Blue blue = new Blue(delegate);
        Node unconstrainedPattern = explicitThreeItemListPattern(blue, delegate);
        Node constrainedPattern = explicitThreeItemListPattern(blue, delegate);
        constrainedPattern.getProperties().get("values").schema(new blue.language.model.Schema().maxItems(3));

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode("values:\n  blueId: " + delegate.getBlueIdByName("Candidate List")),
                unconstrainedPattern));
        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode("values:\n  blueId: " + delegate.getBlueIdByName("Candidate List")),
                constrainedPattern));
    }

    @Test
    void explicitListPatternRejectsNonListCandidatesEvenWhenItemsAreOptional() {
        Blue blue = new Blue(new BasicNodeProvider());
        Node optionalListPattern = blue.yamlToNode(
                "values:\n" +
                "  items:\n" +
                "    - name: Optional list item label\n" +
                "      description: Optional list item description");

        assertTrue(blue.nodeMatchesType(blue.yamlToNode("other: true"), optionalListPattern));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("values: scalar"), optionalListPattern));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode(
                "values:\n" +
                "  child: scalar"), optionalListPattern));
        assertTrue(blue.nodeMatchesType(blue.yamlToNode(
                "values:\n" +
                "  type: List"), optionalListPattern));
        assertTrue(blue.nodeMatchesType(blue.yamlToNode(
                "values:\n" +
                "  type: List\n" +
                "  items: []"), optionalListPattern));
    }

    @Test
    void explicitThreeItemListPatternRejectsListWithOnlyFirstAndLastReferenceItems() {
        BasicNodeProvider delegate = explicitListProvider(false, false);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode(
                        "values:\n" +
                        "  type: List\n" +
                        "  items:\n" +
                        "    - blueId: " + delegate.getBlueIdByName("First Exact") + "\n" +
                        "    - blueId: " + delegate.getBlueIdByName("Last Details")),
                explicitThreeItemListPattern(blue, delegate)));

        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("First Exact")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Last Details")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Active Status")));
    }

    @Test
    void explicitListPatternReconstructsBundledFirstItemOnlyWhenMorePositionsAreNeeded() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Active Status\nvalue: active");
        List<Node> bundledItems = Arrays.asList(
                new Node().name("Bundled First").value("first"),
                new Node().name("Bundled Middle").properties("score", new Node().value(12)),
                new Node().name("Bundled Last").properties("details",
                        new Node().properties("status",
                                new Node().blueId(delegate.getBlueIdByName("Active Status"))))
        );
        String bundleBlueId = NodeContentHandler
                .parseAndCalculateBlueId(bundledItems, new Preprocessor(delegate)::preprocessWithDefaultBlue)
                .blueId;
        delegate.addListAndItsItems(bundledItems);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        Node candidate = blue.yamlToNode(
                "values:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - blueId: " + bundleBlueId);
        Node pattern = blue.yamlToNode(
                "values:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - first\n" +
                "    - score:\n" +
                "        schema:\n" +
                "          required: true\n" +
                "          minimum: 10\n" +
                "    - details:\n" +
                "        status:\n" +
                "          blueId: " + delegate.getBlueIdByName("Active Status"));

        assertTrue(blue.nodeMatchesType(candidate, pattern));

        assertEquals(1, provider.fetchesFor(bundleBlueId));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Active Status")));
    }

    @Test
    void explicitObjectPatternRejectsNonObjectCandidatesEvenWhenFieldsAreOptional() {
        Blue blue = new Blue(new BasicNodeProvider());
        Node optionalObjectPattern = blue.yamlToNode(
                "profile:\n" +
                "  nickname:\n" +
                "    name: Optional nickname label\n" +
                "    description: Optional nickname description");

        assertTrue(blue.nodeMatchesType(blue.yamlToNode("other: true"), optionalObjectPattern));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("profile: scalar"), optionalObjectPattern));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode(
                "profile:\n" +
                "  type: List\n" +
                "  items: []"), optionalObjectPattern));
        assertTrue(blue.nodeMatchesType(blue.yamlToNode(
                "profile:\n" +
                "  type: Dictionary"), optionalObjectPattern));
        assertTrue(blue.nodeMatchesType(blue.yamlToNode(
                "profile:\n" +
                "  other: value"), optionalObjectPattern));
    }

    @Test
    void collectionTypeMetadataRejectsWrongPayloadKindsWhenCandidateNodeExists() {
        Blue blue = new Blue(new BasicNodeProvider());

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode("values: scalar"),
                blue.yamlToNode(
                        "values:\n" +
                        "  itemType: Text")));
        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode(
                        "values:\n" +
                        "  a: 1"),
                blue.yamlToNode(
                        "values:\n" +
                        "  itemType: Text")));
        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode(
                        "values:\n" +
                        "  type: List"),
                blue.yamlToNode(
                        "values:\n" +
                        "  itemType: Text")));

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode("values: scalar"),
                blue.yamlToNode(
                        "values:\n" +
                        "  keyType: Text")));
        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode(
                        "values:\n" +
                        "  - one"),
                blue.yamlToNode(
                        "values:\n" +
                        "  valueType: Text")));
        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode(
                        "values:\n" +
                        "  type: Dictionary"),
                blue.yamlToNode(
                        "values:\n" +
                        "  keyType: Text\n" +
                        "  valueType: Text")));
    }

    @Test
    void dictionaryKeyTypeMergesKeysWithoutExpandingValues() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Huge Value\nvalue: huge");
        delegate.addSingleDocs(
                "name: Integer Key Dictionary\n" +
                "type: Dictionary\n" +
                "'1':\n" +
                "  blueId: " + delegate.getBlueIdByName("Huge Value") + "\n" +
                "'2':\n" +
                "  blueId: " + delegate.getBlueIdByName("Huge Value"));
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode("values:\n  blueId: " + delegate.getBlueIdByName("Integer Key Dictionary")),
                blue.yamlToNode(
                        "values:\n" +
                        "  type: Dictionary\n" +
                        "  keyType: Integer")));
        assertEquals(1, provider.fetches);
    }

    @Test
    void dictionaryValueTypeResolvesOnlyNonExactReferenceValuesNeededForConformance() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Active Value\nvalue: active");
        delegate.addSingleDocs("name: Ignored Value\nvalue: ignored");
        delegate.addSingleDocs(
                "name: Active Dictionary\n" +
                "type: Dictionary\n" +
                "alice:\n" +
                "  blueId: " + delegate.getBlueIdByName("Active Value") + "\n" +
                "bob:\n" +
                "  blueId: " + delegate.getBlueIdByName("Active Value") + "\n" +
                "ignored:\n" +
                "  blueId: " + delegate.getBlueIdByName("Ignored Value"));
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode("values:\n  blueId: " + delegate.getBlueIdByName("Active Dictionary")),
                blue.yamlToNode(
                        "values:\n" +
                        "  type: Dictionary\n" +
                        "  valueType:\n" +
                        "    blueId: " + delegate.getBlueIdByName("Active Value"))));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Active Dictionary")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Ignored Value")));
    }

    @Test
    void complexMultiLevelObjectMatchesByExpandingOnlyObservedBranches() {
        BasicNodeProvider delegate = complexOrderProvider(false);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode("order:\n  blueId: " + delegate.getBlueIdByName("Order")),
                complexOrderPattern(blue, delegate.getBlueIdByName("Active Status"))));

        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Order")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Customer")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Active Status")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Line Item One")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Line Item Two")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Customer Notes")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Order Audit")));
    }

    @Test
    void complexMultiLevelObjectRejectsDeepMismatchWithoutExpandingUnobservedBranches() {
        BasicNodeProvider delegate = complexOrderProvider(true);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertFalse(blue.nodeMatchesType(
                blue.yamlToNode("order:\n  blueId: " + delegate.getBlueIdByName("Order")),
                complexOrderPattern(blue, delegate.getBlueIdByName("Active Status"))));

        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Order")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Customer")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Active Status")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Line Item One")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Line Item Two")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Customer Notes")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Order Audit")));
    }

    @Test
    void generatedMultiLevelObjectPatternMatchesByWalkingOnlyObservedReferencePath() {
        int depth = 7;
        int ignoredSiblings = 20;
        BasicNodeProvider delegate = generatedNestedReferenceProvider(false, depth, ignoredSiblings);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                generatedNestedCandidate(blue, delegate),
                generatedNestedPattern(blue, delegate, depth)));

        assertGeneratedNestedFetches(delegate, provider, depth, ignoredSiblings);
    }

    @Test
    void generatedMultiLevelObjectPatternRejectsDeepMismatchWithSameBoundedFetches() {
        int depth = 7;
        int ignoredSiblings = 20;
        BasicNodeProvider delegate = generatedNestedReferenceProvider(true, depth, ignoredSiblings);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertFalse(blue.nodeMatchesType(
                generatedNestedCandidate(blue, delegate),
                generatedNestedPattern(blue, delegate, depth)));

        assertGeneratedNestedFetches(delegate, provider, depth, ignoredSiblings);
    }

    @Test
    void generatedFrozenMatcherCachesObservedReferencePathAcrossRepeatedMatches() {
        int depth = 7;
        int ignoredSiblings = 20;
        BasicNodeProvider delegate = generatedNestedReferenceProvider(false, depth, ignoredSiblings);
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);
        NodeTypeMatcher matcher = new NodeTypeMatcher(blue);
        FrozenNode candidate = FrozenNode.fromResolvedNode(generatedNestedCandidate(blue, delegate));
        FrozenNode pattern = FrozenNode.fromResolvedNode(generatedNestedPattern(blue, delegate, depth));

        assertTrue(matcher.matchesResolvedType(candidate, pattern));
        assertGeneratedNestedFetches(delegate, provider, depth, ignoredSiblings);

        int fetchesAfterFirstMatch = provider.fetches;
        for (int i = 0; i < 25; i++) {
            assertTrue(matcher.matchesResolvedType(candidate, pattern));
        }
        assertEquals(fetchesAfterFirstMatch, provider.fetches,
                "repeated frozen matches should reuse the already-resolved observed path");
    }

    @Test
    void complexItemTypeConformanceResolvesOnlyItemsAndTypeDefinitionsThatMatter() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: USD\nvalue: USD");
        delegate.addSingleDocs(
                "name: Line Item Debug\n" +
                "payload:\n" +
                "  deeply:\n" +
                "    nested:\n" +
                "      value: ignored");
        delegate.addSingleDocs(
                "name: Positive USD Line Item\n" +
                "sku:\n" +
                "  type: Text\n" +
                "  schema:\n" +
                "    minLength: 5\n" +
                "quantity:\n" +
                "  type: Integer\n" +
                "  schema:\n" +
                "    minimum: 1\n" +
                "currency:\n" +
                "  blueId: " + delegate.getBlueIdByName("USD"));
        delegate.addSingleDocs(
                "name: Line Item One\n" +
                "sku: SKU-1\n" +
                "quantity: 2\n" +
                "currency:\n" +
                "  blueId: " + delegate.getBlueIdByName("USD") + "\n" +
                "debug:\n" +
                "  blueId: " + delegate.getBlueIdByName("Line Item Debug"));
        delegate.addSingleDocs(
                "name: Line Item Two\n" +
                "sku: SKU-2\n" +
                "quantity: 4\n" +
                "currency:\n" +
                "  blueId: " + delegate.getBlueIdByName("USD") + "\n" +
                "debug:\n" +
                "  blueId: " + delegate.getBlueIdByName("Line Item Debug"));
        delegate.addSingleDocs(
                "name: Cart\n" +
                "type: List\n" +
                "items:\n" +
                "  - blueId: " + delegate.getBlueIdByName("Line Item One") + "\n" +
                "  - blueId: " + delegate.getBlueIdByName("Line Item Two"));
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        assertTrue(blue.nodeMatchesType(
                blue.yamlToNode("cart:\n  blueId: " + delegate.getBlueIdByName("Cart")),
                blue.yamlToNode(
                        "cart:\n" +
                        "  type: List\n" +
                        "  itemType:\n" +
                        "    blueId: " + delegate.getBlueIdByName("Positive USD Line Item"))));

        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Cart")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Positive USD Line Item")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Line Item One")));
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Line Item Two")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("USD")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Line Item Debug")));
    }

    @Test
    void enforcesListItemTypeAcrossAllItems() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: Allowed Item\nvalue: ok");
        nodeProvider.addSingleDocs("name: Forbidden Item\nvalue: not-ok");
        nodeProvider.addSingleDocs(
                "name: Allowed Container\n" +
                "itemsList:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - blueId: " + nodeProvider.getBlueIdByName("Allowed Item"));
        nodeProvider.addSingleDocs(
                "name: Forbidden Container\n" +
                "itemsList:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - blueId: " + nodeProvider.getBlueIdByName("Forbidden Item"));
        Blue blue = new Blue(nodeProvider);
        Node target = blue.yamlToNode(
                "itemsList:\n" +
                "  type: List\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Allowed Item"));

        assertTrue(blue.nodeMatchesType(nodeProvider.getNodeByName("Allowed Container"), target));
        assertFalse(blue.nodeMatchesType(nodeProvider.getNodeByName("Forbidden Container"), target));
    }

    @Test
    void listItemTypeCanMatchNarrowerTypeByConcreteItemConformance() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Active State\n" +
                "type: Text\n" +
                "value: active");
        Blue blue = new Blue(nodeProvider);
        Node target = blue.yamlToNode(
                "states:\n" +
                "  type: List\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Active State"));

        assertTrue(blue.nodeMatchesType(blue.yamlToNode(
                "states:\n" +
                "  type: List\n" +
                "  itemType: Text\n" +
                "  items:\n" +
                "    - active\n" +
                "    - active"), target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode(
                "states:\n" +
                "  type: List\n" +
                "  itemType: Text\n" +
                "  items:\n" +
                "    - active\n" +
                "    - inactive"), target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode(
                "states:\n" +
                "  type: List\n" +
                "  itemType: Text"), target));
    }

    @Test
    void supportsImplicitListAndDictionaryPayloadsForCoreTypes() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: ImplicitListNode\n" +
                "items:\n" +
                "  - value: 1\n" +
                "  - value: 2");
        nodeProvider.addSingleDocs(
                "name: ImplicitDictNode\n" +
                "a:\n" +
                "  value: 1\n" +
                "b:\n" +
                "  value: 2");
        Blue blue = new Blue(nodeProvider);

        assertTrue(blue.nodeMatchesType(nodeProvider.getNodeByName("ImplicitListNode"), blue.yamlToNode("type: List")));
        assertTrue(blue.nodeMatchesType(nodeProvider.getNodeByName("ImplicitDictNode"), blue.yamlToNode("type: Dictionary")));
        assertFalse(blue.nodeMatchesType(nodeProvider.getNodeByName("ImplicitListNode"), blue.yamlToNode("type: Dictionary")));
        assertFalse(blue.nodeMatchesType(nodeProvider.getNodeByName("ImplicitDictNode"), blue.yamlToNode("type: List")));
        assertFalse(blue.nodeMatchesType(nodeProvider.getNodeByName("ImplicitListNode"), blue.yamlToNode(
                "type: Text\n" +
                "items:\n" +
                "  - value: 1")));
    }

    @Test
    void supportsEventPayloadsWhereJsonArrayIsImplicitList() {
        Blue blue = new Blue(new BasicNodeProvider());
        Node target = blue.yamlToNode(
                "message:\n" +
                "  request:\n" +
                "    type: List");

        assertTrue(blue.nodeMatchesType(blue.yamlToNode(
                "message:\n" +
                "  request:\n" +
                "    type: List\n" +
                "    items:\n" +
                "      - 1\n" +
                "      - 2\n" +
                "      - 3"), target));
        assertTrue(blue.nodeMatchesType(blue.yamlToNode(
                "message:\n" +
                "  request:\n" +
                "    - 1\n" +
                "    - 2\n" +
                "    - 3"), target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode(
                "message:\n" +
                "  request:\n" +
                "    a: 1\n" +
                "    b: 2"), target));
    }

    @Test
    void enforcesDictionaryKeyAndValueTypes() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: Activation State\nvalue: active");
        nodeProvider.addSingleDocs("name: Wrong State\nvalue: wrong");
        Blue blue = new Blue(nodeProvider);
        Node target = blue.yamlToNode(
                "participantsState:\n" +
                "  type: Dictionary\n" +
                "  keyType:\n" +
                "    blueId: " + Properties.TEXT_TYPE_BLUE_ID + "\n" +
                "  valueType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Activation State"));

        Node matching = new Node().name("Container").properties("participantsState",
                new Node().type(new Node().blueId(DICTIONARY_TYPE_BLUE_ID))
                        .keyType(new Node().blueId(Properties.TEXT_TYPE_BLUE_ID))
                        .properties("alice",
                                new Node()
                                        .blueId(nodeProvider.getBlueIdByName("Activation State"))
                                        .type(new Node().blueId(nodeProvider.getBlueIdByName("Activation State")))
                                        .value("active")));
        Node mismatched = matching.clone();
        mismatched.getProperties().get("participantsState").getProperties().put("alice",
                new Node()
                        .blueId(nodeProvider.getBlueIdByName("Wrong State"))
                        .type(new Node().blueId(nodeProvider.getBlueIdByName("Wrong State")))
                        .value("wrong"));

        assertTrue(blue.nodeMatchesType(matching, target));
        assertFalse(blue.nodeMatchesType(mismatched, target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("participantsState:\n  not-an-int: active"), blue.yamlToNode(
                "participantsState:\n" +
                "  type: Dictionary\n" +
                "  keyType: Integer")));
    }

    @Test
    void dictionaryValueTypeCanMatchNarrowerTypeByConcreteValueConformance() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Active State\n" +
                "type: Text\n" +
                "value: active");
        Blue blue = new Blue(nodeProvider);
        Node target = blue.yamlToNode(
                "states:\n" +
                "  type: Dictionary\n" +
                "  valueType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Active State"));

        assertTrue(blue.nodeMatchesType(blue.yamlToNode(
                "states:\n" +
                "  type: Dictionary\n" +
                "  valueType: Text\n" +
                "  alice: active\n" +
                "  bob: active"), target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode(
                "states:\n" +
                "  type: Dictionary\n" +
                "  valueType: Text\n" +
                "  alice: active\n" +
                "  bob: inactive"), target));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode(
                "states:\n" +
                "  type: Dictionary\n" +
                "  valueType: Text"), target));
    }

    @Test
    void rejectsPrimitiveCoreTypePayloadMismatches() {
        Blue blue = new Blue(new BasicNodeProvider());

        assertTrue(blue.nodeMatchesType(blue.yamlToNode("x: 1"), blue.yamlToNode("x:\n  type: Integer")));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("x: one"), blue.yamlToNode("x:\n  type: Integer")));
        assertTrue(blue.nodeMatchesType(blue.yamlToNode("x: true"), blue.yamlToNode("x:\n  type: Boolean")));
        assertFalse(blue.nodeMatchesType(blue.yamlToNode("x: true"), blue.yamlToNode("x:\n  type: Text")));
    }

    @Test
    void acceptsUntypedProgrammaticScalarPayloadsForCorePrimitivePatterns() {
        Blue blue = new Blue(new BasicNodeProvider());
        Node event = new Node()
                .properties("kind", new Node().value("allowed"))
                .properties("amount", new Node().value(new java.math.BigInteger("5")))
                .properties("enabled", new Node().value(true));

        assertTrue(blue.nodeMatchesType(event, blue.yamlToNode(
                "kind:\n" +
                "  type: Text\n" +
                "amount:\n" +
                "  type: Integer\n" +
                "enabled:\n" +
                "  type: Boolean")));
        assertFalse(blue.nodeMatchesType(event, blue.yamlToNode(
                "kind:\n" +
                "  type: Integer")));
    }

    @Test
    void compatibilityApiDoesNotMutateInputNodes() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "x:\n" +
                "  type: Text");
        Blue blue = new Blue(nodeProvider);
        Node node = blue.yamlToNode(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "x: abc");
        Node target = blue.yamlToNode("x:\n  type: Text");
        String beforeNode = YAML_MAPPER.writeValueAsString(node);
        String beforeTarget = YAML_MAPPER.writeValueAsString(target);

        assertTrue(blue.nodeMatchesType(node, target));

        assertEquals(beforeNode, YAML_MAPPER.writeValueAsString(node));
        assertEquals(beforeTarget, YAML_MAPPER.writeValueAsString(target));
    }

    @Test
    void resolvedFrozenMatchingDoesNotFetchFromProviderAfterSnapshotResolution() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs(
                "name: Request\n" +
                "payload:\n" +
                "  type: Integer");
        delegate.addSingleDocs(
                "name: Operation Event\n" +
                "message:\n" +
                "  type:\n" +
                "    blueId: " + delegate.getBlueIdByName("Request"));
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);

        ResolvedSnapshot snapshot = blue.resolveToSnapshot(blue.yamlToNode(
                "name: Event\n" +
                "type:\n" +
                "  blueId: " + delegate.getBlueIdByName("Operation Event") + "\n" +
                "message:\n" +
                "  payload: 7"));
        FrozenNode target = FrozenNode.fromResolvedNode(blue.resolve(blue.yamlToNode(
                "type:\n" +
                "  blueId: " + delegate.getBlueIdByName("Operation Event"))));
        int fetchesAfterResolution = provider.fetches;

        NodeTypeMatcher matcher = new NodeTypeMatcher(blue);
        for (int i = 0; i < 100; i++) {
            assertTrue(matcher.matchesResolvedType(snapshot.frozenResolvedRoot(), target));
        }

        assertEquals(fetchesAfterResolution, provider.fetches);
    }

    @Test
    void directFrozenReferenceMatchingCachesResolvedReferenceLookups() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs(
                "name: Request Event\n" +
                "message:\n" +
                "  payload: 7\n" +
                "  ignored:\n" +
                "    deeply:\n" +
                "      nested: true");
        CountingNodeProvider provider = new CountingNodeProvider(delegate);
        Blue blue = new Blue(provider);
        FrozenNode candidateReference = FrozenNode.fromResolvedNode(new Node().blueId(delegate.getBlueIdByName("Request Event")));
        FrozenNode target = FrozenNode.fromResolvedNode(blue.yamlToNode(
                "message:\n" +
                "  payload: 7"));
        NodeTypeMatcher matcher = new NodeTypeMatcher(blue);

        for (int i = 0; i < 20; i++) {
            assertTrue(matcher.matchesResolvedType(candidateReference, target));
        }

        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Request Event")));
    }

    @Test
    void directFrozenReferenceMatchingCachesUnresolvedReferenceMisses() {
        CountingNodeProvider provider = new CountingNodeProvider(new BasicNodeProvider());
        Blue blue = new Blue(provider);
        FrozenNode missingReference = FrozenNode.fromResolvedNode(new Node().blueId("missing-reference"));
        FrozenNode target = FrozenNode.fromResolvedNode(blue.yamlToNode("payload: 1"));
        NodeTypeMatcher matcher = new NodeTypeMatcher(blue);

        for (int i = 0; i < 20; i++) {
            assertFalse(matcher.matchesResolvedType(missingReference, target));
        }

        assertEquals(2, provider.fetchesFor("missing-reference"));
    }

    @Test
    void resolvedSnapshotPointerMatchingUsesPathIndex() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Request\n" +
                "payload:\n" +
                "  type: Integer");
        Blue blue = new Blue(nodeProvider);
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(blue.yamlToNode(
                "message:\n" +
                "  request:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Request") + "\n" +
                "    payload: 5"));
        FrozenNode requestTarget = FrozenNode.fromResolvedNode(blue.yamlToNode(
                "payload:\n" +
                "  schema:\n" +
                "    required: true"));

        assertTrue(blue.nodeMatchesType(snapshot, "/message/request", requestTarget));
        assertFalse(blue.nodeMatchesType(snapshot, "/message", requestTarget));
    }

    @Test
    void missingSnapshotPointerMatchesOnlyOptionalTargetPatterns() {
        Blue blue = new Blue(new BasicNodeProvider());
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(blue.yamlToNode("message: ok"));
        FrozenNode optionalTarget = FrozenNode.fromResolvedNode(blue.yamlToNode(
                "schema:\n" +
                "  minLength: 3"));
        FrozenNode requiredTarget = FrozenNode.fromResolvedNode(blue.yamlToNode(
                "schema:\n" +
                "  required: true"));
        FrozenNode valueTarget = FrozenNode.fromResolvedNode(blue.yamlToNode("value: ok"));

        assertTrue(blue.nodeMatchesType(snapshot, "/missing", optionalTarget));
        assertFalse(blue.nodeMatchesType(snapshot, "/missing", requiredTarget));
        assertFalse(blue.nodeMatchesType(snapshot, "/missing", valueTarget));
    }

    private BasicNodeProvider complexOrderProvider(boolean invalidCustomerId) {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Active Status\nvalue: active");
        delegate.addSingleDocs(
                "name: Customer Notes\n" +
                "internal:\n" +
                "  very:\n" +
                "    deep:\n" +
                "      ignored: true");
        delegate.addSingleDocs(
                "name: Order Audit\n" +
                "events:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - created\n" +
                "    - checked");
        delegate.addSingleDocs(
                "name: Line Item One\n" +
                "sku: SKU-1\n" +
                "quantity: 2");
        delegate.addSingleDocs(
                "name: Line Item Two\n" +
                "sku: SKU-2\n" +
                "quantity: 1");
        delegate.addSingleDocs(
                "name: Customer\n" +
                "id: " + (invalidCustomerId ? "BAD-123" : "C-123") + "\n" +
                "status:\n" +
                "  blueId: " + delegate.getBlueIdByName("Active Status") + "\n" +
                "profile:\n" +
                "  email: ada@example.com\n" +
                "  preferences:\n" +
                "    type: Dictionary\n" +
                "    language: en\n" +
                "    timezone: UTC\n" +
                "notes:\n" +
                "  blueId: " + delegate.getBlueIdByName("Customer Notes"));
        delegate.addSingleDocs(
                "name: Order\n" +
                "customer:\n" +
                "  blueId: " + delegate.getBlueIdByName("Customer") + "\n" +
                "lineItems:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - blueId: " + delegate.getBlueIdByName("Line Item One") + "\n" +
                "    - blueId: " + delegate.getBlueIdByName("Line Item Two") + "\n" +
                "metadata:\n" +
                "  type: Dictionary\n" +
                "  source: checkout\n" +
                "  priority: standard\n" +
                "audit:\n" +
                "  blueId: " + delegate.getBlueIdByName("Order Audit"));
        return delegate;
    }

    private Node complexOrderPattern(Blue blue, String activeStatusBlueId) {
        return blue.yamlToNode(
                "order:\n" +
                "  customer:\n" +
                "    id:\n" +
                "      type: Text\n" +
                "      schema:\n" +
                "        minLength: 5\n" +
                "        maxLength: 5\n" +
                "    status:\n" +
                "      blueId: " + activeStatusBlueId + "\n" +
                "    profile:\n" +
                "      email:\n" +
                "        type: Text\n" +
                "        schema:\n" +
                "          minLength: 12\n" +
                "      preferences:\n" +
                "        type: Dictionary\n" +
                "        keyType: Text\n" +
                "  lineItems:\n" +
                "    type: List\n" +
                "    schema:\n" +
                "      allowMultiple: true\n" +
                "      minItems: 2\n" +
                "      maxItems: 2\n" +
                "  metadata:\n" +
                "    type: Dictionary\n" +
                "    keyType: Text");
    }

    private BasicNodeProvider generatedNestedReferenceProvider(boolean invalidLeaf,
                                                              int depth,
                                                              int ignoredSiblings) {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: Generated Exact Currency\nvalue: USD");
        for (int i = 0; i < ignoredSiblings; i++) {
            delegate.addSingleDocs(
                    "name: Generated Ignored " + i + "\n" +
                    "payload:\n" +
                    "  branch:\n" +
                    "    very:\n" +
                    "      deep:\n" +
                    "        value: ignored-" + i);
        }
        delegate.addSingleDocs(generatedLeafYaml(delegate, invalidLeaf, ignoredSiblings));

        String childBlueId = delegate.getBlueIdByName("Generated Leaf");
        for (int level = depth - 1; level >= 0; level--) {
            delegate.addSingleDocs(generatedLevelYaml(delegate, level, childBlueId, ignoredSiblings));
            childBlueId = delegate.getBlueIdByName("Generated Level " + level);
        }
        delegate.addSingleDocs(generatedRootYaml(delegate, childBlueId, ignoredSiblings));
        return delegate;
    }

    private String generatedLeafYaml(BasicNodeProvider delegate, boolean invalidLeaf, int ignoredSiblings) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: Generated Leaf\n")
                .append("kind: package\n")
                .append("customer:\n")
                .append("  id: C-123\n")
                .append("metrics:\n")
                .append("  score: ").append(invalidLeaf ? 5 : 42).append("\n")
                .append("  currency:\n")
                .append("    blueId: ").append(delegate.getBlueIdByName("Generated Exact Currency")).append("\n");
        appendIgnoredReferences(yaml, delegate, ignoredSiblings, 0);
        return yaml.toString();
    }

    private String generatedLevelYaml(BasicNodeProvider delegate,
                                      int level,
                                      String childBlueId,
                                      int ignoredSiblings) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: Generated Level ").append(level).append("\n")
                .append("level: ").append(level).append("\n")
                .append("tracked:\n")
                .append("  blueId: ").append(childBlueId).append("\n")
                .append("inlineNoise:\n")
                .append("  layer:\n")
                .append("    number: ").append(level).append("\n");
        appendIgnoredReferences(yaml, delegate, ignoredSiblings, 0);
        return yaml.toString();
    }

    private String generatedRootYaml(BasicNodeProvider delegate, String childBlueId, int ignoredSiblings) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: Generated Root\n")
                .append("tracked:\n")
                .append("  blueId: ").append(childBlueId).append("\n")
                .append("rootNoise:\n")
                .append("  source: ignored\n");
        appendIgnoredReferences(yaml, delegate, ignoredSiblings, 0);
        return yaml.toString();
    }

    private void appendIgnoredReferences(StringBuilder yaml,
                                         BasicNodeProvider delegate,
                                         int ignoredSiblings,
                                         int indent) {
        for (int i = 0; i < ignoredSiblings; i++) {
            appendIndent(yaml, indent).append("ignored").append(i).append(":\n");
            appendIndent(yaml, indent + 2)
                    .append("blueId: ")
                    .append(delegate.getBlueIdByName("Generated Ignored " + i))
                    .append("\n");
        }
    }

    private Node generatedNestedCandidate(Blue blue, BasicNodeProvider delegate) {
        return blue.yamlToNode(
                "candidate:\n" +
                "  blueId: " + delegate.getBlueIdByName("Generated Root"));
    }

    private Node generatedNestedPattern(Blue blue, BasicNodeProvider delegate, int depth) {
        StringBuilder yaml = new StringBuilder("candidate:\n");
        int indent = 2;
        for (int i = 0; i <= depth; i++) {
            appendIndent(yaml, indent).append("tracked:\n");
            indent += 2;
        }
        appendIndent(yaml, indent).append("kind: package\n");
        appendIndent(yaml, indent).append("customer:\n");
        appendIndent(yaml, indent + 2).append("id:\n");
        appendIndent(yaml, indent + 4).append("type: Text\n");
        appendIndent(yaml, indent + 4).append("schema:\n");
        appendIndent(yaml, indent + 6).append("minLength: 5\n");
        appendIndent(yaml, indent + 6).append("maxLength: 5\n");
        appendIndent(yaml, indent).append("metrics:\n");
        appendIndent(yaml, indent + 2).append("score:\n");
        appendIndent(yaml, indent + 4).append("type: Integer\n");
        appendIndent(yaml, indent + 4).append("schema:\n");
        appendIndent(yaml, indent + 6).append("minimum: 40\n");
        appendIndent(yaml, indent + 2).append("currency:\n");
        appendIndent(yaml, indent + 4)
                .append("blueId: ")
                .append(delegate.getBlueIdByName("Generated Exact Currency"))
                .append("\n");
        return blue.yamlToNode(yaml.toString());
    }

    private StringBuilder appendIndent(StringBuilder yaml, int indent) {
        for (int i = 0; i < indent; i++) {
            yaml.append(' ');
        }
        return yaml;
    }

    private void assertGeneratedNestedFetches(BasicNodeProvider delegate,
                                              CountingNodeProvider provider,
                                              int depth,
                                              int ignoredSiblings) {
        assertEquals(depth + 2, provider.fetches,
                "matching should fetch only root, observed levels, and observed leaf");
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Generated Root")));
        for (int level = 0; level < depth; level++) {
            assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Generated Level " + level)));
        }
        assertEquals(1, provider.fetchesFor(delegate.getBlueIdByName("Generated Leaf")));
        assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Generated Exact Currency")),
                "exact blueId leaves should be compared by identity without fetching");
        for (int i = 0; i < ignoredSiblings; i++) {
            assertEquals(0, provider.fetchesFor(delegate.getBlueIdByName("Generated Ignored " + i)),
                    "unmentioned sibling branch should not be expanded: " + i);
        }
    }

    private BasicNodeProvider explicitListProvider(boolean wrongLastStatus, boolean extraItem) {
        BasicNodeProvider delegate = new BasicNodeProvider();
        delegate.addSingleDocs("name: First Exact\nvalue: first");
        delegate.addSingleDocs("name: Active Status\nvalue: active");
        delegate.addSingleDocs("name: Wrong Status\nvalue: wrong");
        delegate.addSingleDocs("name: Unchecked Huge\nvalue: huge");
        delegate.addSingleDocs(
                "name: Middle Score\n" +
                "score: 12\n" +
                "debug:\n" +
                "  blueId: " + delegate.getBlueIdByName("Unchecked Huge"));
        delegate.addSingleDocs(
                "name: Last Details\n" +
                "details:\n" +
                "  status:\n" +
                "    blueId: " + delegate.getBlueIdByName(wrongLastStatus ? "Wrong Status" : "Active Status") + "\n" +
                "  audit:\n" +
                "    blueId: " + delegate.getBlueIdByName("Unchecked Huge"));
        delegate.addSingleDocs(
                "name: Extra Item\n" +
                "value: extra");
        delegate.addSingleDocs(
                "name: Candidate List\n" +
                "type: List\n" +
                "items:\n" +
                "  - blueId: " + delegate.getBlueIdByName("First Exact") + "\n" +
                "  - blueId: " + delegate.getBlueIdByName("Middle Score") + "\n" +
                "  - blueId: " + delegate.getBlueIdByName("Last Details") +
                (extraItem ? "\n  - blueId: " + delegate.getBlueIdByName("Extra Item") : ""));
        return delegate;
    }

    private Node explicitThreeItemListPattern(Blue blue, BasicNodeProvider delegate) {
        return blue.yamlToNode(
                "values:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - blueId: " + delegate.getBlueIdByName("First Exact") + "\n" +
                "    - score:\n" +
                "        type: Integer\n" +
                "        schema:\n" +
                "          required: true\n" +
                "          minimum: 10\n" +
                "    - details:\n" +
                "        status:\n" +
                "          blueId: " + delegate.getBlueIdByName("Active Status"));
    }

    private static final class CountingNodeProvider implements NodeProvider {
        private final BasicNodeProvider delegate;
        private final List<String> fetchedBlueIds = new java.util.ArrayList<>();
        private int fetches;

        private CountingNodeProvider(BasicNodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetches++;
            fetchedBlueIds.add(blueId);
            return delegate.fetchByBlueId(blueId);
        }

        private int fetchesFor(String blueId) {
            int count = 0;
            for (String fetchedBlueId : fetchedBlueIds) {
                if (blueId.equals(fetchedBlueId)) {
                    count++;
                }
            }
            return count;
        }
    }
}
