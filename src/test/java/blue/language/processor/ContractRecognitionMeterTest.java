package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractRecognitionMeterTest {

    @Test
    void canonicalClassificationBatchGroupsDistinctHeadersAndDeduplicatesThem() {
        GasMeter gas = new GasMeter();
        ContractRecognitionMeter meter =
                new ContractRecognitionMeter(gas);

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

        assertEquals(1, gas.trace().size());
        GasTraceEntry aggregate = gas.trace().get(0);
        assertEquals("contractHeaderRecognized", aggregate.counter());
        assertEquals(2L, aggregate.quantity());
        assertEquals("/", aggregate.scopePath());
        assertEquals(
                "structural-and-channel-headers",
                aggregate.reason());

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

        assertEquals(
                1,
                gas.trace().size(),
                "headers admitted in a prior batch remain recognized");
    }

    @Test
    void singleHeaderClassificationBatchPreservesExactContext() {
        GasMeter gas = new GasMeter();
        ContractRecognitionMeter meter =
                new ContractRecognitionMeter(gas);

        meter.beginCanonicalClassificationBatch();
        meter.recognizeHeader(
                "/child",
                "in",
                Arrays.asList("channel-contribution"),
                "target-channel-header");
        meter.flushCanonicalClassificationBatch();

        assertEquals(1, gas.trace().size());
        GasTraceEntry entry = gas.trace().get(0);
        assertEquals(1L, entry.quantity());
        assertEquals("/child", entry.scopePath());
        assertEquals("in", entry.contractKey());
        assertEquals("target-channel-header", entry.reason());
    }

    @Test
    void fullRecognitionChargesEachExactContributionTupleOnce() {
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

        loader.load(
                frozen,
                frozen,
                "/",
                ProcessingMetricsSink.NOOP,
                meter,
                "participating-contract-header");
        loader.load(
                frozen,
                frozen,
                "/",
                ProcessingMetricsSink.NOOP,
                meter,
                "participating-contract-header");

        assertEquals(
                2L,
                quantity(
                        gas,
                        "processor",
                        "contractHeaderRecognized"));

        first.properties("order", new Node().value(7));
        FrozenNode changed =
                FrozenNode.fromResolvedNode(
                        scope(first, second));
        loader.load(
                changed,
                changed,
                "/",
                ProcessingMetricsSink.NOOP,
                meter,
                "participating-contract-header");

        assertEquals(
                3L,
                quantity(
                        gas,
                        "processor",
                        "contractHeaderRecognized"),
                "only the changed ordered contribution tuple is new");
    }

    @Test
    void malformedProcessEmbeddedBodyChargesItsExactHeaderButNoPathEntry() {
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

        assertThrows(
                MustUnderstandFailureException.class,
                () -> processor.contractLoader()
                        .loadExternalClassification(
                                scope,
                                scope,
                                "/",
                                null,
                                true,
                                ProcessingMetricsSink.NOOP,
                                new ContractRecognitionMeter(gas),
                                "structural-route-header"));

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
    void absentProcessEmbeddedHasNoSyntheticHeaderCharge() {
        DocumentProcessor processor =
                DocumentProcessor.builder().build();
        FrozenNode scope = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "child",
                        new Node()));
        GasMeter gas = new GasMeter();

        ContractBundle bundle =
                processor.contractLoader()
                        .loadExternalClassification(
                                scope,
                                scope,
                                "/",
                                null,
                                true,
                                ProcessingMetricsSink.NOOP,
                                new ContractRecognitionMeter(gas),
                                "structural-route-header");

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
    void pathEntryExhaustionStopsBeforeTheSecondEntryAndHeader() {
        DocumentProcessor processor =
                DocumentProcessor.builder().build();
        FrozenNode scope = processEmbeddedScope(
                "/first",
                "/second/leaf");

        GasMeter completeGas = new GasMeter();
        processor.contractLoader()
                .loadExternalClassification(
                        scope,
                        scope,
                        "/",
                        null,
                        true,
                        ProcessingMetricsSink.NOOP,
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
        assertEquals(
                Arrays.asList("/first", "/second/leaf"),
                logicalPaths,
                "route gas names the authored logical paths, not manifest pointers");

        long prefix = prefixBeforeSecondPathEntry(
                completeGas);
        GasMeter limited =
                new GasMeter(
                        GasSchedule.contracts10(),
                        prefix);

        GasLimitExceededException failure =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> processor.contractLoader()
                                .loadExternalClassification(
                                        scope,
                                        scope,
                                        "/",
                                        null,
                                        true,
                                        ProcessingMetricsSink.NOOP,
                                        new ContractRecognitionMeter(
                                                limited),
                                        "structural-route-header"));

        assertEquals(prefix, failure.admittedGas());
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
        Node pathList = new Node();
        List<Node> items = new ArrayList<>();
        for (String path : paths) {
            items.add(new Node().value(path));
        }
        pathList.items(items);
        Node embedded = new Node()
                .type(reference(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", pathList);
        return FrozenNode.fromResolvedNode(
                new Node().contracts(
                        new Node().properties(
                                "embedded",
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
