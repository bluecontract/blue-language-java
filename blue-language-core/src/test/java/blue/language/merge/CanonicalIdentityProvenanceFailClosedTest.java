package blue.language.merge;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CanonicalIdentityProvenanceFailClosedTest {

    @Test
    void recorderRetainsPureReferenceProofAndSelfContainedInlineSource() {
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        CanonicalTypeIdentityRecorder recorder =
                new CanonicalTypeIdentityRecorder();
        Node nestedAuthoredType = new Node().name("Nested inline type");
        String nestedBlueId = recorder.recordCompleted(
                identities,
                nestedAuthoredType.clone(),
                nestedAuthoredType,
                null);
        Node outerAuthoredType = new Node()
                .name("Outer inline type")
                .items(new Node()
                        .position(0)
                        .type(nestedAuthoredType.clone()));
        Node completedOuterType = new Node()
                .name("Outer inline type")
                .items(new Node().type(nestedAuthoredType.clone()));

        String outerBlueId = recorder.recordCompleted(
                identities,
                completedOuterType,
                outerAuthoredType,
                null);

        CanonicalTypeIdentityEvidence evidence = identities
                .findCanonicalTypeIdentityEvidence(completedOuterType)
                .orElseThrow(AssertionError::new);
        Node proofItem = evidence.canonicalTypeIdentityInput()
                .getItems().get(0);
        Node sourceItem = evidence.authoredTypeSource()
                .getItems().get(0);
        Node proofType = proofItem.getType();
        Node sourceType = sourceItem.getType();
        assertEquals(outerBlueId, evidence.blueId());
        assertNull(proofItem.getPosition());
        assertTrue(proofType.isReferenceOnly());
        assertEquals(nestedBlueId, proofType.getBlueId());
        assertEquals(0, sourceItem.getPosition());
        assertFalse(sourceType.isReferenceOnly());
        assertEquals("Nested inline type", sourceType.getName());
    }

    @Test
    void rejectsEvidenceForACompletedTypeMutatedAfterRecording() {
        Node completedType = new Node().name("Recorded effective type");
        String canonicalBlueId = DirectBlueIdCalculator.calculateBlueId(
                completedType);
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        identities.record(
                completedType,
                canonicalBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                FrozenNode.fromNode(completedType),
                FrozenNode.fromResolvedNode(completedType));

        completedType.description("mutated after recording");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> identities.require(completedType));
        assertTrue(failure.getMessage().contains(
                "changed after identity evidence was recorded"));
    }

    @Test
    void findsEvidenceAcrossEquivalentPropertyInsertionOrders() {
        Node recordedType = effectiveType("left", "right");
        String canonicalBlueId = DirectBlueIdCalculator.calculateBlueId(
                recordedType);
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        identities.record(
                recordedType,
                canonicalBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                FrozenNode.fromNode(recordedType),
                FrozenNode.fromResolvedNode(recordedType));

        Node reorderedType = effectiveType("right", "left");

        assertEquals(
                canonicalBlueId,
                identities.requireCanonicalTypeBlueId(reorderedType));
    }

    @Test
    void preservesCopiedColdAndWarmEvidenceLookupParity() {
        Node recordedType = effectiveType("left", "right")
                .inlineValue(true)
                .preprocessingTransformationConfiguration(true);
        String canonicalBlueId = DirectBlueIdCalculator.calculateBlueId(
                recordedType);
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        identities.record(
                recordedType,
                canonicalBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                FrozenNode.fromNode(recordedType),
                FrozenNode.fromResolvedNode(recordedType));
        CanonicalTypeIdentityIndex.EvidenceSnapshot warmEvidence =
                identities.completeSnapshotForResolvedReference(
                        new Node().type(recordedType));

        Node copiedType = recordedType.clone()
                .inlineValue(false)
                .preprocessingTransformationConfiguration(false);
        Node reorderedCopy = effectiveType("right", "left");

        assertEquals(
                canonicalBlueId,
                identities.requireCanonicalTypeBlueId(copiedType));
        assertEquals(
                canonicalBlueId,
                warmEvidence.requireCanonicalTypeBlueId(reorderedCopy));
    }

    @Test
    void findsCopiedEvidenceForADeepTypeWithoutRecursiveKeying() {
        String canonicalBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Deep effective type identity"));
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        Node recordedType = deepType(30_000);
        identities.record(
                recordedType,
                canonicalBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.VERIFIED_REFERENCE,
                null,
                null);

        assertEquals(
                canonicalBlueId,
                identities.requireCanonicalTypeBlueId(deepType(30_000)));
    }

    @Test
    void rejectsCanonicalContentWhoseCalculatedIdentityDisagreesWithKey() {
        Node canonical = new Node().name("Verified canonical value");
        String blueId = DirectBlueIdCalculator.calculateBlueId(canonical);
        FrozenNode frozenCanonical = FrozenNode.fromNode(canonical);
        FrozenNode firstResolved = FrozenNode.fromResolvedNode(
                new Node().value("first"));
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        cache.putVerifiedResolved(new VerifiedReferenceResolution(
                blueId,
                frozenCanonical,
                firstResolved,
                completeEmptyEvidence()));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> cache.putVerifiedResolved(
                        new VerifiedReferenceResolution(
                                blueId,
                                FrozenNode.fromNode(
                                        new Node().name(
                                                "Different canonical value")),
                                FrozenNode.fromResolvedNode(
                                        new Node().value("second")),
                                completeEmptyEvidence())));
        assertTrue(failure.getMessage().contains(
                "not cache key"));
        assertTrue(cache.getVerifiedResolved(blueId)
                        .orElseThrow(AssertionError::new)
                        .sameResolvedStructure(firstResolved),
                "rejected evidence must not replace the verified entry");
    }

    @Test
    void rejectsResolvedCacheEntryWithoutCompleteTypeEvidence() {
        Node canonical = new Node().name("Verified canonical value");
        String blueId = DirectBlueIdCalculator.calculateBlueId(canonical);
        FrozenNode frozenCanonical = FrozenNode.fromNode(canonical);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> cache.putVerifiedResolved(
                        new VerifiedReferenceResolution(
                                blueId,
                                frozenCanonical,
                                FrozenNode.fromResolvedNode(
                                        new Node().value("resolved")),
                                CanonicalTypeIdentityIndex.EvidenceSnapshot
                                        .incompleteEmpty())));

        assertTrue(failure.getMessage().contains(
                "identity evidence is incomplete"));
    }

    @Test
    void rejectsInlineTypeCycleCrossingThroughAProperty() {
        Node inlineType = new Node().name("Recursive inline type");
        inlineType.properties(
                "member",
                new Node().type(inlineType));
        Node root = new Node().type(inlineType);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> InlineTypeCycleValidator.validate(root));
        assertTrue(failure.getMessage().contains(
                "Cyclic inline type hierarchy"));
    }

    @Test
    void rejectsDirectInlineTypeObjectCycle() {
        Node inlineType = new Node().name("Direct cycle");
        inlineType.type(inlineType);

        assertThrows(
                IllegalStateException.class,
                () -> InlineTypeCycleValidator.validate(
                        new Node().type(inlineType)));
    }

    @Test
    void permitsSharedAcyclicInlineSubgraphs() {
        Node shared = new Node()
                .type(new Node().name("Shared leaf type"));
        Node inlineType = new Node()
                .name("Acyclic inline type")
                .properties("left", shared, "right", shared);

        assertDoesNotThrow(
                () -> InlineTypeCycleValidator.validate(
                        new Node().type(inlineType)));
    }

    @Test
    void acceptsExactPureTypeReferenceAsCompleteCacheEvidence() {
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Referenced type"));
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();

        assertDoesNotThrow(
                () -> identities.completeSnapshotForResolvedReference(
                        new Node().type(new Node().blueId(typeBlueId))));
    }

    @Test
    void acceptsRecordedPureTypeReferenceInCompleteCacheEvidence() {
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Referenced type"));
        Node completedType = new Node().blueId(typeBlueId);
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        identities.record(
                completedType,
                typeBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.VERIFIED_REFERENCE,
                null,
                null);

        assertDoesNotThrow(
                () -> identities.completeSnapshotForResolvedReference(
                        new Node().type(completedType)));
    }

    @Test
    void completeCoverageCannotHideAnUnrecordedNestedType() {
        Node recordedType = new Node().name("Recorded root type");
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();
        identities.record(
                recordedType,
                DirectBlueIdCalculator.calculateBlueId(recordedType),
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                FrozenNode.fromNode(recordedType),
                FrozenNode.fromResolvedNode(recordedType));
        Node resolvedRoot = new Node()
                .type(recordedType)
                .properties("nested", new Node().type(
                        new Node().name("Unrecorded nested type")));

        assertThrows(
                IllegalStateException.class,
                () -> identities.markCompleteCoverage(resolvedRoot));
        assertFalse(identities.hasCompleteCoverage());
    }

    @Test
    void acceptsExactPureSchemaReferenceAsCompleteCacheEvidence() {
        String schemaBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Referenced schema"));
        CanonicalTypeIdentityIndex identities =
                new CanonicalTypeIdentityIndex();

        assertDoesNotThrow(
                () -> identities.completeSnapshotForResolvedReference(
                        new Node().schema(
                                new Schema().blueId(schemaBlueId))));
    }

    private static CanonicalTypeIdentityIndex.EvidenceSnapshot
    completeEmptyEvidence() {
        return new CanonicalTypeIdentityIndex()
                .completeSnapshotForResolvedReference(new Node());
    }

    private static Node effectiveType(
            String firstProperty,
            String secondProperty) {
        Map<String, Node> properties = new LinkedHashMap<>();
        properties.put(firstProperty, new Node().value(firstProperty));
        properties.put(secondProperty, new Node().value(secondProperty));
        return new Node()
                .name("Ordered effective type")
                .properties(properties);
    }

    private static Node deepType(int depth) {
        Node current = new Node().name("Deep leaf");
        for (int index = 0; index < depth; index++) {
            current = new Node().properties("next", current);
        }
        return current;
    }
}
