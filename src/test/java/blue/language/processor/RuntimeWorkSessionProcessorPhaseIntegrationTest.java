package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.utils.BlueIdCalculator;
import blue.language.model.wire.JsonPointer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeWorkSessionProcessorPhaseIntegrationTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Runtime Work Session Integration Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final Node HANDLER_TYPE =
            new Node().name("Runtime Work Session Integration Handler");
    private static final String HANDLER_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(HANDLER_TYPE);
    private static final String TOPIC = "runtime-work-session-topic";
    private static final String DOMAIN = "runtime-work-session-domain";
    private static final String COUNTER_OPERATION = "operation";
    private static final String PHASE_CHANNEL_KEYS =
            "external.header.channel-keys";
    private static final String PHASE_CHECKPOINT_DOMAIN =
            "external.header.checkpoint-domain";
    private static final String PHASE_EVENT_KEYS =
            "external.event.keys";
    private static final String PHASE_PRESELECTION =
            "external.event.preselection";
    private static final String PHASE_CHANNEL_EVALUATION =
            "channel.evaluation";
    private static final String PHASE_EXTERNAL_PAYLOAD =
            "external.payload";
    private static final String PHASE_LOGICAL_TARGET =
            "external.logical-target";
    private static final String PHASE_CHECKPOINT_SUBJECT =
            "external.checkpoint-subject";
    private static final String PHASE_CHANNEL_CHECKPOINT =
            "channel.checkpoint";
    private static final String PHASE_HANDLER_REGISTRATION =
            "handler.registration";
    private static final String PHASE_HANDLER_MATCH =
            "handler.match";
    private static final List<String> PROCESSING_PHASES =
            Collections.unmodifiableList(Arrays.asList(
                    PHASE_CHANNEL_KEYS,
                    PHASE_CHECKPOINT_DOMAIN,
                    PHASE_EVENT_KEYS,
                    PHASE_PRESELECTION,
                    PHASE_CHANNEL_EVALUATION,
                    PHASE_EXTERNAL_PAYLOAD,
                    PHASE_LOGICAL_TARGET,
                    PHASE_CHECKPOINT_SUBJECT,
                    PHASE_CHANNEL_CHECKPOINT,
                    PHASE_HANDLER_REGISTRATION,
                    PHASE_HANDLER_MATCH
            ));
    private static final String HOSTED_NAMESPACE_PREFIX =
            "hosted.";
    private static final String NESTED_ALPHA_NAMESPACE =
            "hosted.handler.match.nested-a";
    private static final String NESTED_ZETA_NAMESPACE =
            "hosted.handler.match.nested-z";
    private static final Map<String, Long> ONE_OPERATION =
            Collections.singletonMap(COUNTER_OPERATION, 3L);

    @Test
    void shouldSupplyRuntimeWorkSessionsToEveryRegisteredProcessorPhase() {
        // given
        SessionRecorder recorder = new SessionRecorder();

        // when
        ProcessorPhaseRun run =
                executeProcessorScenario(recorder);

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                run.initialized.status(),
                diagnostic(run.initialized));
        assertEquals(
                ProcessorStatus.SUCCESS,
                run.debug.processResult().status(),
                diagnostic(run.debug.processResult()));
        assertTrue(run.debug.processResult().commits());
        assertTrue(
                recorder.processingPhases()
                        .containsAll(PROCESSING_PHASES),
                recorder.processingPhases().toString());
    }

    @Test
    void shouldExcludeAdmissionWorkAndMergeEachProcessingChargeOnce() {
        // given
        SessionRecorder recorder = new SessionRecorder();

        // when
        ProcessorPhaseRun run =
                executeProcessorScenario(recorder);
        List<GasTraceEntry> runtimeTrace =
                runtimeTrace(run.debug.trace().gas());
        int processingChargeCount =
                recorder.processingChargeCount();
        long processingGas = recorder.processingGas();

        // then
        assertTrue(
                recorder.sawAdmissionWork(),
                "out-of-band and diagnostic passes must remain explicit");
        assertEquals(
                processingChargeCount,
                runtimeTrace.size(),
                "each processing-time runtime charge must merge exactly once");
        assertEquals(
                processingGas,
                totalGas(runtimeTrace),
                "diagnostic/admission twins must not enter PROCESS gas");
    }

    @Test
    void shouldKeepRuntimeSessionsOpenWhileUsedAndCloseThemAfterProcessing() {
        // given
        SessionRecorder recorder = new SessionRecorder();

        // when
        executeProcessorScenario(recorder);
        boolean allSessionsOpenWhenUsed =
                recorder.allSessionsOpenWhenUsed();
        boolean allSessionsClosed =
                recorder.allSessionsClosed();

        // then
        assertTrue(
                allSessionsOpenWhenUsed,
                "processor phases must receive live sessions");
        assertTrue(
                allSessionsClosed,
                "processor phase owners must close every supplied session");
    }

    @Test
    void shouldMergeNestedRuntimeLedgersOnceInCanonicalNamespaceOrder() {
        // given
        SessionRecorder recorder = new SessionRecorder();

        // when
        ProcessorPhaseRun run =
                executeProcessorScenario(recorder);
        List<GasTraceEntry> runtimeTrace =
                runtimeTrace(run.debug.trace().gas());
        long nestedACount = countNamespace(
                runtimeTrace,
                NESTED_ALPHA_NAMESPACE);
        long nestedZCount = countNamespace(
                runtimeTrace,
                NESTED_ZETA_NAMESPACE);
        int nestedAIndex = namespaceIndex(
                runtimeTrace,
                NESTED_ALPHA_NAMESPACE);
        int nestedZIndex = namespaceIndex(
                runtimeTrace,
                NESTED_ZETA_NAMESPACE);

        // then
        assertEquals(
                1L,
                nestedACount);
        assertEquals(
                1L,
                nestedZCount);
        assertTrue(
                nestedAIndex < nestedZIndex,
                "one session merges independent nested components in "
                        + "canonical namespace order");
    }

    private static ProcessorPhaseRun executeProcessorScenario(
            SessionRecorder recorder) {
        PhaseChannelProcessor channelProcessor =
                new PhaseChannelProcessor(recorder);
        PhaseHandlerProcessor handlerProcessor =
                new PhaseHandlerProcessor(recorder);
        try (DocumentProcessor owner =
                     DocumentProcessor.builder()
                             .registerContractProcessor(
                                     CHANNEL_TYPE_BLUE_ID,
                                     CHANNEL_TYPE,
                                     channelProcessor)
                             .registerContractProcessor(
                                     HANDLER_TYPE_BLUE_ID,
                                     HANDLER_TYPE,
                                     handlerProcessor)
                             .withExternalDeliveryEvidenceVerifier(
                                     (document, event, evidence) -> {
                                         // The scenario isolates
                                         // processor-owned runtime phases
                                         // from an environmental feeder.
                                     })
                             .withExternalDeliveryPlanDeriver(
                                     RuntimeWorkSessionProcessorPhaseIntegrationTest
                                             ::deliveryPlan)
                             .build()) {
            Node source = new Node().contracts(
                    new Node()
                            .properties(
                                    "source",
                                    channelNode())
                            .properties(
                                    "handler",
                                    handlerNode()));
            DocumentProcessingResult initialized =
                    owner.initializeDocument(source);
            recorder.reset();
            ProcessingDebugResult debug =
                    owner.processDocumentWithTrace(
                            initialized.document(),
                            eventNode());
            return new ProcessorPhaseRun(
                    initialized,
                    debug);
        }
    }

    private static ExternalDeliveryPlan deliveryPlan(
            Node root,
            Node event) {
        Node channel = root.getContracts()
                .getProperties().get("source");
        String contribution =
                BlueIdCalculator.calculateBlueId(channel);
        String checkpointSubject =
                BlueIdCalculator.calculateBlueId(event);
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder(
                                JsonPointer.ROOT, "source")
                        .order(0)
                        .sourceContribution(contribution)
                        .effectiveTypeBlueId(
                                CHANNEL_TYPE_BLUE_ID)
                        .subscriptionKey(TOPIC)
                        .checkpointDomainBlueId(
                                CheckpointDomain.derive(
                                        CHANNEL_TYPE_BLUE_ID,
                                        Collections.singletonList(
                                                contribution),
                                        DOMAIN))
                        .checkpointSubjectBlueId(
                                checkpointSubject)
                        .build();
        return ExternalDeliveryPlan.builder()
                .revisions(1L, 1L)
                .eventOrderKey(
                        ExternalOrderKey.of(
                                Collections.singletonList(
                                        checkpointSubject)))
                .delivery(delivery)
                .exactRuntimeState()
                .build();
    }

    private static Node channelNode() {
        return new Node()
                .type(new Node().blueId(
                        CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "order",
                        new Node().value(0))
                .properties(
                        "subscriptionKey",
                        new Node().value(TOPIC));
    }

    private static Node handlerNode() {
        return new Node()
                .type(new Node().blueId(
                        HANDLER_TYPE_BLUE_ID));
    }

    private static Node eventNode() {
        return new Node().properties(
                "subscriptionKey",
                new Node().value(TOPIC));
    }

    private static String diagnostic(
            DocumentProcessingResult result) {
        return result.diagnostic() != null
                ? result.diagnostic().message()
                : null;
    }

    private static List<GasTraceEntry> runtimeTrace(
            List<GasTraceEntry> trace) {
        List<GasTraceEntry> runtime = new ArrayList<>();
        for (GasTraceEntry entry : trace) {
            if (entry.namespace().startsWith(
                    HOSTED_NAMESPACE_PREFIX)) {
                runtime.add(entry);
            }
        }
        return runtime;
    }

    private static long totalGas(
            List<GasTraceEntry> trace) {
        long total = 0L;
        for (GasTraceEntry entry : trace) {
            total += entry.subtotal();
        }
        return total;
    }

    private static long countNamespace(
            List<GasTraceEntry> trace,
            String namespace) {
        long count = 0L;
        for (GasTraceEntry entry : trace) {
            if (namespace.equals(entry.namespace())) {
                count++;
            }
        }
        return count;
    }

    private static int namespaceIndex(
            List<GasTraceEntry> trace,
            String namespace) {
        for (int index = 0; index < trace.size(); index++) {
            if (namespace.equals(
                    trace.get(index).namespace())) {
                return index;
            }
        }
        return -1;
    }

    /** Captures both observable processor outcomes from one scenario run. */
    private static final class ProcessorPhaseRun {
        private final DocumentProcessingResult initialized;
        private final ProcessingDebugResult debug;

        private ProcessorPhaseRun(
                DocumentProcessingResult initialized,
                ProcessingDebugResult debug) {
            this.initialized = initialized;
            this.debug = debug;
        }
    }

    public static final class PhaseChannel
            extends ChannelContract {
        private String subscriptionKey;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(
                String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }
    }

    public static final class PhaseHandler
            extends HandlerContract {
    }

    private static final class PhaseChannelProcessor
            implements ChannelProcessor<PhaseChannel> {

        private final SessionRecorder recorder;
        private final ExternalChannelSubscriptionFunctions<
                PhaseChannel> functions;

        private PhaseChannelProcessor(
                SessionRecorder recorder) {
            this.recorder = recorder;
            this.functions =
                    new ExternalChannelSubscriptionFunctions<
                            PhaseChannel>() {
                        @Override
                        public List<String> channelKeys(
                                PhaseChannel channel,
                                ExternalChannelFunctionContext context) {
                            recorder.charge(
                                    context.runtimeWorkSession(),
                                    PHASE_CHANNEL_KEYS);
                            return Collections.singletonList(
                                    channel.getSubscriptionKey());
                        }

                        @Override
                        public String checkpointDomainDiscriminator(
                                PhaseChannel channel,
                                ExternalChannelFunctionContext context) {
                            recorder.charge(
                                    context.runtimeWorkSession(),
                                    PHASE_CHECKPOINT_DOMAIN);
                            return DOMAIN;
                        }

                        @Override
                        public List<String> eventKeys(
                                Node event,
                                ExternalChannelFunctionContext context) {
                            recorder.charge(
                                    context.runtimeWorkSession(),
                                    PHASE_EVENT_KEYS);
                            return Collections.singletonList(TOPIC);
                        }

                        @Override
                        public boolean preselects(
                                PhaseChannel channel,
                                Node event,
                                ExternalChannelFunctionContext context) {
                            recorder.charge(
                                    context.runtimeWorkSession(),
                                    PHASE_PRESELECTION);
                            return true;
                        }

                        @Override
                        public boolean accepts(
                                PhaseChannel channel,
                                Node event,
                                ExternalChannelFunctionContext context) {
                            recorder.charge(
                                    context.runtimeWorkSession(),
                                    PHASE_CHANNEL_EVALUATION);
                            return true;
                        }

                        @Override
                        public Node payload(
                                PhaseChannel channel,
                                Node event,
                                ExternalChannelFunctionContext context) {
                            recorder.charge(
                                    context.runtimeWorkSession(),
                                    PHASE_EXTERNAL_PAYLOAD);
                            return event.clone();
                        }

                        @Override
                        public String logicalDeliveryKey(
                                PhaseChannel channel,
                                Node event,
                                Node payload,
                                ExternalChannelFunctionContext context) {
                            recorder.charge(
                                    context.runtimeWorkSession(),
                                    PHASE_LOGICAL_TARGET);
                            return context.channelKey();
                        }

                        @Override
                        public Node checkpointSubject(
                                PhaseChannel channel,
                                Node event,
                                Node payload,
                                ExternalChannelFunctionContext context) {
                            recorder.charge(
                                    context.runtimeWorkSession(),
                                    PHASE_CHECKPOINT_SUBJECT);
                            return event.clone();
                        }
                    };
        }

        @Override
        public Class<PhaseChannel> contractType() {
            return PhaseChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                PhaseChannel>
        externalSubscriptionFunctions() {
            return functions;
        }

        @Override
        public boolean isNewerEvent(
                PhaseChannel channel,
                ChannelCheckpointContext context) {
            recorder.charge(
                    context.runtimeWorkSession(),
                    PHASE_CHANNEL_CHECKPOINT);
            return true;
        }
    }

    private static final class PhaseHandlerProcessor
            implements HandlerProcessor<PhaseHandler> {

        private final SessionRecorder recorder;

        private PhaseHandlerProcessor(
                SessionRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public Class<PhaseHandler> contractType() {
            return PhaseHandler.class;
        }

        @Override
        public String deriveChannel(
                PhaseHandler handler,
                HandlerRegistrationContext context) {
            recorder.charge(
                    context.runtimeWorkSession(),
                    PHASE_HANDLER_REGISTRATION);
            return "source";
        }

        @Override
        public boolean matches(
                PhaseHandler handler,
                HandlerMatchContext context) {
            recorder.chargeNestedMatch(
                    context.runtimeWorkSession());
            return true;
        }

        @Override
        public void execute(
                PhaseHandler handler,
                ProcessorExecutionContext context) {
            recorder.observe(
                    context.runtimeWorkSession());
        }
    }

    private static final class SessionRecorder {
        private final Map<RuntimeWorkSession, Map<String, Integer>>
                ordinals = new IdentityHashMap<>();
        private final List<RuntimeWorkSession> sessions =
                new ArrayList<>();
        private final Map<String, Integer> processingPhases =
                new LinkedHashMap<>();
        private int processingChargeCount;
        private long processingGas;
        private boolean admissionWork;
        private boolean allSessionsOpenWhenUsed = true;

        synchronized void charge(
                RuntimeWorkSession session,
                String phase) {
            Map<String, Integer> sessionOrdinals =
                    ordinals.computeIfAbsent(
                            session,
                            ignored -> new LinkedHashMap<>());
            int ordinal =
                    sessionOrdinals.getOrDefault(
                            phase, 0) + 1;
            sessionOrdinals.put(phase, ordinal);
            String namespace =
                    HOSTED_NAMESPACE_PREFIX
                            + phase + "." + ordinal;
            chargeLedger(session, namespace, phase);
        }

        synchronized void chargeNestedMatch(
                RuntimeWorkSession session) {
            observe(session);
            GasMeter.ChildGasLedger zeta =
                    session.openLedger(
                            NESTED_ZETA_NAMESPACE,
                            ONE_OPERATION);
            zeta.charge(
                    COUNTER_OPERATION,
                    1L,
                    GasChargeContext.reason(
                            "handler.match.z"));
            GasMeter.ChildGasLedger alpha =
                    session.openLedger(
                            NESTED_ALPHA_NAMESPACE,
                            ONE_OPERATION);
            alpha.charge(
                    COUNTER_OPERATION,
                    1L,
                    GasChargeContext.reason(
                            "handler.match.a"));
            session.submit(zeta);
            session.submit(alpha);
            record(
                    session,
                    PHASE_HANDLER_MATCH,
                    2);
        }

        synchronized void observe(
                RuntimeWorkSession session) {
            sessions.add(session);
            allSessionsOpenWhenUsed &=
                    session.isOpen();
        }

        synchronized void reset() {
            ordinals.clear();
            sessions.clear();
            processingPhases.clear();
            processingChargeCount = 0;
            processingGas = 0L;
            admissionWork = false;
            allSessionsOpenWhenUsed = true;
        }

        synchronized List<String> processingPhases() {
            return new ArrayList<>(
                    processingPhases.keySet());
        }

        synchronized boolean sawAdmissionWork() {
            return admissionWork;
        }

        synchronized boolean allSessionsOpenWhenUsed() {
            return allSessionsOpenWhenUsed;
        }

        synchronized boolean allSessionsClosed() {
            for (RuntimeWorkSession session : sessions) {
                if (session.isOpen()) {
                    return false;
                }
            }
            return true;
        }

        synchronized int processingChargeCount() {
            return processingChargeCount;
        }

        synchronized long processingGas() {
            return processingGas;
        }

        private void chargeLedger(
                RuntimeWorkSession session,
                String namespace,
                String phase) {
            observe(session);
            GasMeter.ChildGasLedger ledger =
                    session.openLedger(
                            namespace,
                            ONE_OPERATION);
            ledger.charge(
                    COUNTER_OPERATION,
                    1L,
                    GasChargeContext.reason(phase));
            session.submit(ledger);
            record(session, phase, 1);
        }

        private void record(
                RuntimeWorkSession session,
                String phase,
                int charges) {
            if (session.mode()
                    == RuntimeWorkSession.Mode.PROCESSING) {
                processingPhases.put(
                        phase,
                        processingPhases.getOrDefault(
                                phase, 0) + charges);
                processingChargeCount += charges;
                processingGas +=
                        charges * ONE_OPERATION.get(
                                COUNTER_OPERATION);
            } else {
                admissionWork = true;
            }
        }
    }
}
