package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import java.math.BigInteger;
import blue.language.processor.contracts.MutateEventContractProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentProcessorEventImmutabilityTest {

    private Blue blue;

    @BeforeEach
    void setUp() {
        blue = new Blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new MutateEventContractProcessor());
        blue.registerContractProcessor(new SetPropertyOnEventContractProcessor());
    }

    @Test
    void handlersSeeImmutableEventSnapshots() {
        String documentYaml = "name: Immutable\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  mutator:\n" +
                "    channel: testChannel\n" +
                "    type:\n" +
                "      blueId: MutateEvent\n" +
                "  recorder:\n" +
                "    channel: testChannel\n" +
                "    order: 1\n" +
                "    type:\n" +
                "      blueId: SetPropertyOnEvent\n" +
                "    expectedKind: original\n" +
                "    propertyKey: /result\n" +
                "    propertyValue: 42\n";

        Node initialized = blue.initializeDocument(blue.yamlToNode(documentYaml)).document().clone();

        String eventYaml = "type:\n" +
                "  blueId: TestEvent\n" +
                "eventId: evt-immutable\n" +
                "kind: original\n";
        Node event = blue.yamlToNode(eventYaml);

        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node resultNode = result.document().getProperties().get("result");
        assertEquals(BigInteger.valueOf(42), resultNode.getValue());
    }
}
