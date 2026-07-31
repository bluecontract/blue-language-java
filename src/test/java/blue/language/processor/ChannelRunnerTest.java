package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.NormalizingTestEventChannelProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.utils.BlueIdCalculator;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies checkpoint behaviour for the {@link ChannelRunner} in isolation.
 */
final class ChannelRunnerTest {

    @Test
    void shouldMergeSourceCheckpointsFromDifferentStaleBundles() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(
                new IncrementPropertyContractProcessor());
        String yaml = "contracts:\n"
                + "  zSource:\n"
                + "    type:\n"
                + "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n"
                + "  aSource:\n"
                + "    type:\n"
                + "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n"
                + "  incrementZ:\n"
                + "    channel: zSource\n"
                + "    type:\n"
                + "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n"
                + "    propertyKey: /zCount\n"
                + "  incrementA:\n"
                + "    channel: aSource\n"
                + "    type:\n"
                + "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n"
                + "    propertyKey: /aCount\n";
        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = execution(
                owner,
                document,
                Arrays.asList("zSource", "aSource"));
        execution.preflightScope("/");
        ContractBundle zBundle = execution.bundleForScope("/");
        CheckpointManager checkpointManager =
                new CheckpointManager(
                        execution.runtime(),
                        ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(
                owner,
                execution,
                execution.runtime(),
                checkpointManager);
        Node event = blue.objectToNode(
                new TestEvent()
                        .eventId("coalesced")
                        .kind("direct"));

        // when
        runner.runExternalChannel(
                "/",
                zBundle,
                zBundle.channelBinding("zSource"),
                event);
        execution.preflightScope("/");
        ContractBundle aBundle = execution.bundleForScope("/");
        runner.runExternalChannel(
                "/",
                aBundle,
                aBundle.channelBinding("aSource"),
                event);
        runner.persistPendingCheckpoints("/");
        Node entries = execution.runtime().document().getAsNode(
                "/contracts/checkpoint/entries");

        // then
        assertNotNull(entries.getProperties().get("zSource"));
        assertNotNull(entries.getProperties().get("aSource"));
        assertEquals(
                Arrays.asList("aSource", "zSource"),
                new ArrayList<>(entries.getProperties().keySet()));
        assertNull(entries.getProperties().get("incrementZ"));
        assertNull(entries.getProperties().get("incrementA"));
    }

    @Test
    void shouldDiscardTentativeCheckpointAfterDeliveryFailure() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(
                new IncrementPropertyContractProcessor());
        String yaml = "contracts:\n"
                + "  testChannel:\n"
                + "    type:\n"
                + "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n"
                + "  increment:\n"
                + "    channel: testChannel\n"
                + "    type:\n"
                + "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n"
                + "    propertyKey: /counter\n";
        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = execution(owner, document);
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");
        ChannelRunner runner = new ChannelRunner(
                owner,
                execution,
                execution.runtime(),
                new CheckpointManager(
                        execution.runtime(),
                        ProcessorEngine::canonicalSignature));
        Node event = blue.objectToNode(
                new TestEvent()
                        .eventId("will-fail")
                        .kind("direct"));
        runner.runExternalChannel(
                "/",
                bundle,
                bundle.channelBinding("testChannel"),
                event);

        // when
        execution.fail(
                ProcessorStatus.RUNTIME_FATAL,
                ProcessorDiagnostic.of(
                        ProcessorErrorCategory.RuntimeExecutionFailure,
                        "forced failure after pending source"));
        runner.persistAllPendingCheckpoints();

        // then
        assertNull(ProcessorEngine.nodeAt(
                execution.runtime().document(),
                "/contracts/checkpoint"));
    }

    @Test
    void shouldSkipDuplicateEventsAndProcessNewEventsUsingCheckpoint() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  increment:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "    propertyKey: /counter\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = execution(owner, document);
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        List<ContractBundle.ChannelBinding> bindings = bundle.channelsOfType(ChannelContract.class);
        ContractBundle.ChannelBinding channelBinding = bindings.get(0);

        Node event = blue.objectToNode(new TestEvent().eventId("evt-1").kind("original"));
        Node secondEvent = blue.objectToNode(new TestEvent().eventId("evt-2").kind("original"));

        // when
        runner.runExternalChannel("/", bundle, channelBinding, event);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");
        Node counterNode = execution.runtime().document().getProperties().get("counter");
        BigInteger afterFirstEvent =
                counterNode != null
                        ? (BigInteger) counterNode.getValue()
                        : null;
        Object checkpointAfterFirstEvent =
                bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT);

        runner.runExternalChannel("/", bundle, channelBinding, event);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");
        BigInteger afterDuplicate = (BigInteger) execution.runtime().document().getProperties().get("counter").getValue();

        runner.runExternalChannel("/", bundle, channelBinding, secondEvent);
        runner.persistPendingCheckpoints("/");
        BigInteger afterNewEvent = (BigInteger) execution.runtime().document().getProperties().get("counter").getValue();

        // then
        assertNotNull(afterFirstEvent);
        assertEquals(BigInteger.ONE, afterFirstEvent);
        assertNotNull(checkpointAfterFirstEvent);
        assertEquals(BigInteger.ONE, afterDuplicate);
        assertEquals(new BigInteger("2"), afterNewEvent);
    }

    @Test
    void shouldTreatDifferentContentWithSameEventIdAsNewByDefault() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  increment:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "    propertyKey: /counter\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = execution(owner, document);
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        ContractBundle.ChannelBinding channelBinding = bundle.channelsOfType(ChannelContract.class).get(0);

        Node first = blue.objectToNode(new TestEvent().eventId("evt-1").kind("original"));
        Node sameIdDifferentPayload = blue.objectToNode(new TestEvent().eventId("evt-1").kind("mutated"));
        Node newId = blue.objectToNode(new TestEvent().eventId("evt-2").kind("mutated"));

        // when
        runner.runExternalChannel("/", bundle, channelBinding, first);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");
        runner.runExternalChannel("/", bundle, channelBinding, sameIdDifferentPayload);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");
        runner.runExternalChannel("/", bundle, channelBinding, sameIdDifferentPayload);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");
        runner.runExternalChannel("/", bundle, channelBinding, newId);
        runner.persistPendingCheckpoints("/");
        Node counterNode = execution.runtime().document().getProperties().get("counter");

        // then
        assertNotNull(counterNode);
        assertEquals(new BigInteger("3"), counterNode.getValue());
    }

    @Test
    void shouldSkipDuplicateEventsByCanonicalPayloadWhenNoEventIdPresent() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  increment:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "    propertyKey: /counter\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = execution(owner, document);
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        ContractBundle.ChannelBinding channelBinding = bundle.channelsOfType(ChannelContract.class).get(0);

        Node first = blue.objectToNode(new TestEvent().kind("original"));
        Node duplicate = blue.objectToNode(new TestEvent().kind("original"));
        Node different = blue.objectToNode(new TestEvent().kind("other"));

        // when
        runner.runExternalChannel("/", bundle, channelBinding, first);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");
        runner.runExternalChannel("/", bundle, channelBinding, duplicate);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");
        runner.runExternalChannel("/", bundle, channelBinding, different);
        runner.persistPendingCheckpoints("/");
        Node counterNode = execution.runtime().document().getProperties().get("counter");

        // then
        assertNotNull(counterNode);
        assertEquals(new BigInteger("2"), counterNode.getValue());
    }

    @Test
    void shouldDeliverChannelizedEventToHandlersAndStoreOriginalEventInCheckpoint() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new NormalizingTestEventChannelProcessor());
        blue.registerContractProcessor(new SetPropertyOnEventContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  setFlag:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY_ON_EVENT + "\n" +
                "    expectedKind: " + NormalizingTestEventChannelProcessor.NORMALIZED_KIND + "\n" +
                "    propertyKey: /flag\n" +
                "    propertyValue: 7\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = execution(owner, document);
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");
        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);
        ContractBundle.ChannelBinding channelBinding = bundle.channelsOfType(ChannelContract.class).get(0);
        Node event = blue.objectToNode(new TestEvent().eventId("evt-1").kind("original"));
        Object checkpointBeforeEvent =
                bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT);

        // when
        runner.runExternalChannel("/", bundle, channelBinding, event);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        Node flagNode = execution.runtime().document().getProperties().get("flag");
        ChannelEventCheckpoint checkpoint = (ChannelEventCheckpoint) bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT);
        Node storedSubject = checkpoint.entry(channelBinding.key()).getSubject();

        // then
        assertNull(checkpointBeforeEvent);
        assertNotNull(flagNode);
        assertEquals(7, ((Number) flagNode.getValue()).intValue());
        assertNotNull(checkpoint);
        assertNotNull(storedSubject);
        assertEquals(BlueIdCalculator.calculateBlueId(event),
                storedSubject.getBlueId());
    }

    @Test
    void shouldVerifyDuplicateSignatureForChannelizedEventsUsesOriginalExternalEvent() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new NormalizingTestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  increment:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "    propertyKey: /counter\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = execution(owner, document);
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        ContractBundle.ChannelBinding channelBinding = bundle.channelsOfType(ChannelContract.class).get(0);
        Node first = blue.objectToNode(new TestEvent().kind("first"));
        Node second = blue.objectToNode(new TestEvent().kind("second"));

        // when
        runner.runExternalChannel("/", bundle, channelBinding, first);
        runner.persistPendingCheckpoints("/");
        runner.runExternalChannel("/", bundle, channelBinding, second);
        runner.persistPendingCheckpoints("/");
        Node counterNode = execution.runtime().document().getProperties().get("counter");

        // then
        assertNotNull(counterNode);
        assertEquals(new BigInteger("2"), counterNode.getValue());
    }

    private static ContractBundle refreshBundle(
            ProcessorEngine.Execution execution) {
        execution.preflightScope("/");
        return execution.bundleForScope("/");
    }

    private static ProcessorEngine.Execution execution(
            DocumentProcessor owner,
            Node document) {
        return execution(
                owner,
                document,
                Collections.singletonList("testChannel"));
    }

    private static ProcessorEngine.Execution execution(
            DocumentProcessor owner,
            Node document,
            List<String> channelKeys) {
        Node bindingEvent = new TestEvent()
                .eventId("runner-binding")
                .toNode();
        VerifiedExecutionEvidence.Builder evidence =
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
                                                "runner")));
        for (String channelKey : channelKeys) {
            Node channel = document.getContracts()
                    .getProperties().get(channelKey);
            String contributionBlueId =
                    BlueIdCalculator.calculateBlueId(channel);
            String effectiveTypeBlueId =
                    channel.getType().getBlueId();
            evidence.delivery(
                    ExternalDeliverySnapshot.builder("/", channelKey)
                        .sourceContribution(contributionBlueId)
                        .effectiveTypeBlueId(effectiveTypeBlueId)
                        .subscriptionKey(
                                bindingEvent.getType().getBlueId())
                        .checkpointDomainBlueId(
                                CheckpointDomain.derive(
                                        effectiveTypeBlueId,
                                        Collections.singletonList(
                                                contributionBlueId),
                                        null))
                        .checkpointSubjectBlueId(
                                BlueIdCalculator.calculateBlueId(
                                        bindingEvent))
                        .build());
        }
        return new ProcessorEngine.Execution(
                owner,
                document.clone(),
                bindingEvent,
                evidence.build());
    }
}
