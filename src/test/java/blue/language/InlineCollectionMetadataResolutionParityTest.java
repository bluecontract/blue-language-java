package blue.language;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Schema;
import blue.language.provider.NodeProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineCollectionMetadataResolutionParityTest {

    @Test
    void shouldResolveInlineAndReferenceMetadataTypeChainsIdentically() {
        // given
        Node baseType = new Node()
                .name("Metadata Base")
                .type(reference(TEXT_TYPE_BLUE_ID))
                .schema(new Schema().required(true));
        String baseBlueId = DirectBlueIdCalculator.calculateBlueId(baseType);
        Node inlineDerivedType = new Node()
                .name("Metadata Derived")
                .type(baseType.clone())
                .schema(new Schema().minLength(BigInteger.ONE));
        Node canonicalDerivedType = new Node()
                .name("Metadata Derived")
                .type(reference(baseBlueId))
                .schema(new Schema()
                        .required(true)
                        .minLength(BigInteger.ONE));
        String derivedBlueId = DirectBlueIdCalculator.calculateBlueId(
                canonicalDerivedType);
        List<TypePosition> positions = Arrays.asList(
                new TypePosition(
                        "itemType", LIST_TYPE_BLUE_ID,
                        Node::getItemType, Node::itemType),
                new TypePosition(
                        "keyType", DICTIONARY_TYPE_BLUE_ID,
                        Node::getKeyType, Node::keyType),
                new TypePosition(
                        "valueType", DICTIONARY_TYPE_BLUE_ID,
                        Node::getValueType, Node::valueType));

        // when
        for (TypePosition position : positions) {
            Node inlineContainerType = position.set(
                    new Node()
                            .name(position.name + " Container")
                            .type(reference(position.containerTypeBlueId)),
                    inlineDerivedType.clone());
            Node canonicalContainerType = position.set(
                    new Node()
                            .name(position.name + " Container")
                            .type(reference(position.containerTypeBlueId)),
                    reference(derivedBlueId));
            String containerBlueId = DirectBlueIdCalculator.calculateBlueId(
                    canonicalContainerType);
            NodeProvider provider = blueId -> {
                if (baseBlueId.equals(blueId)) {
                    return Collections.singletonList(baseType.clone());
                }
                if (derivedBlueId.equals(blueId)) {
                    return Collections.singletonList(
                            canonicalDerivedType.clone());
                }
                if (containerBlueId.equals(blueId)) {
                    return Collections.singletonList(
                            canonicalContainerType.clone());
                }
                return null;
            };
            Blue blue = new Blue(provider);
            Node inlineDerivedCanonical = blue.canonicalize(
                    inlineDerivedType);

            // then
            assertEquals(derivedBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineDerivedCanonical),
                    () -> "inline derived canonical: "
                            + NodeWireForm.get(inlineDerivedCanonical));
            Node inlineContainerCanonical = blue.canonicalize(
                    inlineContainerType);
            assertEquals(containerBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineContainerCanonical),
                    () -> "inline container canonical: "
                            + NodeWireForm.get(inlineContainerCanonical));
            Node inlineSource = new Node()
                    .name("Root")
                    .type(inlineContainerType);
            Node referenceSource = new Node()
                    .name("Root")
                    .type(reference(containerBlueId));
            Object inlineBefore = NodeWireForm.get(inlineSource);
            Object referenceBefore = NodeWireForm.get(referenceSource);

            Node inlineResolved = blue.resolve(inlineSource);
            Node referenceResolved = blue.resolve(referenceSource);
            Node inlineMetadata = position.get(inlineResolved);
            Node referenceMetadata = position.get(referenceResolved);

            assertEquals(Boolean.TRUE,
                    inlineMetadata.getSchema().getRequiredValue());
            assertEquals(BigInteger.ONE,
                    inlineMetadata.getSchema().getMinLengthExact());
            assertEquals(
                    NodeToBlueIdInput.getWithResolvedBlueIdMetadata(
                            referenceResolved),
                    NodeToBlueIdInput.getWithResolvedBlueIdMetadata(
                            inlineResolved));
            assertEquals(
                    NodeToBlueIdInput.getWithResolvedBlueIdMetadata(
                            referenceMetadata),
                    NodeToBlueIdInput.getWithResolvedBlueIdMetadata(
                            inlineMetadata));

            Node inlineCanonical = blue.canonicalize(inlineSource);
            Node referenceCanonical = blue.canonicalize(referenceSource);
            assertEquals(NodeWireForm.get(referenceCanonical),
                    NodeWireForm.get(inlineCanonical));
            assertEquals(
                    blue.calculateSourceDocumentBlueId(referenceSource),
                    blue.calculateSourceDocumentBlueId(inlineSource));
            assertTrue(inlineCanonical.getType().isReferenceOnly());
            assertEquals(containerBlueId,
                    inlineCanonical.getType().getBlueId());
            assertEquals(inlineBefore, NodeWireForm.get(inlineSource));
            assertEquals(referenceBefore, NodeWireForm.get(referenceSource));
        }
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class TypePosition {
        private final String name;
        private final String containerTypeBlueId;
        private final Function<Node, Node> getter;
        private final BiConsumer<Node, Node> setter;

        private TypePosition(
                String name,
                String containerTypeBlueId,
                Function<Node, Node> getter,
                BiConsumer<Node, Node> setter) {
            this.name = name;
            this.containerTypeBlueId = containerTypeBlueId;
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
}
