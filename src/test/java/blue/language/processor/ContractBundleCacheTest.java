package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.IncrementPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.TestEvent;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractBundleCacheTest {

    @Test
    void processingStateChangesReuseBundleAndRefreshCheckpointMarkers() {
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(metrics);
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "count: 0\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  increment:\n" +
                "    type:\n" +
                "      blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "    channel: testChannel\n" +
                "    propertyKey: /count\n")).document();

        DocumentProcessingResult first = blue.processDocument(initialized, event(blue, "evt-1"));
        long missesAfterFirst = metrics.bundleLoadCacheMisses;
        DocumentProcessingResult second = blue.processDocument(first.document(), event(blue, "evt-2"));
        long hitsAfterSecond = metrics.bundleLoadCacheHits;
        DocumentProcessingResult duplicate = blue.processDocument(second.document(), event(blue, "evt-2"));

        assertEquals(new BigInteger("2"), duplicate.document().get("/count"));
        assertEquals(missesAfterFirst, metrics.bundleLoadCacheMisses,
                "checkpoint payload changes should reuse the checkpoint-shaped bundle");
        assertTrue(hitsAfterSecond > 0, "second run should reuse at least one cached bundle");
        assertTrue(metrics.bundlesReused > 0, "bundle reuse metric should be incremented");
    }

    @Test
    void changingContractsInvalidatesBundleCache() {
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(metrics);
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "orders: {}\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  set:\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    channel: testChannel\n" +
                "    path: /orders\n" +
                "    propertyKey: count\n" +
                "    propertyValue: 1\n")).document();

        DocumentProcessingResult first = blue.processDocument(initialized, event(blue, "evt-1"));
        long missesBeforeContractChange = metrics.bundleLoadCacheMisses;
        Node changedContracts = first.document().clone();
        changedContracts.getAsNode("/contracts/set")
                .properties("propertyValue", new Node().value(2));
        DocumentProcessingResult second = blue.processDocument(changedContracts, event(blue, "evt-2"));

        assertEquals(new BigInteger("2"), second.document().get("/orders/count"));
        assertTrue(metrics.bundleLoadCacheMisses > missesBeforeContractChange,
                "changing /contracts should force a new bundle build");
    }

    @Test
    void changingChannelBindingsInvalidatesBundleCacheKey() {
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(metrics);
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "orders: {}\n" +
                "channelBindings:\n" +
                "  owner:\n" +
                "    timelineId: one\n" +
                "contracts:\n" +
                "  testChannel:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  set:\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    channel: testChannel\n" +
                "    path: /orders\n" +
                "    propertyKey: count\n" +
                "    propertyValue: 1\n")).document();

        DocumentProcessingResult first = blue.processDocument(initialized, event(blue, "evt-1"));
        long missesBeforeBindingChange = metrics.bundleLoadCacheMisses;
        Node changedBindings = first.document().clone();
        changedBindings.getAsNode("/channelBindings/owner")
                .properties("timelineId", new Node().value("two"));
        blue.processDocument(changedBindings, event(blue, "evt-2"));

        assertTrue(metrics.bundleLoadCacheMisses > missesBeforeBindingChange,
                "changing /channelBindings should force a new bundle build");
    }

    @Test
    void embeddedScopesCacheIndependently() {
        RecordingMetrics metrics = new RecordingMetrics();
        Blue blue = configuredBlue(metrics);
        Node initialized = blue.initializeDocument(blue.yamlToNode(
                "child:\n" +
                "  count: 0\n" +
                "  contracts:\n" +
                "    testChannel:\n" +
                "      type:\n" +
                "        blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "    increment:\n" +
                "      type:\n" +
                "        blueId: GsQfKqSUXxx24JTvsHDaY5pJ2cE6vZnn7j1NQ5RFDCWv\n" +
                "      channel: testChannel\n" +
                "      propertyKey: /count\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: 8FVc8MPz6DcTMgcY3RXU6EBpGa9arWPJ141K2H86yi8Q\n" +
                "    paths:\n" +
                "      - /child\n")).document();

        DocumentProcessingResult first = blue.processDocument(initialized, event(blue, "evt-1"));
        long hitsBeforeSecond = metrics.bundleLoadCacheHits;
        DocumentProcessingResult second = blue.processDocument(first.document(), event(blue, "evt-2"));

        assertEquals(new BigInteger("2"), second.document().get("/child/count"));
        assertTrue(metrics.bundleLoadCacheHits - hitsBeforeSecond >= 2,
                "root and embedded child scopes should be independently reusable");
    }

    private Blue configuredBlue(RecordingMetrics metrics) {
        Blue blue = ProcessorTestSupport.blue();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new IncrementPropertyContractProcessor());
        blue.registerContractProcessor(new SetPropertyContractProcessor());
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
