package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class ProcessorOwnedSnapshotVerificationTest {
    private static final DocumentId ROOT = new DocumentId("processor-owned");
    private static final int LIMIT = 16 * 1024 * 1024;

    @Test void actualDetachedRepresentationsRejectExtensibleAndRawPayloadAliases() {
        Node plain = new Node().name("owned").properties("n", new Node().value(1));
        assertTrue(record(plain).hasDetachedStandardDocument());
        assertFalse(record(new AliasNode(plain)).hasDetachedStandardDocument(),
                "A custom clone can return a caller-retained standard Node");
        assertFalse(record(new SelfNode()).hasDetachedStandardDocument());
        assertFalse(record(new Node().properties("nested", new SelfNode())).hasDetachedStandardDocument());
        assertFalse(record(new Node().schema(new MutableSchema())).hasDetachedStandardDocument());
        assertFalse(record(new Node().schema(new Schema().minimum(new Node().value(new MutableNumber())))).hasDetachedStandardDocument());
        assertFalse(record(new Node().value(new MutableNumber())).hasDetachedStandardDocument());
        assertFalse(record(new Node().value(new MutableDecimal())).hasDetachedStandardDocument());
        for (Object payload : new Object[] {plain, new Schema(), Arrays.asList(plain),
                Collections.singletonMap("hidden", new Schema()), new Object[] {plain}}) {
            assertFalse(record(new Node().value(payload)).hasDetachedStandardDocument(),
                    "Node/Schema values within raw payloads are not cloned as structural edges");
        }
        assertTrue(record(new Node().value(Collections.singletonMap("values",
                Arrays.asList("a", 1L, new BigDecimal("1.25"), new int[] {1, 2})))).hasDetachedStandardDocument());
    }

    @Test void resultAndWitnessOwnershipCannotBeInferredFromReturnedNodeCopies() {
        AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(ROOT, document("owned")), Collections.emptyList(), ROOT);
        ManagedDocumentSnapshot original = graph.managedDocument(ROOT);
        Node alias = original.document();
        ManagedDocumentSnapshot unsafe = new ManagedDocumentSnapshot(ROOT, original.blueId(), new AliasNode(alias),
                false, false, false, 0, original.componentGeneration());
        AffectedClosureSnapshot source = new AffectedClosureSnapshot(graph.closureIdentity(), graph.graphGeneration(),
                Collections.singletonList(unsafe), graph.occurrences(), graph.occurrenceBindingSetIdentity(),
                graph.components(), Collections.emptyList());
        assertFalse(source.hasDetachedRepresentation());
        RootedWitnessFrame.State witnesses = RootedWitnessFrame.State.fromStoredOriginals(Collections.singletonMap(ROOT, source));
        ManagedDocumentSnapshot safe = new ManagedDocumentSnapshot(ROOT, original.blueId(), alias, false, false,
                false, 0, original.componentGeneration());
        AffectedClosureSnapshot output = new AffectedClosureSnapshot(graph.closureIdentity(), graph.graphGeneration(),
                Collections.singletonList(safe), graph.occurrences(), graph.occurrenceBindingSetIdentity(),
                graph.components(), Collections.emptyList(), witnesses);
        assertTrue(safe.hasDetachedStandardDocument());
        assertFalse(output.hasDetachedRepresentation(), "Even a detached current body cannot hide an aliased witness original");
        alias.properties("external mutation", new Node().value(2));
        assertThrows(IllegalArgumentException.class, () -> ClosureEvidenceVerifier.verifySnapshot(source));
        assertFalse(output.hasVerifiedOwnedState());
    }

    @Test void cyclicProofSubclassNeverQualifiesAsOwnedStandardEvidence() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = CompositionReactionCycleTest.ring(fixture, "owned-proof", 3, 1);
            ComponentSnapshot component = graph.components().get(0);
            ArrayList<Node> proof = new ArrayList<>(component.completeCyclicProof().declaredPlaceholderSet());
            // Preserve ordinary proof shape while the runtime subtype retains an extra alias.
            proof.set(0, new SelfNode().name("custom"));
            ComponentSnapshot unsafe = new ComponentSnapshot(component.componentIdentity(), component.componentStateIdentity(),
                    component.componentGeneration(), component.kind(), component.orderedMemberDocumentIds(),
                    component.orderedMemberBlueIds(), component.masterBlueId(),
                    CyclicSetProof.fromDeclaredPlaceholderSet(proof), component.cyclicProofIdentity());
            assertFalse(unsafe.hasDetachedStandardProof());
        }
    }

    @Test void processorOutputEliminatesThreeFullVerificationsButPublicCopiesDoNotGainProof() {
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture(true)) {
            AffectedClosureSnapshot graph = snapshot(Collections.singletonMap(ROOT, document("owned")), Collections.emptyList(), ROOT);
            ClosureProcessResult produced = fixture.admit(fixture.admission(graph, 100_000L).withRootedContext(
                    RootedProcessingContext.derive(graph, ROOT, Collections.singletonMap(ROOT, hash('c'))), hash('d'))).processResult();
            assertTrue(produced.commits(), diagnostic(produced));
            AffectedClosureSnapshot owned = produced.rootedProjection().resultingSnapshot();
            assertSame(owned, produced.storageVerifiedOutput());
            AffectedClosureSnapshot ordinary = new AffectedClosureSnapshot(owned.closureIdentity(), owned.graphGeneration(),
                    owned.managedDocuments(), owned.occurrences(), owned.occurrenceBindingSetIdentity(),
                    owned.components(), owned.publicRootDocumentIds(), owned.rootedWitnesses());
            AtomicInteger control = new AtomicInteger(), optimized = new AtomicInteger();
            AffectedClosureSnapshotStorageCodec cold = new AffectedClosureSnapshotStorageCodec(LIMIT, 128, 0, 0, control::incrementAndGet);
            AffectedClosureSnapshotStorageCodec fast = new AffectedClosureSnapshotStorageCodec(LIMIT, 128, 0, 0, optimized::incrementAndGet);
            for (int n = 0; n < 3; n++) assertArrayEquals(cold.encode(ordinary), fast.encode(owned));
            assertEquals(3, control.get()); assertEquals(0, optimized.get());
            assertFalse(ordinary.hasVerifiedOwnedState());
            assertFalse(owned.hasVerifiedStorageSnapshot(), "Processor state ownership is not decoder/publication authority");
            ClosureProcessResult publicCopy = new ClosureProcessResult(produced.storageInputSnapshot(), produced.status(),
                    produced.invocationIdentity(), produced.outputClosureIdentity(), produced.graphGeneration(), produced.resultingDocuments(),
                    produced.resultingComponents(), produced.occurrenceBindings(), produced.occurrenceBindingSetIdentity(),
                    produced.graphChanges(), produced.graphChangesIdentity(), produced.subscriptionDeltas(), produced.subscriptionDeltasIdentity(),
                    produced.checkpointWrites(), produced.checkpointWritesIdentity(), produced.publicEvents(), produced.publicEventsIdentity(),
                    produced.totalGas(), produced.gasTrace(), produced.gasTraceIdentity(), produced.rejectedCharge(),
                    produced.rejectedWorkOccurrence(), produced.commitCompanion(), produced.diagnostic());
            assertNull(publicCopy.storageVerifiedOutput(), "Successful public result validation cannot assert processor ownership");
            owned.managedDocument(ROOT).document().name("mutated caller copy");
            assertArrayEquals(cold.encode(ordinary), fast.encode(owned));
        }
    }

    private static ManagedDocumentSnapshot record(Node node) {
        return new ManagedDocumentSnapshot(ROOT, id(new Node().value(0)), node, false, false, true, 0, 0);
    }
    private static final class AliasNode extends Node {
        private final Node alias;
        AliasNode(Node alias) { this.alias = alias; }
        @Override public Node clone() { return alias; }
    }
    private static final class SelfNode extends Node {
        @Override public Node clone() { return this; }
    }
    private static final class MutableSchema extends Schema { int extra; }
    private static final class MutableNumber extends Number {
        long value;
        @Override public int intValue() { return (int) value; }
        @Override public long longValue() { return value; }
        @Override public float floatValue() { return value; }
        @Override public double doubleValue() { return value; }
    }
    private static final class MutableDecimal extends BigDecimal {
        int extra;
        MutableDecimal() { super("1.25"); }
    }
}
