package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.TestEvent;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractBundleCacheTest {

    @Test
    void shouldVerifyProcessingStateChangesRebuildMeteredBundlesAndRefreshCheckpointMarkers() {
        // given
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(metrics);
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "count: 0\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  increment:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "    channel: testChannel\n" +
                "    propertyKey: /count\n")).document();

        // when
        DocumentProcessingResult first = blue.processDocument(initialized, event(blue, "evt-1"));
        DocumentProcessingResult second = blue.processDocument(first.document(), event(blue, "evt-2"));
        DocumentProcessingResult duplicate = blue.processDocument(second.document(), event(blue, "evt-2"));

        // then
        assertEquals(new BigInteger("2"), duplicate.document().get("/count"));
        assertEquals(0L, metrics.bundleLoadCacheHits,
                "metered PROCESS recognition cannot take a physical cache discount");
        assertEquals(0L, metrics.bundlesReused,
                "exact recognition rebuilds the observable bundle each run");
    }

    @Test
    void shouldVerifyChangingContractsInvalidatesBundleCache() {
        // given
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(metrics);
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "orders: {}\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "  set:\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    channel: testChannel\n" +
                "    path: /orders\n" +
                "    propertyKey: count\n" +
                "    propertyValue: 1\n")).document();

        // when
        DocumentProcessingResult first = blue.processDocument(initialized, event(blue, "evt-1"));
        Node changedContracts = first.document().clone();
        changedContracts.getAsNode("/contracts/set")
                .properties("propertyValue", new Node().value(2));
        DocumentProcessingResult second = blue.processDocument(changedContracts, event(blue, "evt-2"));

        // then
        assertEquals(new BigInteger("2"), second.document().get("/orders/count"));
        assertEquals(0L, metrics.bundleLoadCacheHits);
        assertEquals(0L, metrics.bundlesReused);
    }

    @Test
    void shouldVerifyEmbeddedScopesCacheIndependently() {
        // given
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(metrics);
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "child:\n" +
                "  count: 0\n" +
                "  contracts:\n" +
                "    testChannel:\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL + "\n" +
                "    increment:\n" +
                "      type:\n" +
                "        blueId: " + ProcessorTestTypeBlueIds.INCREMENT_PROPERTY + "\n" +
                "      channel: testChannel\n" +
                "      propertyKey: /count\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /child\n")).document();

        // when
        DocumentProcessingResult first = blue.processDocument(initialized, event(blue, "evt-1"));
        DocumentProcessingResult second = blue.processDocument(first.document(), event(blue, "evt-2"));

        // then
        assertEquals(new BigInteger("2"), second.document().get("/child/count"));
        assertEquals(0L, metrics.bundleLoadCacheHits,
                "root and child recognition both remain representation-independent");
        assertEquals(0L, metrics.bundlesReused);
    }

    private Blue configuredBlue(RecordingMetrics metrics) {
        Blue blue = ProcessorTestSupport.blue();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
        return blue;
    }

    private Node event(Blue blue, String eventId) {
        return blue.objectToNode(new TestEvent().eventId(eventId));
    }

    private static final class RecordingMetrics implements ProcessingMetricsSink {
        long bundleLoadCacheHits;
        long bundleLoadCacheMisses;
        long bundlesReused;

        @Override
        public void incrementBundleLoadCacheHits() {
            bundleLoadCacheHits++;
        }

        @Override
        public void incrementBundleLoadCacheMisses() {
            bundleLoadCacheMisses++;
        }

        @Override
        public void incrementBundlesReused() {
            bundlesReused++;
        }
    }
}
