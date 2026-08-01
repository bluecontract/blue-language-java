package blue.language.merge;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.limits.Limits;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Characterizes per-invocation state ownership and compatibility result views.
 */
final class MergerResolutionSessionTest {

    private static final long TEST_TIMEOUT_SECONDS = 10L;

    @Test
    void shouldIsolateConcurrentInvocationsOnOneMerger() throws Exception {
        // given
        CountDownLatch concurrentProcessors = new CountDownLatch(2);
        Merger merger = new Merger(
                new ConcurrentScalarProcessor(concurrentProcessors),
                emptyProvider());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Node left;
        Node right;

        // when
        try {
            Future<Node> leftFuture = executor.submit(
                    () -> merger.resolve(new Node().value("left"),
                            Limits.NO_LIMITS));
            Future<Node> rightFuture = executor.submit(
                    () -> merger.resolve(new Node().value("right"),
                            Limits.NO_LIMITS));
            left = leftFuture.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            right = rightFuture.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        assertEquals("left", left.getValue());
        assertEquals("right", right.getValue());
    }

    @Test
    void shouldReuseOneSessionForReentrantResolutionOnOwningThread() {
        // given
        Merger merger = new Merger(
                new ReentrantScalarProcessor(), emptyProvider());

        // when
        Node resolved = merger.resolve(
                new Node().value("outer"), Limits.NO_LIMITS);

        // then
        assertEquals("inner", resolved.getValue());
    }

    @Test
    void shouldExposeEquivalentCompatibilityAndStandaloneResolutionViews() {
        // given
        Merger merger = new Merger(
                new ScalarProcessor(), emptyProvider());
        Node source = new Node().value("value");

        // when
        Merger.SnapshotResolution compatibility =
                merger.resolveSnapshot(source, Limits.NO_LIMITS);
        SnapshotResolution standalone = compatibility.asStandalone();
        VerifiedReferenceResolution evidence =
                standalone.verifiedReferenceResolution();
        ResolvedSnapshot snapshot =
                ResolvedSnapshot.fromResolverResult(compatibility);

        // then
        assertNotNull(compatibility.verifiedReferenceResolution());
        assertNotNull(evidence);
        assertEquals(
                compatibility.verifiedReferenceResolution().requestedBlueId(),
                evidence.requestedBlueId());
        assertSame(compatibility.canonicalRoot(), standalone.canonicalRoot());
        assertSame(compatibility.resolvedRoot(), standalone.resolvedRoot());
        assertSame(evidence, snapshot.verifiedReferenceResolution());
        assertSame(standalone.provenance(), snapshot.resolutionProvenance());
    }

    private static NodeProvider emptyProvider() {
        return ignoredBlueId -> null;
    }

    private static class ScalarProcessor implements MergingProcessor {

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver) {
            if (source.getRawValue() != null) {
                target.value(source.getRawValue());
            }
        }
    }

    private static final class ConcurrentScalarProcessor
            extends ScalarProcessor {

        private final CountDownLatch concurrentProcessors;

        private ConcurrentScalarProcessor(
                CountDownLatch concurrentProcessors) {
            this.concurrentProcessors = concurrentProcessors;
        }

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver) {
            concurrentProcessors.countDown();
            await(concurrentProcessors);
            super.process(target, source, nodeProvider, nodeResolver);
        }
    }

    private static final class ReentrantScalarProcessor
            extends ScalarProcessor {

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver) {
            if ("outer".equals(source.getRawValue())) {
                Node inner = nodeResolver.resolve(
                        new Node().value("inner"), Limits.NO_LIMITS);
                target.value(inner.getRawValue());
                return;
            }
            super.process(target, source, nodeProvider, nodeResolver);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrent resolver invocations did not overlap.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while awaiting concurrent resolution.",
                    interrupted);
        }
    }
}
