package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class CompositionCollectionChannelTest {
    @Test
    void shouldCompileCatchAllAndCollectionDescriptorsAndAcceptEscapedMemberEvents() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture();
             ManagedDocumentStepProcessor steps = new ManagedDocumentStepProcessor(fixture.owner)) {
            for (int size : new int[]{0, 1, 3}) {
                Node members = new Node().properties(Collections.emptyMap());
                String[] keys = {"a/b", "a~b", "plain"};
                for (int i = size - 1; i >= 0; i--) members.properties(keys[i], document("same"));
                Node parent = document("collection").properties("children", members);
                parent.getContracts().properties("embedded", process("collectionPaths", "/children"))
                        .properties("all", embedded(null))
                        .properties("members", typed(RuntimeBlueIds.EMBEDDED_COLLECTION_EVENT_CHANNEL)
                                .properties("collectionPath", new Node().value("/children")))
                        .properties("allHandler", handler("all"))
                        .properties("membersHandler", handler("members"));
                // when
                List<ManagedRootChannelOccurrence> descriptors = steps.projectRootChannelSurface(parent);
                List<ManagedProcessEmbeddedPath> paths = steps.projectManagedProcessEmbeddedSurface(parent);
                // then
                ManagedRootChannelOccurrence catchAll = descriptors.stream()
                        .filter(value -> value.rawChannelKey().equals("all")).findFirst().get();
                assertEquals(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL, catchAll.effectiveTypeBlueId());
                assertFalse(catchAll.externalSource());
                assertEquals(id(embedded(null)), catchAll.effectiveRuntimeContributionBlueId());
                List<String> expected = new ArrayList<>();
                for (int i = 0; i < size; i++) expected.add("/children/" + escape(keys[i]));
                Collections.sort(expected);
                assertEquals(expected, paths.stream().map(ManagedProcessEmbeddedPath::absolutePath)
                        .collect(Collectors.toList()));
                for (String path : expected) {
                    assertEquals(Arrays.asList("all", "members"), routes(steps, parent, path));
                }
                assertEquals(Collections.singletonList("all"), routes(steps, parent, "/unrelated/member"));
                assertEquals(Collections.singletonList("all"), routes(steps, parent, "/children/a~1b/nested"));
            }
        }
    }

    @Test
    void shouldInitializeChildrenBornDuringAdmissionAndDrainTheirEffects() {
        // given
        for (String domain : Arrays.asList("Agreement-Order-Payment", "Study-Sample-Reading")) {
            try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
                DocumentId root = new DocumentId("a");
                DocumentId child = new DocumentId("b");
                DocumentId grandchild = new DocumentId("c");
                Node leaf = document(domain + "-leaf").properties("seed", new Node().value(0));
                Node middle = collection(domain + "-middle", leaf, "x/y");
                Node parent = collection(domain + "-root", middle, "x~y");
                Map<DocumentId, Node> bodies = new LinkedHashMap<>();
                bodies.put(grandchild, leaf); bodies.put(child, middle); bodies.put(root, parent);
                List<ManagedOccurrenceBinding> rows = Arrays.asList(
                        fixture.binding(root, "/children/x~0y", child, middle, false),
                        fixture.binding(child, "/children/x~1y", grandchild, leaf, false));
                ClosureInvocationInput input = fixture.admission(snapshot(bodies, rows, root), 100_000L);
                // when
                ClosureAttemptResult attempt = fixture.admit(input);
                // then
                assertTrue(attempt.isComplete(), attempt.kind().toString());
                ClosureProcessResult result = attempt.processResult();
                assertTrue(result.commits(), diagnostic(result));
                assertEquals(Arrays.asList(domain + "-root", domain + "-middle", domain + "-leaf"),
                        fixture.initialized);
                assertEquals(Arrays.asList(domain + "-root:0", domain + "-middle:0"), fixture.reactions);
                assertTrue(result.occurrenceBindings().stream().allMatch(ManagedOccurrenceBinding::active));
                assertTrue(result.resultingDocuments().stream().allMatch(ResultingDocument::initialized));
                assertEquals(3, result.managedTransitionReceipts().size());
                assertTrue(result.publicEvents().isEmpty());
            }
        }
    }

    @Test
    void shouldRetireMembershipDuringReactionAndUseTheChannelSurfaceAtDequeue() {
        // given: two child events exist before the first parent delivery.
        for (boolean removeChannel : new boolean[]{false, true}) {
            try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
                fixture.removeMember = "/children/member";
                fixture.removeChannel = removeChannel;
                DocumentId root = new DocumentId("root");
                DocumentId child = new DocumentId("child");
                Node leaf = document("leaf").properties("seed", new Node().value(0))
                        .properties("seedCopies", new Node().value(2));
                Node parent = collection("parent", leaf, "member");
                Map<DocumentId, Node> bodies = new LinkedHashMap<>();
                bodies.put(root, parent); bodies.put(child, leaf);
                ClosureInvocationInput input = fixture.admission(snapshot(bodies, Collections.singletonList(
                        fixture.binding(root, "/children/member", child, leaf, false)), root), 100_000L);
                // when
                ClosureProcessResult result = fixture.admit(input).processResult();
                // then: retirement cannot cancel frozen containing targets;
                // channel removal does prevent matching the next FIFO event.
                assertTrue(result.commits(), diagnostic(result));
                assertEquals(removeChannel ? 1 : 2, fixture.reactions.size());
                ManagedOccurrenceBinding retired = result.occurrenceBindings().get(0);
                assertFalse(retired.active());
                assertEquals(2L, retired.activationGeneration());
                assertNotEquals(input.snapshot().occurrences().get(0).occurrenceIdentity(),
                        retired.occurrenceIdentity());
                assertEquals(1L, fixture.initialized.stream().filter("leaf"::equals).count());
                assertTrue(result.publicEvents().isEmpty());
            }
        }
    }

    @Test
    void shouldInitializeGeneratedFanOutInCanonicalOrder() {
        // given
        for (int size : new int[]{0, 1, 3, 6}) {
            try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
                DocumentId root = new DocumentId("root");
                Node parent = collection("parent", document("unused"), "unused");
                parent.properties("install", new Node().properties(Collections.emptyMap()));
                Map<DocumentId, Node> bodies = new LinkedHashMap<>();
                List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
                List<String> expected = new ArrayList<>();
                expected.add("parent");
                for (int i = size - 1; i >= 0; i--) {
                    Node child = document("child" + i).properties("seed", new Node().value(0));
                    DocumentId childId = new DocumentId("child" + i);
                    parent.getNode("/install").properties("key/" + i, child);
                    bodies.put(childId, child);
                    bindings.add(fixture.binding(root, "/children/key~1" + i, childId, child, false));
                }
                for (int i = 0; i < size; i++) expected.add("child" + i);
                bodies.put(root, parent);
                // when
                ClosureProcessResult result = fixture.admit(fixture.admission(
                        snapshot(bodies, bindings, root), 100_000L)).processResult();
                // then
                assertTrue(result.commits(), diagnostic(result));
                assertEquals(expected, fixture.initialized);
                assertEquals(Collections.nCopies(size, "parent:0"), fixture.reactions);
                assertEquals(size, result.occurrenceBindings().size());
                assertEquals(size + 1, result.managedTransitionReceipts().size());
            }
        }
    }

    @Test
    void shouldInitializeGeneratedNestingAndNotifyAncestorsInCanonicalOrder() {
        // given
        for (int depth : new int[]{1, 2, 4}) {
            try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
                Map<DocumentId, Node> bodies = new LinkedHashMap<>();
                List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
                Node child = document("node" + depth).properties("seed", new Node().value(0));
                bodies.put(new DocumentId("node" + depth), child);
                for (int i = depth - 1; i >= 0; i--) {
                    DocumentId parentId = new DocumentId("node" + i);
                    Node parent = collection("node" + i, child, "a/b~c");
                    bodies.put(parentId, parent);
                    bindings.add(fixture.binding(parentId, "/children/a~1b~0c",
                            new DocumentId("node" + (i + 1)), child, false));
                    child = parent;
                }
                List<String> initialized = new ArrayList<>();
                List<String> reactions = new ArrayList<>();
                for (int i = 0; i <= depth; i++) initialized.add("node" + i);
                for (int i = 0; i < depth; i++) reactions.add("node" + i + ":0");
                // when
                ClosureProcessResult result = fixture.admit(fixture.admission(snapshot(
                        bodies, bindings, new DocumentId("node0")), 100_000L)).processResult();
                // then
                assertTrue(result.commits(), diagnostic(result));
                assertEquals(initialized, fixture.initialized);
                assertEquals(reactions, fixture.reactions);
                assertEquals(depth + 1, result.managedTransitionReceipts().size());
            }
        }
    }

    private static Node collection(String label, Node child, String key) {
        Node result = document(label).properties("children", new Node().properties(Collections.emptyMap()))
                .properties("install", new Node().properties(key, child));
        result.getContracts().properties("embedded", process("collectionPaths", "/children"))
                .properties("fromChildren", embedded(null)).properties("react", handler("fromChildren"));
        return result;
    }

    private static List<String> routes(ManagedDocumentStepProcessor steps, Node parent, String path) {
        Node event = token(0);
        return steps.classifyEmbeddedEventRoutes(parent, path,
                ExactEventIdentityEvidence.verify(null, event, id(event), null), GasChargeContext.reason("r2-route"))
                .stream().map(ManagedDocumentStepRoute::channelKey).collect(Collectors.toList());
    }
}
