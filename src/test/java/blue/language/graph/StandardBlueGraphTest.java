package blue.language.graph;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.provider.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StandardBlueGraphTest {

    private static final NodeResolver IDENTITY_RESOLVER =
            new NodeResolver() {
                @Override
                public Node resolve(Node node, ResolutionLimits limits) {
                    return node;
                }

                @Override
                public blue.language.merge.TypeEvidenceResolution
                resolveTypeEvidence(Node node, ResolutionLimits limits) {
                    throw new AssertionError(
                            "graph operation must not request type evidence");
                }

            };

    @Test
    void shouldExpandExactReferenceWithoutMutatingProviderOrSource() {
        // given
        Node exact = new Node().value("exact");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exact);
        Node providerNode = exact.clone().blueId(blueId);
        Node reference = new Node().blueId(blueId);
        StandardBlueGraph graph = new StandardBlueGraph(
                requested -> blueId.equals(requested)
                        ? Collections.singletonList(providerNode)
                        : null,
                IDENTITY_RESOLVER);

        // when
        Node expanded = graph.expand(reference);

        // then
        assertEquals("exact", expanded.getValue());
        assertNull(expanded.getBlueId());
        assertTrue(reference.isReferenceOnly());
        assertEquals(blueId, reference.getBlueId());
        assertEquals(blueId, providerNode.getBlueId());
    }

    @Test
    void shouldExpandOnlyDemandedClosureWithinReferenceBudget() {
        // given
        Node wanted = new Node().properties(
                "leaf", new Node().value("wanted"));
        Node unrelated = new Node().properties(
                "leaf", new Node().value("unrelated"));
        String wantedBlueId =
                DirectBlueIdCalculator.calculateBlueId(wanted);
        String unrelatedBlueId =
                DirectBlueIdCalculator.calculateBlueId(unrelated);
        Set<String> requested = new LinkedHashSet<>();
        NodeProvider provider = blueId -> {
            requested.add(blueId);
            if (wantedBlueId.equals(blueId)) {
                return Collections.singletonList(wanted);
            }
            if (unrelatedBlueId.equals(blueId)) {
                return Collections.singletonList(unrelated);
            }
            return null;
        };
        StandardBlueGraph graph = new StandardBlueGraph(
                provider, IDENTITY_RESOLVER);
        Node source = new Node().properties(
                "wanted", new Node().blueId(wantedBlueId),
                "unrelated", new Node().blueId(unrelatedBlueId));
        BlueOperationLimits limits =
                BlueOperationLimits.demandedPath("/wanted/leaf")
                        .withMaxReferenceExpansions(1);

        // when
        BlueOperationResult<Node> result =
                graph.expandLimited(source, limits);

        // then
        assertEquals(BlueOperationOutcome.ESTABLISHED,
                result.outcome());
        Node expanded = result.requireEstablished();
        assertEquals("wanted", expanded.getProperties().get("wanted")
                .getProperties().get("leaf").getValue());
        assertTrue(expanded.getProperties().get("unrelated")
                .isReferenceOnly());
        assertEquals(Collections.singleton(wantedBlueId), requested);
        assertTrue(source.getProperties().get("wanted")
                .isReferenceOnly());
    }

    @Test
    void shouldCollapseExactContentIntoPureReference() {
        // given
        Node exact = new Node().value("collapse me");
        StandardBlueGraph graph = new StandardBlueGraph(
                blueId -> null, IDENTITY_RESOLVER);

        // when
        Node collapsed = graph.collapse(exact);

        // then
        assertTrue(collapsed.isReferenceOnly());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(exact),
                collapsed.getBlueId());
        assertEquals("collapse me", exact.getValue());
    }

    @Test
    void shouldCollapseExpandedTypeEvidenceThroughResolvedIdentity() {
        // given
        Node exactType = new Node()
                .name("Expanded collapse type")
                .properties("fixed", new Node().value("inherited"));
        String typeBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactType);
        Node canonical = new Node()
                .type(new Node().blueId(typeBlueId))
                .properties("own", new Node().value("kept"));
        String canonicalBlueId =
                DirectBlueIdCalculator.calculateBlueId(canonical);
        StandardBlueGraph graph = new StandardBlueGraph(
                requested -> typeBlueId.equals(requested)
                        ? Collections.singletonList(exactType)
                        : null,
                IDENTITY_RESOLVER);

        // when
        Node expanded = graph.expand(canonical);
        Node collapsed = graph.collapse(expanded);

        // then
        assertFalse(expanded.getType().isReferenceOnly());
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectBlueIdCalculator.calculateBlueId(expanded));
        assertTrue(collapsed.isReferenceOnly());
        assertEquals(canonicalBlueId, collapsed.getBlueId());
    }

    @Test
    void shouldCollapseAndExpandExactEmptyObjectWithoutChangingParentIdentity() {
        // given
        Node emptyObject = Nodes.emptyObject();
        String emptyObjectBlueId =
                DirectBlueIdCalculator.calculateBlueId(emptyObject);
        StandardBlueGraph graph = new StandardBlueGraph(
                requested -> emptyObjectBlueId.equals(requested)
                        ? Collections.singletonList(emptyObject)
                        : null,
                IDENTITY_RESOLVER);
        Node inlineParent = new Node().properties(
                "child", emptyObject.clone());

        // when
        Node collapsed = graph.collapse(emptyObject);
        Node referencedParent = new Node().properties(
                "child", collapsed);
        Node expandedParent = graph.expand(referencedParent);

        // then
        assertTrue(collapsed.isReferenceOnly());
        assertEquals(emptyObjectBlueId, collapsed.getBlueId());
        assertTrue(Nodes.isExactEmptyObject(emptyObject));
        assertTrue(Nodes.isExactEmptyObject(
                expandedParent.getProperties().get("child")));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(inlineParent),
                DirectBlueIdCalculator.calculateBlueId(referencedParent));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(inlineParent),
                DirectBlueIdCalculator.calculateBlueId(expandedParent));
    }

    @Test
    void shouldSpecializeThroughInjectedResolverWithoutMutatingInputs() {
        // given
        Node type = new Node().blueId(TEXT_TYPE_BLUE_ID);
        Node overlay = new Node().value("hello");
        AtomicReference<Node> validated = new AtomicReference<>();
        NodeResolver resolver = new NodeResolver() {
            @Override
            public Node resolve(Node node, ResolutionLimits limits) {
                validated.set(node);
                return node;
            }

            @Override
            public blue.language.merge.TypeEvidenceResolution
            resolveTypeEvidence(Node node, ResolutionLimits limits) {
                throw new AssertionError(
                        "specialization must not request type evidence");
            }

        };
        StandardBlueGraph graph = new StandardBlueGraph(
                blueId -> null, resolver);

        // when
        Node specialization = graph.specialize(type, overlay);

        // then
        assertEquals(TEXT_TYPE_BLUE_ID,
                specialization.getType().getBlueId());
        assertEquals("hello", specialization.getValue());
        assertNull(overlay.getType());
        assertNotSame(type, specialization.getType());
        assertNotSame(specialization, validated.get());
        assertFalse(validated.get().isReferenceOnly());
    }
}
