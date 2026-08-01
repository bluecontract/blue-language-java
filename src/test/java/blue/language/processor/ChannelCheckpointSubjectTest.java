package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.TestEventChannel;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static blue.language.processor.model.ProcessorTestTypeBlueIds.TEST_EVENT;
import static blue.language.processor.model.ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ChannelCheckpointSubjectTest {

    @Test
    void shouldStoreFirstInlineSequenceSubjectWithoutSnapshotMaterialization() {
        // given
        Node first = event("first", 10);
        CheckpointScenario scenario = CheckpointScenario.create(first);

        // when
        scenario.deliver(first);
        CheckpointObservation observation = scenario.observe();

        // then
        assertCheckpoint(observation, 10);
        assertEquals(
                Collections.singletonList(null),
                observation.previousSubjectBlueIds);
        assertEquals(
                Collections.emptyList(),
                observation.secondReadSequences);
        assertEquals(0, observation.watchedMaterializations);
    }

    @Test
    void shouldKeepCheckpointWhenInlineSequenceIsLowerOrDuplicate() {
        // given
        Node first = event("first", 10);
        CheckpointScenario scenario = CheckpointScenario.create(first);
        scenario.deliver(first);
        scenario.resetObservations();
        String firstSubjectBlueId =
                DirectBlueIdCalculator.calculateBlueId(subject(10));

        // when
        scenario.deliver(event("lower", 9));
        scenario.deliver(event("duplicate", 10));
        CheckpointObservation observation = scenario.observe();

        // then
        assertCheckpoint(observation, 10);
        assertEquals(
                Arrays.asList(
                        firstSubjectBlueId,
                        firstSubjectBlueId),
                observation.previousSubjectBlueIds);
        assertEquals(
                Arrays.asList(BigInteger.TEN, BigInteger.TEN),
                observation.secondReadSequences);
        assertEquals(0, observation.watchedMaterializations);
    }

    @Test
    void shouldAdvanceCheckpointWhenInlineSequenceIsHigher() {
        // given
        Node first = event("first", 10);
        CheckpointScenario scenario = CheckpointScenario.create(first);
        scenario.deliver(first);
        scenario.resetObservations();
        String firstSubjectBlueId =
                DirectBlueIdCalculator.calculateBlueId(subject(10));

        // when
        scenario.deliver(event("higher", 11));
        CheckpointObservation observation = scenario.observe();

        // then
        assertCheckpoint(observation, 11);
        assertEquals(
                Collections.singletonList(firstSubjectBlueId),
                observation.previousSubjectBlueIds);
        assertEquals(
                Collections.singletonList(BigInteger.TEN),
                observation.secondReadSequences);
        assertEquals(0, observation.watchedMaterializations);
    }

    private static void assertCheckpoint(
            CheckpointObservation observation,
            long expected) {
        assertEquals(
                BigInteger.valueOf(expected),
                observation.storedSequence);
        assertFalse(observation.storedReferenceOnly);
        assertEquals(
                observation.calculatedStoredBlueId,
                observation.storedSubjectBlueId);
    }

    private static ContractBundle refreshBundle(
            ProcessorInvocationState execution) {
        execution.preflightScope("/");
        return execution.bundleForScope("/");
    }

    private static ProcessorInvocationState execution(
            DocumentProcessor owner,
            Node document,
            Node bindingEvent) {
        Node channel = document.getContracts()
                .getProperties().get(
                        "timeline");
        String contributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        channel);
        String subjectBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        subject(sequence(
                                bindingEvent)
                                .longValue()));
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder(
                                "/",
                                "timeline")
                        .sourceContribution(
                                contributionBlueId)
                        .effectiveTypeBlueId(
                                TEST_EVENT_CHANNEL)
                        .subscriptionKey(
                                TEST_EVENT)
                        .checkpointDomainBlueId(
                                CheckpointDomain.derive(
                                        TEST_EVENT_CHANNEL,
                                        Collections.singletonList(
                                                contributionBlueId),
                                        "inline-sequence"))
                        .checkpointSubjectBlueId(
                                subjectBlueId)
                        .build();
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(
                                DirectBlueIdCalculator.calculateBlueId(
                                        document),
                                DirectBlueIdCalculator.calculateBlueId(
                                        bindingEvent))
                        .revisions(0L, 0L)
                        .runtimeRegistryIdentity(
                                owner.runtimeRegistryIdentity())
                        .eventOrderKey(
                                ExternalOrderKey.of(
                                        Collections.<Object>singletonList(
                                                "feeder-order-is-not-newness")))
                        .delivery(delivery)
                        .build();
        return new ProcessorInvocationState(
                owner,
                document.clone(),
                bindingEvent,
                evidence);
    }

    private static Node event(
            String eventId,
            long sequence) {
        return new TestEvent()
                .eventId(eventId)
                .toNode()
                .properties(
                        "sequence",
                        new Node().value(
                                BigInteger.valueOf(
                                        sequence)));
    }

    private static Node subject(long sequence) {
        return new Node().properties(
                "sequence",
                new Node().value(
                        BigInteger.valueOf(
                                sequence)));
    }

    private static BigInteger sequence(
            Node node) {
        return (BigInteger) node.get(
                "/sequence");
    }

    private static final class CheckpointScenario {
        private final InlineSequenceChannelProcessor channelProcessor;
        private final TrackingSnapshotManager snapshots;
        private final ProcessorInvocationState execution;
        private final ChannelRunner runner;
        private ContractBundle bundle;
        private ContractBundle.ChannelBinding channel;

        private CheckpointScenario(
                InlineSequenceChannelProcessor channelProcessor,
                TrackingSnapshotManager snapshots,
                ProcessorInvocationState execution,
                ChannelRunner runner,
                ContractBundle bundle,
                ContractBundle.ChannelBinding channel) {
            this.channelProcessor = channelProcessor;
            this.snapshots = snapshots;
            this.execution = execution;
            this.runner = runner;
            this.bundle = bundle;
            this.channel = channel;
        }

        private static CheckpointScenario create(Node firstEvent) {
            Blue language = ProcessorTestSupport.blue();
            TrackingSnapshotManager snapshots =
                    new TrackingSnapshotManager(
                            language.getDocumentProcessor()
                                    .snapshotManager());
            InlineSequenceChannelProcessor channelProcessor =
                    new InlineSequenceChannelProcessor();
            DocumentProcessor owner = DocumentProcessor.builder()
                    .registerContractProcessor(channelProcessor)
                    .withMatchingService(
                            new ContractMatchingService(language))
                    .withSnapshotManager(snapshots)
                    .build();
            Node document = new Node().contracts(
                    new Node().properties(
                            "timeline",
                            new Node().type(
                                    new Node().blueId(
                                            TEST_EVENT_CHANNEL))));
            for (Node watched : Arrays.asList(
                    subject(9),
                    subject(10),
                    subject(11))) {
                snapshots.watch(
                        DirectBlueIdCalculator.calculateBlueId(watched));
            }
            ProcessorInvocationState execution =
                    execution(owner, document, firstEvent);
            execution.preflightScope("/");
            ContractBundle bundle = execution.bundleForScope("/");
            CheckpointManager checkpointManager =
                    new CheckpointManager(
                            execution.runtime(),
                            ProcessorEngine::canonicalSignature);
            ChannelRunner runner =
                    new ChannelRunner(
                            owner,
                            execution,
                            execution.runtime(),
                            checkpointManager);
            return new CheckpointScenario(
                    channelProcessor,
                    snapshots,
                    execution,
                    runner,
                    bundle,
                    bundle.channelBinding("timeline"));
        }

        private void deliver(Node event) {
            runner.runExternalChannel("/", bundle, channel, event);
            runner.persistPendingCheckpoints("/");
            bundle = refreshBundle(execution);
            channel = bundle.channelBinding("timeline");
        }

        private void resetObservations() {
            channelProcessor.previousSubjectBlueIds.clear();
            channelProcessor.secondReadSequences.clear();
            snapshots.watchedMaterializations = 0;
        }

        private CheckpointObservation observe() {
            ChannelEventCheckpoint checkpoint =
                    (ChannelEventCheckpoint) bundle.marker("checkpoint");
            Node stored = checkpoint.entry("timeline").getSubject();
            return new CheckpointObservation(
                    sequence(stored),
                    stored.isReferenceOnly(),
                    DirectBlueIdCalculator.calculateBlueId(stored),
                    checkpoint.entry("timeline").subjectBlueId(),
                    channelProcessor.previousSubjectBlueIds,
                    channelProcessor.secondReadSequences,
                    snapshots.watchedMaterializations);
        }
    }

    private static final class CheckpointObservation {
        private final BigInteger storedSequence;
        private final boolean storedReferenceOnly;
        private final String calculatedStoredBlueId;
        private final String storedSubjectBlueId;
        private final List<String> previousSubjectBlueIds;
        private final List<BigInteger> secondReadSequences;
        private final int watchedMaterializations;

        private CheckpointObservation(
                BigInteger storedSequence,
                boolean storedReferenceOnly,
                String calculatedStoredBlueId,
                String storedSubjectBlueId,
                List<String> previousSubjectBlueIds,
                List<BigInteger> secondReadSequences,
                int watchedMaterializations) {
            this.storedSequence = storedSequence;
            this.storedReferenceOnly = storedReferenceOnly;
            this.calculatedStoredBlueId = calculatedStoredBlueId;
            this.storedSubjectBlueId = storedSubjectBlueId;
            this.previousSubjectBlueIds =
                    new ArrayList<>(previousSubjectBlueIds);
            this.secondReadSequences =
                    new ArrayList<>(secondReadSequences);
            this.watchedMaterializations = watchedMaterializations;
        }
    }

    private static final class InlineSequenceChannelProcessor
            implements ChannelProcessor<TestEventChannel> {

        private final List<String> previousSubjectBlueIds =
                new ArrayList<>();
        private final List<BigInteger> secondReadSequences =
                new ArrayList<>();
        private final ExternalChannelSubscriptionFunctions<
                TestEventChannel> subscriptionFunctions =
                new ExternalChannelSubscriptionFunctions<
                        TestEventChannel>() {
                    @Override
                    public List<String> channelKeys(
                            TestEventChannel contract) {
                        return Collections.singletonList(
                                TEST_EVENT);
                    }

                    @Override
                    public List<String> eventKeys(
                            Node exactEvent) {
                        return Collections.singletonList(
                                TEST_EVENT);
                    }

                    @Override
                    public Node checkpointSubject(
                            TestEventChannel contract,
                            Node exactEvent,
                            Node exactPayload) {
                        return subject(
                                sequence(exactEvent)
                                        .longValue());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            TestEventChannel contract) {
                        return "inline-sequence";
                    }
                };

        @Override
        public Class<TestEventChannel> contractType() {
            return TestEventChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                TestEventChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
        }

        @Override
        public boolean isNewerEvent(
                TestEventChannel contract,
                ChannelCheckpointContext context) {
            previousSubjectBlueIds.add(
                    context.lastEventSignature());
            Node current = context.currentSubject();
            assertNotNull(current);
            assertFalse(current.getProperties()
                    .containsKey("eventId"));
            BigInteger currentSequence =
                    sequence(current);
            current.properties(
                    "sequence",
                    new Node().value(
                            BigInteger.valueOf(-1L)));
            assertEquals(
                    currentSequence,
                    sequence(
                            context.currentSubject()));
            Node previous = context.lastEvent();
            if (previous == null) {
                return true;
            }
            BigInteger previousSequence =
                    sequence(previous);
            previous.properties(
                    "sequence",
                    new Node().value(
                            BigInteger.valueOf(-1L)));
            BigInteger secondRead =
                    sequence(
                            context.lastEvent());
            secondReadSequences.add(
                    secondRead);
            assertEquals(
                    previousSequence,
                    secondRead);
            return currentSequence
                    .compareTo(
                            secondRead) > 0;
        }
    }

    private static final class TrackingSnapshotManager
            implements ProcessingSnapshotManager {

        private final ProcessingSnapshotManager delegate;
        private final Set<String> watchedBlueIds =
                new LinkedHashSet<>();
        private int watchedMaterializations;

        private TrackingSnapshotManager(
                ProcessingSnapshotManager delegate) {
            this.delegate = delegate;
        }

        private void watch(String blueId) {
            watchedBlueIds.add(blueId);
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            return delegate.fromDocument(
                    document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(
                Node document) {
            return delegate.fromDocumentTransient(
                    document);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return delegate.fromDocumentPreservingPaths(
                    document,
                    preservedPaths);
        }

        @Override
        public ResolvedSnapshot
        fromDocumentTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return delegate
                    .fromDocumentTransientPreservingPaths(
                            document,
                            preservedPaths);
        }

        @Override
        public FrozenNode materializeVerifiedReference(
                FrozenNode reference) {
            return delegate.materializeVerifiedReference(
                    reference);
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            if (reference.isReferenceOnly()
                    && watchedBlueIds.contains(
                    reference.getReferenceBlueId())) {
                watchedMaterializations++;
            }
            return delegate
                    .materializeVerifiedExactReference(
                            reference);
        }

        @Override
        public boolean supportsIncrementalValueResolution() {
            return delegate
                    .supportsIncrementalValueResolution();
        }

        @Override
        public boolean supportsIncrementalValueResolution(
                IncrementalValueResolutionRequest request) {
            return delegate
                    .supportsIncrementalValueResolution(
                            request);
        }

        @Override
        public ConformanceEngine transientConformanceEngine(
                ConformanceEngine conformanceEngine) {
            return delegate.transientConformanceEngine(
                    conformanceEngine);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return delegate.applyPatch(
                    snapshot,
                    patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(
                ResolvedSnapshot snapshot) {
            return delegate.cacheSnapshot(
                    snapshot);
        }
    }
}
