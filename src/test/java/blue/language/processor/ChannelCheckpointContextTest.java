package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.MarkerContract;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelCheckpointContextTest {

    @Test
    void shouldVerifyFactoryPreservesCheckpointFields() {
        // given
        MarkerContract marker = new TestMarker();
        Map<String, MarkerContract> markers = new LinkedHashMap<>();
        markers.put("checkpoint", marker);
        Node event = new Node().properties("timestamp", new Node().value(10));
        Node currentSubject = new Node()
                .properties("timeline", new Node().value("orders"))
                .properties("timestamp", new Node().value(10));
        Node lastEvent = new Node().properties("timestamp", new Node().value(9));

        // when
        ChannelCheckpointContext context = ChannelCheckpointContext.of("/child",
                "inbox::owner",
                event,
                "current-signature",
                currentSubject,
                lastEvent,
                "last-signature",
                markers);

        // then
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
    void shouldVerifyFactoryDefensivelyCopiesEventNodes() {
        // given
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

        // when
        event.properties("timestamp", new Node().value(11));
        currentSubject.properties("timestamp", new Node().value(12));
        lastEvent.properties("timestamp", new Node().value(8));
        Object eventAfterCallerMutation =
                context.event().get("/timestamp");
        Object subjectAfterCallerMutation =
                context.currentSubject().get("/timestamp");
        Object lastEventAfterCallerMutation =
                context.lastEvent().get("/timestamp");
        Node contextEvent = context.event();
        Node contextCurrentSubject = context.currentSubject();
        Node contextLastEvent = context.lastEvent();
        contextEvent.properties("timestamp", new Node().value(12));
        contextCurrentSubject.properties("timestamp", new Node().value(13));
        contextLastEvent.properties("timestamp", new Node().value(7));
        Object eventAfterReturnedCopyMutation =
                context.event().get("/timestamp");
        Object subjectAfterReturnedCopyMutation =
                context.currentSubject().get("/timestamp");
        Object lastEventAfterReturnedCopyMutation =
                context.lastEvent().get("/timestamp");

        // then
        assertEquals(BigInteger.TEN, eventAfterCallerMutation);
        assertEquals(BigInteger.TEN, subjectAfterCallerMutation);
        assertEquals(BigInteger.valueOf(9), lastEventAfterCallerMutation);
        assertEquals(BigInteger.TEN, eventAfterReturnedCopyMutation);
        assertEquals(BigInteger.TEN, subjectAfterReturnedCopyMutation);
        assertEquals(BigInteger.valueOf(9), lastEventAfterReturnedCopyMutation);
    }

    @Test
    void shouldVerifyFactoryDefensivelyCopiesMarkerMap() {
        // given
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

        // when
        markers.clear();
        Throwable mutationFailure = captureFailure(
                () -> context.markers().put(
                        "other",
                        new TestMarker()));

        // then
        assertSame(marker, context.markers().get("checkpoint"));
        assertFalse(context.markers().isEmpty());
        assertTrue(mutationFailure instanceof UnsupportedOperationException);
    }

    @Test
    void shouldVerifyLazyPreviousSubjectIsDemandedOnceAndDefensivelyCopied() {
        // given
        AtomicInteger materializations =
                new AtomicInteger();
        Node exactPreviousSubject =
                new Node().properties(
                        "timestamp",
                        new Node().value(9));
        // when
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
        String previousSignature = context.lastEventSignature();
        int materializationsBeforeRead = materializations.get();
        Node firstRead = context.lastEvent();
        firstRead.properties(
                "timestamp",
                new Node().value(100));
        exactPreviousSubject.properties(
                "timestamp",
                new Node().value(200));

        Node secondRead = context.lastEvent();
        int materializationsAfterReads = materializations.get();

        // then
        assertEquals("previous", previousSignature);
        assertEquals(0, materializationsBeforeRead);
        assertEquals(1, materializationsAfterReads);
        assertEquals(BigInteger.valueOf(9),
                secondRead.get("/timestamp"));
    }

    @Test
    void shouldVerifyPureReferencePreviousSubjectUsesCapturedVerifiedManagerOnDemand() {
        // given
        Node exactPreviousSubject =
                new Node().properties(
                        "timestamp",
                        new Node().value(9));
        String blueId =
                DirectBlueIdCalculator.calculateBlueId(
                        exactPreviousSubject);
        RecordingExactManager manager =
                RecordingExactManager.returning(
                        exactPreviousSubject);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        Nodes.emptyObject(),
                        null,
                        manager);
        // when
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
        int materializationsBeforeRead = manager.materializations;
        Object firstTimestamp =
                context.lastEvent().get("/timestamp");
        Object secondTimestamp =
                context.lastEvent().get("/timestamp");
        int materializationsAfterReads = manager.materializations;

        // then
        assertEquals(0, materializationsBeforeRead);
        assertEquals(BigInteger.valueOf(9), firstTimestamp);
        assertEquals(BigInteger.valueOf(9), secondTimestamp);
        assertEquals(1, materializationsAfterReads);
    }

    @Test
    void shouldVerifyPureReferencePreviousSubjectRejectsProviderIdentityMismatch() {
        // given
        Node expected =
                new Node().value(
                        "expected");
        String expectedBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        expected);
        RecordingExactManager manager =
                RecordingExactManager.returning(
                        new Node().value(
                                "wrong"));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        Nodes.emptyObject(),
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

        // when
        ProcessorFailureException failure =
                captureFailure(context::lastEvent);

        // then
        assertEquals(ProcessorFailureException.class, failure.getClass());
        assertEquals(
                ProcessorErrorCategory
                        .InvalidProcessingDocument,
                failure.errorCategory());
        assertEquals(1, manager.materializations);
    }

    @Test
    void shouldVerifyPureReferencePreviousSubjectPropagatesProviderUnavailability() {
        // given
        Node expected =
                new Node().value(
                        "expected");
        String expectedBlueId =
                DirectBlueIdCalculator.calculateBlueId(
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
                        Nodes.emptyObject(),
                        null,
                        manager);
        // when
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
        Throwable failure = captureFailure(context::lastEvent);

        // then
        assertSame(unavailable, failure);
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
                    DirectBlueIdCalculator.calculateBlueId(
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
