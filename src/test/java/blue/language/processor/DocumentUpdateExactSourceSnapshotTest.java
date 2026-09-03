package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentUpdateExactSourceSnapshotTest {

    @Test
    void shouldRetainPureReferencesInExactSourceBeforeSnapshot() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Referenced Initial Document")
                .properties("value", new Node().value("initial")));
        String documentBlueId = provider.getBlueIdByName(
                "Referenced Initial Document");
        provider.addSingleNodes(new Node()
                .name("Required Document Marker")
                .properties("document", new Node()
                        .description("Exact referenced document")
                        .schema(new Schema().required(true))));
        String markerBlueId = provider.getBlueIdByName(
                "Required Document Marker");
        Blue blue = ProcessorTestSupport.blue(provider);
        Node source = new Node().properties(
                "child",
                new Node().contracts(new Node().properties(
                        "marker",
                        new Node()
                                .type(new Node().blueId(markerBlueId))
                                .properties("document",
                                        new Node().blueId(
                                                documentBlueId)))));
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(source);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                snapshot,
                blue.getDocumentProcessor().conformanceEngine(),
                blue.getDocumentProcessor().snapshotManager());

        // when
        DocumentUpdateData update = runtime.applyPatch(
                "/", JsonPatch.remove("/child"));

        Node before = update.before();
        Node referencedDocument = before == null
                ? null
                : before.getAsNode("/contracts/marker/document");

        // then
        assertNotNull(before);
        assertNotNull(referencedDocument);
        assertTrue(referencedDocument.isReferenceOnly(),
                "Document Update must retain the exact Source reference");
        assertEquals(documentBlueId, referencedDocument.getBlueId());
        assertEquals(
                snapshot.canonicalAt("/child").blueId(),
                blue.calculateSourceDocumentBlueId(before),
                "Source and resolved snapshots must identify the same value");
    }

    @Test
    void shouldProjectDerivedBeforeValueWhenSelectedInputHasNoNode() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Derived Value Type")
                .properties(
                        "kind", new Node().value("inherited kind")));
        String derivedTypeBlueId = provider.getBlueIdByName(
                "Derived Value Type");
        Node exactDerived = new Node()
                .name("Derived Value")
                .type(new Node().blueId(derivedTypeBlueId))
                .properties("content", new Node().value("from source"));
        provider.addSingleNodes(exactDerived);
        String derivedBlueId = provider.getBlueIdByName("Derived Value");
        provider.addSingleNodes(new Node()
                .name("Document With Derived Field")
                .properties(
                        "derived", new Node().blueId(derivedBlueId),
                        "fixedText", new Node().value("from type")));
        String documentTypeBlueId = provider.getBlueIdByName(
                "Document With Derived Field");
        Blue blue = ProcessorTestSupport.blue(provider);
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(
                new Node().type(new Node().blueId(documentTypeBlueId)));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                snapshot,
                blue.getDocumentProcessor().conformanceEngine(),
                blue.getDocumentProcessor().snapshotManager());

        // when
        DocumentUpdateData update = runtime.applyPatch(
                "/",
                JsonPatch.add(
                        "/derived",
                        new Node().properties(
                                "content", new Node().value("authored"))));

        Node before = update.before();
        DocumentUpdateData fixedUpdate = runtime.applyPatch(
                "/",
                JsonPatch.add(
                        "/fixedText",
                        new Node().value("from type")));

        // then
        assertNull(snapshot.sourceAt("/derived"));
        assertNull(snapshot.canonicalAt("/derived"));
        assertNotNull(snapshot.resolvedAt("/derived"));
        assertNotNull(before);
        assertTrue(before.isReferenceOnly(),
                "verified materialized references stay exact references");
        assertEquals(derivedBlueId, blue.calculateSourceDocumentBlueId(before));
        assertTrue(FrozenNode.fromNode(before).isStrictCanonical(),
                "derived fallback must be Canonical Identity Input");
        assertNotNull(fixedUpdate.before());
        assertEquals("from type", fixedUpdate.before().getValue());
        assertTrue(FrozenNode.fromNode(fixedUpdate.before())
                        .isStrictCanonical(),
                "derived fixed values require a strict canonical projection");
    }

    @Test
    void shouldUseMatchedAuthoritativeInputAndResolvedLanes() {
        // given
        CanonicalTypeIdentityLookup identities = completeUntypedLookup();
        FrozenNode initial = FrozenNode.fromNode(Nodes.emptyObject());
        Node exactValue = new Node()
                .properties("value", new Node().value("same value"));
        String valueBlueId = FrozenNode.fromNode(exactValue).blueId();
        ImmutableJsonPatch patch = ImmutableJsonPatch.from(
                JsonPatch.add("/target", exactValue), initial, initial);
        ImmutablePatchPlanner.PatchPlan selectedPlan =
                ImmutablePatchPlanner.forFrozen(initial).plan("/", patch);
        ImmutablePatchPlanner.PatchPlan resolvedPlan =
                ImmutablePatchPlanner.forFrozen(initial).plan("/", patch);
        BatchPatchRecord record = new BatchPatchRecord(
                patch,
                selectedPlan,
                resolvedPlan,
                null,
                selectedPlan.after(),
                true,
                null,
                false);
        BatchPatchResult.UpdatePlan updatePlan =
                new BatchPatchResult.UpdatePlan(
                        Collections.singletonList(record),
                        selectedPlan.root(),
                        resolvedPlan.root(),
                        identities,
                        selectedPlan.root(),
                        resolvedPlan.root(),
                        identities,
                        null,
                        Collections.<BatchPatchResult.GeneralizationMetadataWrite>
                                emptyList(),
                        true);
        BatchPatchResult result = new BatchPatchResult(
                selectedPlan.root(),
                resolvedPlan.root(),
                null,
                updatePlan,
                Collections.singletonList(patch),
                Collections.<BatchPatchResult.GeneralizationMetadataWrite>
                        emptyList(),
                identities,
                true,
                true,
                0L,
                0L,
                0L);
        FrozenNode authoritativeSource = FrozenNode.fromNode(
                new Node().properties(
                        "target", new Node().blueId(valueBlueId)));
        FrozenNode authoritativeResolved = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "target", exactValue.clone().blueId(valueBlueId)));
        ResolvedSnapshot authoritative = ResolvedSnapshot.withSource(
                authoritativeSource,
                authoritativeResolved,
                identities,
                true);

        // when
        Node after = result.updatesAgainst(authoritative, null, null)
                .get(0).after();

        // then
        assertNotNull(after);
        assertTrue(after.isReferenceOnly());
        assertEquals(valueBlueId, after.getBlueId());
    }

    @Test
    void shouldFailClosedForUnverifiedMaterializedReferenceFallback() {
        // given
        Node exactValue = new Node()
                .properties("value", new Node().value("content"));
        String valueBlueId = FrozenNode.fromNode(exactValue).blueId();
        FrozenNode resolved = FrozenNode.fromResolvedNode(
                exactValue.clone().blueId(valueBlueId));

        // when
        Runnable action = () -> BatchPatchRecord.exactInputSnapshot(
                null,
                resolved,
                completeUntypedLookup(),
                null);

        // then
        assertThrows(
                IllegalStateException.class,
                action::run);
    }

    @Test
    void shouldRetainVerifiedIdentityForMaterializedReferenceFallback() {
        // given
        Node exactValue = new Node()
                .name("Referenced Exact Value")
                .properties("content", new Node().value("source"));
        FrozenNode exact = FrozenNode.fromNode(exactValue);
        String valueBlueId = exact.blueId();
        FrozenNode verifiedResolved = FrozenNode.fromResolvedNode(
                exactValue.clone().properties(
                        "derived", new Node().value("effective")));
        FrozenNode capturedResolved = FrozenNode.fromResolvedNode(
                verifiedResolved.toNode().blueId(valueBlueId));
        ProcessingSnapshotManager manager = new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ResolvedSnapshot applyPatch(
                    ResolvedSnapshot snapshot,
                    JsonPatch patch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FrozenNode materializeVerifiedExactReference(
                    FrozenNode reference) {
                return exact;
            }

            @Override
            public FrozenNode materializeVerifiedReference(
                    FrozenNode reference) {
                return verifiedResolved;
            }
        };

        // when
        FrozenNode projected = BatchPatchRecord.exactInputSnapshot(
                null,
                capturedResolved,
                completeUntypedLookup(),
                manager);

        // then
        assertTrue(projected.isReferenceOnly());
        assertEquals(valueBlueId, projected.getReferenceBlueId());
    }

    @Test
    void shouldUseTypeEvidenceForGeneratedMetadataBeforeValue() {
        // given
        Node completedType = new Node().name("Prior Generated Type");
        String typeBlueId = FrozenNode.fromNode(completedType).blueId();
        CanonicalTypeIdentityLookup identities = completeLookupFor(
                completedType, typeBlueId);
        FrozenNode selectedBefore = FrozenNode.fromNode(
                new Node().properties("target", Nodes.emptyObject()));
        FrozenNode resolvedBefore = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "target", new Node().type(completedType)));
        FrozenNode selectedAfter = FrozenNode.fromNode(
                new Node().properties(
                        "target", new Node().type(
                                new Node().blueId(typeBlueId))));
        BatchPatchResult.GeneralizationMetadataWrite write =
                new BatchPatchResult.GeneralizationMetadataWrite(
                        "/target/type",
                        FrozenNode.fromNode(
                                new Node().blueId(typeBlueId)),
                        0);
        BatchPatchResult.UpdatePlan plan =
                new BatchPatchResult.UpdatePlan(
                        Collections.<BatchPatchRecord>emptyList(),
                        selectedBefore,
                        resolvedBefore,
                        identities,
                        selectedAfter,
                        resolvedBefore,
                        identities,
                        null,
                        Collections.singletonList(write),
                        true);

        // when
        Node before = plan.build(null).get(0).before();

        // then
        assertNotNull(before);
        assertTrue(before.isReferenceOnly());
        assertEquals(typeBlueId, before.getBlueId());
    }

    private static CanonicalTypeIdentityLookup completeUntypedLookup() {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                return Optional.empty();
            }
        };
    }

    private static CanonicalTypeIdentityLookup completeLookupFor(
            Node completedType,
            String blueId) {
        FrozenNode.ResolvedStructuralKey expected =
                FrozenNode.fromResolvedNode(completedType)
                        .resolvedStructuralKey();
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node candidate) {
                return expected.equals(
                        FrozenNode.fromResolvedNode(candidate)
                                .resolvedStructuralKey())
                        ? Optional.of(
                                CanonicalTypeIdentityEvidence.identityOnly(
                                        blueId))
                        : Optional.<CanonicalTypeIdentityEvidence>empty();
            }
        };
    }
}
