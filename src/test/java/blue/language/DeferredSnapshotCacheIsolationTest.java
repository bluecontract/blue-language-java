package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredSnapshotCacheIsolationTest {

    @Test
    void shouldPreventColdDeferredSnapshotFromPoisoningOrdinaryManagerCache()
            throws ReflectiveOperationException {
        // given
        Fixture fixture = new Fixture();

        // when
        ResolvedSnapshot deferred =
                fixture.manager.fromDocumentPreservingPaths(
                        fixture.document,
                        Collections.singleton("/body"));
        int entriesAfterDeferredResolution =
                fixture.derivedSnapshotEntries();
        ResolvedSnapshot cachedDeferred =
                fixture.manager.cacheSnapshot(deferred);
        int entriesAfterDeferredCaching =
                fixture.derivedSnapshotEntries();
        ResolvedSnapshot complete =
                fixture.manager.fromDocument(fixture.document);
        int entriesAfterCompleteResolution =
                fixture.derivedSnapshotEntries();

        // then
        assertDeferred(deferred);
        assertEquals(0, entriesAfterDeferredResolution);
        assertSame(deferred, cachedDeferred);
        assertEquals(0, entriesAfterDeferredCaching);
        assertComplete(complete);
        assertEquals(1, entriesAfterCompleteResolution);
        assertEquals(deferred.blueId(), complete.blueId());
    }

    @Test
    void shouldKeepWarmCompleteSnapshotWhenDeferredTwinArrives()
            throws ReflectiveOperationException {
        // given
        Fixture fixture = new Fixture();
        // when
        ResolvedSnapshot warm =
                fixture.manager.fromDocument(fixture.document);
        ResolvedSnapshot deferred =
                fixture.manager.fromDocumentTransientPreservingPaths(
                        fixture.document,
                        Collections.singleton("/body"));
        ResolvedSnapshot cachedDeferred =
                fixture.manager.cacheSnapshot(deferred);
        ResolvedSnapshot completeAgain =
                fixture.manager.fromDocument(fixture.document);
        int derivedSnapshotEntries =
                fixture.derivedSnapshotEntries();

        // then
        assertComplete(warm);
        assertDeferred(deferred);
        assertSame(deferred, cachedDeferred);
        assertSame(warm, completeAgain);
        assertComplete(completeAgain);
        assertEquals(1, derivedSnapshotEntries);
    }

    @Test
    void shouldRejectPinningDeferredSnapshotAsAuthoritative()
            throws ReflectiveOperationException {
        // given
        Fixture fixture = new Fixture();
        // when
        ResolvedSnapshot deferred =
                fixture.manager.fromDocumentPreservingPaths(
                        fixture.document,
                        Collections.singleton("/body"));
        boolean resolutionComplete = deferred
                .toStrictBlueIdValidatedCanonical()
                .isResolutionComplete();
        IllegalArgumentException failure = captureFailure(
                () -> fixture.blue.cacheResolvedSnapshot(deferred));
        int derivedSnapshotEntries =
                fixture.derivedSnapshotEntries();
        int pinnedSnapshotEntries = fixture.blue.cacheStats()
                .region("pinnedAuthoritativeSnapshots").entries();

        // then
        assertFalse(resolutionComplete);
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(0, derivedSnapshotEntries);
        assertEquals(0, pinnedSnapshotEntries);
    }

    private static void assertDeferred(ResolvedSnapshot snapshot) {
        assertFalse(snapshot.isResolutionComplete());
        assertNull(snapshot.resolvedAt("/body/materialized"));
    }

    private static void assertComplete(ResolvedSnapshot snapshot) {
        assertTrue(snapshot.isResolutionComplete());
        assertNotNull(snapshot.resolvedAt("/body/materialized"));
        assertEquals("yes",
                snapshot.resolvedAt("/body/materialized").getValue());
    }

    private static final class Fixture {
        private final Blue blue;
        private final Node document;
        private final ProcessingSnapshotManager manager;

        private Fixture() throws ReflectiveOperationException {
            Node body = new Node().properties(
                    "materialized", new Node().value("yes"));
            String bodyBlueId =
                    BlueIdCalculator.calculateBlueId(body);
            Node containerType = new Node().properties(
                    "body", new Node().type(
                            new Node().blueId(bodyBlueId)));
            String containerTypeBlueId =
                    BlueIdCalculator.calculateBlueId(containerType);
            this.blue = new Blue(blueId ->
                    bodyBlueId.equals(blueId)
                            ? Collections.singletonList(body.clone())
                            : containerTypeBlueId.equals(blueId)
                            ? Collections.singletonList(
                                    containerType.clone())
                            : null);
            this.document = new Node()
                    .type(new Node().blueId(
                            containerTypeBlueId))
                    .properties("body",
                            new Node().blueId(bodyBlueId));
            Field managerField =
                    DocumentProcessor.class.getDeclaredField(
                            "snapshotManager");
            managerField.setAccessible(true);
            this.manager = (ProcessingSnapshotManager) managerField.get(
                    blue.getDocumentProcessor());
        }

        private int derivedSnapshotEntries() {
            return blue.cacheStats()
                    .region("derivedResolvedSnapshots").entries();
        }
    }
}
