package blue.language.merge;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.merge.processor.TypeAssigner;
import blue.language.provider.NodeProvider;
import blue.language.resolve.ResolutionLimits;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CanonicalTypeIdentityIndexTest {

    @Test
    void shouldReuseEvidenceAcrossExactClonesAndRejectChangedProvenance() {
        // given
        Node canonicalType = new Node().name("Effective Type");
        String canonicalBlueId = id(canonicalType);
        Node resolved = canonicalType.clone().blueId(canonicalBlueId);
        Node cloneWithDifferentAnnotation = resolved.clone()
                .blueId(id(new Node().name("Unrelated annotation")));
        CanonicalTypeIdentityIndex index = new CanonicalTypeIdentityIndex();

        // when
        recordEvidence(index,
                resolved,
                canonicalBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE);

        // then
        assertEquals(canonicalBlueId, index.require(resolved).blueId());
        assertEquals(canonicalBlueId,
                index.require(resolved.clone()).blueId());
        assertThrows(IllegalStateException.class,
                () -> index.require(cloneWithDifferentAnnotation));
        assertEquals(
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                index.require(resolved.clone()).kind());
    }

    @Test
    void shouldKeepDistinctExactReferenceProvenanceForSameCompletedShape() {
        // given
        Node completedShape = new Node().name("Same completed shape");
        String inlineBlueId = id(completedShape);
        String firstReferenceBlueId = id(
                new Node().name("First exact type source"));
        String secondReferenceBlueId = id(
                new Node().name("Second exact type source"));
        Node inline = completedShape.clone();
        Node firstReference = completedShape.clone()
                .blueId(firstReferenceBlueId);
        Node secondReference = completedShape.clone()
                .blueId(secondReferenceBlueId);
        CanonicalTypeIdentityIndex index = new CanonicalTypeIdentityIndex();

        // when
        recordEvidence(index,
                inline,
                inlineBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE);
        recordEvidence(index,
                firstReference,
                firstReferenceBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.VERIFIED_REFERENCE);
        recordEvidence(index,
                secondReference,
                secondReferenceBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.VERIFIED_REFERENCE);
        CanonicalTypeIdentityIndex.EvidenceSnapshot evidence =
                index.completeSnapshotForResolvedReference(
                        new Node().properties(
                                "inline", new Node().type(inline),
                                "first", new Node().type(firstReference),
                                "second", new Node().type(secondReference)));

        // then
        assertEquals(inlineBlueId,
                evidence.requireCanonicalTypeBlueId(inline.clone()));
        assertEquals(firstReferenceBlueId,
                evidence.requireCanonicalTypeBlueId(firstReference.clone()));
        assertEquals(secondReferenceBlueId,
                evidence.requireCanonicalTypeBlueId(secondReference.clone()));
    }

    @Test
    void shouldCombineIndependentEvidenceKindsForOneIdentity() {
        // given
        Node resolved = new Node().name("Shared Effective Type");
        String blueId = id(resolved);
        CanonicalTypeIdentityIndex index = new CanonicalTypeIdentityIndex();

        // when
        recordEvidence(index,
                resolved,
                blueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE);
        recordEvidence(index,
                resolved.clone(),
                blueId,
                CanonicalTypeIdentityIndex.EvidenceKind.VERIFIED_REFERENCE);

        // then
        assertEquals(
                CanonicalTypeIdentityIndex.EvidenceKind
                        .AUTHORED_INLINE_AND_VERIFIED_REFERENCE,
                index.require(resolved).kind());
    }

    @Test
    void shouldReuseEvidenceAcrossObjectInsertionOrders() {
        // given
        Node first = new Node()
                .properties("alpha", new Node().value("a"))
                .properties("omega", new Node().value("z"));
        Node second = new Node()
                .properties("omega", new Node().value("z"))
                .properties("alpha", new Node().value("a"));
        Map<String, Object> firstValue = new LinkedHashMap<>();
        firstValue.put("alpha", "a");
        firstValue.put("omega", "z");
        Map<String, Object> secondValue = new LinkedHashMap<>();
        secondValue.put("omega", "z");
        secondValue.put("alpha", "a");
        first.properties("json", new Node().value(firstValue));
        second.properties("json", new Node().value(secondValue));
        String blueId = id(first);
        CanonicalTypeIdentityIndex index = new CanonicalTypeIdentityIndex();

        // when
        recordEvidence(index,
                first,
                blueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE);

        // then
        assertEquals(blueId, index.require(second).blueId());
    }

    @Test
    void shouldKeepProvenanceDistinctIdentitiesForOneResolvedStructure() {
        // given
        Node first = new Node().name("Same Effective Type");
        Node second = first.clone();
        Node firstAuthored = new Node().name("Inherited declaration");
        Node secondAuthored = new Node()
                .name("Inherited declaration")
                .description("Explicitly repeated inherited value");
        Node firstCanonical = firstAuthored.clone();
        Node secondCanonical = secondAuthored.clone();
        String firstBlueId = id(firstCanonical);
        String secondBlueId = id(secondCanonical);
        CanonicalTypeIdentityIndex index = new CanonicalTypeIdentityIndex();

        // when
        index.record(
                first,
                firstBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                FrozenNode.fromNode(firstCanonical),
                FrozenNode.fromResolvedNode(firstAuthored));
        index.record(
                second,
                secondBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                FrozenNode.fromNode(secondCanonical),
                FrozenNode.fromResolvedNode(secondAuthored));
        CanonicalTypeIdentityIndex.EvidenceSnapshot firstOnly =
                index.completeSnapshotForResolvedReference(
                        new Node().type(first));

        // then
        assertEquals(firstBlueId, index.require(first).blueId());
        assertEquals(secondBlueId, index.require(second).blueId());
        assertThrows(
                IllegalStateException.class,
                () -> index.require(first.clone()),
                "a detached physical shape must not guess between identities");
        assertEquals(1, firstOnly.size(),
                "a snapshot must not export unrelated sibling evidence");
        assertEquals(
                firstBlueId,
                firstOnly.requireCanonicalTypeBlueId(first.clone()));
        assertEquals(
                secondBlueId,
                index.requireCanonicalTypeBlueId(
                        second.clone(), secondAuthored.clone()));
    }

    @Test
    void shouldNotApplyAuthoredEvidenceToAnUncoveredCompletedStructure() {
        // given
        Node authored = new Node().name("Exact authored declaration");
        Node covered = new Node().name("Covered completed structure");
        Node uncovered = new Node().name("Different completed structure");
        String blueId = id(authored);
        CanonicalTypeIdentityIndex index = new CanonicalTypeIdentityIndex();
        index.record(
                covered,
                blueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE,
                FrozenNode.fromNode(authored),
                FrozenNode.fromResolvedNode(authored));

        // when
        boolean evidencePresent = index.findCanonicalTypeIdentityEvidence(
                uncovered, authored.clone()).isPresent();

        // then
        assertFalse(evidencePresent,
                "authored provenance must also cover the completed shape");
    }

    @Test
    void shouldFailClosedOnMissAndIncompleteTransportEvidence() {
        // given
        CanonicalTypeIdentityIndex empty = new CanonicalTypeIdentityIndex();

        // when
        CanonicalTypeIdentityIndex.EvidenceSnapshot incomplete =
                empty.snapshot();

        // then
        assertThrows(IllegalStateException.class,
                () -> empty.require(new Node().name("Missing")));
        assertFalse(incomplete.hasCompleteCoverage());
        assertThrows(IllegalStateException.class,
                incomplete::requireCompleteCoverage);
        assertThrows(IllegalStateException.class,
                () -> new CanonicalTypeIdentityIndex()
                        .importComplete(incomplete));
    }

    @Test
    void shouldTreatExactPureReferencesAsSelfIdentifyingWithoutSidecarEvidence() {
        // given
        String blueId = id(new Node().name("Exact deferred type"));
        Node reference = reference(blueId);
        CanonicalTypeIdentityIndex index = new CanonicalTypeIdentityIndex();

        // when
        CanonicalTypeIdentityIndex.EvidenceSnapshot snapshot = index.snapshot();

        // then
        assertEquals(blueId,
                index.requireCanonicalTypeBlueId(reference.clone()));
        assertEquals(blueId,
                snapshot.requireCanonicalTypeBlueId(reference.clone()));
        assertEquals(blueId,
                CanonicalTypeIdentityLookup.incomplete()
                        .requireCanonicalTypeBlueId(reference.clone()));

        assertThrows(IllegalStateException.class,
                () -> CanonicalTypeIdentityLookup.incomplete()
                        .requireCanonicalTypeBlueId(
                                new Node().name("Materialized type")));
    }

    @Test
    void shouldImportCompleteCloneStableEvidence() {
        // given
        Node resolved = new Node().name("Imported Effective Type");
        String blueId = id(resolved);
        CanonicalTypeIdentityIndex producer = new CanonicalTypeIdentityIndex();
        recordEvidence(producer,
                resolved,
                blueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE);
        CanonicalTypeIdentityIndex.EvidenceSnapshot evidence =
                producer.completeSnapshotForResolvedReference(
                        new Node().type(resolved));
        CanonicalTypeIdentityIndex consumer = new CanonicalTypeIdentityIndex();

        // when
        consumer.importComplete(evidence);

        // then
        assertTrue(evidence.hasCompleteCoverage());
        assertEquals(blueId, consumer.require(resolved.clone()).blueId());
    }

    @Test
    void shouldRejectCombiningIncompleteTransportEvidence() {
        // given
        Node resolved = new Node().name("Complete transport type");
        CanonicalTypeIdentityIndex producer =
                new CanonicalTypeIdentityIndex();
        recordEvidence(producer,
                resolved,
                id(resolved),
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE);
        CanonicalTypeIdentityIndex.EvidenceSnapshot complete =
                producer.completeSnapshotForResolvedReference(
                        new Node().type(resolved));
        CanonicalTypeIdentityIndex.EvidenceSnapshot incomplete =
                new CanonicalTypeIdentityIndex().snapshot();

        // when
        boolean completeCoverage = complete.hasCompleteCoverage();

        // then
        assertTrue(completeCoverage);
        assertThrows(IllegalStateException.class,
                () -> complete.combine(incomplete));
        assertThrows(IllegalStateException.class,
                () -> incomplete.combine(complete));
    }

    @Test
    void shouldRejectCompleteReferenceEvidenceMissingFromResolvedRoot() {
        // given
        CanonicalTypeIdentityIndex producer =
                new CanonicalTypeIdentityIndex();
        Node unrecordedType = new Node().name("Unrecorded Type");

        // when
        Node resolvedRoot = new Node().type(unrecordedType);

        // then
        assertThrows(IllegalStateException.class,
                () -> producer.completeSnapshotForResolvedReference(
                        resolvedRoot));
    }

    @Test
    void shouldNotMarkWholeGraphCoverageWithUnrecordedNestedType() {
        // given
        CanonicalTypeIdentityIndex producer =
                new CanonicalTypeIdentityIndex();
        Node recordedType = new Node().name("Recorded Type");
        String recordedBlueId = id(recordedType);
        recordEvidence(producer,
                recordedType,
                recordedBlueId,
                CanonicalTypeIdentityIndex.EvidenceKind.AUTHORED_INLINE);
        Node resolvedRoot = new Node()
                .type(recordedType)
                .properties("nested", new Node().type(
                        new Node().name("Unrecorded Nested Type")));

        // when
        boolean initiallyIncomplete = !producer.hasCompleteCoverage();

        // then
        assertTrue(initiallyIncomplete);
        assertThrows(IllegalStateException.class,
                () -> producer.markCompleteCoverage(resolvedRoot));
        assertFalse(producer.hasCompleteCoverage());
    }

    @Test
    void shouldKeepReferenceEvidenceIncompleteAfterCoverageGap() {
        // given
        CanonicalTypeIdentityIndex producer =
                new CanonicalTypeIdentityIndex();
        producer.noteCoverageGap();

        // when
        CanonicalTypeIdentityIndex.EvidenceSnapshot evidence =
                producer.completeSnapshotForResolvedReference(
                        new Node().type(
                                new Node().name("Unvisited Type")));

        // then
        assertFalse(evidence.hasCompleteCoverage());
    }

    @Test
    void shouldTransportIdenticalNestedEvidenceAcrossColdAndWarmResolution() {
        // given
        Node elementType = new Node().name("Element Type");
        String elementBlueId = id(elementType);
        Node containerType = new Node()
                .name("Container Type")
                .properties("member",
                        new Node().type(reference(elementBlueId)));
        String containerBlueId = id(containerType);
        AtomicInteger providerCalls = new AtomicInteger();
        NodeProvider provider = blueId -> {
            providerCalls.incrementAndGet();
            if (elementBlueId.equals(blueId)) {
                return Collections.singletonList(elementType.clone());
            }
            if (containerBlueId.equals(blueId)) {
                return Collections.singletonList(containerType.clone());
            }
            return null;
        };
        MergingProcessor processor = new TypeAssigner();
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        Node source = new Node()
                .name("Instance")
                .type(reference(containerBlueId));

        // when
        SnapshotResolution cold = new Merger(processor, provider, cache)
                .resolveSnapshot(source.clone(), ResolutionLimits.NO_LIMITS);
        int callsAfterCold = providerCalls.get();
        SnapshotResolution warm = new Merger(processor, provider, cache)
                .resolveSnapshot(source.clone(), ResolutionLimits.NO_LIMITS);

        CanonicalTypeIdentityIndex.EvidenceSnapshot coldEvidence =
                (CanonicalTypeIdentityIndex.EvidenceSnapshot)
                        cold.canonicalTypeIdentities();
        CanonicalTypeIdentityIndex.EvidenceSnapshot warmEvidence =
                (CanonicalTypeIdentityIndex.EvidenceSnapshot)
                        warm.canonicalTypeIdentities();
        Node coldContainer = cold.resolvedRoot().getType().toNode();
        Node warmContainer = warm.resolvedRoot().getType().toNode();
        Node coldElement = cold.resolvedRoot().getType()
                .getProperties().get("member").getType().toNode();
        Node warmElement = warm.resolvedRoot().getType()
                .getProperties().get("member").getType().toNode();

        // then
        coldEvidence.requireCompleteCoverage();
        warmEvidence.requireCompleteCoverage();
        assertEquals(containerBlueId,
                coldEvidence.requireCanonicalTypeBlueId(coldContainer));
        assertEquals(containerBlueId,
                warmEvidence.requireCanonicalTypeBlueId(warmContainer));
        assertEquals(elementBlueId,
                coldEvidence.requireCanonicalTypeBlueId(coldElement));
        assertEquals(elementBlueId,
                warmEvidence.requireCanonicalTypeBlueId(warmElement));
        assertEquals(coldEvidence, warmEvidence);
        assertEquals(callsAfterCold, providerCalls.get(),
                "complete warm evidence must permit provider-free reuse");
    }

    @Test
    void shouldRejectLimitedWholeNodeCanonicalizationAndPermitCompleteRetry() {
        // given
        MergingProcessor processor = (target, source, provider, resolver,
                                      typeIdentities) -> {
            // No custom fields are needed for the coverage assertion.
        };
        Merger merger = new Merger(processor, ignored -> null);

        // when
        Node source = new Node().name("Limited");

        // then
        assertThrows(IllegalStateException.class,
                () -> merger.resolveSnapshot(
                        source.clone(),
                        ResolutionLimits.withMaxDepth(1)));

        SnapshotResolution eager = merger.resolveSnapshot(
                        source.clone(),
                        ResolutionLimits.NO_LIMITS);
        SnapshotResolution retry = merger.resolveSnapshot(
                        source.clone(),
                        ResolutionLimits.NO_LIMITS);

        eager.canonicalTypeIdentities().requireCompleteCoverage();
        retry.canonicalTypeIdentities().requireCompleteCoverage();
        assertEquals(eager.canonicalRoot().blueId(),
                retry.canonicalRoot().blueId());
        assertTrue(eager.resolvedRoot().sameResolvedStructure(
                retry.resolvedRoot()));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String id(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static void recordEvidence(
            CanonicalTypeIdentityIndex index,
            Node completedType,
            String blueId,
            CanonicalTypeIdentityIndex.EvidenceKind kind) {
        Node normalizedAuthored = kind
                == CanonicalTypeIdentityIndex.EvidenceKind.VERIFIED_REFERENCE
                ? null
                : NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                        completedType.clone());
        FrozenNode canonicalTypeIdentityInput = normalizedAuthored != null
                ? FrozenNode.fromNode(normalizedAuthored)
                : null;
        FrozenNode authoredSource = normalizedAuthored != null
                ? FrozenNode.fromResolvedNode(normalizedAuthored)
                : null;
        index.record(
                completedType,
                blueId,
                kind,
                canonicalTypeIdentityInput,
                authoredSource);
    }
}
