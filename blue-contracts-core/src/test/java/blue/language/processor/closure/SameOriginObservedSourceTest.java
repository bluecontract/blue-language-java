package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Structural chain tests, not a claim that synthetic transport programs prove execution. */
class SameOriginObservedSourceTest {
    private static final DocumentId CONSUMER = new DocumentId("consumer"), OTHER = new DocumentId("other"), SOURCE = new DocumentId("source");

    @Test void repeatedFailuresKeepTheSameSuccessfulPinNotTheLatestOfferedPredecessor() {
        try (Data data = new Data()) {
            SourceObservationGap first = data.gap(CONSUMER, data.state(0), data.program(0, 1));
            SourceObservationGap second = data.gap(CONSUMER, data.state(0), data.program(1, 2));
            SameOriginOperationResult.ObservedSource observed = select(data, data.state(2), data.bindings(CONSUMER, 0, true),
                    Collections.singletonMap(CONSUMER, Arrays.asList(first, second)));
            assertEquals(SOURCE, observed.documentId());
            assertEquals(data.state(0).blueId(), observed.blueId());
            assertEquals(0L, observed.epoch());
            assertNotEquals(data.state(2).blueId(), observed.blueId());
        }
    }

    @Test void unrelatedConsumersGapDoesNotChangeThisGroupsObservation() {
        try (Data data = new Data()) {
            SourceObservationGap unrelated = data.gap(OTHER, data.state(0), data.program(0, 1));
            SameOriginOperationResult.ObservedSource observed = select(data, data.state(2), data.bindings(CONSUMER, 2, true),
                    Collections.singletonMap(OTHER, Collections.singletonList(unrelated)));
            assertEquals(data.state(2).blueId(), observed.blueId()); assertEquals(2L, observed.epoch());
        }
    }

    @Test void pendingInitializationAliasDoesNotReplaceTheOriginalExternalObservationPin() {
        try (Data data = new Data()) {
            List<ManagedOccurrenceBinding> bindings = data.bindings(CONSUMER, 2, true);
            bindings.add(ManagedOccurrenceBinding.derived(data.fixture.input.environment().managedBindingPolicyIdentity(), CONSUMER,
                    ScopeAddress.embedded("/new", 2), SOURCE, data.state(0).blueId(), false, 0L));
            SameOriginOperationResult.ObservedSource observed = select(data, data.state(2), bindings, Collections.emptyMap());
            assertEquals(data.state(2).blueId(), observed.blueId()); assertEquals(2L, observed.epoch());
        }
    }

    @Test void wrongOrderMissingLinkChangedSuccessfulPinAndWrongOwnedReferenceAreRejected() {
        try (Data data = new Data()) {
            SourceObservationGap first = data.gap(CONSUMER, data.state(0), data.program(0, 1));
            SourceObservationGap second = data.gap(CONSUMER, data.state(0), data.program(1, 2));
            assertThrows(IllegalArgumentException.class, () -> select(data, data.state(2), data.bindings(CONSUMER, 0, true),
                    Collections.singletonMap(CONSUMER, Arrays.asList(second, first))));
            assertThrows(IllegalArgumentException.class, () -> select(data, data.state(2), data.bindings(CONSUMER, 0, true),
                    Collections.singletonMap(CONSUMER, Collections.singletonList(first))));
            SourceObservationGap changedPin = data.gap(CONSUMER, data.state(1), data.program(1, 2));
            assertThrows(IllegalArgumentException.class, () -> select(data, data.state(2), data.bindings(CONSUMER, 0, true),
                    Collections.singletonMap(CONSUMER, Arrays.asList(first, changedPin))));
            assertThrows(IllegalArgumentException.class, () -> select(data, data.state(2), data.bindings(CONSUMER, 1, true), Collections.emptyMap()));
        }
    }

    @Test void coownedDifferentSuccessfulPinsAreRetainedSeparately() {
        try (Data data = new Data()) {
            SourceObservationGap first = data.gap(CONSUMER, data.state(0), data.program(0, 1));
            SourceObservationGap second = data.gap(CONSUMER, data.state(0), data.program(1, 2));
            SourceObservationGap other = data.gap(OTHER, data.state(1), data.program(1, 2));
            Map<DocumentId, List<SourceObservationGap>> gaps = new HashMap<>();
            gaps.put(CONSUMER, Arrays.asList(first, second)); gaps.put(OTHER, Collections.singletonList(other));
            List<ManagedOccurrenceBinding> bindings = data.bindings(CONSUMER, 0, true); bindings.addAll(data.bindings(OTHER, 1, true));
            Set<DocumentId> owners = new HashSet<>(Arrays.asList(CONSUMER, OTHER));
            List<SameOriginOperationResult.ObservedSource> result = SameOriginOperationResultAssembler.observedSourcesForGroup(
                    owners, data.state(2), bindings, gaps, owners, Collections.emptySet());
            assertEquals(2, result.size());
            assertEquals(CONSUMER, result.get(0).consumerDocumentId()); assertEquals(0L, result.get(0).epoch());
            assertEquals(OTHER, result.get(1).consumerDocumentId()); assertEquals(1L, result.get(1).epoch());
        }
    }

    @Test void mixedGapAndNoGapConsumersKeepTheirOwnSuccessfulPin() {
        try (Data data = new Data()) {
            Map<DocumentId, List<SourceObservationGap>> gaps = Collections.singletonMap(CONSUMER,
                    Collections.singletonList(data.gap(CONSUMER, data.state(0), data.program(0, 1))));
            Set<DocumentId> owners = new HashSet<>(Arrays.asList(CONSUMER, OTHER));
            List<ManagedOccurrenceBinding> bindings = data.bindings(CONSUMER, 0, true); bindings.addAll(data.bindings(OTHER, 1, true));
            List<SameOriginOperationResult.ObservedSource> result = SameOriginOperationResultAssembler.observedSourcesForGroup(
                    owners, data.state(1), bindings, gaps, owners, Collections.emptySet());
            assertEquals(2, result.size());
            assertEquals(data.state(0).blueId(), result.get(0).blueId()); assertEquals(0L, result.get(0).epoch());
            assertEquals(data.state(1).blueId(), result.get(1).blueId()); assertEquals(1L, result.get(1).epoch());
        }
    }

    @Test void actualMembershipAndDurablePriorOccurrenceAreBothRequiredForAGapHeader() {
        try (Data data = new Data()) {
            Set<DocumentId> owners = new HashSet<>(Arrays.asList(CONSUMER, OTHER));
            List<ManagedOccurrenceBinding> bindings = data.bindings(CONSUMER, 1, true); bindings.addAll(data.bindings(OTHER, 1, true));
            List<SameOriginOperationResult.ObservedSource> selected = SameOriginOperationResultAssembler.observedSourcesForGroup(owners,
                    data.state(1), bindings, Collections.emptyMap(), Collections.singleton(CONSUMER), Collections.emptySet());
            assertEquals(1, selected.size()); assertEquals(CONSUMER, selected.get(0).consumerDocumentId());
            assertTrue(SameOriginOperationResultAssembler.observedSourcesForGroup(owners, data.state(1), Collections.emptyList(),
                    Collections.emptyMap(), Collections.singleton(CONSUMER), Collections.emptySet()).isEmpty());
        }
    }

    private static SameOriginOperationResult.ObservedSource select(Data ignored, SourceObservationProgram.SourceState state,
            List<ManagedOccurrenceBinding> bindings, Map<DocumentId, List<SourceObservationGap>> gaps) {
        return SameOriginOperationResultAssembler.observedSourcesForGroup(Collections.singleton(CONSUMER), state, bindings, gaps,
                Collections.singleton(CONSUMER), Collections.emptySet()).get(0);
    }

    private static final class Data implements AutoCloseable {
        final SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture();
        final Map<Integer, SourceObservationProgram.SourceState> states = new HashMap<>();
        SourceObservationProgram.SourceState state(int counter) {
            return states.computeIfAbsent(counter, value -> {
                Node body = fixture.initialization.program().sourceResults().get(0).document().properties("counter", new Node().value(BigInteger.valueOf(value)));
                return new SourceObservationProgram.SourceState(SOURCE, DirectBlueIdCalculator.calculateBlueId(body), value, true,
                        FrozenNode.fromResolvedNode(body));
            });
        }
        SourceObservationProgram program(int before, int after) {
            Node event = new Node().name("Source entry").value(BigInteger.valueOf(after));
            String eventId = DirectBlueIdCalculator.calculateBlueId(event);
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, eventId,
                    blue.language.processor.ExternalOrderKey.of(Arrays.asList((long) after, eventId)),
                    fixture.input.environment().externalOrderPolicyIdentity());
            return new SourceObservationProgram(hash(before * 100 + after), ProcessingCause.Kind.EXTERNAL, cause.causeIdentity(), cause,
                    fixture.input.environment(), fixture.input.executionPolicy(), Collections.singletonList(state(before)),
                    Collections.singletonList(state(after)), Collections.singleton(SOURCE), Collections.emptyList());
        }
        SourceObservationGap gap(DocumentId consumer, SourceObservationProgram.SourceState observed, SourceObservationProgram offered) {
            return SourceObservationGap.fromAuthenticatedManagedFailure(consumer, SOURCE,
                    hash(900 + 100 * (int) offered.sourceResults().get(0).epoch() + (int) observed.epoch()),
                    offered.causeIdentity(), ProcessorStatus.RUNTIME_FATAL, observed.blueId(), observed.epoch(), offered);
        }
        List<ManagedOccurrenceBinding> bindings(DocumentId consumer, int counter, boolean active) {
            return new ArrayList<>(Collections.singletonList(ManagedOccurrenceBinding.derived(fixture.input.environment().managedBindingPolicyIdentity(),
                    consumer, ScopeAddress.embedded("/source", 1), SOURCE, state(counter).blueId(), active, null)));
        }
        @Override public void close() { fixture.close(); }
    }

    private static String hash(int value) { return String.format(java.util.Locale.ROOT, "sha256:%064x", value); }
}
