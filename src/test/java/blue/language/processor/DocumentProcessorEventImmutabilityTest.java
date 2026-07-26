package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import java.math.BigInteger;
import blue.language.processor.contracts.MutateEventContractProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.model.TestEvent;
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
    void handlersSeeImmutableEventSnapshots() {
        String documentYaml = "name: Immutable\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  mutator:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: EgL9wruNhEJTS5RspenxoyRngKEbXzMwDM4ZZ8gCHsiv\n" +
                "  recorder:\n" +
                "    channel: testChannel\n" +
                "    order: 1\n" +
                "    type:\n" +
                "      blueId: H1qKGon7JWgUU9P8oUiHjxoR5hWbkAzVWWNukXf4cHz\n" +
                "    expectedKind: original\n" +
                "    propertyKey: /result\n" +
                "    propertyValue: 42\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(documentYaml)).document().clone();

        Node event = new TestEvent()
                .eventId("evt-immutable")
                .kind("original")
                .toNode();

        DocumentProcessingResult result = blue.processDocument(initialized, event);

        assertEquals(ProcessorStatus.SUCCESS, result.status());
        assertTrue(result.events().isEmpty(),
                "the exact input event is never echoed to the public outbox");
        Node resultNode = result.document().getProperties().get("result");
        assertEquals(BigInteger.valueOf(42), resultNode.getValue());
    }
}
