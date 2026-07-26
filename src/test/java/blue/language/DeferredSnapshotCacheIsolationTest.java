package blue.language;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredSnapshotCacheIsolationTest {

    @Test
    void coldDeferredSnapshotCannotPoisonOrdinaryManagerCache()
            throws ReflectiveOperationException {
        Fixture fixture = new Fixture();

        ResolvedSnapshot deferred =
                fixture.manager.fromDocumentPreservingPaths(
                        fixture.document,
                        Collections.singleton("/body"));

        assertDeferred(deferred);
        assertEquals(0, fixture.derivedSnapshotEntries());
        assertSame(deferred, fixture.manager.cacheSnapshot(deferred));
        assertEquals(0, fixture.derivedSnapshotEntries());

        ResolvedSnapshot complete =
                fixture.manager.fromDocument(fixture.document);

        assertComplete(complete);
        assertEquals(1, fixture.derivedSnapshotEntries());
        assertEquals(deferred.blueId(), complete.blueId());
    }

    @Test
    void warmCompleteSnapshotIsNotReplacedByDeferredTwin()
            throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        ResolvedSnapshot warm =
                fixture.manager.fromDocument(fixture.document);
        assertComplete(warm);

        ResolvedSnapshot deferred =
                fixture.manager.fromDocumentTransientPreservingPaths(
                        fixture.document,
                        Collections.singleton("/body"));
        assertDeferred(deferred);
        assertSame(deferred, fixture.manager.cacheSnapshot(deferred));

        ResolvedSnapshot completeAgain =
                fixture.manager.fromDocument(fixture.document);

        assertSame(warm, completeAgain);
        assertComplete(completeAgain);
        assertEquals(1, fixture.derivedSnapshotEntries());
    }

    @Test
    void deferredSnapshotCannotBePinnedAsAuthoritative()
            throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        ResolvedSnapshot deferred =
                fixture.manager.fromDocumentPreservingPaths(
                        fixture.document,
                        Collections.singleton("/body"));

        assertFalse(deferred.toStrictBlueIdValidatedCanonical()
                .isResolutionComplete());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.blue.cacheResolvedSnapshot(deferred));
        assertEquals(0, fixture.derivedSnapshotEntries());
        assertEquals(0, fixture.blue.cacheStats()
                .region("pinnedAuthoritativeSnapshots").entries());
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
