package blue.language;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessingMetricsSink;
import blue.language.snapshot.ResolvedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedSnapshotSelectionCacheTest {

    @Test
    void warmNodeCacheKeepsCompactSelectionWhileSnapshotSelectsResolvedView() {
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        CountingMetrics metrics = new CountingMetrics();
        blue.getDocumentProcessor().processingMetricsSink(metrics);

        DocumentProcessingResult compact = blue.processDocument(
                fixture.compact(), fixture.auditEvent("compact-first"));
        int hitsAfterFirst = metrics.cacheHits.get();
        DocumentProcessingResult warmCompact = blue.processDocument(
                compact.document(), fixture.auditEvent("compact-second"));

        assertEquals(0, executions.get(), "cache reuse must not select type-derived contracts");
        assertFalse(hasContract(warmCompact.document(), "audit"));
        assertTrue(metrics.cacheHits.get() > hitsAfterFirst,
                "the unchanged Node continuation must reuse its input snapshot");

        ResolvedSnapshot snapshot = blue.resolveToSnapshot(fixture.compact());
        DocumentProcessingResult initializedSnapshot = blue.initializeDocument(snapshot);
        DocumentProcessingResult processedSnapshot = blue.processDocument(
                initializedSnapshot.snapshot(), fixture.auditEvent("snapshot"));

        assertEquals(1, executions.get());
        assertTrue(hasContract(processedSnapshot.document(), "audit"));
    }

    @Test
    void structurallyEqualCloneHitsAndMutationMissesSelectedSnapshotCache() {
        Blue blue = new Blue();
        DocumentProcessingResult initialized = blue.initializeDocument(
                new Node().properties("counter", new Node().value(0)).contracts(new Node()));
        CountingMetrics metrics = new CountingMetrics();
        blue.getDocumentProcessor().processingMetricsSink(metrics);

        DocumentProcessingResult cloneResult = blue.processDocument(
                initialized.document().clone(), new Node().properties("kind", new Node().value("noop")));
        DocumentProcessingResult coldResult = new Blue().processDocument(
                initialized.document().clone(), new Node().properties("kind", new Node().value("noop")));

        assertEquals(1, metrics.cacheHits.get(), "an exact clone should reuse the immutable companion snapshot");
        assertEquals(0, metrics.cacheMisses.get());
        assertEquals(coldResult.totalGas(), cloneResult.totalGas(),
                "host snapshot reuse must not alter processor gas");

        Node mutated = initialized.document().clone();
        mutated.properties("counter", new Node().value(1));
        blue.processDocument(mutated, new Node().properties("kind", new Node().value("noop-2")));

        assertTrue(metrics.cacheMisses.get() > 0, "a changed selected tree must not reuse the old snapshot");
    }

    @Test
    void compactAndResolvedSelectionsWithOneSemanticIdentityDoNotContaminateCache() {
        MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture fixture =
                new MaterializedSelectedProcessingDocumentFailFirstTest.AuditFixture();
        AtomicInteger executions = new AtomicInteger();
        Blue blue = fixture.newBlue(executions);
        Node compact = fixture.compact();
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(compact);

        assertEquals(snapshot.blueId(), blue.calculateSemanticBlueId(compact));
        assertEquals(snapshot.blueId(), blue.calculateSemanticBlueId(snapshot.resolvedRoot()));
        assertNotEquals(blue.nodeToJson(compact), blue.nodeToJson(snapshot.resolvedRoot()));

        DocumentProcessingResult resolved = blue.processDocument(snapshot, fixture.auditEvent("resolved-first"));
        DocumentProcessingResult compactResult = blue.processDocument(compact, fixture.auditEvent("compact-after"));

        assertEquals(1, executions.get(), "only the explicitly resolved selection should execute audit");
        assertTrue(hasContract(resolved.document(), "audit"));
        assertFalse(hasContract(compactResult.document(), "audit"));
    }

    private static boolean hasContract(Node document, String key) {
        return document != null
                && document.getContracts() != null
                && document.getContracts().getProperties() != null
                && document.getContracts().getProperties().containsKey(key);
    }

    private static final class CountingMetrics implements ProcessingMetricsSink {
        private final AtomicInteger cacheHits = new AtomicInteger();
        private final AtomicInteger cacheMisses = new AtomicInteger();

        @Override
        public void incrementProcessingSnapshotCacheHits() {
            cacheHits.incrementAndGet();
        }

        @Override
        public void incrementProcessingSnapshotCacheMisses() {
            cacheMisses.incrementAndGet();
        }
    }
}
