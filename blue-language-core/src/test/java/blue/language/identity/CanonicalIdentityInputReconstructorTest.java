package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.merge.Merger;
import blue.language.merge.SnapshotResolution;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.ValuePropagator;
import blue.language.resolve.ResolutionLimits;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalIdentityInputReconstructorTest {

    private final CanonicalIdentityInputBuilder builder =
            new CanonicalIdentityInputBuilder();

    @Test
    void canonicalizesInlineAndVerifiedReferenceTypesIdentically() {
        Node type = typeDefinition("Actual Type", "fixed");
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(type);
        CanonicalTypeIdentityLookup evidence = completeEvidence(
                identity(type, typeBlueId),
                identity(retained(type, typeBlueId), typeBlueId));

        Node inlineCanonical = builder.build(
                completedInstance("X", type.clone(), "fixed"),
                new Node().name("X").type(type.clone()),
                evidence);
        Node referenceCanonical = builder.build(
                completedInstance(
                        "X", retained(type, typeBlueId), "fixed"),
                new Node().name("X").type(reference(typeBlueId)),
                evidence);

        assertEquals(NodeWireForm.get(referenceCanonical),
                NodeWireForm.get(inlineCanonical));
        assertEquals(typeBlueId,
                inlineCanonical.getType().getBlueId());
        assertTrue(inlineCanonical.getType().isReferenceOnly());
    }

    @Test
    void canonicalizesNestedAndCollectionTypePositionsToPureReferences() {
        Node elementType = typeDefinition("Element Type", "element");
        String elementBlueId = DirectBlueIdCalculator.calculateBlueId(
                elementType);
        CanonicalTypeIdentityLookup evidence = completeEvidence(
                identity(elementType, elementBlueId),
                identity(retained(elementType, elementBlueId),
                        elementBlueId));
        List<TypePosition> positions = Arrays.asList(
                new TypePosition(Node::getType, Node::type),
                new TypePosition(Node::getItemType, Node::itemType),
                new TypePosition(Node::getKeyType, Node::keyType),
                new TypePosition(Node::getValueType, Node::valueType));

        for (TypePosition position : positions) {
            Node inlineSource = new Node().properties(
                    "child", position.set(new Node(), elementType.clone()));
            Node inlineResolved = new Node().properties(
                    "child", position.set(new Node(), elementType.clone()));
            Node referenceSource = new Node().properties(
                    "child", position.set(
                            new Node(), reference(elementBlueId)));
            Node referenceResolved = new Node().properties(
                    "child", position.set(
                            new Node(), retained(elementType, elementBlueId)));

            Node inlineCanonical = builder.build(
                    inlineResolved, inlineSource, evidence);
            Node referenceCanonical = builder.build(
                    referenceResolved, referenceSource, evidence);

            assertEquals(NodeWireForm.get(referenceCanonical),
                    NodeWireForm.get(inlineCanonical));
            Node canonicalType = position.get(
                    inlineCanonical.getProperties().get("child"));
            assertTrue(canonicalType.isReferenceOnly());
            assertEquals(elementBlueId, canonicalType.getBlueId());
        }
    }

    @Test
    void keepsDistinctResolverIssuedIdentitiesDistinct() {
        Node firstType = typeDefinition("First Type", "same");
        Node secondType = typeDefinition("Second Type", "same");
        String firstId = DirectBlueIdCalculator.calculateBlueId(firstType);
        String secondId = DirectBlueIdCalculator.calculateBlueId(secondType);
        CanonicalTypeIdentityLookup evidence = completeEvidence(
                identity(firstType, firstId),
                identity(secondType, secondId));

        Node first = builder.build(
                completedInstance("X", firstType.clone(), "same"),
                new Node().name("X").type(firstType.clone()),
                evidence);
        Node second = builder.build(
                completedInstance("X", secondType.clone(), "same"),
                new Node().name("X").type(secondType.clone()),
                evidence);

        assertNotEquals(first.getType().getBlueId(),
                second.getType().getBlueId());
        assertNotEquals(
                DirectBlueIdCalculator.calculateBlueId(first),
                DirectBlueIdCalculator.calculateBlueId(second));
    }

    @Test
    void rejectsMaterializedTypeAnnotationsWithoutMatchingEvidence() {
        Node type = typeDefinition("Inline Type", "fixed");
        String expected = DirectBlueIdCalculator.calculateBlueId(type);
        String unrelated = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Unrelated Type"));

        assertThrows(IllegalStateException.class, () -> builder.build(
                new Node().type(retained(type, unrelated)),
                new Node().type(type.clone()),
                completeEvidence(identity(type, expected))));
    }

    @Test
    void preservesDistinctExactTypeProvenanceForTheSameCompletedShape() {
        Node sharedCompletedShape = typeDefinition("Completed", "same");
        String firstId = DirectBlueIdCalculator.calculateBlueId(
                typeDefinition("First exact source", "same"));
        String secondId = DirectBlueIdCalculator.calculateBlueId(
                typeDefinition("Second exact source", "same"));
        Node firstCompleted = retained(sharedCompletedShape, firstId);
        Node secondCompleted = retained(sharedCompletedShape, secondId);
        CanonicalTypeIdentityLookup evidence = completeEvidence(
                identity(firstCompleted, firstId),
                identity(secondCompleted, secondId));

        Node first = builder.build(
                new Node().name("X").type(firstCompleted.clone()),
                new Node().name("X").type(reference(firstId)),
                evidence);
        Node second = builder.build(
                new Node().name("X").type(secondCompleted.clone()),
                new Node().name("X").type(reference(secondId)),
                evidence);

        assertEquals(firstId, first.getType().getBlueId());
        assertEquals(secondId, second.getType().getBlueId());
        assertNotEquals(
                DirectBlueIdCalculator.calculateBlueId(first),
                DirectBlueIdCalculator.calculateBlueId(second));
    }

    @Test
    void doesNotOmitNestedOverrideBetweenSameShapeExactTypes() {
        Node sharedCompletedShape = typeDefinition("Completed", "same");
        String firstId = DirectBlueIdCalculator.calculateBlueId(
                typeDefinition("First exact source", "same"));
        String secondId = DirectBlueIdCalculator.calculateBlueId(
                typeDefinition("Second exact source", "same"));
        Node firstCompleted = retained(sharedCompletedShape, firstId);
        Node secondCompleted = retained(sharedCompletedShape, secondId);
        Node authoredContainerType = new Node()
                .name("Container")
                .properties("child",
                        new Node().type(reference(firstId)));
        String containerId = DirectBlueIdCalculator.calculateBlueId(
                authoredContainerType);
        Node completedContainerType = retained(
                new Node()
                        .name("Container")
                        .properties("child", new Node()
                                .type(firstCompleted.clone())),
                containerId);
        CanonicalTypeIdentityLookup evidence = completeEvidence(
                identity(firstCompleted, firstId),
                identity(secondCompleted, secondId),
                identity(completedContainerType, containerId));
        Node source = new Node()
                .type(reference(containerId))
                .properties("child", Nodes.emptyObject());
        Node resolved = new Node()
                .type(completedContainerType.clone())
                .properties("child", new Node()
                        .type(secondCompleted.clone()));

        Node canonical = builder.build(resolved, source, evidence);

        assertEquals(secondId, canonical.getProperties()
                .get("child").getType().getBlueId());
    }

    @Test
    void failsClosedForMissingOrIncompleteEvidence() {
        Node type = typeDefinition("Provider Type", "fixed");
        String blueId = DirectBlueIdCalculator.calculateBlueId(type);
        Node resolved = new Node().type(retained(type, blueId));
        Node source = new Node().type(reference(blueId));

        assertThrows(IllegalStateException.class,
                () -> builder.build(
                        resolved, source,
                        CanonicalTypeIdentityLookup.incomplete()));
        assertThrows(IllegalStateException.class,
                () -> builder.build(
                        resolved, source,
                        completeEvidence()));
    }

    @Test
    void canonicalizesEmptyInlineTypesInEveryTypePositionToTheExactReference() {
        String emptyTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                Nodes.emptyObject());
        CanonicalTypeIdentityLookup evidence = completeEvidence(
                identity(Nodes.emptyObject(), emptyTypeBlueId));
        List<TypePosition> positions = Arrays.asList(
                new TypePosition(Node::getType, Node::type),
                new TypePosition(Node::getItemType, Node::itemType),
                new TypePosition(Node::getKeyType, Node::keyType),
                new TypePosition(Node::getValueType, Node::valueType));

        for (TypePosition position : positions) {
            Node inlineSource = position.set(
                    new Node().name("X"), Nodes.emptyObject());
            Node referenceSource = position.set(
                    new Node().name("X"), reference(emptyTypeBlueId));

            Node inlineCanonical = builder.build(
                    inlineSource.clone(), inlineSource, evidence);
            Node referenceCanonical = builder.build(
                    referenceSource.clone(), referenceSource,
                    completeEvidence());
            Node canonicalType = position.get(inlineCanonical);

            assertTrue(canonicalType.isReferenceOnly());
            assertEquals(emptyTypeBlueId, canonicalType.getBlueId());
            assertEquals(
                    NodeWireForm.get(referenceCanonical),
                    NodeWireForm.get(inlineCanonical));
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            referenceCanonical),
                    DirectBlueIdCalculator.calculateBlueId(
                            inlineCanonical));
        }
        assertEquals(emptyTypeBlueId,
                FrozenNode.empty().blueId());
    }

    @Test
    void retainsExactPureTypeReferenceWithoutMaterializedEvidence() {
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Preserved exact type"));
        Node source = new Node().name("X").type(reference(typeBlueId));

        Node canonical = builder.build(
                source.clone(), source, completeEvidence());

        assertTrue(canonical.getType().isReferenceOnly());
        assertEquals(typeBlueId, canonical.getType().getBlueId());
    }

    @Test
    void preservesVerifiedSchemaReferenceProvenanceAndIdentityParity() {
        Node minimumLength = new Node()
                .type(new Node().blueId(INTEGER_TYPE_BLUE_ID))
                .value(BigInteger.ONE);
        Node schemaContent = new Node().properties(
                "minLength", minimumLength.clone());
        String schemaBlueId = DirectBlueIdCalculator.calculateBlueId(
                schemaContent);
        Schema inlineSchema = new Schema().minLength(minimumLength);
        Node inlineSource = new Node()
                .name("Text field")
                .schema(inlineSchema);
        Node referenceSource = new Node()
                .name("Text field")
                .schema(new Schema().blueId(schemaBlueId));
        Merger merger = new Merger(
                new SequentialMergingProcessor(Arrays.asList(
                        new ValuePropagator(),
                        new SchemaPropagator())),
                blueId -> schemaBlueId.equals(blueId)
                        ? Collections.singletonList(schemaContent)
                        : null);

        SnapshotResolution inline = merger.resolveSnapshot(
                inlineSource, ResolutionLimits.NO_LIMITS);
        SnapshotResolution referenced = merger.resolveSnapshot(
                referenceSource, ResolutionLimits.NO_LIMITS);

        assertEquals(
                inline.canonicalRoot().blueId(),
                referenced.canonicalRoot().blueId());
        assertTrue(referenced.canonicalRoot()
                .getSchema().isReferenceOnly());
        assertEquals(schemaBlueId, referenced.canonicalRoot()
                .getSchema().getBlueId());
        assertEquals(
                NodeWireForm.get(inline.resolvedRoot().toNode()),
                NodeWireForm.get(referenced.resolvedRoot().toNode()));
    }

    @Test
    void doesNotTreatMixedSourceSchemaBlueIdAsReferenceProvenance() {
        String schemaBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().properties(
                        "minLength", new Node().value(
                                BigInteger.ONE)));
        Node resolved = new Node().schema(
                new Schema().minLength(BigInteger.ONE));
        Node source = new Node().schema(new Schema()
                .blueId(schemaBlueId)
                .minLength(BigInteger.ONE));

        Node canonical = builder.build(
                resolved, source, completeEvidence());

        assertNull(canonical.getSchema().getBlueId());
        assertEquals(BigInteger.ONE,
                canonical.getSchema().getMinLengthExact());
    }

    @Test
    void sharedObjectIdentityCannotBypassNestedTypeEvidence() {
        Node nestedType = new Node().name("Unproven nested type");
        Node sharedChild = new Node().type(nestedType);
        Node completedParent = new Node()
                .name("Completed parent")
                .properties("child", sharedChild);
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(
                completedParent);
        CanonicalTypeIdentityLookup parentOnlyEvidence = completeEvidence(
                identity(completedParent, parentBlueId));
        Node resolved = new Node()
                .type(completedParent)
                .properties("child", sharedChild);
        Node source = new Node().type(reference(parentBlueId));

        assertThrows(IllegalStateException.class, () -> builder.build(
                resolved, source, parentOnlyEvidence));
    }

    private static Node typeDefinition(String name, String fixedValue) {
        return new Node()
                .name(name)
                .properties("inherited", new Node().value(fixedValue));
    }

    private static Node completedInstance(
            String name,
            Node type,
            String inheritedValue) {
        return new Node()
                .name(name)
                .type(type)
                .properties("inherited", new Node().value(inheritedValue));
    }

    private static Node retained(Node type, String blueId) {
        return type.clone().blueId(blueId);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static Evidence identity(Node completedType, String blueId) {
        return new Evidence(completedType, blueId);
    }

    private static CanonicalTypeIdentityLookup completeEvidence(
            Evidence... entries) {
        Map<FrozenNode.ResolvedStructuralKey, String> identities =
                new HashMap<>();
        for (Evidence entry : entries) {
            String previous = identities.put(
                    structuralKey(entry.completedType), entry.blueId);
            if (previous != null && !previous.equals(entry.blueId)) {
                throw new IllegalArgumentException(
                        "Conflicting test identity evidence");
            }
        }
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                String blueId = identities.get(structuralKey(completedType));
                return blueId != null
                        ? Optional.of(CanonicalTypeIdentityEvidence
                                .identityOnly(blueId))
                        : Optional.<CanonicalTypeIdentityEvidence>empty();
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(
                    Node completedType,
                    Node authoredTypeSource) {
                Optional<CanonicalTypeIdentityEvidence> evidence =
                        findCanonicalTypeIdentityEvidence(completedType);
                if (authoredTypeSource != null
                        && authoredTypeSource.isReferenceOnly()
                        && evidence.isPresent()
                        && !authoredTypeSource.getBlueId().equals(
                                evidence.get().blueId())) {
                    throw new IllegalStateException(
                            "Authored reference conflicts with explicit test "
                                    + "identity evidence");
                }
                return evidence;
            }

            @Override
            public String requireCanonicalTypeBlueId(Node completedType) {
                String blueId = identities.get(structuralKey(completedType));
                if (blueId == null) {
                    throw new IllegalStateException(
                            "Missing explicit test identity evidence");
                }
                return blueId;
            }
        };
    }

    private static FrozenNode.ResolvedStructuralKey structuralKey(Node node) {
        return FrozenNode.fromResolvedNode(node)
                .resolvedStructuralKey();
    }

    private static final class Evidence {
        private final Node completedType;
        private final String blueId;

        private Evidence(Node completedType, String blueId) {
            this.completedType = completedType;
            this.blueId = blueId;
        }
    }

    private static final class TypePosition {
        private final Function<Node, Node> getter;
        private final BiConsumer<Node, Node> setter;

        private TypePosition(
                Function<Node, Node> getter,
                BiConsumer<Node, Node> setter) {
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
