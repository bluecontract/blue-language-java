package blue.language.processor.external;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalContractIntegrationTest {

    private static final String CHANNEL_BLUE_ID = "external.counter/Always Channel";
    private static final String HANDLER_BLUE_ID = "external.counter/Add Amount";
    private static final String UNKNOWN_BLUE_ID = "external.counter/Unknown Handler";

    @Test
    void builderRegistersExternalContractsByExplicitBlueIdAndExecutesThem() {
        ExternalAddAmountProcessor.reset();
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(CHANNEL_BLUE_ID, new ExternalAlwaysChannelProcessor())
                .registerContractProcessor(HANDLER_BLUE_ID, new ExternalAddAmountProcessor())
                .build();

        Blue blue = new Blue();
        Node document = blue.yamlToNode(counterDocument(HANDLER_BLUE_ID));

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        assertFalse(initialized.capabilityFailure(), initialized.failureReason());

        DocumentProcessingResult processed = processor.processDocument(initialized.document(), amountEvent(7));

        assertFalse(processed.capabilityFailure(), processed.failureReason());
        assertEquals(new BigInteger("7"), processed.document().get("/counter"));
        assertEquals(HANDLER_BLUE_ID, ExternalAddAmountProcessor.lastTypeBlueId);
        assertEquals("incoming", ExternalAddAmountProcessor.lastChannelKey);
        assertEquals("/counter", ExternalAddAmountProcessor.lastCounterPath);
    }

    @Test
    void blueFacadePreservesExternalContractResolverWhenRuntimeServicesRefresh() {
        ExternalAddAmountProcessor.reset();
        Blue blue = new Blue();
        blue.registerContractProcessor(CHANNEL_BLUE_ID, new ExternalAlwaysChannelProcessor());
        blue.registerContractProcessor(HANDLER_BLUE_ID, new ExternalAddAmountProcessor());

        blue.nodeProvider(ignored -> null);

        Node document = blue.yamlToNode(counterDocument(HANDLER_BLUE_ID));
        DocumentProcessingResult initialized = blue.initializeDocument(document);
        DocumentProcessingResult processed = blue.processDocument(initialized.document(), amountEvent(5));

        assertFalse(processed.capabilityFailure(), processed.failureReason());
        assertEquals(new BigInteger("5"), processed.document().get("/counter"));
        assertEquals(HANDLER_BLUE_ID, ExternalAddAmountProcessor.lastTypeBlueId);
    }

    @Test
    void unknownExternalContractTypeProducesCapabilityFailureWithoutMutation() {
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(CHANNEL_BLUE_ID, new ExternalAlwaysChannelProcessor())
                .build();
        Blue blue = new Blue();
        Node document = blue.yamlToNode(counterDocument(UNKNOWN_BLUE_ID));

        DocumentProcessingResult result = processor.initializeDocument(document);

        assertTrue(result.capabilityFailure());
        assertTrue(result.failureReason().contains(UNKNOWN_BLUE_ID));
        assertFalse(result.document().getProperties().get("contracts").getProperties().containsKey("initialized"));
        assertEquals(new BigInteger("0"), result.document().get("/counter"));
    }

    private static String counterDocument(String handlerBlueId) {
        return "name: External Counter\n" +
                "counter: 0\n" +
                "contracts:\n" +
                "  incoming:\n" +
                "    type:\n" +
                "      blueId: " + CHANNEL_BLUE_ID + "\n" +
                "  addAmount:\n" +
                "    type:\n" +
                "      blueId: " + handlerBlueId + "\n" +
                "    channel: incoming\n" +
                "    counterPath: /counter\n";
    }

    private static Node amountEvent(int amount) {
        return new Node().properties("amount", new Node().value(BigInteger.valueOf(amount)));
    }

    public static final class ExternalAlwaysChannel extends ChannelContract {
    }

    public static final class ExternalAddAmount extends HandlerContract {
        private String counterPath;

        public String getCounterPath() {
            return counterPath;
        }

        public void setCounterPath(String counterPath) {
            this.counterPath = counterPath;
        }
    }

    public static final class ExternalAlwaysChannelProcessor implements ChannelProcessor<ExternalAlwaysChannel> {

        @Override
        public Class<ExternalAlwaysChannel> contractType() {
            return ExternalAlwaysChannel.class;
        }

        @Override
        public boolean matches(ExternalAlwaysChannel contract, ChannelEvaluationContext context) {
            return true;
        }
    }

    public static final class ExternalAddAmountProcessor implements HandlerProcessor<ExternalAddAmount> {

        static String lastTypeBlueId;
        static String lastChannelKey;
        static String lastCounterPath;

        static void reset() {
            lastTypeBlueId = null;
            lastChannelKey = null;
            lastCounterPath = null;
        }

        @Override
        public Class<ExternalAddAmount> contractType() {
            return ExternalAddAmount.class;
        }

        @Override
        public void execute(ExternalAddAmount contract, ProcessorExecutionContext context) {
            lastTypeBlueId = contract.getTypeBlueId();
            lastChannelKey = contract.getChannelKey();
            lastCounterPath = contract.getCounterPath();

            String counterPath = context.resolvePointer(contract.getCounterPath());
            Node existing = context.documentAt(counterPath);
            BigInteger current = existing != null && existing.getValue() instanceof BigInteger
                    ? (BigInteger) existing.getValue()
                    : BigInteger.ZERO;
            Node amountNode = context.event().getProperties().get("amount");
            BigInteger amount = (BigInteger) amountNode.getValue();
            context.applyPatch(JsonPatch.replace(counterPath, new Node().value(current.add(amount))));
        }
    }
}
