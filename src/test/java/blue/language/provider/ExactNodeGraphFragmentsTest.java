package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.UncheckedObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactNodeGraphFragmentsTest {

    @Test
    void recordsEveryInlineNodeAsAnExactShallowFragment() {
        Fixture fixture = fixture();
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(fixture.root);

        Map<String, Node> expectedInlineNodes = new TreeMap<>();
        collectInlineNodes(
                fixture.root,
                expectedInlineNodes,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));

        assertEquals(expectedInlineNodes.keySet(),
                new TreeSet<>(graph.blueIds()));
        assertEquals(expectedInlineNodes.keySet(),
                graph.fragments().keySet());

        for (Map.Entry<String, Node> entry
                : graph.fragments().entrySet()) {
            String blueId = entry.getKey();
            Node fragment = entry.getValue();
            assertNull(fragment.getBlueId(),
                    "A fragment must not contain its own identity.");
            assertEquals(blueId,
                    BlueIdCalculator.calculateBlueId(fragment));
            assertDirectChildrenArePureReferences(fragment);
        }

        ExactNodeGraphFragments.RootRepresentation root =
                graph.roots().get(0);
        String originalBlueId =
                BlueIdCalculator.calculateBlueId(fixture.root);
        assertEquals(originalBlueId, root.blueId());
        assertEquals(originalBlueId,
                BlueIdCalculator.calculateBlueId(root.original()));
        assertEquals(originalBlueId,
                BlueIdCalculator.calculateBlueId(root.directFragment()));
        assertEquals(originalBlueId,
                root.pureReference().getBlueId());
        assertTrue(root.pureReference().isReferenceOnly());
        Schema directSchema = root.directFragment().getSchema();
        assertFalse(directSchema.getMinLength().isReferenceOnly());
        assertTrue(directSchema.getMinimum().isReferenceOnly());
        assertFalse(directSchema.getEnum().get(0).isReferenceOnly());
        assertTrue(directSchema.getEnum().get(1).isReferenceOnly());
        assertEquals(
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(fixture.root),
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(root.original()));
    }

    @Test
    void ordersFragmentsDeterministicallyAndKeepsRootsIndependent() {
        Fixture fixture = fixture();
        Node unrelated = new Node().properties(
                "unrelated", new Node().value("separate"));
        ExactNodeGraphFragments first =
                new ExactNodeGraphFragments(fixture.root, unrelated);
        ExactNodeGraphFragments reversed =
                new ExactNodeGraphFragments(unrelated, fixture.root);

        List<String> sorted = new ArrayList<>(first.blueIds());
        Collections.sort(sorted);
        assertEquals(sorted, first.blueIds());
        assertEquals(first.blueIds(), reversed.blueIds());
        assertEquals(first.blueIds(),
                new ArrayList<>(first.fragments().keySet()));

        assertEquals(BlueIdCalculator.calculateBlueId(fixture.root),
                first.roots().get(0).blueId());
        assertEquals(BlueIdCalculator.calculateBlueId(unrelated),
                first.roots().get(1).blueId());
        assertEquals(BlueIdCalculator.calculateBlueId(unrelated),
                reversed.roots().get(0).blueId());
        assertEquals(BlueIdCalculator.calculateBlueId(fixture.root),
                reversed.roots().get(1).blueId());

        String unrelatedBlueId =
                BlueIdCalculator.calculateBlueId(unrelated);
        Node unrelatedFragment =
                first.fragments().get(unrelatedBlueId);
        assertEquals(unrelatedBlueId,
                BlueIdCalculator.calculateBlueId(unrelatedFragment));
        assertFalse(unrelatedFragment.getProperties()
                .containsKey("root-only"));
    }

    @Test
    void snapshotsAndProviderResultsAreDefensive() {
        Node child = new Node().value("original");
        Node supplied = new Node().name("retained")
                .properties("child", child);
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(supplied);
        String rootBlueId = graph.roots().get(0).blueId();

        child.value("mutated-input");
        supplied.name("mutated-input");
        assertEquals("retained", graph.roots().get(0).original().getName());
        assertEquals(rootBlueId,
                BlueIdCalculator.calculateBlueId(
                        graph.roots().get(0).original()));

        assertThrows(UnsupportedOperationException.class,
                () -> graph.blueIds().add(rootBlueId));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.fragments().put(
                        rootBlueId, new Node().value("replacement")));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.roots().add(graph.roots().get(0)));

        Node returnedFragment = graph.fragments().get(rootBlueId);
        returnedFragment.name("tampered-copy");
        assertEquals("retained",
                graph.fragments().get(rootBlueId).getName());

        Node returnedOriginal = graph.roots().get(0).original();
        returnedOriginal.name("tampered-original-copy");
        assertEquals("retained",
                graph.roots().get(0).original().getName());

        Node returnedDirect = graph.roots().get(0).directFragment();
        returnedDirect.name("tampered-direct-copy");
        assertEquals("retained",
                graph.roots().get(0).directFragment().getName());

        List<Node> firstFetch =
                graph.provider().fetchByBlueId(rootBlueId);
        firstFetch.get(0).name("tampered-provider-copy");
        List<Node> secondFetch =
                graph.provider().fetchByBlueId(rootBlueId);
        assertNotSame(firstFetch.get(0), secondFetch.get(0));
        assertEquals(rootBlueId,
                BlueIdCalculator.calculateBlueId(secondFetch.get(0)));
    }

    @Test
    void providerReturnsVerifiedFoundAndCanonicalNotFoundOutcomes() {
        Fixture fixture = fixture();
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(fixture.root);
        String rootBlueId = graph.roots().get(0).blueId();
        NodeProvider provider = graph.provider();

        NodeProviderResult found =
                provider.fetchResultByBlueId(rootBlueId);
        assertEquals(NodeProviderOutcome.FOUND, found.outcome());
        assertEquals(rootBlueId,
                BlueIdCalculator.calculateBlueId(found.nodes().get(0)));

        NodeProviderResult verifiedFound =
                new VerifyingNodeProvider(provider)
                        .fetchResultByBlueId(rootBlueId);
        assertEquals(NodeProviderOutcome.FOUND,
                verifiedFound.outcome());

        String missingBlueId = BlueIdCalculator.calculateBlueId(
                new Node().value("definitely-not-admitted"));
        assertNotEquals(rootBlueId, missingBlueId);
        assertEquals(NodeProviderOutcome.NOT_FOUND,
                provider.fetchResultByBlueId(missingBlueId).outcome());
        assertNull(provider.fetchByBlueId(missingBlueId));
        assertEquals(NodeProviderOutcome.NOT_FOUND,
                new VerifyingNodeProvider(provider)
                        .fetchResultByBlueId(missingBlueId)
                        .outcome());
    }

    @Test
    void rejectsCyclicMembersMixedObjectCyclesAndSelfIdentityContent() {
        String plainBlueId = BlueIdCalculator.calculateBlueId(
                new Node().value("ordinary-reference-target"));

        IllegalArgumentException memberFailure =
                assertThrows(IllegalArgumentException.class,
                        () -> new ExactNodeGraphFragments(
                                new Node().properties(
                                        "member",
                                        new Node().blueId(
                                                plainBlueId + "#0"))));
        assertTrue(memberFailure.getMessage()
                .contains("Cyclic-set/member"));

        IllegalArgumentException placeholderFailure =
                assertThrows(IllegalArgumentException.class,
                        () -> new ExactNodeGraphFragments(
                                new Node().properties(
                                        "member",
                                        new Node().blueId("this#0"))));
        assertTrue(placeholderFailure.getMessage()
                .contains("Cyclic-set/member"));

        Node mixedCycle = new Node();
        mixedCycle.properties(
                "external", new Node().blueId(plainBlueId),
                "objectCycle", mixedCycle);
        IllegalArgumentException cycleFailure =
                assertThrows(IllegalArgumentException.class,
                        () -> new ExactNodeGraphFragments(mixedCycle));
        assertTrue(cycleFailure.getMessage().contains("cycle"));

        Node ownIdentityInContent = new Node()
                .blueId(plainBlueId)
                .value("content");
        IllegalArgumentException ownIdentityFailure =
                assertThrows(IllegalArgumentException.class,
                        () -> new ExactNodeGraphFragments(
                                ownIdentityInContent));
        assertTrue(ownIdentityFailure.getMessage()
                .contains("own BlueId"));

        assertThrows(IllegalArgumentException.class,
                () -> new ExactNodeGraphFragments(
                        new Node().blueId(plainBlueId)));
        assertThrows(IllegalArgumentException.class,
                () -> new ExactNodeGraphFragments(
                        Collections.<Node>emptyList()));
    }

    private static Fixture fixture() {
        String externalBlueId = BlueIdCalculator.calculateBlueId(
                new Node().value("external-content"));
        Node leaf = new Node().value("leaf");
        Node objectChild = new Node().properties(
                "leaf", leaf,
                "external", new Node().blueId(externalBlueId));
        Node listChild = new Node().items(
                new Node().value(7),
                new Node().properties(
                        "deep", new Node().value(true)));
        Node inlineType = new Node().properties(
                "kind", new Node().value("fixture-type"));
        Node contracts = new Node().properties(
                "guard", new Node().value(false));
        Schema schema = new Schema()
                .minLength(new Node().value(1))
                .minimum(new Node().name("decorated-floor").value(0))
                .enumValues(Arrays.asList(
                        new Node().value("red"),
                        new Node().blueId(externalBlueId)));
        Node root = new Node()
                .name("root")
                .type(inlineType)
                .contracts(contracts)
                .schema(schema)
                .properties(
                        "child", objectChild,
                        "list", listChild,
                        "root-only", new Node().value("root"));
        return new Fixture(root);
    }

    private static void collectInlineNodes(
            Node node,
            Map<String, Node> nodes,
            Set<Node> visited) {
        if (node == null || node.isReferenceOnly() || !visited.add(node)) {
            return;
        }
        nodes.put(BlueIdCalculator.calculateBlueId(node), node);
        collectInlineNodes(node.getType(), nodes, visited);
        collectInlineNodes(node.getItemType(), nodes, visited);
        collectInlineNodes(node.getKeyType(), nodes, visited);
        collectInlineNodes(node.getValueType(), nodes, visited);
        collectInlineNodes(node.getContracts(), nodes, visited);
        collectInlineNodes(node.getBlue(), nodes, visited);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                collectInlineNodes(item, nodes, visited);
            }
        }
        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                collectInlineNodes(property, nodes, visited);
            }
        }
        collectInlineNodes(node.getSchema(), nodes, visited);
    }

    private static void collectInlineNodes(
            Schema schema,
            Map<String, Node> nodes,
            Set<Node> visited) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        collectExplicitSchemaNode(schema.getMinimum(), nodes, visited);
        collectExplicitSchemaNode(schema.getMaximum(), nodes, visited);
        collectExplicitSchemaNode(
                schema.getExclusiveMinimum(), nodes, visited);
        collectExplicitSchemaNode(
                schema.getExclusiveMaximum(), nodes, visited);
        collectExplicitSchemaNode(schema.getMultipleOf(), nodes, visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                collectExplicitSchemaNode(value, nodes, visited);
            }
        }
    }

    private static void collectExplicitSchemaNode(
            Node node,
            Map<String, Node> nodes,
            Set<Node> visited) {
        if (node != null && !isPlainSchemaScalar(node)) {
            collectInlineNodes(node, nodes, visited);
        }
    }

    private static void assertDirectChildrenArePureReferences(Node node) {
        assertPureReferenceOrNull(node.getType());
        assertPureReferenceOrNull(node.getItemType());
        assertPureReferenceOrNull(node.getKeyType());
        assertPureReferenceOrNull(node.getValueType());
        assertPureReferenceOrNull(node.getContracts());
        assertPureReferenceOrNull(node.getBlue());
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                assertTrue(item.isReferenceOnly());
            }
        }
        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                assertTrue(property.isReferenceOnly());
            }
        }
        assertDirectSchemaChildrenArePureReferences(node.getSchema());
    }

    private static void assertDirectSchemaChildrenArePureReferences(
            Schema schema) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        assertPlainSchemaScalarOrNull(schema.getRequired());
        assertPlainSchemaScalarOrNull(schema.getMinLength());
        assertPlainSchemaScalarOrNull(schema.getMaxLength());
        assertSchemaValueOrReference(schema.getMinimum());
        assertSchemaValueOrReference(schema.getMaximum());
        assertSchemaValueOrReference(schema.getExclusiveMinimum());
        assertSchemaValueOrReference(schema.getExclusiveMaximum());
        assertSchemaValueOrReference(schema.getMultipleOf());
        assertPlainSchemaScalarOrNull(schema.getMinItems());
        assertPlainSchemaScalarOrNull(schema.getMaxItems());
        assertPlainSchemaScalarOrNull(schema.getUniqueItems());
        assertPlainSchemaScalarOrNull(schema.getMinFields());
        assertPlainSchemaScalarOrNull(schema.getMaxFields());
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                assertSchemaValueOrReference(value);
            }
        }
    }

    private static void assertPlainSchemaScalarOrNull(Node node) {
        assertTrue(node == null || isPlainSchemaScalar(node));
    }

    private static void assertSchemaValueOrReference(Node node) {
        assertTrue(node == null
                || node.isReferenceOnly()
                || isPlainSchemaScalar(node));
    }

    private static boolean isPlainSchemaScalar(Node node) {
        return node != null
                && node.getRawValue() != null
                && node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null;
    }

    private static void assertPureReferenceOrNull(Node node) {
        assertTrue(node == null || node.isReferenceOnly());
    }

    private static final class Fixture {

        private final Node root;

        private Fixture(Node root) {
            this.root = root;
        }
    }
}
