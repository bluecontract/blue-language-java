package blue.language.merge;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.processor.TypeAssigner;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TypeEvidenceResolutionTest {

    private static final List<TypePosition> METADATA_TYPE_POSITIONS =
            Arrays.asList(
                    new TypePosition(
                            "itemType", Node::getItemType, Node::itemType),
                    new TypePosition(
                            "keyType", Node::getKeyType, Node::keyType),
                    new TypePosition(
                            "valueType", Node::getValueType,
                            Node::valueType));

    @Test
    void limitedResolutionRetainsReachedTypeEvidenceWithoutClaimingCanonicalRoot() {
        Node inlineType = new Node().name("Reached Inline Type");
        String inlineTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                inlineType);
        Node source = new Node()
                .type(inlineType.clone())
                .value("instance");
        ResolutionLimits targetLimited =
                ResolutionLimits.withSinglePath("/value");

        TypeEvidenceResolution result = new Merger(
                new TypeAssigner(),
                blueId -> null)
                .resolveTypeEvidence(source, targetLimited);

        assertFalse(result.canonicalTypeIdentities()
                .hasCompleteCoverage());
        assertEquals(
                inlineTypeBlueId,
                result.canonicalTypeIdentities()
                        .requireCanonicalTypeBlueId(
                                result.resolvedRoot().getType().toNode()));
    }

    @Test
    void limitedResolutionDoesNotRecordPrunedInlineTypeEvidence() {
        Node prunedInlineType = new Node().properties(
                "omitted", new Node().name("Omitted declaration"));
        Node holderType = new Node().properties(
                "selected", new Node().type(prunedInlineType.clone()));
        Node source = new Node()
                .type(holderType.clone())
                .properties(
                        "selected", new Node().name("Selected instance"));

        TypeEvidenceResolution result = new Merger(
                new TypeAssigner(),
                blueId -> null)
                .resolveTypeEvidence(
                        source,
                        ResolutionLimits.withSinglePath("/selected"));

        assertFalse(result.canonicalTypeIdentities()
                .hasCompleteCoverage());
        assertFalse(result.canonicalTypeIdentities()
                .findCanonicalTypeIdentityEvidence(holderType)
                .isPresent());
        assertNull(result.resolvedRoot().getType());
    }

    @Test
    void limitedResolutionOmitsUnprovenInlineMetadataTypeEvidence() {
        for (TypePosition position : METADATA_TYPE_POSITIONS) {
            Node inlineType = partiallySelectedType(position.name);
            Node source = position.set(
                    new Node().name("Root"), inlineType.clone());

            TypeEvidenceResolution result = new Merger(
                    new TypeMetadataCopyingProcessor(),
                    blueId -> null)
                    .resolveTypeEvidence(
                            source,
                            ResolutionLimits.withSinglePath("/selected"));

            assertFalse(result.canonicalTypeIdentities()
                            .hasCompleteCoverage(),
                    position.name);
            assertFalse(result.canonicalTypeIdentities()
                            .findCanonicalTypeIdentityEvidence(inlineType)
                            .isPresent(),
                    position.name);
            assertNull(position.get(result.resolvedRoot().toNode()),
                    position.name);
        }
    }

    @Test
    void limitedResolutionRetainsPureReferenceForUnprovenMetadataType() {
        for (TypePosition position : METADATA_TYPE_POSITIONS) {
            Node canonicalType = partiallySelectedType(position.name);
            String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                    canonicalType);
            Node source = position.set(
                    new Node().name("Root"),
                    new Node().blueId(typeBlueId));
            NodeProvider provider = requestedBlueId -> {
                assertEquals(typeBlueId, requestedBlueId, position.name);
                return Collections.singletonList(canonicalType.clone());
            };

            TypeEvidenceResolution result = new Merger(
                    new TypeMetadataCopyingProcessor(), provider)
                    .resolveTypeEvidence(
                            source,
                            ResolutionLimits.withSinglePath("/selected"));

            Node retained = position.get(result.resolvedRoot().toNode());
            assertFalse(result.canonicalTypeIdentities()
                            .hasCompleteCoverage(),
                    position.name);
            assertTrue(retained.isReferenceOnly(), position.name);
            assertEquals(typeBlueId, retained.getBlueId(), position.name);
        }
    }

    private static Node partiallySelectedType(String position) {
        return new Node().properties(
                "selected", new Node().name(position + " selected"),
                "omitted", new Node().name(position + " omitted"));
    }

    private static final class TypePosition {
        private final String name;
        private final Function<Node, Node> getter;
        private final BiConsumer<Node, Node> setter;

        private TypePosition(
                String name,
                Function<Node, Node> getter,
                BiConsumer<Node, Node> setter) {
            this.name = name;
            this.getter = getter;
            this.setter = setter;
        }

        private Node get(Node node) {
            return getter.apply(node);
        }

        private Node set(Node node, Node type) {
            setter.accept(node, type);
            return node;
        }
    }

    private static final class TypeMetadataCopyingProcessor
            implements MergingProcessor {

        @Override
        public void process(
                Node target,
                Node source,
                NodeProvider nodeProvider,
                NodeResolver nodeResolver,
                CanonicalTypeIdentityLookup typeIdentities) {
            target.itemType(source.getItemType());
            target.keyType(source.getKeyType());
            target.valueType(source.getValueType());
        }
    }
}
