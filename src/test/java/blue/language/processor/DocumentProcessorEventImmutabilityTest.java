package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import java.math.BigInteger;
import blue.language.processor.contracts.MutateEventContractProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorEventImmutabilityTest {

    private Blue blue;

    @BeforeEach
    void setUp() {
        blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(new MutateEventContractProcessor());
        blue.registerContractProcessor(new SetPropertyOnEventContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
    }

    @Test
    void shouldExposeImmutableEventSnapshotsToHandlers() {
        // given
        String documentYaml = "name: Immutable\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  mutator:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.MUTATE_EVENT + "\n" +
                "  recorder:\n" +
                "    channel: testChannel\n" +
                "    order: 1\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY_ON_EVENT + "\n" +
                "    expectedKind: original\n" +
                "    propertyKey: /result\n" +
                "    propertyValue: 42\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(documentYaml)).document().clone();

        Node event = new TestEvent()
                .eventId("evt-immutable")
                .kind("original")
                .toNode();

        // when
        DocumentProcessingResult result = blue.processDocument(initialized, event);
        Node resultNode = result.document().getProperties().get("result");

        // then
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertTrue(result.events().isEmpty(),
                "the exact input event is never echoed to the public outbox");
        assertEquals(BigInteger.valueOf(42), resultNode.getValue());
    }
}
