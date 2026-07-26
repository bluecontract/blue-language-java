package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.NormalizingTestEventChannelProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.utils.BlueIdCalculator;
import java.math.BigInteger;
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
    void skipsDuplicateEventsUsingCheckpoint() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  increment:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "    propertyKey: /counter\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document.clone());
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        List<ContractBundle.ChannelBinding> bindings = bundle.channelsOfType(ChannelContract.class);
        ContractBundle.ChannelBinding channelBinding = bindings.get(0);

        Node event = blue.objectToNode(new TestEvent().eventId("evt-1").kind("original"));

        runner.runExternalChannel("/", bundle, channelBinding, event);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");

        Node counterNode = execution.runtime().document().getProperties().get("counter");
        assertNotNull(counterNode);
        assertEquals(BigInteger.ONE, counterNode.getValue());
        assertNotNull(bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT));

        runner.runExternalChannel("/", bundle, channelBinding, event);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);
        channelBinding = bundle.channelBinding("testChannel");
        BigInteger afterDuplicate = (BigInteger) execution.runtime().document().getProperties().get("counter").getValue();
        assertEquals(BigInteger.ONE, afterDuplicate);

        Node secondEvent = blue.objectToNode(new TestEvent().eventId("evt-2").kind("original"));
        runner.runExternalChannel("/", bundle, channelBinding, secondEvent);
        runner.persistPendingCheckpoints("/");
        BigInteger afterNewEvent = (BigInteger) execution.runtime().document().getProperties().get("counter").getValue();
        assertEquals(new BigInteger("2"), afterNewEvent);
    }

    @Test
    void treatsDifferentContentWithSameEventIdAsNewByDefault() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  increment:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "    propertyKey: /counter\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document.clone());
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        ContractBundle.ChannelBinding channelBinding = bundle.channelsOfType(ChannelContract.class).get(0);

        Node first = blue.objectToNode(new TestEvent().eventId("evt-1").kind("original"));
        Node sameIdDifferentPayload = blue.objectToNode(new TestEvent().eventId("evt-1").kind("mutated"));
        Node newId = blue.objectToNode(new TestEvent().eventId("evt-2").kind("mutated"));

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
        assertNotNull(counterNode);
        assertEquals(new BigInteger("3"), counterNode.getValue());
    }

    @Test
    void skipsDuplicateEventsByCanonicalPayloadWhenNoEventIdPresent() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  increment:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "    propertyKey: /counter\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document.clone());
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        ContractBundle.ChannelBinding channelBinding = bundle.channelsOfType(ChannelContract.class).get(0);

        Node first = blue.objectToNode(new TestEvent().kind("original"));
        Node duplicate = blue.objectToNode(new TestEvent().kind("original"));
        Node different = blue.objectToNode(new TestEvent().kind("other"));

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
        assertNotNull(counterNode);
        assertEquals(new BigInteger("2"), counterNode.getValue());
    }

    @Test
    void deliversChannelizedEventToHandlersAndStoresOriginalEventInCheckpoint() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new NormalizingTestEventChannelProcessor());
        blue.registerContractProcessor(new SetPropertyOnEventContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  setFlag:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: H1qKGon7JWgUU9P8oUiHjxoR5hWbkAzVWWNukXf4cHz\n" +
                "    expectedKind: " + NormalizingTestEventChannelProcessor.NORMALIZED_KIND + "\n" +
                "    propertyKey: /flag\n" +
                "    propertyValue: 7\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document.clone());
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        assertNull(bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT));

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        ContractBundle.ChannelBinding channelBinding = bundle.channelsOfType(ChannelContract.class).get(0);
        Node event = blue.objectToNode(new TestEvent().eventId("evt-1").kind("original"));

        runner.runExternalChannel("/", bundle, channelBinding, event);
        runner.persistPendingCheckpoints("/");
        bundle = refreshBundle(execution);

        Node flagNode = execution.runtime().document().getProperties().get("flag");
        assertNotNull(flagNode);
        assertEquals(7, ((Number) flagNode.getValue()).intValue());

        ChannelEventCheckpoint checkpoint = (ChannelEventCheckpoint) bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT);
        assertNotNull(checkpoint);
        Node storedSubject = checkpoint.entry(channelBinding.key()).getSubject();
        assertNotNull(storedSubject);
        assertEquals(BlueIdCalculator.calculateBlueId(event),
                storedSubject.getBlueId());
    }

    @Test
    void duplicateSignatureForChannelizedEventsUsesOriginalExternalEvent() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new NormalizingTestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());

        String yaml = "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  increment:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "    propertyKey: /counter\n";

        Node document = blue.yamlToNode(yaml);
        DocumentProcessor owner = blue.getDocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document.clone());
        execution.preflightScope("/");
        ContractBundle bundle = execution.bundleForScope("/");

        CheckpointManager checkpointManager = new CheckpointManager(execution.runtime(), ProcessorEngine::canonicalSignature);
        ChannelRunner runner = new ChannelRunner(owner, execution, execution.runtime(), checkpointManager);

        ContractBundle.ChannelBinding channelBinding = bundle.channelsOfType(ChannelContract.class).get(0);
        Node first = blue.objectToNode(new TestEvent().kind("first"));
        Node second = blue.objectToNode(new TestEvent().kind("second"));

        runner.runExternalChannel("/", bundle, channelBinding, first);
        runner.persistPendingCheckpoints("/");
        runner.runExternalChannel("/", bundle, channelBinding, second);
        runner.persistPendingCheckpoints("/");

        Node counterNode = execution.runtime().document().getProperties().get("counter");
        assertNotNull(counterNode);
        assertEquals(new BigInteger("2"), counterNode.getValue());
    }

    private static ContractBundle refreshBundle(
            ProcessorEngine.Execution execution) {
        execution.preflightScope("/");
        return execution.bundleForScope("/");
    }
}
