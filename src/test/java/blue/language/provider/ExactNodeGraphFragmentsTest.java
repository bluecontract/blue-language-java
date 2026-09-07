package blue.language.provider;

import blue.language.api.NodeProviderOutcome;

import blue.language.preprocess.provider.BasicNodeProvider;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.registry.NodeProviderWrapper;
import blue.language.identity.BlueIds;
import blue.language.codec.jackson.UncheckedObjectMapper;
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

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactNodeGraphFragmentsTest {

    @Test
    void shouldRecordEveryInlineNodeAsAnExactShallowFragment() {
        // given
        Fixture fixture = fixture();
        Map<String, Node> expectedInlineNodes = new TreeMap<>();

        // when
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(fixture.root);
        collectInlineNodes(
                fixture.root,
                expectedInlineNodes,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()));
        ExactNodeGraphFragments.RootRepresentation root =
                graph.roots().get(0);
        String originalBlueId =
                DirectBlueIdCalculator.calculateBlueId(fixture.root);
        Schema directSchema = root.directFragment().getSchema();

        // then
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
                    DirectBlueIdCalculator.calculateBlueId(fragment));
            assertDirectChildrenArePureReferences(fragment);
        }

        assertEquals(originalBlueId, root.blueId());
        assertEquals(originalBlueId,
                DirectBlueIdCalculator.calculateBlueId(root.original()));
        assertEquals(originalBlueId,
                DirectBlueIdCalculator.calculateBlueId(root.directFragment()));
        assertEquals(originalBlueId,
                root.pureReference().getBlueId());
        assertTrue(root.pureReference().isReferenceOnly());
        assertFalse(directSchema.getMinLength().isReferenceOnly());
        assertTrue(directSchema.getMinimum().isReferenceOnly());
        assertFalse(directSchema.getEnum().get(0).isReferenceOnly());
        assertTrue(directSchema.getEnum().get(1).isReferenceOnly());
        assertEquals(
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(fixture.root),
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(root.original()));
    }

    @Test
    void shouldOrderFragmentsDeterministicallyAndKeepRootsIndependent() {
        // given
        Fixture fixture = fixture();
        Node unrelated = new Node().properties(
                "unrelated", new Node().value("separate"));
        // when
        ExactNodeGraphFragments first =
                new ExactNodeGraphFragments(fixture.root, unrelated);
        ExactNodeGraphFragments reversed =
                new ExactNodeGraphFragments(unrelated, fixture.root);
        List<String> sorted = new ArrayList<>(first.blueIds());
        Collections.sort(sorted);
        String unrelatedBlueId =
                DirectBlueIdCalculator.calculateBlueId(unrelated);
        Node unrelatedFragment =
                first.fragments().get(unrelatedBlueId);

        // then
        assertEquals(sorted, first.blueIds());
        assertEquals(first.blueIds(), reversed.blueIds());
        assertEquals(first.blueIds(),
                new ArrayList<>(first.fragments().keySet()));

        assertEquals(DirectBlueIdCalculator.calculateBlueId(fixture.root),
                first.roots().get(0).blueId());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(unrelated),
                first.roots().get(1).blueId());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(unrelated),
                reversed.roots().get(0).blueId());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(fixture.root),
                reversed.roots().get(1).blueId());

        assertEquals(unrelatedBlueId,
                DirectBlueIdCalculator.calculateBlueId(unrelatedFragment));
        assertFalse(unrelatedFragment.getProperties()
                .containsKey("root-only"));
    }

    @Test
    void shouldSplitOnlySelectedCutsAndTheirAncestorSpine() {
        // given
        Node root = UncheckedObjectMapper.YAML_MAPPER.readValue(
                "name: Fragmented Root\n"
                        + "selected:\n"
                        + "  a: 1\n"
                        + "  body:\n"
                        + "    code: selected\n"
                        + "    constants: [A, B]\n"
                        + "archive:\n"
                        + "  data:\n"
                        + "    untouched: true\n"
                        + "sibling:\n"
                        + "  x: 9\n",
                Node.class);

        // when
        ExactNodeGraphFragments graph = ExactNodeGraphFragments.split(
                root,
                Arrays.asList(
                        "/selected/body",
                        "/archive",
                        "/sibling"));
        ExactNodeGraphFragments.RootRepresentation forms =
                graph.roots().get(0);
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        Node directRoot = forms.directFragment();
        Node directSelected = graph.provider().fetchByBlueId(
                directRoot.getProperties()
                        .get("selected").getBlueId()).get(0);
        Node directBody = graph.provider().fetchByBlueId(
                directSelected.getProperties()
                        .get("body").getBlueId()).get(0);
        Node roundTrip = new Blue(graph.provider())
                .expand(forms.pureReference());

        // then
        assertEquals(5, graph.fragments().size());
        assertEquals(rootBlueId, forms.blueId());
        assertEquals(rootBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        forms.directFragment()));
        assertEquals(rootBlueId, forms.pureReference().getBlueId());

        assertTrue(directRoot.getProperties()
                .get("selected").isReferenceOnly());
        assertTrue(directRoot.getProperties()
                .get("archive").isReferenceOnly());
        assertTrue(directRoot.getProperties()
                .get("sibling").isReferenceOnly());

        assertFalse(directSelected.getProperties()
                .get("a").isReferenceOnly());
        assertTrue(directSelected.getProperties()
                .get("body").isReferenceOnly());

        assertFalse(directBody.getProperties()
                .get("code").isReferenceOnly());
        assertFalse(directBody.getProperties()
                .get("constants").isReferenceOnly());

        assertEquals(
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(root),
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(roundTrip));
    }

    @Test
    void shouldCanonicalizeSelectedCutOrderAndSupportEscapedAndListSegments() {
        // given
        Node root = new Node().properties(
                "z", new Node().value(3),
                "a/b", new Node().items(
                        new Node().value("first"),
                        new Node().properties(
                                "deep", new Node().value(true))),
                "m", new Node().value(2));

        // when
        ExactNodeGraphFragments authored =
                ExactNodeGraphFragments.split(
                        root,
                        Arrays.asList("/z", "/a~1b/1", "/m"));
        ExactNodeGraphFragments reversed =
                ExactNodeGraphFragments.split(
                        root,
                        Arrays.asList("/m", "/a~1b/1", "/z"));
        Node directRoot = authored.roots().get(0).directFragment();
        Node directList = authored.provider().fetchByBlueId(
                directRoot.getProperties().get("a/b")
                        .getBlueId()).get(0);
        IllegalArgumentException missingFailure = captureFailure(
                () -> ExactNodeGraphFragments.split(
                        root, Collections.singletonList("/missing")));
        IllegalArgumentException nonCanonicalIndexFailure = captureFailure(
                () -> ExactNodeGraphFragments.split(
                        root, Collections.singletonList("/a~1b/01")));

        // then
        assertEquals(authored.blueIds(), reversed.blueIds());
        assertEquals(authored.fragments().keySet(),
                reversed.fragments().keySet());
        for (String blueId : authored.blueIds()) {
            assertEquals(
                    UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                            authored.fragments().get(blueId)),
                    UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                            reversed.fragments().get(blueId)));
        }
        assertEquals(5, authored.fragments().size());

        assertFalse(directList.getItems().get(0).isReferenceOnly());
        assertTrue(directList.getItems().get(1).isReferenceOnly());
        assertTrue(missingFailure instanceof IllegalArgumentException);
        assertTrue(nonCanonicalIndexFailure instanceof IllegalArgumentException);
    }

    @Test
    void shouldDefensivelyCopySnapshotsAndProviderResults() {
        // given
        Node child = new Node().value("original");
        Node supplied = new Node().name("retained")
                .properties("child", child);

        // when
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(supplied);
        String rootBlueId = graph.roots().get(0).blueId();
        child.value("mutated-input");
        supplied.name("mutated-input");
        String retainedOriginalName = graph.roots().get(0).original().getName();
        String retainedOriginalBlueId =
                DirectBlueIdCalculator.calculateBlueId(graph.roots().get(0).original());
        UnsupportedOperationException blueIdsFailure = captureFailure(
                () -> graph.blueIds().add(rootBlueId));
        UnsupportedOperationException fragmentsFailure = captureFailure(
                () -> graph.fragments().put(
                        rootBlueId, new Node().value("replacement")));
        UnsupportedOperationException rootsFailure = captureFailure(
                () -> graph.roots().add(graph.roots().get(0)));
        Node returnedFragment = graph.fragments().get(rootBlueId);
        returnedFragment.name("tampered-copy");
        String fragmentNameAfterTamper =
                graph.fragments().get(rootBlueId).getName();
        Node returnedOriginal = graph.roots().get(0).original();
        returnedOriginal.name("tampered-original-copy");
        String originalNameAfterTamper =
                graph.roots().get(0).original().getName();
        Node returnedDirect = graph.roots().get(0).directFragment();
        returnedDirect.name("tampered-direct-copy");
        String directNameAfterTamper =
                graph.roots().get(0).directFragment().getName();
        List<Node> firstFetch =
                graph.provider().fetchByBlueId(rootBlueId);
        firstFetch.get(0).name("tampered-provider-copy");
        List<Node> secondFetch =
                graph.provider().fetchByBlueId(rootBlueId);

        // then
        assertEquals("retained", retainedOriginalName);
        assertEquals(rootBlueId, retainedOriginalBlueId);
        assertTrue(blueIdsFailure instanceof UnsupportedOperationException);
        assertTrue(fragmentsFailure instanceof UnsupportedOperationException);
        assertTrue(rootsFailure instanceof UnsupportedOperationException);
        assertEquals("retained", fragmentNameAfterTamper);
        assertEquals("retained", originalNameAfterTamper);
        assertEquals("retained", directNameAfterTamper);
        assertNotSame(firstFetch.get(0), secondFetch.get(0));
        assertEquals(rootBlueId,
                DirectBlueIdCalculator.calculateBlueId(secondFetch.get(0)));
    }

    @Test
    void shouldReturnVerifiedFoundAndCanonicalNotFoundProviderOutcomes() {
        // given
        Fixture fixture = fixture();
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(fixture.root);
        String rootBlueId = graph.roots().get(0).blueId();
        NodeProvider provider = graph.provider();

        // when
        NodeProviderResult found =
                provider.fetchResultByBlueId(rootBlueId);
        NodeProviderResult verifiedFound =
                new VerifyingNodeProvider(provider)
                        .fetchResultByBlueId(rootBlueId);
        String missingBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("definitely-not-admitted"));
        NodeProviderOutcome missingOutcome =
                provider.fetchResultByBlueId(missingBlueId).outcome();
        List<Node> missing = provider.fetchByBlueId(missingBlueId);
        NodeProviderOutcome verifiedMissingOutcome =
                new VerifyingNodeProvider(provider)
                        .fetchResultByBlueId(missingBlueId).outcome();

        // then
        assertEquals(NodeProviderOutcome.FOUND, found.outcome());
        assertEquals(rootBlueId,
                DirectBlueIdCalculator.calculateBlueId(found.nodes().get(0)));
        assertEquals(NodeProviderOutcome.FOUND, verifiedFound.outcome());
        assertNotEquals(rootBlueId, missingBlueId);
        assertEquals(NodeProviderOutcome.NOT_FOUND, missingOutcome);
        assertNull(missing);
        assertEquals(NodeProviderOutcome.NOT_FOUND, verifiedMissingOutcome);
    }

    @Test
    void shouldPreserveOpaqueFinalCyclicMemberEdgesWithoutClaimingThemLocally() {
        // given
        CyclicMemberFixture cyclic = cyclicMemberFixture();
        Node root = new Node()
                .name("root-with-cyclic-edge")
                .type(new Node().blueId(cyclic.memberBlueId))
                .properties(
                        "member",
                        new Node().blueId(cyclic.memberBlueId));
        Node event = new Node()
                .name("event-with-cyclic-edge")
                .properties(
                        "member",
                        new Node().blueId(cyclic.memberBlueId));
        String expectedRootBlueId =
                DirectBlueIdCalculator.calculateBlueId(root);
        String expectedEventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);

        // when
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(root, event);
        ExactNodeGraphFragments.RootRepresentation rootForms =
                graph.roots().get(0);
        ExactNodeGraphFragments.RootRepresentation eventForms =
                graph.roots().get(1);
        NodeProviderOutcome cyclicMemberOutcome = graph.provider()
                .fetchResultByBlueId(cyclic.memberBlueId)
                .outcome();
        List<Node> cyclicMemberContent =
                graph.provider().fetchByBlueId(cyclic.memberBlueId);

        // then
        assertEquals(expectedRootBlueId, rootForms.blueId());
        assertEquals(expectedRootBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        rootForms.directFragment()));
        assertEquals(expectedEventBlueId, eventForms.blueId());
        assertEquals(expectedEventBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        eventForms.directFragment()));
        assertEquals(cyclic.memberBlueId,
                rootForms.directFragment().getType().getBlueId());
        assertEquals(cyclic.memberBlueId,
                rootForms.directFragment().getProperties()
                        .get("member").getBlueId());
        assertEquals(cyclic.memberBlueId,
                eventForms.directFragment().getProperties()
                        .get("member").getBlueId());
        assertFalse(graph.blueIds().contains(cyclic.memberBlueId));
        assertFalse(graph.fragments().containsKey(
                cyclic.memberBlueId));
        assertEquals(NodeProviderOutcome.NOT_FOUND, cyclicMemberOutcome);
        assertNull(cyclicMemberContent);
    }

    @Test
    void shouldResolveOpaqueCyclicMemberWithComposedVerifiedProviderButNotPlainProvider() {
        // given
        CyclicMemberFixture cyclic = cyclicMemberFixture();
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(
                        new Node().properties(
                                "member",
                                new Node().blueId(
                                        cyclic.memberBlueId)));
        NodeProvider composed = NodeProviderWrapper.wrap(
                new SequentialNodeProvider(
                        graph.provider(),
                        cyclic.provider));

        // when
        NodeProviderResult found =
                composed.fetchResultByBlueId(
                        cyclic.memberBlueId);
        List<Node> unprovedContent =
                cyclic.provider.fetchByBlueId(
                        cyclic.memberBlueId);
        NodeProvider unproved = blueId ->
                cyclic.memberBlueId.equals(blueId)
                        ? unprovedContent
                        : null;
        NodeProviderResult invalid =
                new VerifyingNodeProvider(unproved)
                        .fetchResultByBlueId(
                                cyclic.memberBlueId);

        // then
        assertEquals(NodeProviderOutcome.FOUND,
                found.outcome());
        assertFalse(found.nodes().isEmpty());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                invalid.outcome());
        assertTrue(invalid.diagnostic().orElse("")
                .contains("cyclic-set-aware verifier"));
    }

    @Test
    void shouldSupportOpaqueFinalCyclicMembersInSchemaReferencesAndValues() {
        // given
        CyclicMemberFixture cyclic = cyclicMemberFixture();
        Node schemaReferenceRoot = new Node()
                .schema(new Schema().blueId(
                        cyclic.memberBlueId));
        Node schemaValueRoot = new Node()
                .schema(new Schema().enumValues(
                        Collections.singletonList(
                                new Node().blueId(
                                        cyclic.memberBlueId))));

        // when
        ExactNodeGraphFragments graph =
                new ExactNodeGraphFragments(
                        schemaReferenceRoot,
                        schemaValueRoot);
        Node directSchemaReference =
                graph.roots().get(0).directFragment();
        Node directSchemaValue =
                graph.roots().get(1).directFragment();

        // then
        assertEquals(cyclic.memberBlueId,
                directSchemaReference.getSchema()
                        .getBlueId());
        assertEquals(cyclic.memberBlueId,
                directSchemaValue.getSchema()
                        .getEnum().get(0).getBlueId());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        schemaReferenceRoot),
                DirectBlueIdCalculator.calculateBlueId(
                        directSchemaReference));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        schemaValueRoot),
                DirectBlueIdCalculator.calculateBlueId(
                        directSchemaValue));
        assertFalse(graph.fragments().containsKey(
                cyclic.memberBlueId));
    }

    @Test
    void shouldRejectCyclicPlaceholdersAndMalformedMemberReferences() {
        // given
        String plainBlueId = ordinaryReferenceBlueId();

        // when
        Throwable placeholderFailure =
                captureFailure(
                        () -> new ExactNodeGraphFragments(
                                new Node().properties(
                                        "member",
                                        new Node().blueId("this#0"))));
        Throwable zeroPlaceholderFailure = captureFailure(
                () -> new ExactNodeGraphFragments(
                        new Node().properties(
                                "member",
                                new Node().blueId(
                                        BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER))));
        Throwable malformedMemberFailure = captureFailure(
                () -> new ExactNodeGraphFragments(
                        new Node().properties(
                                "member",
                                new Node().blueId(
                                        plainBlueId + "#01"))));

        // then
        assertTrue(placeholderFailure instanceof IllegalArgumentException);
        assertTrue(placeholderFailure.getMessage()
                .contains("only inside cyclic BlueId calculation"));
        assertTrue(zeroPlaceholderFailure instanceof IllegalArgumentException);
        assertTrue(malformedMemberFailure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectCyclicMembersAsPreviousAnchorsOrClaimedContent() {
        // given
        String cyclicMemberBlueId = cyclicMemberBlueId();

        // when
        Throwable previousMemberFailure = captureFailure(
                () -> new ExactNodeGraphFragments(
                        new Node().items(
                                new Node().previousBlueId(
                                        cyclicMemberBlueId),
                                new Node().value("tail"))));
        Throwable claimedMemberFailure = captureFailure(
                () -> new ExactNodeGraphFragments(
                        new Node().properties(
                                "member",
                                new Node()
                                        .blueId(cyclicMemberBlueId)
                                        .value("claimed member content"))));

        // then
        assertTrue(previousMemberFailure instanceof IllegalArgumentException);
        assertTrue(claimedMemberFailure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectMixedObjectCyclesDuringFragmentCollection() {
        // given
        String plainBlueId = ordinaryReferenceBlueId();
        Node mixedCycle = new Node();
        mixedCycle.properties(
                "external", new Node().blueId(plainBlueId),
                "objectCycle", mixedCycle);

        // when
        Throwable cycleFailure =
                captureFailure(
                        () -> new ExactNodeGraphFragments(mixedCycle));

        // then
        assertTrue(cycleFailure instanceof IllegalArgumentException);
        assertTrue(cycleFailure.getMessage().contains("cycle"));
    }

    @Test
    void shouldRejectRootContentThatClaimsItsOwnBlueId() {
        // given
        String plainBlueId = ordinaryReferenceBlueId();
        Node ownIdentityInContent = new Node()
                .blueId(plainBlueId)
                .value("content");

        // when
        Throwable ownIdentityFailure =
                captureFailure(
                        () -> new ExactNodeGraphFragments(
                                ownIdentityInContent));

        // then
        assertTrue(ownIdentityFailure instanceof IllegalArgumentException);
        assertTrue(ownIdentityFailure.getMessage().contains("own BlueId"));
    }

    @Test
    void shouldRejectReferenceOnlyOrEmptyFragmentRoots() {
        // given
        String plainBlueId = ordinaryReferenceBlueId();
        String cyclicMemberBlueId = cyclicMemberBlueId();

        // when
        Throwable plainReferenceFailure = captureFailure(
                () -> new ExactNodeGraphFragments(
                        new Node().blueId(plainBlueId)));
        Throwable cyclicReferenceFailure = captureFailure(
                () -> new ExactNodeGraphFragments(
                        new Node().blueId(
                                cyclicMemberBlueId)));
        Throwable emptyRootsFailure = captureFailure(
                () -> new ExactNodeGraphFragments(
                        Collections.<Node>emptyList()));

        // then
        assertTrue(plainReferenceFailure instanceof IllegalArgumentException);
        assertTrue(cyclicReferenceFailure instanceof IllegalArgumentException);
        assertTrue(emptyRootsFailure instanceof IllegalArgumentException);
    }

    private static String ordinaryReferenceBlueId() {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value("ordinary-reference-target"));
    }

    private static String cyclicMemberBlueId() {
        return ordinaryReferenceBlueId() + "#0";
    }

    private static CyclicMemberFixture cyclicMemberFixture() {
        Node cyclicSet = new Node().items(
                new Node()
                        .name("Fragment Cyclic A")
                        .properties(
                                "next",
                                new Node().type(
                                        new Node().blueId(
                                                "this#1"))),
                new Node()
                        .name("Fragment Cyclic B")
                        .properties(
                                "next",
                                new Node().type(
                                        new Node().blueId(
                                                "this#0"))));
        BasicNodeProvider provider =
                new BasicNodeProvider(cyclicSet);
        return new CyclicMemberFixture(
                provider,
                provider.getBlueIdByName(
                        "Fragment Cyclic A"));
    }

    private static Fixture fixture() {
        String externalBlueId = DirectBlueIdCalculator.calculateBlueId(
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
        String inlineTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                inlineType);
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
                .type(new Node().blueId(inlineTypeBlueId))
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
        nodes.put(DirectBlueIdCalculator.calculateBlueId(node), node);
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

    private static final class CyclicMemberFixture {

        private final BasicNodeProvider provider;
        private final String memberBlueId;

        private CyclicMemberFixture(
                BasicNodeProvider provider,
                String memberBlueId) {
            this.provider = provider;
            this.memberBlueId = memberBlueId;
        }
    }
}
