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
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ChannelCheckpointSubjectTest {

    private static final String CHANNEL_TYPE_BLUE_ID =
            "BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L";
    private static final String EVENT_TYPE_BLUE_ID =
            "Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf";

    @Test
    void inlineSequenceSubjectSurvivesCheckpointAndDrivesStrictNewness() {
        Blue language = ProcessorTestSupport.blue();
        TrackingSnapshotManager snapshots =
                new TrackingSnapshotManager(
                        language.getDocumentProcessor()
                                .snapshotManager());
        InlineSequenceChannelProcessor channelProcessor =
                new InlineSequenceChannelProcessor();
        DocumentProcessor owner = DocumentProcessor.builder()
                .registerContractProcessor(
                        channelProcessor)
                .withMatchingService(
                        new ContractMatchingService(
                                language))
                .withSnapshotManager(
                        snapshots)
                .build();
        Node document = new Node().contracts(
                new Node().properties(
                        "timeline",
                        new Node().type(
                                new Node().blueId(
                                        CHANNEL_TYPE_BLUE_ID))));
        Node first = event("first", 10);
        Node lower = event("lower", 9);
        Node duplicate = event("duplicate", 10);
        Node higher = event("higher", 11);
        for (Node subject : Arrays.asList(
                subject(9),
                subject(10),
                subject(11))) {
            snapshots.watch(
                    BlueIdCalculator.calculateBlueId(
                            subject));
        }

        ProcessorEngine.Execution execution =
                execution(
                        owner,
                        document,
                        first);
        execution.preflightScope("/");
        ContractBundle bundle =
                execution.bundleForScope("/");
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
        ContractBundle.ChannelBinding channel =
                bundle.channelBinding(
                        "timeline");

        runner.runExternalChannel(
                "/", bundle, channel, first);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channel = bundle.channelBinding("timeline");
        assertStoredInlineSequence(
                bundle, 10);

        runner.runExternalChannel(
                "/", bundle, channel, lower);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channel = bundle.channelBinding("timeline");
        assertStoredInlineSequence(
                bundle, 10);

        runner.runExternalChannel(
                "/", bundle, channel, duplicate);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channel = bundle.channelBinding("timeline");
        assertStoredInlineSequence(
                bundle, 10);

        runner.runExternalChannel(
                "/", bundle, channel, higher);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        assertStoredInlineSequence(
                bundle, 11);

        String firstSubjectBlueId =
                BlueIdCalculator.calculateBlueId(
                        subject(10));
        assertEquals(
                Arrays.asList(
                        null,
                        firstSubjectBlueId,
                        firstSubjectBlueId,
                        firstSubjectBlueId),
                channelProcessor
                        .previousSubjectBlueIds);
        assertEquals(
                Arrays.asList(
                        BigInteger.TEN,
                        BigInteger.TEN,
                        BigInteger.TEN),
                channelProcessor
                        .secondReadSequences);
        assertEquals(0,
                snapshots.watchedMaterializations);
    }

    private static void assertStoredInlineSequence(
            ContractBundle bundle,
            long expected) {
        ChannelEventCheckpoint checkpoint =
                (ChannelEventCheckpoint) bundle.marker(
                        "checkpoint");
        assertNotNull(checkpoint);
        Node stored = checkpoint.entry(
                "timeline")
                .getSubject();
        assertNotNull(stored);
        assertFalse(stored.isReferenceOnly());
        assertEquals(
                BigInteger.valueOf(expected),
                stored.get("/sequence"));
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        stored),
                checkpoint.entry(
                        "timeline")
                        .subjectBlueId());
    }

    private static ContractBundle refreshBundle(
            ProcessorEngine.Execution execution) {
        execution.preflightScope("/");
        return execution.bundleForScope("/");
    }

    private static ProcessorEngine.Execution execution(
            DocumentProcessor owner,
            Node document,
            Node bindingEvent) {
        Node channel = document.getContracts()
                .getProperties().get(
                        "timeline");
        String contributionBlueId =
                BlueIdCalculator.calculateBlueId(
                        channel);
        String subjectBlueId =
                BlueIdCalculator.calculateBlueId(
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
                                CHANNEL_TYPE_BLUE_ID)
                        .subscriptionKey(
                                EVENT_TYPE_BLUE_ID)
                        .checkpointDomainBlueId(
                                CheckpointDomain.derive(
                                        CHANNEL_TYPE_BLUE_ID,
                                        Collections.singletonList(
                                                contributionBlueId),
                                        "inline-sequence"))
                        .checkpointSubjectBlueId(
                                subjectBlueId)
                        .build();
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder(
                                BlueIdCalculator.calculateBlueId(
                                        document),
                                BlueIdCalculator.calculateBlueId(
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
        return new ProcessorEngine.Execution(
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
                                EVENT_TYPE_BLUE_ID);
                    }

                    @Override
                    public List<String> eventKeys(
                            Node exactEvent) {
                        return Collections.singletonList(
                                EVENT_TYPE_BLUE_ID);
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
