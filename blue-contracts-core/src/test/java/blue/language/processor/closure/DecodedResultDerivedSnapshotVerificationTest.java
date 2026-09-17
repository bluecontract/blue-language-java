package blue.language.processor.closure;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Decoder ownership and fully validated processor-owned output lend only pure state verification. */
final class DecodedResultDerivedSnapshotVerificationTest {
    private static final int LIMIT = 16 * 1024 * 1024;
    private static final DocumentId ROOT = new DocumentId("d0");

    @Test void acceptedResultProjectionAndRetainedEpochViewReuseOnlyTheirOwnVerification() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureProcessResult original = rootedResult(fixture);
            ClosureProcessResultStorageCodec results = new ClosureProcessResultStorageCodec(LIMIT, 128);
            AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
            byte[] resultBytes = results.encode(original);
            ClosureProcessResult restored = results.decode(resultBytes);
            AffectedClosureSnapshot originalOutput = original.rootedProjection().resultingSnapshot();
            AffectedClosureSnapshot output = restored.rootedProjection().resultingSnapshot();
            assertTrue(originalOutput.hasVerifiedOwnedState(), "The internal processor retained its validated, detached output");
            assertSame(original.storageVerifiedOutput(), originalOutput);
            assertTrue(output.hasVerifiedOwnedState());
            assertFalse(output.hasVerifiedStorageSnapshot(), "A derived output was not a parsed snapshot record");
            AtomicInteger full = new AtomicInteger();
            ClosureEvidenceVerifier.verifySnapshot(output, full::incrementAndGet);
            ClosureEvidenceVerifier.verifySnapshot(output, full::incrementAndGet);
            assertEquals(0, full.get());
            assertArrayEquals(snapshots.encode(originalOutput), snapshots.encode(output));
            assertArrayEquals(resultBytes, results.encode(restored));

            Map<DocumentId, Long> epochs = retainedEpochs(original, 1L);
            AffectedClosureSnapshot expected = original.rootedProjection().retainedSnapshot(epochs);
            AffectedClosureSnapshot retained = restored.rootedProjection().retainedSnapshot(epochs, full::incrementAndGet);
            assertEquals(0, full.get(), "The exact owned source proves the factory's epoch-only transform");
            assertTrue(retained.hasVerifiedOwnedState());
            assertFalse(retained.hasVerifiedStorageSnapshot());
            ClosureEvidenceVerifier.verifySnapshot(retained, full::incrementAndGet);
            ClosureEvidenceVerifier.verifySnapshot(retained, full::incrementAndGet);
            assertEquals(0, full.get(), "Later encoding/context checks can reuse this exact owned view");
            assertArrayEquals(snapshots.encode(expected), snapshots.encode(retained));
            assertEquals(originalOutput.managedDocument(ROOT).epoch(), output.managedDocument(ROOT).epoch());
            assertEquals(output.managedDocument(ROOT).epoch() + 1L, retained.managedDocument(ROOT).epoch());
            assertSame(output.rootedWitnesses(), retained.rootedWitnesses());

            AffectedClosureSnapshot independentlyRetained = restored.rootedProjection().retainedSnapshot(epochs,
                    full::incrementAndGet);
            assertNotSame(retained, independentlyRetained, "No role view or wire alias is interned");
            assertEquals(0, full.get(), "A distinct wrapper gets its own narrowly derived proof without re-finalizing bodies");
            assertArrayEquals(snapshots.encode(retained), snapshots.encode(independentlyRetained));

            AffectedClosureSnapshot unchanged = restored.rootedProjection().retainedSnapshot(retainedEpochs(restored, 0),
                    full::incrementAndGet);
            assertNotSame(output, unchanged, "Unchanged epochs do not intern the retained wrapper into its source");
            assertEquals(0, full.get());
            assertArrayEquals(snapshots.encode(original.rootedProjection().retainedSnapshot(retainedEpochs(original, 0))),
                    snapshots.encode(unchanged));

            output.managedDocument(ROOT).document().properties("callerMutation", new Node().value(1));
            retained.managedDocument(ROOT).document().properties("callerMutation", new Node().value(2));
            output.components().get(0).completeCyclicProof().declaredPlaceholderSet().get(0).name("caller proof mutation");
            assertArrayEquals(resultBytes, results.encode(restored));
            assertArrayEquals(snapshots.encode(expected), snapshots.encode(retained));
        }
    }

    @Test void publicRoleCopiesAndChangedEvidenceNeverInheritDerivedProof() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureProcessResult original = rootedResult(fixture);
            ClosureProcessResultStorageCodec results = new ClosureProcessResultStorageCodec(LIMIT, 128);
            AffectedClosureSnapshot output = results.decode(results.encode(original)).rootedProjection().resultingSnapshot();
            AffectedClosureSnapshot copy = copy(output, output.managedDocuments());
            AtomicInteger full = new AtomicInteger();
            ClosureEvidenceVerifier.verifySnapshot(copy, full::incrementAndGet);
            ClosureEvidenceVerifier.verifySnapshot(copy, full::incrementAndGet);
            assertEquals(2, full.get());
            assertFalse(copy.hasVerifiedOwnedState(), "Equal fields and successful verification confer no ownership");

            List<ManagedDocumentSnapshot> changed = new ArrayList<>(output.managedDocuments());
            ManagedDocumentSnapshot before = changed.get(0);
            changed.set(0, new ManagedDocumentSnapshot(before.documentId(), before.blueId(),
                    before.document().properties("injected", new Node().value(1)), before.initialized(),
                    before.terminated(), before.publicRoot(), before.epoch(), before.componentGeneration()));
            AffectedClosureSnapshot forged = copy(output, changed);
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceVerifier.verifySnapshot(forged, full::incrementAndGet));
            assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceVerifier.verifySnapshot(forged, full::incrementAndGet));
            assertEquals(4, full.get());
            assertFalse(forged.hasVerifiedOwnedState());

            AffectedClosureSnapshot ordinaryRetained = original.rootedProjection().retainedSnapshot(retainedEpochs(original, 1),
                    full::incrementAndGet);
            ClosureEvidenceVerifier.verifySnapshot(ordinaryRetained, full::incrementAndGet);
            assertEquals(4, full.get(), "The processor-owned source also proves its exact epoch-only transform");
            assertTrue(ordinaryRetained.hasVerifiedOwnedState());
        }
    }

    @Test void derivedProofCannotRelaxEpochOwnershipPhysicalBoundsOrOuterResultAcceptance() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            ClosureProcessResultStorageCodec results = new ClosureProcessResultStorageCodec(LIMIT, 128);
            ClosureProcessResult original = rootedResult(fixture);
            byte[] bytes = results.encode(original);
            ClosureProcessResult restored = results.decode(bytes);
            RootedPublicationProjection projection = restored.rootedProjection();
            assertThrows(IllegalArgumentException.class, () -> projection.retainedSnapshot(Collections.emptyMap()));
            assertThrows(IllegalArgumentException.class, () -> projection.retainedSnapshot(retainedEpochs(restored, -1L)));
            assertThrows(IllegalArgumentException.class, () -> projection.retainedSnapshot(retainedEpochs(restored, 2L)));
            Map<DocumentId, Long> foreign = retainedEpochs(restored, 1L);
            foreign.put(new DocumentId("foreign-owner"), 1L);
            assertThrows(IllegalArgumentException.class, () -> projection.retainedSnapshot(foreign));

            AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
            AffectedClosureSnapshot retained = projection.retainedSnapshot(retainedEpochs(restored, 1L));
            for (AffectedClosureSnapshot selected : new AffectedClosureSnapshot[] {projection.resultingSnapshot(), retained}) {
                byte[] frame = snapshots.encode(selected);
                assertThrows(IllegalArgumentException.class,
                        () -> new AffectedClosureSnapshotStorageCodec(frame.length - 1, 128).encode(selected));
                assertThrows(IllegalArgumentException.class,
                        () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 1).encode(selected));
                assertArrayEquals(frame, snapshots.encode(snapshots.decode(frame)));
            }
            byte[] corrupt = bytes.clone(); corrupt[corrupt.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> results.decode(corrupt));
            assertThrows(IllegalArgumentException.class, () -> new ClosureProcessResultStorageCodec(bytes.length - 1, 128).decode(bytes));
            assertTrue(original.rootedProjection().resultingSnapshot().hasVerifiedOwnedState());
            assertArrayEquals(bytes, results.encode(restored));
        }
    }

    private static ClosureProcessResult rootedResult(CompositionCampaignFixture fixture) {
        AffectedClosureSnapshot graph = CompositionReactionCycleTest.ring(fixture, "derived-snapshot-proof", 3, 1);
        Map<DocumentId, String> histories = new LinkedHashMap<>();
        graph.managedDocuments().forEach(document -> histories.put(document.documentId(), hash('c')));
        ClosureProcessResult result = fixture.admit(fixture.admission(graph, 100_000L).withRootedContext(
                RootedProcessingContext.derive(graph, ROOT, histories), hash('d'))).processResult();
        assertTrue(result.commits(), diagnostic(result));
        assertNotNull(result.rootedProjection());
        return result;
    }

    /** Exercised with real independently selected historical witness contexts, not a fabricated ownership list. */
    static void assertHistoricalEpochTransfer(ClosureProcessResult original) {
        ClosureProcessResultStorageCodec results = new ClosureProcessResultStorageCodec(LIMIT, 128);
        AffectedClosureSnapshotStorageCodec snapshots = new AffectedClosureSnapshotStorageCodec(LIMIT, 128);
        ClosureProcessResult restored = results.decode(results.encode(original));
        RootedPublicationProjection projection = restored.rootedProjection();
        AffectedClosureSnapshot source = projection.resultingSnapshot();
        assertNotNull(source.rootedWitnesses()); assertFalse(source.rootedWitnesses().sources().isEmpty());
        AtomicInteger full = new AtomicInteger();
        for (long advance : new long[] {0, 1}) {
            AffectedClosureSnapshot ordinary = original.rootedProjection().retainedSnapshot(retainedEpochs(original, advance));
            AffectedClosureSnapshot retained = projection.retainedSnapshot(retainedEpochs(restored, advance), full::incrementAndGet);
            assertEquals(0, full.get()); assertTrue(retained.hasVerifiedOwnedState());
            assertNotSame(source, retained); assertSame(source.rootedWitnesses(), retained.rootedWitnesses());
            byte[] expected = snapshots.encode(ordinary);
            assertArrayEquals(expected, snapshots.encode(retained));
            assertArrayEquals(expected, snapshots.encode(snapshots.decode(expected)));
            for (DocumentId witness : source.rootedWitnesses().sources()) {
                assertSame(source.managedDocument(witness), retained.managedDocument(witness));
                source.rootedWitnesses().storedOriginals().get(witness).managedDocument(witness)
                        .document().properties("caller witness edit", new Node().value(1));
                retained.managedDocument(witness).document().properties("caller retained witness edit", new Node().value(2));
                assertArrayEquals(expected, snapshots.encode(retained));
                List<ManagedDocumentSnapshot> changed = new ArrayList<>(retained.managedDocuments());
                for (int index = 0; index < changed.size(); index++) {
                    ManagedDocumentSnapshot before = changed.get(index);
                    if (before.documentId().equals(witness)) changed.set(index, new ManagedDocumentSnapshot(witness,
                            before.blueId(), before.document(), before.initialized(), before.terminated(), before.publicRoot(),
                            before.epoch() + 1L, before.componentGeneration()));
                }
                assertThrows(IllegalArgumentException.class, () -> copy(retained, changed),
                        "Witness position guards are constructor invariants, not elided pure-state work");
                List<ManagedDocumentSnapshot> changedBodies = new ArrayList<>(retained.managedDocuments());
                for (int index = 0; index < changedBodies.size(); index++) {
                    ManagedDocumentSnapshot before = changedBodies.get(index);
                    if (before.documentId().equals(witness)) changedBodies.set(index, new ManagedDocumentSnapshot(witness,
                            before.blueId(), before.document().properties("forged witness", new Node().value(3)),
                            before.initialized(), before.terminated(), before.publicRoot(), before.epoch(), before.componentGeneration()));
                }
                assertThrows(IllegalArgumentException.class, () -> copy(retained, changedBodies),
                        "An owned epoch transform cannot lend its proof to changed witness content");
                Map<DocumentId, Long> foreign = retainedEpochs(restored, advance);
                foreign.put(witness, retained.managedDocument(witness).epoch());
                assertThrows(IllegalArgumentException.class, () -> projection.retainedSnapshot(foreign));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new AffectedClosureSnapshotStorageCodec(expected.length - 1, 128).encode(retained));
            assertThrows(IllegalArgumentException.class,
                    () -> new AffectedClosureSnapshotStorageCodec(LIMIT, 1).encode(retained));
        }
    }

    private static Map<DocumentId, Long> retainedEpochs(ClosureProcessResult result, long advance) {
        Map<DocumentId, Long> epochs = new LinkedHashMap<>();
        RootedPublicationProjection projection = result.rootedProjection();
        for (DocumentId owner : projection.ownedDocumentIds()) {
            epochs.put(owner, projection.resultingSnapshot().managedDocument(owner).epoch() + advance);
        }
        return epochs;
    }

    private static AffectedClosureSnapshot copy(AffectedClosureSnapshot source, List<ManagedDocumentSnapshot> documents) {
        return new AffectedClosureSnapshot(source.closureIdentity(), source.graphGeneration(), documents,
                source.occurrences(), source.occurrenceBindingSetIdentity(), source.components(),
                source.publicRootDocumentIds(), source.rootedWitnesses());
    }
}
