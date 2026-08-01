package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.EmitEventsContractProcessor;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TerminateScopeContractProcessor;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static blue.language.processor.DocumentProcessingResultTestSupport.diagnosticCategory;
import static blue.language.processor.DocumentProcessingResultTestSupport.diagnosticMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable coverage for the live-gas and infinite-reaction requirements in
 * the final Contracts 1.0 implementation prompt.
 *
 * <p>Every loop is made exclusively from ordinary Handler effects. The tests
 * therefore exercise the same synchronous Document Update cascade and
 * invocation event FIFO used by applications; there is no host-side loop,
 * callback, or opaque gas result.</p>
 */
final class GasReactionBoundaryTest {

    private static final String TEST_EVENT_CHANNEL =
            ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;
    private static final String TEST_EVENT =
            ProcessorTestTypeBlueIds.TEST_EVENT;
    private static final String SET_PROPERTY =
            ProcessorTestTypeBlueIds.SET_PROPERTY;
    private static final String INCREMENT_PROPERTY =
            ProcessorTestTypeBlueIds.INCREMENT_PROPERTY;
    private static final String EMIT_EVENTS =
            ProcessorTestTypeBlueIds.EMIT_EVENTS;
    private static final String TERMINATE_SCOPE =
            ProcessorTestTypeBlueIds.TERMINATE_SCOPE;

    @Test
    void shouldVerifyDocumentUpdateCycleStopsOnLiveGasAndRollsBackExactRunState() {
        // given
        Supplier<Blue> factory = () -> processingBlue(
                null,
                new EmitEventsContractProcessor(),
                new SetPropertyContractProcessor(),
                new IncrementPropertyContractProcessor());
        Node initialized = initialize(factory, documentUpdateCycleDocument());
        Node event = event("document-update-cycle", "seed");

        // when
        ProcessingDebugResult first = process(
                () -> processingBlue(
                        6_000L,
                        new EmitEventsContractProcessor(),
                        new SetPropertyContractProcessor(),
                        new IncrementPropertyContractProcessor()),
                initialized,
                event);
        ProcessingDebugResult replay = process(
                () -> processingBlue(
                        6_000L,
                        new EmitEventsContractProcessor(),
                        new SetPropertyContractProcessor(),
                        new IncrementPropertyContractProcessor()),
                initialized,
                event);

        // then
        assertGasRollback(initialized, first);
        assertDeterministicFailureTrace(first, replay);
        assertTrue(
                first.trace().counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .DOCUMENT_UPDATE_DELIVERED) >= 2L,
                "the live limit must stop an executing Document Update cycle");
        assertTrue(
                first.trace().records(
                        ProcessingTraceRecord.Kind.DOCUMENT_UPDATE).size() >= 2,
                "the failure prefix must contain the repeated update cascade");
    }

    @Test
    void shouldVerifyEmbeddedEventCycleStopsOnLiveGasAndRollsBackExactRunState() {
        // given
        Supplier<Blue> factory = () -> processingBlue(
                null,
                new EmitEventsContractProcessor());
        Node initialized = initialize(factory, embeddedEventCycleDocument());
        Node event = event("embedded-event-cycle", "seed");

        // when
        ProcessingDebugResult first = process(
                () -> processingBlue(
                        8_000L,
                        new EmitEventsContractProcessor()),
                initialized,
                event);
        ProcessingDebugResult replay = process(
                () -> processingBlue(
                        8_000L,
                        new EmitEventsContractProcessor()),
                initialized,
                event);

        // then
        assertGasRollback(initialized, first);
        assertDeterministicFailureTrace(first, replay);
        assertTrue(
                first.trace().counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_EVENT_DELIVERED) >= 1L,
                "the cycle must be entered through an Embedded Node Channel");
        assertTrue(
                first.trace().counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .INTERNAL_EVENT_DEQUEUED) >= 2L,
                "the invocation FIFO must execute the repeating reaction");
    }

    @Test
    void shouldVerifyLargeFiniteHandlerQueueCompletesInCanonicalOrderDeterministically() {
        // given
        final int queueSize = 512;
        // when
        boolean withinPortableLimit =
                queueSize < GasSchedule.contracts10()
                        .portableLimit(
                                GasScheduleConstants.PortableLimit
                                        .EVENTS_PER_CONTRACT_RESULT);
        Supplier<Blue> factory = () -> processingBlue(
                null,
                new EmitEventsContractProcessor());
        Node initialized = initialize(
                factory,
                finiteQueueDocument(queueSize));
        Node event = event("finite-queue", "seed");

        ProcessingDebugResult first =
                process(factory, initialized, event);
        ProcessingDebugResult replay =
                process(factory, initialized, event);

        // then
        assertTrue(withinPortableLimit);
        assertEquals(
                ProcessorStatus.SUCCESS,
                first.processResult().status(),
                diagnosticMessage(first.processResult()));
        assertTrue(first.processResult().commits());
        assertEquals(queueSize, first.processResult().events().size());
        assertEquals(
                queueSize,
                first.trace().counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .INTERNAL_EVENT_DEQUEUED));
        assertEquals(
                "finite-0000",
                first.processResult().events().get(0)
                        .getAsText("/eventId"));
        assertEquals(
                String.format("finite-%04d", queueSize - 1),
                first.processResult().events().get(queueSize - 1)
                        .getAsText("/eventId"));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        first.processResult().document()),
                DirectBlueIdCalculator.calculateBlueId(
                        replay.processResult().document()));
        assertEquals(
                nodeProjection(first.processResult().events()),
                nodeProjection(replay.processResult().events()));
        assertEquals(
                first.processResult().totalGas(),
                replay.processResult().totalGas());
        assertEquals(gasProjection(first.trace()),
                gasProjection(replay.trace()));
        assertEquals(recordProjection(first.trace()),
                recordProjection(replay.trace()));
    }

    @Test
    void shouldVerifyGasExhaustionDuringInitializationRollsBackAtExactTracePrefix() {
        // given
        String counter =
                GasScheduleConstants.ProcessorCounter
                        .SCOPE_INITIALIZATION;
        String authored = phaseDocument(Phase.INITIALIZATION);

        // when
        PhaseBoundaryObservation observation = observePhaseBoundary(
                counter,
                authored,
                false,
                Phase.INITIALIZATION);

        // then
        assertPhaseBoundary(observation, counter);
    }

    @Test
    void shouldVerifyGasExhaustionDuringCascadeRollsBackAtExactTracePrefix() {
        // given
        String counter =
                GasScheduleConstants.ProcessorCounter
                        .DOCUMENT_UPDATE_DELIVERED;
        String authored = phaseDocument(Phase.CASCADE);

        // when
        PhaseBoundaryObservation observation = observePhaseBoundary(
                counter,
                authored,
                true,
                Phase.CASCADE);

        // then
        assertPhaseBoundary(observation, counter);
    }

    @Test
    void shouldVerifyGasExhaustionDuringCheckpointRollsBackAtExactTracePrefix() {
        // given
        String counter =
                GasScheduleConstants.ProcessorCounter
                        .CHECKPOINT_WRITTEN;
        String authored = phaseDocument(Phase.CHECKPOINT);

        // when
        PhaseBoundaryObservation observation = observePhaseBoundary(
                counter,
                authored,
                true,
                Phase.CHECKPOINT);

        // then
        assertPhaseBoundary(observation, counter);
    }

    @Test
    void shouldVerifyGasExhaustionDuringTerminationRollsBackAtExactTracePrefix() {
        // given
        String counter =
                GasScheduleConstants.ProcessorCounter
                        .TERMINATION_REQUESTED;
        String authored = phaseDocument(Phase.TERMINATION);

        // when
        PhaseBoundaryObservation observation = observePhaseBoundary(
                counter,
                authored,
                true,
                Phase.TERMINATION);

        // then
        assertPhaseBoundary(observation, counter);
    }

    private PhaseBoundaryObservation observePhaseBoundary(
            String counter,
            String authoredYaml,
            boolean initializeFirst,
            Phase phase) {
        Supplier<Blue> unlimitedFactory =
                () -> phaseBlue(null, phase);
        Node authored = parse(unlimitedFactory, authoredYaml);
        Node input = initializeFirst
                ? initialize(unlimitedFactory, authoredYaml)
                : authored;
        Node event = event(
                "phase-" + phase.name().toLowerCase(),
                "seed");

        ProcessingDebugResult successful =
                process(unlimitedFactory, input, event);
        int failedChargeIndex = firstGasIndex(
                successful.trace(),
                GasScheduleConstants.Namespace.PROCESSOR,
                counter);
        long exactPrefixBudget = failedChargeIndex >= 0
                ? successful.trace().gas()
                        .subList(0, failedChargeIndex)
                        .stream()
                        .mapToLong(GasTraceEntry::subtotal)
                        .sum()
                : 0L;

        Supplier<Blue> limitedFactory =
                () -> phaseBlue(exactPrefixBudget, phase);
        ProcessingDebugResult first =
                process(limitedFactory, input, event);
        ProcessingDebugResult replay =
                process(limitedFactory, input, event);

        return new PhaseBoundaryObservation(
                input,
                successful,
                failedChargeIndex,
                first,
                replay);
    }

    private void assertPhaseBoundary(
            PhaseBoundaryObservation observation,
            String counter) {
        assertEquals(
                ProcessorStatus.SUCCESS,
                observation.successful
                        .processResult()
                        .status(),
                diagnosticMessage(
                        observation.successful
                                .processResult()));
        assertTrue(
                observation.failedChargeIndex >= 0,
                "successful control run did not reach processor."
                        + counter);
        assertGasRollback(observation.input, observation.first);
        assertEquals(
                counter,
                observation.first
                        .processResult()
                        .diagnostic()
                        .details()
                        .get(ProcessorDiagnosticConstants
                                .FIELD_COUNTER));
        assertEquals(
                gasProjection(observation.successful.trace())
                        .subList(
                                0,
                                observation.failedChargeIndex),
                gasProjection(observation.first.trace()),
                "the failed charge itself must be omitted");
        assertTrue(
                isPrefix(
                        recordProjection(
                                observation.first.trace()),
                        recordProjection(
                                observation.successful.trace())),
                "the failed run record must be an exact successful prefix");
        assertDeterministicFailureTrace(
                observation.first,
                observation.replay);
    }

    private Blue phaseBlue(Long gasLimit, Phase phase) {
        switch (phase) {
            case INITIALIZATION:
                return processingBlue(gasLimit);
            case CASCADE:
                return processingBlue(
                        gasLimit,
                        new EmitEventsContractProcessor(),
                        new SetPropertyContractProcessor());
            case CHECKPOINT:
                return processingBlue(
                        gasLimit,
                        new EmitEventsContractProcessor(),
                        new SetPropertyContractProcessor());
            case TERMINATION:
                return processingBlue(
                        gasLimit,
                        new EmitEventsContractProcessor(),
                        new SetPropertyContractProcessor(),
                        new TerminateScopeContractProcessor());
            default:
                throw new IllegalArgumentException(
                        "Unknown phase: " + phase);
        }
    }

    private Blue processingBlue(
            Long gasLimit,
            ContractProcessor<?>... processors) {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        if (processors != null) {
            for (ContractProcessor<?> processor : processors) {
                blue.registerContractProcessor(processor);
            }
        }
        if (gasLimit == null) {
            DocumentProcessorExactFeederSupport.install(blue);
        } else {
            DocumentProcessorExactFeederSupport.install(
                    blue, gasLimit);
        }
        return blue;
    }

    private Node initialize(
            Supplier<Blue> factory,
            String yaml) {
        Blue blue = factory.get();
        try {
            Node authored = blue.yamlToNode(yaml);
            DocumentProcessingResult result =
                    blue.initializeDocument(authored);
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    result.status(),
                    diagnosticMessage(result));
            return result.document();
        } finally {
            blue.close();
        }
    }

    private Node parse(
            Supplier<Blue> factory,
            String yaml) {
        Blue blue = factory.get();
        try {
            return blue.yamlToNode(yaml);
        } finally {
            blue.close();
        }
    }

    private ProcessingDebugResult process(
            Supplier<Blue> factory,
            Node input,
            Node event) {
        Blue blue = factory.get();
        try {
            return blue.getDocumentProcessor()
                    .processDocumentWithTrace(
                            input.clone(), event.clone());
        } finally {
            blue.close();
        }
    }

    private void assertGasRollback(
            Node input,
            ProcessingDebugResult debug) {
        DocumentProcessingResult result =
                debug.processResult();
        assertEquals(
                ProcessorStatus.GAS_LIMIT_EXCEEDED,
                result.status(),
                diagnosticMessage(result));
        assertEquals(
                ProcessorErrorCategory.GasLimitExceeded,
                diagnosticCategory(result));
        assertFalse(result.commits());
        assertEquals(
                input.toString(),
                result.document().toString(),
                "noncommitting gas exhaustion must return the exact input Root");
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(input),
                DirectBlueIdCalculator.calculateBlueId(
                        result.document()));
        assertTrue(
                result.events().isEmpty(),
                "Root emissions must be discarded");
        assertNull(
                nodeOrNull(
                        result.document(),
                        "/contracts/checkpoint"),
                "pending source checkpoints must be discarded");
    }

    private void assertDeterministicFailureTrace(
            ProcessingDebugResult first,
            ProcessingDebugResult replay) {
        assertEquals(
                first.processResult().status(),
                replay.processResult().status());
        assertEquals(
                first.processResult().diagnostic().details(),
                replay.processResult().diagnostic().details());
        assertEquals(
                first.processResult().totalGas(),
                replay.processResult().totalGas());
        assertEquals(
                gasProjection(first.trace()),
                gasProjection(replay.trace()));
        assertEquals(
                recordProjection(first.trace()),
                recordProjection(replay.trace()));
        assertEquals(
                first.trace().semanticDemands(),
                replay.trace().semanticDemands());
        assertEquals(
                contractSnapshotProjection(first.trace()),
                contractSnapshotProjection(replay.trace()));
    }

    private int firstGasIndex(
            ProcessingConformanceTrace trace,
            String namespace,
            String counter) {
        for (int index = 0;
             index < trace.gas().size();
             index++) {
            GasTraceEntry entry = trace.gas().get(index);
            if (namespace.equals(entry.namespace())
                    && counter.equals(entry.counter())) {
                return index;
            }
        }
        return -1;
    }

    private boolean isPrefix(
            List<String> prefix,
            List<String> complete) {
        return prefix.size() <= complete.size()
                && prefix.equals(
                complete.subList(0, prefix.size()));
    }

    private List<String> gasProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection = new ArrayList<>();
        for (GasTraceEntry entry : trace.gas()) {
            projection.add(
                    entry.sequence()
                            + "|" + entry.namespace()
                            + "|" + entry.counter()
                            + "|" + entry.quantity()
                            + "|" + entry.weight()
                            + "|" + entry.subtotal()
                            + "|" + entry.scopePath()
                            + "|" + entry.contractKey()
                            + "|" + entry.logicalPath()
                            + "|" + entry.reason());
        }
        return projection;
    }

    private List<String> recordProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection = new ArrayList<>();
        for (ProcessingTraceRecord record : trace.records()) {
            projection.add(
                    record.sequence()
                            + "|" + record.kind()
                            + "|" + record.scopePath()
                            + "|" + record.contractKey()
                            + "|" + record.logicalPath()
                            + "|" + record.details()
                            + "|" + (record.node() != null
                            ? ProcessorEngine.canonicalSignature(
                                    record.node())
                            : null));
        }
        return projection;
    }

    private List<String> contractSnapshotProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection = new ArrayList<>();
        for (Map.Entry<String, EffectiveContractSnapshot> entry
                : trace.contractSnapshots().entrySet()) {
            EffectiveContractSnapshot snapshot =
                    entry.getValue();
            projection.add(
                    entry.getKey()
                            + "|" + snapshot.scopePath()
                            + "|" + snapshot.key()
                            + "|" + snapshot
                            .sourceContributionNodeBlueIds()
                            + "|" + snapshot.effectiveTypeBlueId()
                            + "|" + snapshot.role()
                            + "|" + snapshot.order()
                            + "|" + snapshot.dispatchFields()
                            + "|" + snapshot
                            .executableBodyNodeBlueIds()
                            + "|" + snapshot
                            .deterministicDependencyNodeBlueIds());
        }
        return projection;
    }

    private List<String> nodeProjection(List<Node> nodes) {
        List<String> identities =
                new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            identities.add(node.toString());
        }
        return identities;
    }

    private Node event(String eventId, String kind) {
        return new TestEvent()
                .eventId(eventId)
                .kind(kind)
                .toNode();
    }

    private Node nodeOrNull(Node root, String pointer) {
        try {
            return root.getNode(pointer);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String documentUpdateCycleDocument() {
        return "name: Document Update gas cycle\n"
                + "counter: 0\n"
                + "contracts:\n"
                + externalChannel("incoming", 0)
                + emitHandler("publicBeforeCycle", "incoming", 0,
                "cycle-public", "bystander")
                + "  seed:\n"
                + "    order: 1\n"
                + "    channel: incoming\n"
                + "    type:\n"
                + "      blueId: " + SET_PROPERTY + "\n"
                + "    propertyKey: /counter\n"
                + "    propertyValue: 1\n"
                + "  counterUpdates:\n"
                + "    type:\n"
                + "      blueId: "
                + RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL + "\n"
                + "    path: /counter\n"
                + "  incrementForever:\n"
                + "    channel: counterUpdates\n"
                + "    type:\n"
                + "      blueId: " + INCREMENT_PROPERTY + "\n"
                + "    propertyKey: /counter\n";
    }

    private String embeddedEventCycleDocument() {
        return "name: Embedded event gas cycle\n"
                + "child:\n"
                + "  name: Event source child\n"
                + "  contracts:\n"
                + indent(externalChannel("incoming", 0), 2)
                + indent(emitHandler(
                "start", "incoming", 0,
                "child-loop", "loop"), 2)
                + "contracts:\n"
                + "  embedded:\n"
                + "    type:\n"
                + "      blueId: "
                + RuntimeBlueIds.PROCESS_EMBEDDED + "\n"
                + "    paths:\n"
                + "      - /child\n"
                + "  fromChild:\n"
                + "    type:\n"
                + "      blueId: "
                + RuntimeBlueIds.EMBEDDED_NODE_CHANNEL + "\n"
                + "    sourcePath: /child\n"
                + "    event:\n"
                + "      type:\n"
                + "        blueId: " + TEST_EVENT + "\n"
                + emitHandler(
                "bridge", "fromChild", 0,
                "root-loop-0", "loop", null)
                + "  rootLoop:\n"
                + "    type:\n"
                + "      blueId: "
                + RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL + "\n"
                + "    event:\n"
                + "      type:\n"
                + "        blueId: " + TEST_EVENT + "\n"
                + emitHandler(
                "repeat", "rootLoop", 0,
                "root-loop-1", "loop", "loop");
    }

    private String finiteQueueDocument(int queueSize) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: Maximum finite event queue\n")
                .append("contracts:\n")
                .append(externalChannel("incoming", 0))
                .append("  enqueue:\n")
                .append("    channel: incoming\n")
                .append("    type:\n")
                .append("      blueId: ")
                .append(EMIT_EVENTS)
                .append('\n')
                .append("    expectedKind: seed\n")
                .append("    events:\n");
        for (int index = 0; index < queueSize; index++) {
            yaml.append("      - type:\n")
                    .append("          blueId: ")
                    .append(TEST_EVENT)
                    .append('\n')
                    .append("        eventId: ")
                    .append(String.format(
                            "finite-%04d", index))
                    .append('\n')
                    .append("        kind: finite\n");
        }
        return yaml.toString();
    }

    private String phaseDocument(Phase phase) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: Gas phase ")
                .append(phase.name().toLowerCase())
                .append('\n')
                .append("contracts:\n")
                .append(externalChannel("incoming", 0));
        if (phase == Phase.INITIALIZATION) {
            return yaml.toString();
        }
        yaml.append(emitHandler(
                "publicBeforeBoundary", "incoming", 0,
                "phase-public", "bystander"));
        if (phase == Phase.CASCADE) {
            yaml.append("  mutate:\n")
                    .append("    order: 1\n")
                    .append("    channel: incoming\n")
                    .append("    type:\n")
                    .append("      blueId: ")
                    .append(SET_PROPERTY)
                    .append('\n')
                    .append("    propertyKey: /cascadeSource\n")
                    .append("    propertyValue: 1\n")
                    .append("  updates:\n")
                    .append("    type:\n")
                    .append("      blueId: ")
                    .append(RuntimeBlueIds
                            .DOCUMENT_UPDATE_CHANNEL)
                    .append('\n')
                    .append("    path: /cascadeSource\n")
                    .append("  observe:\n")
                    .append("    channel: updates\n")
                    .append("    type:\n")
                    .append("      blueId: ")
                    .append(SET_PROPERTY)
                    .append('\n')
                    .append("    propertyKey: /cascadeObserved\n")
                    .append("    propertyValue: 1\n");
        } else if (phase == Phase.CHECKPOINT) {
            yaml.append("  mutate:\n")
                    .append("    order: 1\n")
                    .append("    channel: incoming\n")
                    .append("    type:\n")
                    .append("      blueId: ")
                    .append(SET_PROPERTY)
                    .append('\n')
                    .append("    propertyKey: /checkpointWork\n")
                    .append("    propertyValue: 1\n");
        } else if (phase == Phase.TERMINATION) {
            yaml.append("  mutate:\n")
                    .append("    order: 1\n")
                    .append("    channel: incoming\n")
                    .append("    type:\n")
                    .append("      blueId: ")
                    .append(SET_PROPERTY)
                    .append('\n')
                    .append("    propertyKey: /beforeTermination\n")
                    .append("    propertyValue: 1\n")
                    .append("  terminate:\n")
                    .append("    order: 2\n")
                    .append("    channel: incoming\n")
                    .append("    type:\n")
                    .append("      blueId: ")
                    .append(TERMINATE_SCOPE)
                    .append('\n')
                    .append("    mode: graceful\n")
                    .append("    reason: exact gas boundary\n");
        }
        return yaml.toString();
    }

    private String externalChannel(
            String key,
            int order) {
        return "  " + key + ":\n"
                + "    order: " + order + "\n"
                + "    type:\n"
                + "      blueId: " + TEST_EVENT_CHANNEL
                + "\n";
    }

    private String emitHandler(
            String key,
            String channel,
            int order,
            String eventId,
            String kind) {
        return emitHandler(
                key, channel, order, eventId, kind, "seed");
    }

    private String emitHandler(
            String key,
            String channel,
            int order,
            String eventId,
            String kind,
            String expectedKind) {
        return "  " + key + ":\n"
                + "    order: " + order + "\n"
                + "    channel: " + channel + "\n"
                + "    type:\n"
                + "      blueId: " + EMIT_EVENTS + "\n"
                + (expectedKind != null
                ? "    expectedKind: " + expectedKind + "\n"
                : "")
                + "    events:\n"
                + "      - type:\n"
                + "          blueId: " + TEST_EVENT + "\n"
                + "        eventId: " + eventId + "\n"
                + "        kind: " + kind + "\n";
    }

    private String indent(String value, int spaces) {
        String padding = String.join(
                "", Collections.nCopies(spaces, " "));
        String indented =
                padding + value.replace(
                        "\n", "\n" + padding);
        return value.endsWith("\n")
                ? indented.substring(
                0, indented.length() - spaces)
                : indented;
    }

    private static final class PhaseBoundaryObservation {
        private final Node input;
        private final ProcessingDebugResult successful;
        private final int failedChargeIndex;
        private final ProcessingDebugResult first;
        private final ProcessingDebugResult replay;

        private PhaseBoundaryObservation(
                Node input,
                ProcessingDebugResult successful,
                int failedChargeIndex,
                ProcessingDebugResult first,
                ProcessingDebugResult replay) {
            this.input = input;
            this.successful = successful;
            this.failedChargeIndex = failedChargeIndex;
            this.first = first;
            this.replay = replay;
        }
    }

    private enum Phase {
        INITIALIZATION,
        CASCADE,
        CHECKPOINT,
        TERMINATION
    }
}
