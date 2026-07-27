package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.MarkerContract;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelCheckpointContextTest {

    @Test
    void factoryPreservesCheckpointFields() {
        MarkerContract marker = new TestMarker();
        Map<String, MarkerContract> markers = new LinkedHashMap<>();
        markers.put("checkpoint", marker);
        Node event = new Node().properties("timestamp", new Node().value(10));
        Node currentSubject = new Node()
                .properties("timeline", new Node().value("orders"))
                .properties("timestamp", new Node().value(10));
        Node lastEvent = new Node().properties("timestamp", new Node().value(9));

        ChannelCheckpointContext context = ChannelCheckpointContext.of("/child",
                "inbox::owner",
                event,
                "current-signature",
                currentSubject,
                lastEvent,
                "last-signature",
                markers);

        assertEquals("/child", context.scopePath());
        assertEquals("inbox::owner", context.channelKey());
        assertEquals("current-signature", context.eventSignature());
        assertEquals("last-signature", context.lastEventSignature());
        assertSame(marker, context.markers().get("checkpoint"));
        assertEquals(BigInteger.TEN, context.event().get("/timestamp"));
        assertEquals("orders", context.currentSubject().get("/timeline"));
        assertEquals(BigInteger.TEN,
                context.currentSubject().get("/timestamp"));
        assertEquals(BigInteger.valueOf(9), context.lastEvent().get("/timestamp"));
    }

    @Test
    void factoryDefensivelyCopiesEventNodes() {
        Node event = new Node().properties("timestamp", new Node().value(10));
        Node currentSubject = new Node()
                .properties("timestamp", new Node().value(10));
        Node lastEvent = new Node().properties("timestamp", new Node().value(9));

        ChannelCheckpointContext context = ChannelCheckpointContext.of("/",
                "channel",
                event,
                "current",
                currentSubject,
                lastEvent,
                "last",
                null);

        event.properties("timestamp", new Node().value(11));
        currentSubject.properties("timestamp", new Node().value(12));
        lastEvent.properties("timestamp", new Node().value(8));

        assertEquals(BigInteger.TEN, context.event().get("/timestamp"));
        assertEquals(BigInteger.TEN,
                context.currentSubject().get("/timestamp"));
        assertEquals(BigInteger.valueOf(9), context.lastEvent().get("/timestamp"));

        Node contextEvent = context.event();
        Node contextCurrentSubject = context.currentSubject();
        Node contextLastEvent = context.lastEvent();
        contextEvent.properties("timestamp", new Node().value(12));
        contextCurrentSubject.properties("timestamp", new Node().value(13));
        contextLastEvent.properties("timestamp", new Node().value(7));

        assertEquals(BigInteger.TEN, context.event().get("/timestamp"));
        assertEquals(BigInteger.TEN,
                context.currentSubject().get("/timestamp"));
        assertEquals(BigInteger.valueOf(9), context.lastEvent().get("/timestamp"));
    }

    @Test
    void factoryDefensivelyCopiesMarkerMap() {
        MarkerContract marker = new TestMarker();
        Map<String, MarkerContract> markers = new LinkedHashMap<>();
        markers.put("checkpoint", marker);

        ChannelCheckpointContext context = ChannelCheckpointContext.of("/",
                "channel",
                null,
                null,
                null,
                null,
                markers);

        markers.clear();

        assertSame(marker, context.markers().get("checkpoint"));
        assertFalse(context.markers().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> context.markers().put("other", new TestMarker()));
    }

    @Test
    void lazyPreviousSubjectIsDemandedOnceAndDefensivelyCopied() {
        AtomicInteger materializations =
                new AtomicInteger();
        Node exactPreviousSubject =
                new Node().properties(
                        "timestamp",
                        new Node().value(9));
        ChannelCheckpointContext context =
                ChannelCheckpointContext.withLazyLastEvent(
                        "/",
                        "timeline",
                        new Node().properties(
                                "timestamp",
                                new Node().value(10)),
                        "current",
                        new Node().properties(
                                "timestamp",
                                new Node().value(10)),
                        "previous",
                        null,
                        () -> {
                            materializations.incrementAndGet();
                            return exactPreviousSubject;
                        });

        assertEquals("previous",
                context.lastEventSignature());
        assertEquals(0, materializations.get());

        Node firstRead = context.lastEvent();
        firstRead.properties(
                "timestamp",
                new Node().value(100));
        exactPreviousSubject.properties(
                "timestamp",
                new Node().value(200));

        Node secondRead = context.lastEvent();
        assertEquals(1, materializations.get());
        assertEquals(BigInteger.valueOf(9),
                secondRead.get("/timestamp"));
    }

    @Test
    void pureReferencePreviousSubjectUsesCapturedVerifiedManagerOnDemand() {
        Node exactPreviousSubject =
                new Node().properties(
                        "timestamp",
                        new Node().value(9));
        String blueId =
                BlueIdCalculator.calculateBlueId(
                        exactPreviousSubject);
        RecordingExactManager manager =
                RecordingExactManager.returning(
                        exactPreviousSubject);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node(),
                        null,
                        manager);
        ChannelCheckpointContext context =
                ChannelCheckpointContext.withLazyLastEvent(
                        "/",
                        "timeline",
                        new Node(),
                        "current",
                        new Node().properties(
                                "timestamp",
                                new Node().value(10)),
                        blueId,
                        null,
                        runtime.checkpointSubjectMaterializer(
                                new Node().blueId(
                                        blueId)));

        assertEquals(0, manager.materializations);
        assertEquals(
                BigInteger.valueOf(9),
                context.lastEvent().get(
                        "/timestamp"));
        assertEquals(
                BigInteger.valueOf(9),
                context.lastEvent().get(
                        "/timestamp"));
        assertEquals(1, manager.materializations);
    }

    @Test
    void pureReferencePreviousSubjectRejectsProviderIdentityMismatch() {
        Node expected =
                new Node().value(
                        "expected");
        String expectedBlueId =
                BlueIdCalculator.calculateBlueId(
                        expected);
        RecordingExactManager manager =
                RecordingExactManager.returning(
                        new Node().value(
                                "wrong"));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node(),
                        null,
                        manager);
        ChannelCheckpointContext context =
                ChannelCheckpointContext.withLazyLastEvent(
                        "/",
                        "timeline",
                        new Node(),
                        "current",
                        new Node().properties(
                                "timestamp",
                                new Node().value(10)),
                        expectedBlueId,
                        null,
                        runtime.checkpointSubjectMaterializer(
                                new Node().blueId(
                                        expectedBlueId)));

        ProcessorFailureException failure =
                assertThrows(
                        ProcessorFailureException.class,
                        context::lastEvent);
        assertEquals(
                ProcessorErrorCategory
                        .InvalidProcessingDocument,
                failure.errorCategory());
        assertEquals(1, manager.materializations);
    }

    @Test
    void pureReferencePreviousSubjectPropagatesProviderUnavailability() {
        Node expected =
                new Node().value(
                        "expected");
        String expectedBlueId =
                BlueIdCalculator.calculateBlueId(
                        expected);
        IllegalStateException unavailable =
                new IllegalStateException(
                        "Provider unavailable for "
                                + expectedBlueId);
        RecordingExactManager manager =
                RecordingExactManager.failing(
                        unavailable);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node(),
                        null,
                        manager);
        ChannelCheckpointContext context =
                ChannelCheckpointContext.withLazyLastEvent(
                        "/",
                        "timeline",
                        new Node(),
                        "current",
                        new Node().properties(
                                "timestamp",
                                new Node().value(10)),
                        expectedBlueId,
                        null,
                        runtime.checkpointSubjectMaterializer(
                                new Node().blueId(
                                        expectedBlueId)));

        assertSame(
                unavailable,
                assertThrows(
                        IllegalStateException.class,
                        context::lastEvent));
        assertEquals(1, manager.materializations);
    }

    private static final class TestMarker extends MarkerContract {
    }

    private static final class RecordingExactManager
            implements ProcessingSnapshotManager {

        private final FrozenNode result;
        private final RuntimeException failure;
        private int materializations;

        private RecordingExactManager(
                FrozenNode result,
                RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        private static RecordingExactManager returning(
                Node result) {
            return new RecordingExactManager(
                    FrozenNode.fromNode(
                            result),
                    null);
        }

        private static RecordingExactManager failing(
                RuntimeException failure) {
            return new RecordingExactManager(
                    null,
                    failure);
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            Node canonical = document.clone();
            return new ResolvedSnapshot(
                    canonical,
                    canonical.clone(),
                    BlueIdCalculator.calculateBlueId(
                            canonical));
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            materializations++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return snapshot;
        }
    }
}
