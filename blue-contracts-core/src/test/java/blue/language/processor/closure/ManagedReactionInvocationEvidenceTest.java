package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;
import static org.junit.jupiter.api.Assertions.*;

/** Data/identity tests; actual source FIFO and occurrence eligibility are interpreter obligations. */
class ManagedReactionInvocationEvidenceTest {
    private static final DocumentId A = new DocumentId("consumer"), B = new DocumentId("source");

    @Test
    void reactionContextIsSeparateFromProvenanceAndSurvivesEveryInputCopy() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureInvocationInput base = input(owner);
            ManagedReactionContext context = context(base, 20);
            ClosureInvocationInput placed = base.withManagedReaction(context);
            assertSame(base.cause(), placed.cause());
            assertFalse(base.managedReaction().isPresent());
            assertSame(context, placed.managedReaction().get());
            assertNotEquals(base.invocationIdentity(), placed.invocationIdentity());
            assertEquals(base.invocationIdentity(), ClosureIdentityService.INSTANCE.identity(
                    ClosureIdentityService.Constructor.INVOCATION,
                    ClosureIdentityService.INSTANCE.invocationIdentityConstructorValue(base)),
                    "Ordinary invocation identities retain their existing closed constructor");
            assertEquals(placed.invocationIdentity(), placed.withManagedReaction(context).invocationIdentity());
            assertNotEquals(placed.invocationIdentity(), base.withManagedReaction(context(base, 21)).invocationIdentity());
            Map<DocumentId, String> predecessors = Collections.singletonMap(A, hash(30));
            ClosureInvocationInput first = ClosureEvidenceFactory.withSemanticPredecessors(placed, predecessors);
            ClosureInvocationInput second = ClosureEvidenceFactory.withSemanticPredecessors(base, predecessors).withManagedReaction(context);
            assertEquals(first.invocationIdentity(), second.invocationIdentity());
            assertEquals(predecessors, first.semanticPredecessors());
            assertSame(context, first.managedReaction().get());
            assertSame(context, first.withInvocationIdentity(hash(31)).managedReaction().get());
            assertEquals(first.semanticPredecessors(), first.withInvocationIdentity(hash(31)).semanticPredecessors());
        }
    }

    @Test
    void reactionPlacementRequiresExternalProcessingAndExactEndpoints() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureInvocationInput base = input(owner);
            AdmissionCause cause = ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                    "test", null, null, "FULL_HISTORY");
            ClosureInvocationInput admission = ClosureEvidenceFactory.admitClosure(base.snapshot(), cause, null,
                    base.executionPolicy(), base.environment());
            assertThrows(IllegalArgumentException.class, () -> admission.withManagedReaction(context(base, 20)));
            ManagedReactionContext foreign = new ManagedReactionContext(hash(10), hash(11), hash(12), cut(base), hash(20),
                    Collections.singletonList(new ManagedReactionContext.DueOccurrence(hash(13), A,
                            new DocumentId("absent"), hash(14), hash(15))));
            assertThrows(IllegalArgumentException.class, () -> base.withManagedReaction(foreign));
        }
    }

    @Test
    void coldContextPreservesCutAndPositionAndSharesTheEnclosingFragmentBudget() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ManagedReactionContext context = context(input(owner), 20);
            Map<String, byte[]> store = new LinkedHashMap<>();
            String key = ManagedReactionContextCodec.encode(context, store::put, Limits.defaults());
            ManagedReactionContext restored = ManagedReactionContextCodec.decode(key, store::get, Limits.defaults());
            assertNotSame(context, restored);
            assertEquals(context.identity(), restored.identity());
            assertEquals(context.activationCut(), restored.activationCut());
            assertEquals(context.reactionOriginIdentity(), restored.reactionOriginIdentity());
            assertEquals(context.dueOccurrences().get(0).expectedLanePositionIdentity(),
                    restored.dueOccurrences().get(0).expectedLanePositionIdentity());
            assertEquals(key, ManagedReactionContextCodec.encode(restored, (id, bytes) -> {}, Limits.defaults()));
            long exactBytes = store.values().stream().mapToLong(bytes -> bytes.length).sum();
            Limits exact = new Limits(1024 * 1024, exactBytes, 64, store.size());
            assertNotNull(ManagedReactionContextCodec.decode(key, store::get, exact));
            Encoder encoder = new Encoder((id, bytes) -> {}, exact);
            encoder.blob(new byte[] {1});
            assertThrows(CapacityExceeded.class, () -> ManagedReactionContextCodec.encode(context, encoder));
            ObjectNode changed = (ObjectNode) json(store.get(key));
            changed.put("sourcePosition", hash(21));
            byte[] encoded = bytes(changed); String changedKey = digest(encoded); store.put(changedKey, encoded);
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> ManagedReactionContextCodec.decode(changedKey, store::get, Limits.defaults()));
        }
    }

    @Test
    void sourceProgramRetainsItsProducingReactionPositionThroughColdRead() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureInvocationInput base = input(owner);
            ManagedReactionContext context = context(base, 20);
            List<SourceObservationProgram.SourceState> states = new ArrayList<>();
            for (ManagedDocumentSnapshot document : base.snapshot().managedDocuments())
                states.add(new SourceObservationProgram.SourceState(document.documentId(), document.blueId(), document.epoch(),
                        document.initialized(), FrozenNode.fromResolvedNode(document.document())));
            SourceObservationProgram program = new SourceObservationProgram(base.withManagedReaction(context).invocationIdentity(),
                    base.cause().kind(), base.cause().causeIdentity(), (ExternalEventCause) base.cause(), base.environment(),
                    base.executionPolicy(), states, states, Collections.singleton(A), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), context);
            Map<String, byte[]> store = new LinkedHashMap<>();
            String key = SourceObservationProgramCodec.encode(program, store::put, Limits.defaults());
            SourceObservationProgram restored = SourceObservationProgramCodec.decode(key, store::get, Limits.defaults());
            assertEquals(program.causeIdentity(), restored.causeIdentity());
            assertEquals(context.identity(), restored.managedReaction().get().identity());
            assertEquals(context.sourceReactionPositionIdentity(), restored.managedReaction().get().sourceReactionPositionIdentity());
            assertEquals(key, SourceObservationProgramCodec.encode(restored, (id, bytes) -> {}, Limits.defaults()));
        }
    }

    private static ManagedReactionContext context(ClosureInvocationInput input, int position) {
        return new ManagedReactionContext(hash(10), hash(11), hash(12), cut(input), hash(position),
                Collections.singletonList(new ManagedReactionContext.DueOccurrence(hash(13), A, B, hash(14), hash(15))));
    }
    private static ExternalOrderKey cut(ClosureInvocationInput input) {
        return ExternalOrderKey.of(Arrays.asList(100L, ((ExternalEventCause) input.cause()).eventBlueId()));
    }
    private static ClosureInvocationInput input(DocumentProcessor owner) {
        ClosureEnvironment environment = ClosureEvidenceFactory.environment(owner, hash(1), hash(2),
                "lineage", "binding", "provider", "micros", "limits", GasSchedule.contracts10().portableLimits());
        ManagedDocumentSnapshot a = document(A), b = document(B);
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(0, Arrays.asList(a, b), Collections.emptyList(),
                Arrays.asList(ClosureEvidenceFactory.acyclicComponent(a), ClosureEvidenceFactory.acyclicComponent(b)), Collections.singletonList(A));
        Node event = new Node().name("source E10"); String eventId = DirectBlueIdCalculator.calculateBlueId(event);
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, eventId,
                ExternalOrderKey.of(Arrays.asList(10L, eventId)), environment.externalOrderPolicyIdentity());
        return ClosureEvidenceFactory.processClosure(snapshot, cause, Collections.emptyList(),
                ClosureEvidenceFactory.executionPolicy(100000L, Collections.emptyMap(), "policy"), environment);
    }
    private static ManagedDocumentSnapshot document(DocumentId id) {
        Node body = new Node().name(id.value());
        return new ManagedDocumentSnapshot(id, DirectBlueIdCalculator.calculateBlueId(body), body, true, false, id.equals(A), 1, 0);
    }
    private static String hash(int number) { return "sha256:" + String.format(Locale.ROOT, "%064x", number); }
}
