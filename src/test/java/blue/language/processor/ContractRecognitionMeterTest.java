package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractRecognitionMeterTest {

    @Test
    void shouldVerifyCanonicalClassificationBatchGroupsDistinctHeadersAndDeduplicatesThem() {
        // given
        GasMeter gas = new GasMeter();
        ContractRecognitionMeter meter =
                new ContractRecognitionMeter(gas);

        // when
        meter.beginCanonicalClassificationBatch();
        meter.recognizeHeader(
                "/",
                "embedded",
                Arrays.asList("embedded-contribution"),
                "structural-route-header");
        meter.recognizeHeader(
                "/child",
                "in",
                Arrays.asList("channel-contribution"),
                "target-channel-header");
        meter.recognizeHeader(
                "/child",
                "in",
                Arrays.asList("channel-contribution"),
                "target-channel-header");
        meter.flushCanonicalClassificationBatch();
        int traceSize = gas.trace().size();
        GasTraceEntry aggregate = gas.trace().get(0);

        // then
        assertEquals(1, traceSize);
        assertEquals("contractHeaderRecognized", aggregate.counter());
        assertEquals(2L, aggregate.quantity());
        assertEquals("/", aggregate.scopePath());
        assertEquals(
                "structural-and-channel-headers",
                aggregate.reason());
    }

    @Test
    void shouldNotChargeHeadersRecognizedInPriorCanonicalBatch() {
        // given
        GasMeter gas = new GasMeter();
        ContractRecognitionMeter meter =
                new ContractRecognitionMeter(gas);

        // when
        meter.beginCanonicalClassificationBatch();
        meter.recognizeHeader(
                "/",
                "embedded",
                Arrays.asList("embedded-contribution"),
                "structural-route-header");
        meter.recognizeHeader(
                "/child",
                "in",
                Arrays.asList("channel-contribution"),
                "target-channel-header");
        meter.flushCanonicalClassificationBatch();
        meter.beginCanonicalClassificationBatch();
        meter.recognizeHeader(
                "/",
                "embedded",
                Arrays.asList("embedded-contribution"),
                "structural-route-header");
        meter.recognizeHeader(
                "/child",
                "in",
                Arrays.asList("channel-contribution"),
                "target-channel-header");
        meter.flushCanonicalClassificationBatch();
        int traceSize = gas.trace().size();

        // then
        assertEquals(
                1,
                traceSize,
                "headers admitted in a prior batch remain recognized");
    }

    @Test
    void shouldVerifySingleHeaderClassificationBatchPreservesExactContext() {
        // given
        GasMeter gas = new GasMeter();
        ContractRecognitionMeter meter =
                new ContractRecognitionMeter(gas);

        // when
        meter.beginCanonicalClassificationBatch();
        meter.recognizeHeader(
                "/child",
                "in",
                Arrays.asList("channel-contribution"),
                "target-channel-header");
        meter.flushCanonicalClassificationBatch();
        int traceSize = gas.trace().size();
        GasTraceEntry entry = gas.trace().get(0);

        // then
        assertEquals(1, traceSize);
        assertEquals(1L, entry.quantity());
        assertEquals("/child", entry.scopePath());
        assertEquals("in", entry.contractKey());
        assertEquals("target-channel-header", entry.reason());
    }

    @Test
    void shouldVerifyFullRecognitionChargesEachExactContributionTupleOnce() {
        // given
        DocumentProcessor processor =
                DocumentProcessor.builder().build();
        ContractLoader loader = processor.contractLoader();
        GasMeter gas = new GasMeter();
        ContractRecognitionMeter meter =
                new ContractRecognitionMeter(gas);

        Node first = channel(
                RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL);
        Node second = channel(
                RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL);
        Node scope = scope(first, second);
        FrozenNode frozen = FrozenNode.fromResolvedNode(scope);

        // when
        loader.load(
                frozen,
                frozen,
                "/",
                NoOpProcessingObserver.INSTANCE,
                meter,
                "participating-contract-header");
        loader.load(
                frozen,
                frozen,
                "/",
                NoOpProcessingObserver.INSTANCE,
                meter,
                "participating-contract-header");
        long quantityAfterDuplicateLoad =
                quantity(
                        gas,
                        "processor",
                        "contractHeaderRecognized");
        first.properties("order", new Node().value(7));
        FrozenNode changed =
                FrozenNode.fromResolvedNode(
                        scope(first, second));
        loader.load(
                changed,
                changed,
                "/",
                NoOpProcessingObserver.INSTANCE,
                meter,
                "participating-contract-header");
        long quantityAfterChangedContribution =
                quantity(
                        gas,
                        "processor",
                        "contractHeaderRecognized");

        // then
        assertEquals(
                2L,
                quantityAfterDuplicateLoad);
        assertEquals(
                3L,
                quantityAfterChangedContribution,
                "only the changed ordered contribution tuple is new");
    }

    @Test
    void shouldVerifyMalformedProcessEmbeddedBodyChargesItsExactHeaderButNoPathEntry() {
        // given
        DocumentProcessor processor =
                DocumentProcessor.builder().build();
        Node malformed = new Node()
                .type(reference(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().value("/child"));
        FrozenNode scope = FrozenNode.fromResolvedNode(
                new Node().contracts(
                        new Node().properties(
                                "embedded",
                                malformed)));
        GasMeter gas = new GasMeter();

        // when
        Throwable failure = captureFailure(
                () -> processor.contractLoader()
                        .loadExternalClassification(
                                scope,
                                scope,
                                "/",
                                null,
                                true,
                                NoOpProcessingObserver.INSTANCE,
                                new ContractRecognitionMeter(gas),
                                "structural-route-header"));

        // then
        assertTrue(failure instanceof MustUnderstandFailureException);
        assertEquals(
                1L,
                quantity(
                        gas,
                        "processor",
                        "contractHeaderRecognized"));
        assertEquals(
                0L,
                quantity(
                        gas,
                        "processor",
                        "embeddedPathEntryRead"));
    }

    @Test
    void shouldVerifyAbsentProcessEmbeddedHasNoSyntheticHeaderCharge() {
        // given
        DocumentProcessor processor =
                DocumentProcessor.builder().build();
        FrozenNode scope = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "child",
                        new Node()));
        GasMeter gas = new GasMeter();

        // when
        ContractBundle bundle =
                processor.contractLoader()
                        .loadExternalClassification(
                                scope,
                                scope,
                                "/",
                                null,
                                true,
                                NoOpProcessingObserver.INSTANCE,
                                new ContractRecognitionMeter(gas),
                                "structural-route-header");

        // then
        assertTrue(bundle.effectiveContractSnapshots()
                .isEmpty());
        assertEquals(
                0L,
                quantity(
                        gas,
                        "processor",
                        "contractHeaderRecognized"));
    }

    @Test
    void shouldRetainEffectiveProcessEmbeddedDeclarationAtArbitraryKey() {
        // given
        DocumentProcessor processor =
                DocumentProcessor.builder().build();
        FrozenNode selected =
                processEmbeddedScope(
                        "workflowSubscriptions",
                        false,
                        "/child",
                        "/child/grandchild");
        FrozenNode effective =
                processEmbeddedScope(
                        "workflowSubscriptions",
                        true,
                        "/child",
                        "/child/grandchild");

        // when
        ContractBundle bundle =
                processor.contractLoader()
                        .loadExternalClassification(
                                selected,
                                effective,
                                "/",
                                null,
                                true,
                                NoOpProcessingObserver.INSTANCE);

        // then
        assertEquals(
                Arrays.asList(
                        "/child",
                        "/child/grandchild"),
                bundle.embeddedPaths());
        assertEquals(
                RuntimeBlueIds.PROCESS_EMBEDDED,
                bundle.effectiveContractSnapshot(
                        "workflowSubscriptions")
                        .effectiveTypeBlueId());
    }

    @Test
    void shouldVerifyPathEntryExhaustionStopsBeforeTheSecondEntryAndHeader() {
        // given
        DocumentProcessor processor =
                DocumentProcessor.builder().build();
        FrozenNode scope = processEmbeddedScope(
                "/first",
                "/second/leaf");

        // when
        GasMeter completeGas = new GasMeter();
        processor.contractLoader()
                .loadExternalClassification(
                        scope,
                        scope,
                        "/",
                        null,
                        true,
                        NoOpProcessingObserver.INSTANCE,
                        new ContractRecognitionMeter(
                                completeGas),
                        "structural-route-header");
        List<String> logicalPaths = new ArrayList<>();
        for (GasTraceEntry entry : completeGas.trace()) {
            if ("embeddedPathEntryRead".equals(
                    entry.counter())) {
                logicalPaths.add(entry.logicalPath());
            }
        }
        long prefix = prefixBeforeSecondPathEntry(
                completeGas);
        GasMeter limited =
                new GasMeter(
                        GasSchedule.contracts10(),
                        prefix);
        Throwable failure =
                captureFailure(
                        () -> processor.contractLoader()
                                .loadExternalClassification(
                                        scope,
                                        scope,
                                        "/",
                                        null,
                                        true,
                                        NoOpProcessingObserver.INSTANCE,
                                        new ContractRecognitionMeter(
                                                limited),
                                        "structural-route-header"));

        // then
        assertEquals(
                Arrays.asList("/first", "/second/leaf"),
                logicalPaths,
                "route gas names the authored logical paths, not manifest pointers");
        assertTrue(failure instanceof GasLimitExceededException);
        assertEquals(
                prefix,
                ((GasLimitExceededException) failure)
                        .admittedGas());
        assertEquals(
                1L,
                quantity(
                        limited,
                        "processor",
                        "embeddedPathEntryRead"));
        assertEquals(
                1L,
                quantity(
                        limited,
                        "processor",
                        "contractHeaderRecognized"));
        assertEquals(prefix, limited.totalGas());
    }

    private static FrozenNode processEmbeddedScope(
            String... paths) {
        return processEmbeddedScope(
                "embedded",
                true,
                paths);
    }

    private static FrozenNode processEmbeddedScope(
            String key,
            boolean includeType,
            String... paths) {
        Node pathList = new Node();
        List<Node> items = new ArrayList<>();
        for (String path : paths) {
            items.add(new Node().value(path));
        }
        pathList.items(items);
        Node embedded =
                new Node().properties(
                        "paths",
                        pathList);
        if (includeType) {
            embedded.type(reference(
                    RuntimeBlueIds.PROCESS_EMBEDDED));
        }
        return FrozenNode.fromResolvedNode(
                new Node().contracts(
                        new Node().properties(
                                key,
                                embedded)));
    }

    private static Node scope(Node first,
                              Node second) {
        return new Node().contracts(
                new Node()
                        .properties("first", first)
                        .properties("second", second));
    }

    private static Node channel(String typeBlueId) {
        return new Node().type(
                reference(typeBlueId));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static long prefixBeforeSecondPathEntry(
            GasMeter gas) {
        int entries = 0;
        long prefix = 0L;
        for (GasTraceEntry entry : gas.trace()) {
            if ("processor".equals(entry.namespace())
                    && "embeddedPathEntryRead".equals(
                    entry.counter())
                    && ++entries == 2) {
                return prefix;
            }
            prefix += entry.subtotal();
        }
        throw new AssertionError(
                "Complete trace did not contain two path entries: "
                        + Arrays.toString(
                        gas.trace().toArray()));
    }

    private static long quantity(GasMeter gas,
                                 String namespace,
                                 String counter) {
        long quantity = 0L;
        for (GasTraceEntry entry : gas.trace()) {
            if (namespace.equals(entry.namespace())
                    && counter.equals(entry.counter())) {
                quantity += entry.quantity();
            }
        }
        return quantity;
    }
}
