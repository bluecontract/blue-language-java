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
        blue = ProcessorTestSupport.blue();
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

        String eventYaml = "type:\n" +
                "  blueId: Hi8TpcNruWrzfjRGFPDxtviZYap9oJwAFgSnZ6vED8Yf\n" +
                "eventId: evt-immutable\n" +
                "kind: original\n";
        Node event = blue.yamlToNode(eventYaml);

        DocumentProcessingResult result = blue.processDocument(initialized, event);

        Node resultNode = result.document().getProperties().get("result");
        assertEquals(BigInteger.valueOf(42), resultNode.getValue());
    }
}
