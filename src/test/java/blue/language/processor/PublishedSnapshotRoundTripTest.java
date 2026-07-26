package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedSnapshotRoundTripTest {

    @Test
    void snapshotInitializationPublishesStrictDurableCanonicalSnapshot() {
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        ResolvedSnapshot input = blue.resolveToSnapshot(blue.yamlToNode(
                "name: Published Snapshot Initialization\n" +
                "bex:\n" +
                "  do:\n" +
                "    - - 1\n" +
                "      - 2\n" +
                "contracts: {}\n"));

        DocumentProcessingResult result = blue.initializeDocument(input);

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertPublishableRoundTrip(blue, result);
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.counter("processorInputStrictCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorInputUncheckedCanonical"), snapshot.toString());
        assertEquals(1L, snapshot.counter("processorPublishedStrictCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublishedUncheckedCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalizations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalMaterializations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationStrictBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalizationNanos"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationIdentityMismatches"), snapshot.toString());
        assertEquals(1L, snapshot.counter("processorPublicationInvariantChecks"), snapshot.toString());
    }

    @Test
    void snapshotProcessingWithNoExternalMatchPublishesStrictDurableCanonicalSnapshot() {
        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(
                "name: Published Snapshot Processing\n" +
                "bex:\n" +
                "  do:\n" +
                "    - - 1\n" +
                "      - 2\n" +
                "contracts: {}\n");
        DocumentProcessingResult initialized = blue.initializeDocument(document);
        ResolvedSnapshot strictInitialized = blue.loadSnapshot(initialized.snapshot().canonicalRoot());
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        blue.getDocumentProcessor().processingMetricsSink(metrics);

        DocumentProcessingResult result = blue.processDocument(strictInitialized,
                new Node().name("Ignored Published Snapshot Event"));

        assertEquals(ProcessorStatus.NO_MATCH, result.status(), result.failureReason());
        assertPublishableRoundTrip(blue, result);
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.counter("processorInputStrictCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorInputUncheckedCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublishedStrictCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublishedUncheckedCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalizations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalMaterializations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationStrictBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalizationNanos"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationIdentityMismatches"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationInvariantChecks"), snapshot.toString());
    }

    @Test
    void uncheckedSnapshotInputIsCanonicalizedBeforePublication() {
        Blue blue = ProcessorTestSupport.blue();
        RecordingProcessingMetricsSink metrics = new RecordingProcessingMetricsSink();
        blue.getDocumentProcessor().processingMetricsSink(metrics);
        Node document = blue.yamlToNode(
                "name: Unchecked Published Snapshot Input\n" +
                "bex:\n" +
                "  do:\n" +
                "    - - 1\n" +
                "      - 2\n" +
                "contracts: {}\n");
        FrozenNode canonicalRoot = FrozenNode.fromUncheckedCanonicalNode(document);
        ResolvedSnapshot input = new ResolvedSnapshot(canonicalRoot,
                FrozenNode.fromResolvedNode(document),
                canonicalRoot.blueId());

        DocumentProcessingResult result = blue.initializeDocument(input);

        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertPublishableRoundTrip(blue, result);
        ProcessingMetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(0L, snapshot.counter("processorInputStrictCanonical"), snapshot.toString());
        assertEquals(1L, snapshot.counter("processorInputUncheckedCanonical"), snapshot.toString());
        assertEquals(1L, snapshot.counter("processorPublishedStrictCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublishedUncheckedCanonical"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalizations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalMaterializations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationStrictBlueIdCalculations"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationCanonicalizationNanos"), snapshot.toString());
        assertEquals(0L, snapshot.counter("processorPublicationIdentityMismatches"), snapshot.toString());
        assertEquals(1L, snapshot.counter("processorPublicationInvariantChecks"), snapshot.toString());
    }

    private static void assertPublishableRoundTrip(Blue blue, DocumentProcessingResult result) {
        assertNotNull(result.snapshot());
        assertEquals(result.blueId(), result.snapshot().blueId());
        assertEquals(result.snapshot().blueId(), result.snapshot().frozenCanonicalRoot().blueId());
        assertTrue(result.snapshot().frozenCanonicalRoot().isStrictCanonical());
        assertTrue(result.snapshot().frozenCanonicalRoot().isStrictBlueIdValidation());
        Node parsed = blue.jsonToNode(blue.nodeToJson(result.snapshot().canonicalRoot()));
        ResolvedSnapshot reloaded = blue.loadSnapshot(parsed);
        assertEquals(result.blueId(), reloaded.blueId());
    }
}
