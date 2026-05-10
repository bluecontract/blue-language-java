package blue.language.processor.external;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelDelivery;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessor;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.HandlerRegistrationContext;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.MarkerContract;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalContractIntegrationTest {

    private static final String CHANNEL_BLUE_ID = "external.counter/Always Channel";
    private static final String MUTATING_CHANNEL_BLUE_ID = "external.counter/Mutating Channel";
    private static final String SEQUENCE_CHANNEL_BLUE_ID = "external.counter/Sequence Channel";
    private static final String MULTI_DELIVERY_CHANNEL_BLUE_ID = "external.counter/Multi Delivery Channel";
    private static final String DELEGATING_CHANNEL_BLUE_ID = "external.counter/Delegating Channel";
    private static final String OPERATION_BLUE_ID = "external.counter/Operation";
    private static final String HANDLER_BLUE_ID = "external.counter/Add Amount";
    private static final String MATCHING_HANDLER_BLUE_ID = "external.counter/Matching Add Amount";
    private static final String DERIVED_HANDLER_BLUE_ID = "external.counter/Derived Add Amount";
    private static final String CAPTURE_HANDLER_BLUE_ID = "external.counter/Capture Event Flag";
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

    @Test
    void handlerProcessorCanUseSharedFrozenEventPatternMatching() {
        MatchingAddAmountProcessor.reset();
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(CHANNEL_BLUE_ID, new ExternalAlwaysChannelProcessor())
                .registerContractProcessor(MATCHING_HANDLER_BLUE_ID, new MatchingAddAmountProcessor())
                .build();
        Blue blue = new Blue();
        Node document = blue.yamlToNode(
                "name: Matching Counter\n" +
                "counter: 0\n" +
                "contracts:\n" +
                "  incoming:\n" +
                "    type:\n" +
                "      blueId: " + CHANNEL_BLUE_ID + "\n" +
                "  addAllowed:\n" +
                "    type:\n" +
                "      blueId: " + MATCHING_HANDLER_BLUE_ID + "\n" +
                "    channel: incoming\n" +
                "    counterPath: /counter\n" +
                "    event:\n" +
                "      kind: allowed\n");

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        assertFalse(new ContractMatchingService().matches(amountEvent(7, "denied"), blue.yamlToNode("kind: allowed")));
        DocumentProcessingResult denied = processor.processDocument(initialized.document(), amountEvent(7, "denied"));

        assertFalse(MatchingAddAmountProcessor.lastPatternNull);
        assertEquals("allowed", MatchingAddAmountProcessor.lastPatternKindValue);
        assertEquals(new BigInteger("0"), denied.document().get("/counter"));
        assertEquals(0, MatchingAddAmountProcessor.executions);

        DocumentProcessingResult allowed = processor.processDocument(denied.document(), amountEvent(5, "allowed"));

        assertEquals(2, MatchingAddAmountProcessor.matchAttempts);
        assertTrue(MatchingAddAmountProcessor.lastMatch);
        assertEquals(1, MatchingAddAmountProcessor.executions);
        assertEquals(new BigInteger("5"), allowed.document().get("/counter"));
    }

    @Test
    void channelContextEventMutationIsIgnoredUnlessEvaluationReturnsChannelizedEvent() {
        CaptureEventFlagProcessor.reset();
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(MUTATING_CHANNEL_BLUE_ID, new MutatingOnlyChannelProcessor())
                .registerContractProcessor(CAPTURE_HANDLER_BLUE_ID, new CaptureEventFlagProcessor())
                .build();
        Blue blue = new Blue();
        Node document = blue.yamlToNode(
                "name: Immutable Channel Context\n" +
                "contracts:\n" +
                "  incoming:\n" +
                "    type:\n" +
                "      blueId: " + MUTATING_CHANNEL_BLUE_ID + "\n" +
                "  capture:\n" +
                "    type:\n" +
                "      blueId: " + CAPTURE_HANDLER_BLUE_ID + "\n" +
                "    channel: incoming\n");

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        processor.processDocument(initialized.document(), amountEvent(1));

        assertTrue(CaptureEventFlagProcessor.executed);
        assertFalse(CaptureEventFlagProcessor.sawNormalizedFlag);
    }

    @Test
    void channelProcessorCanRejectStaleNonDuplicateEventsUsingCheckpointContext() {
        ExternalAddAmountProcessor.reset();
        SequenceChannelProcessor.reset();
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(SEQUENCE_CHANNEL_BLUE_ID, new SequenceChannelProcessor())
                .registerContractProcessor(HANDLER_BLUE_ID, new ExternalAddAmountProcessor())
                .build();
        Blue blue = new Blue();
        Node document = blue.yamlToNode(counterDocument(SEQUENCE_CHANNEL_BLUE_ID, HANDLER_BLUE_ID));

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        DocumentProcessingResult first = processor.processDocument(initialized.document(), sequencedAmountEvent(7, 10));
        DocumentProcessingResult stale = processor.processDocument(first.document(), sequencedAmountEvent(100, 8));
        DocumentProcessingResult fresh = processor.processDocument(stale.document(), sequencedAmountEvent(5, 11));

        assertEquals(new BigInteger("7"), first.document().get("/counter"));
        assertEquals(new BigInteger("7"), stale.document().get("/counter"));
        assertEquals(new BigInteger("12"), fresh.document().get("/counter"));
        assertEquals(3, SequenceChannelProcessor.newnessChecks);
        assertEquals(new BigInteger("10"), SequenceChannelProcessor.lastPreviousSequence);
        assertEquals(new BigInteger("11"), SequenceChannelProcessor.lastAcceptedSequence);
    }

    @Test
    void handlerProcessorCanDeriveChannelFromAnotherScopeContractDuringLoading() {
        DerivingAddAmountProcessor.reset();
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(CHANNEL_BLUE_ID, new ExternalAlwaysChannelProcessor())
                .registerContractProcessor(OPERATION_BLUE_ID, new ExternalOperationProcessor())
                .registerContractProcessor(DERIVED_HANDLER_BLUE_ID, new DerivingAddAmountProcessor())
                .build();
        Blue blue = new Blue();
        Node document = blue.yamlToNode(
                "name: Derived Channel Counter\n" +
                "counter: 0\n" +
                "contracts:\n" +
                "  incoming:\n" +
                "    type:\n" +
                "      blueId: " + CHANNEL_BLUE_ID + "\n" +
                "  increment:\n" +
                "    type:\n" +
                "      blueId: " + OPERATION_BLUE_ID + "\n" +
                "    channel: incoming\n" +
                "  incrementImpl:\n" +
                "    type:\n" +
                "      blueId: " + DERIVED_HANDLER_BLUE_ID + "\n" +
                "    operation: increment\n" +
                "    counterPath: /counter\n");

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        DocumentProcessingResult processed = processor.processDocument(initialized.document(), amountEvent(4));

        assertFalse(processed.capabilityFailure(), processed.failureReason());
        assertEquals("incoming", DerivingAddAmountProcessor.derivedChannel);
        assertEquals(new BigInteger("4"), processed.document().get("/counter"));
        assertEquals(1, DerivingAddAmountProcessor.executions);
    }

    @Test
    void channelEvaluationCanReturnMultipleDeliveriesWithIndependentCheckpoints() {
        ExternalAddAmountProcessor.reset();
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(MULTI_DELIVERY_CHANNEL_BLUE_ID, new MultiDeliveryChannelProcessor())
                .registerContractProcessor(HANDLER_BLUE_ID, new ExternalAddAmountProcessor())
                .build();
        Blue blue = new Blue();
        Node document = blue.yamlToNode(counterDocument(MULTI_DELIVERY_CHANNEL_BLUE_ID, HANDLER_BLUE_ID));

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        Node incoming = amountEvent(99, "raw");
        DocumentProcessingResult first = processor.processDocument(initialized.document(), incoming);
        DocumentProcessingResult duplicate = processor.processDocument(first.document(), incoming);

        assertEquals(new BigInteger("3"), first.document().get("/counter"));
        assertEquals(new BigInteger("3"), duplicate.document().get("/counter"));
        Node checkpoint = first.document().getAsNode("/contracts/checkpoint");
        assertEquals("raw", checkpoint.getAsText("/lastEvents/incoming::one/kind"));
        assertEquals("raw", checkpoint.getAsText("/lastEvents/incoming::two/kind"));
    }

    @Test
    void channelProcessorCanEvaluateSameScopeChannelFromContext() {
        DelegatingChannelProcessor.reset();
        CaptureEventFlagProcessor.reset();
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(CHANNEL_BLUE_ID, new ExternalAlwaysChannelProcessor())
                .registerContractProcessor(DELEGATING_CHANNEL_BLUE_ID, new DelegatingChannelProcessor())
                .registerContractProcessor(CAPTURE_HANDLER_BLUE_ID, new CaptureEventFlagProcessor())
                .build();
        Blue blue = new Blue();
        Node document = blue.yamlToNode(
                "name: Composite Channel Counter\n" +
                "contracts:\n" +
                "  incoming:\n" +
                "    type:\n" +
                "      blueId: " + CHANNEL_BLUE_ID + "\n" +
                "  composite:\n" +
                "    type:\n" +
                "      blueId: " + DELEGATING_CHANNEL_BLUE_ID + "\n" +
                "    childChannel: incoming\n" +
                "  capture:\n" +
                "    type:\n" +
                "      blueId: " + CAPTURE_HANDLER_BLUE_ID + "\n" +
                "    channel: composite\n");

        DocumentProcessingResult initialized = processor.initializeDocument(document);
        DocumentProcessingResult processed = processor.processDocument(initialized.document(), amountEvent(1));

        assertFalse(processed.capabilityFailure(), processed.failureReason());
        assertEquals("composite", DelegatingChannelProcessor.lastBindingKey);
        assertTrue(DelegatingChannelProcessor.sawIncomingChannel);
        assertTrue(DelegatingChannelProcessor.sawCompositeChannel);
        assertTrue(CaptureEventFlagProcessor.executed);
        assertTrue(CaptureEventFlagProcessor.sawDelegatedFlag);
    }

    @Test
    void derivedHandlerChannelMustResolveToRegisteredChannelInSameScope() {
        DocumentProcessor processor = DocumentProcessor.builder()
                .registerContractProcessor(OPERATION_BLUE_ID, new ExternalOperationProcessor())
                .registerContractProcessor(DERIVED_HANDLER_BLUE_ID, new DerivingAddAmountProcessor())
                .build();
        Blue blue = new Blue();
        Node document = blue.yamlToNode(
                "name: Invalid Derived Channel Counter\n" +
                "counter: 0\n" +
                "contracts:\n" +
                "  increment:\n" +
                "    type:\n" +
                "      blueId: " + OPERATION_BLUE_ID + "\n" +
                "    channel: missing\n" +
                "  incrementImpl:\n" +
                "    type:\n" +
                "      blueId: " + DERIVED_HANDLER_BLUE_ID + "\n" +
                "    operation: increment\n" +
                "    counterPath: /counter\n");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> processor.initializeDocument(document));

        assertTrue(ex.getMessage().contains("unknown channel 'missing'"));
    }

    private static String counterDocument(String handlerBlueId) {
        return counterDocument(CHANNEL_BLUE_ID, handlerBlueId);
    }

    private static String counterDocument(String channelBlueId, String handlerBlueId) {
        return "name: External Counter\n" +
                "counter: 0\n" +
                "contracts:\n" +
                "  incoming:\n" +
                "    type:\n" +
                "      blueId: " + channelBlueId + "\n" +
                "  addAmount:\n" +
                "    type:\n" +
                "      blueId: " + handlerBlueId + "\n" +
                "    channel: incoming\n" +
                "    counterPath: /counter\n";
    }

    private static Node amountEvent(int amount) {
        return new Node().properties("amount", new Node().value(BigInteger.valueOf(amount)));
    }

    private static Node amountEvent(int amount, String kind) {
        return amountEvent(amount).properties("kind", new Node().value(kind));
    }

    private static Node sequencedAmountEvent(int amount, int sequence) {
        return amountEvent(amount).properties("sequence", new Node().value(BigInteger.valueOf(sequence)));
    }

    public static final class ExternalAlwaysChannel extends ChannelContract {
    }

    public static final class MutatingOnlyChannel extends ChannelContract {
    }

    public static final class SequenceChannel extends ChannelContract {
    }

    public static final class MultiDeliveryChannel extends ChannelContract {
    }

    public static final class DelegatingChannel extends ChannelContract {
        private String childChannel;

        public String getChildChannel() {
            return childChannel;
        }

        public void setChildChannel(String childChannel) {
            this.childChannel = childChannel;
        }
    }

    public static final class ExternalOperation extends MarkerContract {
        private String channel;

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }
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

    public static final class MatchingAddAmount extends HandlerContract {
        private String counterPath;

        public String getCounterPath() {
            return counterPath;
        }

        public void setCounterPath(String counterPath) {
            this.counterPath = counterPath;
        }
    }

    public static final class CaptureEventFlag extends HandlerContract {
    }

    public static final class DerivingAddAmount extends HandlerContract {
        private String operation;
        private String counterPath;

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

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

    public static final class MutatingOnlyChannelProcessor implements ChannelProcessor<MutatingOnlyChannel> {

        @Override
        public Class<MutatingOnlyChannel> contractType() {
            return MutatingOnlyChannel.class;
        }

        @Override
        public boolean matches(MutatingOnlyChannel contract, ChannelEvaluationContext context) {
            Node event = context.event();
            if (event != null) {
                event.properties("normalized", new Node().value(true));
            }
            return true;
        }
    }

    public static final class SequenceChannelProcessor implements ChannelProcessor<SequenceChannel> {

        static int newnessChecks;
        static BigInteger lastPreviousSequence;
        static BigInteger lastAcceptedSequence;

        static void reset() {
            newnessChecks = 0;
            lastPreviousSequence = null;
            lastAcceptedSequence = null;
        }

        @Override
        public Class<SequenceChannel> contractType() {
            return SequenceChannel.class;
        }

        @Override
        public boolean matches(SequenceChannel contract, ChannelEvaluationContext context) {
            return sequence(context.event()) != null;
        }

        @Override
        public boolean isNewerEvent(SequenceChannel contract, ChannelCheckpointContext context) {
            newnessChecks++;
            BigInteger current = sequence(context.event());
            BigInteger previous = sequence(context.lastEvent());
            lastPreviousSequence = previous;
            boolean accepted = previous == null || current.compareTo(previous) > 0;
            if (accepted) {
                lastAcceptedSequence = current;
            }
            return accepted;
        }

        private static BigInteger sequence(Node event) {
            if (event == null || event.getProperties() == null) {
                return null;
            }
            Node node = event.getProperties().get("sequence");
            return node != null && node.getValue() instanceof BigInteger
                    ? (BigInteger) node.getValue()
                    : null;
        }
    }

    public static final class MultiDeliveryChannelProcessor implements ChannelProcessor<MultiDeliveryChannel> {

        @Override
        public Class<MultiDeliveryChannel> contractType() {
            return MultiDeliveryChannel.class;
        }

        @Override
        public ChannelEvaluation evaluate(MultiDeliveryChannel contract, ChannelEvaluationContext context) {
            Node first = new Node().properties("amount", new Node().value(BigInteger.ONE));
            Node second = new Node().properties("amount", new Node().value(new BigInteger("2")));
            return ChannelEvaluation.matchDeliveries(java.util.Arrays.asList(
                    ChannelDelivery.of(first, null, "incoming::one", null),
                    ChannelDelivery.of(second, null, "incoming::two", null)));
        }
    }

    public static final class DelegatingChannelProcessor implements ChannelProcessor<DelegatingChannel> {

        static String lastBindingKey;
        static boolean sawIncomingChannel;
        static boolean sawCompositeChannel;

        static void reset() {
            lastBindingKey = null;
            sawIncomingChannel = false;
            sawCompositeChannel = false;
        }

        @Override
        public Class<DelegatingChannel> contractType() {
            return DelegatingChannel.class;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public ChannelEvaluation evaluate(DelegatingChannel contract, ChannelEvaluationContext context) {
            lastBindingKey = context.bindingKey();
            sawIncomingChannel = context.channelKeys().contains("incoming");
            sawCompositeChannel = context.channelKeys().contains("composite");
            ChannelContract child = context.channel(contract.getChildChannel());
            ChannelProcessor processor = context.channelProcessor(child);
            if (child == null || processor == null) {
                return ChannelEvaluation.noMatch();
            }
            ChannelEvaluation childEvaluation = processor.evaluate(child, context.forBindingKey(contract.getChildChannel()));
            if (childEvaluation == null || !childEvaluation.matches()) {
                return ChannelEvaluation.noMatch();
            }
            Node event = childEvaluation.event() != null ? childEvaluation.event() : context.event();
            event.properties("delegated", new Node().value(true));
            return ChannelEvaluation.match(event, childEvaluation.eventId());
        }
    }

    public static final class ExternalOperationProcessor implements ContractProcessor<ExternalOperation> {

        @Override
        public Class<ExternalOperation> contractType() {
            return ExternalOperation.class;
        }
    }

    public static final class DerivingAddAmountProcessor implements HandlerProcessor<DerivingAddAmount> {

        static String derivedChannel;
        static int executions;

        static void reset() {
            derivedChannel = null;
            executions = 0;
        }

        @Override
        public Class<DerivingAddAmount> contractType() {
            return DerivingAddAmount.class;
        }

        @Override
        public String deriveChannel(DerivingAddAmount contract, HandlerRegistrationContext context) {
            ExternalOperation operation = context.contractAs(contract.getOperation(), ExternalOperation.class);
            derivedChannel = operation != null ? operation.getChannel() : null;
            return derivedChannel;
        }

        @Override
        public void execute(DerivingAddAmount contract, ProcessorExecutionContext context) {
            executions++;
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

    public static final class MatchingAddAmountProcessor implements HandlerProcessor<MatchingAddAmount> {

        static int executions;
        static int matchAttempts;
        static boolean lastMatch;
        static boolean lastPatternNull;
        static Object lastPatternKindValue;

        static void reset() {
            executions = 0;
            matchAttempts = 0;
            lastMatch = false;
            lastPatternNull = false;
            lastPatternKindValue = null;
        }

        @Override
        public Class<MatchingAddAmount> contractType() {
            return MatchingAddAmount.class;
        }

        @Override
        public boolean matches(MatchingAddAmount contract, HandlerMatchContext context) {
            matchAttempts++;
            lastPatternNull = contract.getEvent() == null;
            if (contract.getEvent() != null
                    && contract.getEvent().getProperties() != null
                    && contract.getEvent().getProperties().get("kind") != null) {
                lastPatternKindValue = contract.getEvent().getProperties().get("kind").getValue();
            }
            lastMatch = context.matchesEventPattern(contract.getEvent());
            return lastMatch;
        }

        @Override
        public void execute(MatchingAddAmount contract, ProcessorExecutionContext context) {
            executions++;
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

    public static final class CaptureEventFlagProcessor implements HandlerProcessor<CaptureEventFlag> {

        static boolean executed;
        static boolean sawNormalizedFlag;
        static boolean sawDelegatedFlag;

        static void reset() {
            executed = false;
            sawNormalizedFlag = false;
            sawDelegatedFlag = false;
        }

        @Override
        public Class<CaptureEventFlag> contractType() {
            return CaptureEventFlag.class;
        }

        @Override
        public void execute(CaptureEventFlag contract, ProcessorExecutionContext context) {
            executed = true;
            Node event = context.event();
            sawNormalizedFlag = event != null
                    && event.getProperties() != null
                    && event.getProperties().containsKey("normalized");
            sawDelegatedFlag = event != null
                    && event.getProperties() != null
                    && event.getProperties().containsKey("delegated");
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
