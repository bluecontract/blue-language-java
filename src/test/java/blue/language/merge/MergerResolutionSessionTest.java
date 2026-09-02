package blue.language.merge;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;
import blue.language.resolve.ResolutionLimits;
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
                            ResolutionLimits.NO_LIMITS));
            Future<Node> rightFuture = executor.submit(
                    () -> merger.resolve(new Node().value("right"),
                            ResolutionLimits.NO_LIMITS));
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
                new Node().value("outer"), ResolutionLimits.NO_LIMITS);

        // then
        assertEquals("inner", resolved.getValue());
    }

    @Test
    void shouldExposeStandaloneResolutionEvidence() {
        // given
        Merger merger = new Merger(
                new ScalarProcessor(), emptyProvider());
        Node source = new Node().value("value");

        // when
        SnapshotResolution resolution =
                merger.resolveSnapshot(source, ResolutionLimits.NO_LIMITS);
        VerifiedReferenceResolution evidence =
                resolution.verifiedReferenceResolution();
        ResolvedSnapshot snapshot =
                ResolvedSnapshot.fromResolverResult(resolution);

        // then
        assertNotNull(evidence);
        assertSame(resolution.canonicalRoot(), snapshot.frozenCanonicalRoot());
        assertSame(resolution.resolvedRoot(), snapshot.frozenResolvedRoot());
        assertSame(evidence, snapshot.verifiedReferenceResolution());
        assertSame(resolution.provenance(), snapshot.resolutionProvenance());
    }

    private static NodeProvider emptyProvider() {
        return ignoredBlueId -> null;
    }

    private static class ScalarProcessor implements MergingProcessor {

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver,
                            CanonicalTypeIdentityLookup typeIdentities) {
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
                            NodeResolver nodeResolver,
                            CanonicalTypeIdentityLookup typeIdentities) {
            concurrentProcessors.countDown();
            await(concurrentProcessors);
            super.process(target, source, nodeProvider, nodeResolver,
                    typeIdentities);
        }
    }

    private static final class ReentrantScalarProcessor
            extends ScalarProcessor {

        @Override
        public void process(Node target,
                            Node source,
                            NodeProvider nodeProvider,
                            NodeResolver nodeResolver,
                            CanonicalTypeIdentityLookup typeIdentities) {
            if ("outer".equals(source.getRawValue())) {
                Node inner = nodeResolver.resolve(
                        new Node().value("inner"), ResolutionLimits.NO_LIMITS);
                target.value(inner.getRawValue());
                return;
            }
            super.process(target, source, nodeProvider, nodeResolver,
                    typeIdentities);
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
