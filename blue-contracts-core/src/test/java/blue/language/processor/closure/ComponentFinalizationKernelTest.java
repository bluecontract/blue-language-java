package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ComponentFinalizationKernelTest {

    private static final String BINDING_POLICY =
            "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String CLO_01_MASTER =
            "9J59XHipxtMwoMTY7r7HaicepUCtDPkMPFMAzLfdp4iW";

    @Test
    void shouldMatchReleasedClo01CyclicIdentityAndProofOracle() {
        DocumentId a = new DocumentId("simple-a");
        DocumentId b = new DocumentId("simple-b");
        Node aBody = clo01A(CLO_01_MASTER + "#1");
        Node bBody = clo01B(CLO_01_MASTER + "#0");
        ManagedOccurrenceBinding aToB = binding(
                a, "/b", b, CLO_01_MASTER + "#1", true, 1L);
        ManagedOccurrenceBinding bToA = binding(
                b, "/a", a, CLO_01_MASTER + "#0", true, 1L);
        Map<DocumentId, Node> bodies = bodies(
                a, aBody, b, bBody);
        List<ManagedOccurrenceBinding> rows = Arrays.asList(aToB, bToA);

        ComponentFinalizationResult result = finalize(
                bodies, rows, rows, generations(a, 1L, b, 1L));

        assertEquals(1, result.components().size());
        ComponentSnapshot component = result.components().get(0).component();
        assertEquals(ComponentKind.CYCLIC, component.kind());
        assertEquals(CLO_01_MASTER, component.masterBlueId());
        assertEquals(
                "sha256:b27694341619d5d0835f99889e08eed563c00a8124aaf5945b115826c8b7e4b9",
                component.componentIdentity());
        assertEquals(
                "sha256:22090c938702e4d83792a7534ab991825463a5fadb19ef4fe6bcb7d000e0824c",
                component.cyclicProofIdentity());
        assertEquals(
                "sha256:cdfd54d28552cfc08c9bcf5633a2a2e1f06709b2564b8f87e2163c6d79ee8c19",
                component.componentStateIdentity());
        assertEquals(CLO_01_MASTER + "#0", result.document(a).blueId());
        assertEquals(CLO_01_MASTER + "#1", result.document(b).blueId());
        assertEquals(CLO_01_MASTER + "#1",
                result.document(a).document().getNode("/b").getBlueId());
        assertEquals(CLO_01_MASTER + "#0",
                result.document(b).document().getNode("/a").getBlueId());
        assertEquals("this#1", component.completeCyclicProof()
                .declaredPlaceholderSet().get(0).getNode("/b").getBlueId());
        assertEquals("this#0", component.completeCyclicProof()
                .declaredPlaceholderSet().get(1).getNode("/a").getBlueId());
        assertEquals(Integer.valueOf(0), result.components().get(0)
                .canonicalMemberIndexes().get(a));
        assertEquals(Integer.valueOf(1), result.components().get(0)
                .canonicalMemberIndexes().get(b));

        aBody.name("caller mutation");
        Node returned = result.document(a).document();
        returned.name("result mutation");
        assertNull(result.document(a).document().getName());
    }

    @Test
    void shouldBeInvariantToBodyAndBindingInputOrder() {
        DocumentId a = new DocumentId("simple-a");
        DocumentId b = new DocumentId("simple-b");
        ManagedOccurrenceBinding aToB = binding(
                a, "/b", b, CLO_01_MASTER + "#1", true, 1L);
        ManagedOccurrenceBinding bToA = binding(
                b, "/a", a, CLO_01_MASTER + "#0", true, 1L);
        ComponentFinalizationResult first = finalize(
                bodies(a, clo01A("old-b"), b, clo01B("old-a")),
                Arrays.asList(aToB, bToA),
                Arrays.asList(aToB, bToA),
                generations(a, 1L, b, 1L));
        LinkedHashMap<DocumentId, Node> reversed =
                new LinkedHashMap<DocumentId, Node>();
        reversed.put(b, clo01B("old-a"));
        reversed.put(a, clo01A("old-b"));
        ComponentFinalizationResult second = finalize(
                reversed,
                Arrays.asList(bToA, aToB),
                Arrays.asList(bToA, aToB),
                generations(b, 1L, a, 1L));

        assertEquals(first.document(a).blueId(), second.document(a).blueId());
        assertEquals(first.document(b).blueId(), second.document(b).blueId());
        assertEquals(first.components().get(0).component()
                        .componentStateIdentity(),
                second.components().get(0).component()
                        .componentStateIdentity());
    }

    @Test
    void shouldPreserveAnUnchangedAdmittedGenerationZeroComponent() {
        DocumentId root = new DocumentId("root");
        Node body = new Node().name("admitted-root");

        ComponentFinalizationResult result = finalize(
                Collections.singletonMap(root, body),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.singletonMap(root, Long.valueOf(0L)));

        assertEquals(Long.valueOf(0L),
                result.componentGenerations().get(root));
        assertEquals(0L,
                result.components().get(0).component().componentGeneration());
        assertEquals(0L, result.document(root).componentGeneration());
    }

    @Test
    void shouldRetainReleasedClo10DocumentToCanonicalPermutation() {
        DocumentId a = new DocumentId("simple-a");
        DocumentId b = new DocumentId("simple-b");
        String master = "2MFwcUowEYvgMfGp9QS8nwqM75H4n2CUfccLKkq1yynS";
        ManagedOccurrenceBinding aToB = binding(
                a, "/b", b, master + "#1", true, 1L);
        ManagedOccurrenceBinding bToA = binding(
                b, "/a", a, master + "#0", true, 1L);
        List<ManagedOccurrenceBinding> rows = Arrays.asList(aToB, bToA);

        ComponentFinalizationResult result = finalize(
                bodies(
                        a, clo10A(master + "#1"),
                        b, clo10B(master + "#0")),
                rows,
                rows,
                generations(a, 1L, b, 1L));

        ComponentSnapshot component = result.components().get(0).component();
        assertEquals(master, component.masterBlueId());
        assertEquals(Arrays.asList(master + "#0", master + "#1"),
                component.orderedMemberBlueIds());
        assertEquals(Integer.valueOf(0), result.document(a)
                .cyclicMemberIndex());
        assertEquals(Integer.valueOf(1), result.document(b)
                .cyclicMemberIndex());
        assertEquals(
                "sha256:03d27c18f26381aa18f7184ae61882da94dce8120fbd77fce26e46818f42ca04",
                component.cyclicProofIdentity());
        assertEquals(
                "sha256:c171b091b8896ee5eba8f08dac57ed95b2b9b4acb8a390711921eee6ac431577",
                component.componentStateIdentity());
        assertEquals(master + "#1", result.document(a).document()
                .getNode("/b").getBlueId());
        assertEquals(master + "#0", result.document(b).document()
                .getNode("/a").getBlueId());
    }

    @Test
    void shouldFinalizeSelfCycleThroughTheLanguageOraclePath() {
        DocumentId self = new DocumentId("self");
        String previous =
                "11111111111111111111111111111112";
        Node document = new Node().properties(
                "documentId", scalar("self"),
                "memberIdentity", scalar("self"),
                "self", reference(previous),
                "contracts", new Node().properties(
                        "embedded", new Node().properties(
                                "type", reference(
                                        "9ftzzP6ySLmbJ43bjwTbrm6Ff79FKqsVy5xdA1zWxoQ3"),
                                "paths", new Node().items(
                                        scalar("/self")))));
        ManagedOccurrenceBinding selfEdge = binding(
                self, "/self", self, previous, true, 1L);

        ComponentFinalizationResult result = finalize(
                Collections.singletonMap(self, document),
                Collections.singletonList(selfEdge),
                Collections.singletonList(selfEdge),
                Collections.singletonMap(self, Long.valueOf(1L)));

        assertEquals(
                "2Rh775x5AkBuDgFmaTuWKfuZjFiWBpxLhh1ARRmUpVQ3#0",
                result.document(self).blueId());
        assertEquals(
                "2Rh775x5AkBuDgFmaTuWKfuZjFiWBpxLhh1ARRmUpVQ3#0",
                result.document(self).document().getNode("/self").getBlueId());
        assertEquals("this#0", result.components().get(0).component()
                .completeCyclicProof().declaredPlaceholderSet().get(0)
                .getNode("/self").getBlueId());
    }

    @Test
    void shouldRebuildTheCompleteContainingSpineTargetBeforeSource() {
        DocumentId a = new DocumentId("a-parent");
        DocumentId b = new DocumentId("b-leaf");
        DocumentId c = new DocumentId("c-outer");
        Node leaf = new Node().name("changed-leaf");
        Node parent = new Node().name("parent")
                .properties("child", reference("old-leaf"));
        Node outer = new Node().name("outer")
                .properties("parent", reference("old-parent"));
        ManagedOccurrenceBinding aToB = binding(
                a, "/child", b, "old-leaf", true, 1L);
        ManagedOccurrenceBinding cToA = binding(
                c, "/parent", a, "old-parent", true, 1L);
        ManagedOccurrenceBinding inactive = binding(
                c, "/removed", b, "reserved-leaf", false, 2L);
        List<ManagedOccurrenceBinding> rows = Arrays.asList(
                inactive, cToA, aToB);

        ComponentFinalizationResult result = finalize(
                bodies(a, parent, b, leaf, c, outer),
                rows,
                rows,
                generations(a, 3L, b, 2L, c, 4L));

        String leafBlueId = DirectBlueIdCalculator.calculateBlueId(leaf);
        assertEquals(leafBlueId, result.document(b).blueId());
        assertEquals(leafBlueId, result.document(a).document()
                .getNode("/child").getBlueId());
        assertEquals(result.document(a).blueId(), result.document(c)
                .document().getNode("/parent").getBlueId());
        assertNull(NodePathEditor.getOrNull(
                result.document(c).document(), "/removed"));
        assertEquals(Arrays.asList(b, a, c), componentMembers(result));
        assertEquals(Long.valueOf(2L),
                result.componentGenerations().get(b));
        assertEquals(Long.valueOf(3L),
                result.componentGenerations().get(a));
        assertEquals(Long.valueOf(4L),
                result.componentGenerations().get(c));
        assertEquals("changed-leaf", result.document(b).document().getName());
        assertEquals(NodeWireForm.get(leaf),
                NodeWireForm.get(result.document(b).document()));

        ManagedOccurrenceBinding retainedInactive = findBinding(
                result.finalizedGraph().bindings(),
                inactive.occurrenceIdentity());
        assertFalse(retainedInactive.active());
        assertEquals("reserved-leaf",
                retainedInactive.expectedTargetBlueId());
    }

    @Test
    void shouldApplyClo10StyleCycleSplitGenerationTransition() {
        DocumentId a = new DocumentId("simple-a");
        DocumentId b = new DocumentId("simple-b");
        ManagedOccurrenceBinding activeAToB = binding(
                a, "/b", b, "old-master#1", true, 1L);
        ManagedOccurrenceBinding activeBToA = binding(
                b, "/a", a, "old-master#0", true, 1L);
        ManagedOccurrenceBinding inactiveAToB = binding(
                a, "/b", b, "old-master#1", false, 1L);
        ManagedOccurrenceBinding inactiveBToA = binding(
                b, "/a", a, "old-master#0", false, 1L);
        Map<DocumentId, Node> bodies = bodies(
                a, new Node().name("a-without-b"),
                b, new Node().name("b-without-a"));

        ComponentFinalizationResult result = finalize(
                bodies,
                Arrays.asList(activeAToB, activeBToA),
                Arrays.asList(inactiveBToA, inactiveAToB),
                generations(a, 1L, b, 1L));

        assertEquals(2, result.components().size());
        assertEquals(Arrays.asList(a, b), componentMembers(result));
        assertEquals(ComponentKind.ACYCLIC,
                result.document(a).componentKind());
        assertEquals(ComponentKind.ACYCLIC,
                result.document(b).componentKind());
        assertEquals(Long.valueOf(2L),
                result.componentGenerations().get(a));
        assertEquals(Long.valueOf(2L),
                result.componentGenerations().get(b));
        assertNull(result.document(a).cyclicMemberIndex());
        assertNull(result.document(b).preliminaryBlueId());
        assertTrue(result.finalizedGraph().activeBindings().isEmpty());
    }

    @Test
    void shouldRejectMissingPathsEscapedPlaceholdersAndFalseBindingClaims() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        ManagedOccurrenceBinding missing = binding(
                a, "/missing", b, "old-b", true, 1L);
        Map<DocumentId, Node> plainBodies = bodies(
                a, new Node().name("a"), b, new Node().name("b"));

        assertThrows(IllegalArgumentException.class,
                () -> finalize(
                        plainBodies,
                        Collections.singletonList(missing),
                        Collections.singletonList(missing),
                        generations(a, 1L, b, 1L)));

        assertThrows(IllegalArgumentException.class,
                () -> finalize(
                        Collections.singletonMap(a,
                                new Node().properties(
                                        "leak", reference("this#0"))),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        Collections.singletonMap(a, Long.valueOf(1L))));

        ManagedOccurrenceBinding falseClaim = new ManagedOccurrenceBinding(
                hash('1'),
                missing.bindingIdentity(),
                BINDING_POLICY,
                a,
                ScopeAddress.embedded("/missing", 1L),
                b,
                "old-b",
                true,
                null);
        assertThrows(IllegalArgumentException.class,
                () -> finalize(
                        bodies(a, new Node().properties(
                                        "missing", reference("old-b")),
                                b, new Node().name("b")),
                        Collections.singletonList(falseClaim),
                        Collections.singletonList(falseClaim),
                        generations(a, 1L, b, 1L)));
    }

    private static ComponentFinalizationResult finalize(
            Map<DocumentId, Node> bodies,
            List<ManagedOccurrenceBinding> inputRows,
            List<ManagedOccurrenceBinding> resultingRows,
            Map<DocumentId, Long> generations) {
        ManagedDocumentGraph inputGraph = ManagedDocumentGraph.fromBindings(
                bodies.keySet(), inputRows);
        ComponentFinalizationInput input = new ComponentFinalizationInput(
                inputGraph, generations, bodies, resultingRows);
        return new ComponentFinalizationKernel().finalizeComponents(input);
    }

    private static ManagedOccurrenceBinding binding(
            DocumentId source,
            String path,
            DocumentId target,
            String expectedTargetBlueId,
            boolean active,
            long activationGeneration) {
        ScopeAddress address = ScopeAddress.embedded(
                path, activationGeneration);
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        return new ManagedOccurrenceBinding(
                identities.managedOccurrenceIdentity(
                        source, address, target, BINDING_POLICY),
                identities.managedOccurrenceBindingIdentity(
                        source, address, target,
                        expectedTargetBlueId, BINDING_POLICY),
                BINDING_POLICY,
                source,
                address,
                target,
                expectedTargetBlueId,
                active,
                null);
    }

    private static ManagedOccurrenceBinding findBinding(
            List<ManagedOccurrenceBinding> bindings,
            String occurrenceIdentity) {
        for (ManagedOccurrenceBinding binding : bindings) {
            if (occurrenceIdentity.equals(binding.occurrenceIdentity())) {
                return binding;
            }
        }
        throw new AssertionError("Missing occurrence " + occurrenceIdentity);
    }

    private static List<DocumentId> componentMembers(
            ComponentFinalizationResult result) {
        ArrayList<DocumentId> members = new ArrayList<DocumentId>();
        for (FinalizedComponentEvidence component : result.components()) {
            assertEquals(1,
                    component.component().orderedMemberDocumentIds().size());
            members.add(component.component()
                    .orderedMemberDocumentIds().get(0));
        }
        return members;
    }

    private static Node clo01A(String targetBlueId) {
        return new Node().properties(
                "documentId", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "memberIdentity", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "b", reference(targetBlueId),
                "contracts", reference(
                        "Fiod9ArSxfZhdRe3rC5dqM78x2CBfW3zdzSbaY9a6ujg"));
    }

    private static Node clo01B(String targetBlueId) {
        return new Node().properties(
                "documentId", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "memberIdentity", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "a", reference(targetBlueId),
                "contracts", reference(
                        "fyDiUQNFeL6UYVyezYDUYSbHfPX6gZCTQwsJudgNGq9"));
    }

    private static Node clo10A(String targetBlueId) {
        return new Node().properties(
                "documentId", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "memberIdentity", reference(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "b", reference(targetBlueId),
                "contracts", reference(
                        "ERrv83b9RbGZSg2vyzFmzQiU2KoLyfzkuEn234gm7oFA"));
    }

    private static Node clo10B(String targetBlueId) {
        return new Node().properties(
                "documentId", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "memberIdentity", reference(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "a", reference(targetBlueId),
                "contracts", reference(
                        "8uvxz52uKPTRgGi4bB9HDnd5wVi4uGZ9ot1jtZppXA7Z"));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }

    private static Map<DocumentId, Node> bodies(Object... values) {
        LinkedHashMap<DocumentId, Node> result =
                new LinkedHashMap<DocumentId, Node>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((DocumentId) values[index], (Node) values[index + 1]);
        }
        return result;
    }

    private static Map<DocumentId, Long> generations(Object... values) {
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((DocumentId) values[index],
                    Long.valueOf(((Number) values[index + 1]).longValue()));
        }
        return result;
    }

    private static String hash(char digit) {
        StringBuilder value = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            value.append(digit);
        }
        return value.toString();
    }
}
