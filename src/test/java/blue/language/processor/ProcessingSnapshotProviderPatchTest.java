package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeProviderWrapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProcessingSnapshotProviderPatchTest {

    @Test
    void directWriteCanonicalPatchPreservesTrustedProviderProvenance() {
        Node requestedType = new Node().name("Requested Patch Type")
                .properties("inherited", new Node().value("requested"));
        Node trustedType = new Node().name("Trusted Patch Source Type")
                .properties("inherited", new Node().value("trusted"));
        String requestedBlueId = BlueIdCalculator.calculateBlueId(requestedType);
        AtomicInteger providerFetches = new AtomicInteger();
        Blue blue = new Blue(NodeProviderWrapper.unverified(blueId -> {
            providerFetches.incrementAndGet();
            return requestedBlueId.equals(blueId)
                    ? Collections.singletonList(trustedType.clone())
                    : null;
        }));
        CountingSnapshotManager manager = new CountingSnapshotManager(
                blue.getDocumentProcessor().snapshotManager());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                new Node().type(new Node().blueId(requestedBlueId)), null, manager);

        runtime.directWrite("/state", new Node().value("written"));

        ResolvedSnapshot snapshot = runtime.snapshot();
        assertEquals(1, manager.fromDocumentCalls);
        assertEquals(0, manager.applyPatchCalls);
        assertEquals(1, manager.cacheSnapshotCalls);
        assertEquals(1, providerFetches.get());
        assertEquals("written", snapshot.canonicalRoot().getAsText("/state"));
        assertEquals("written", snapshot.resolvedRoot().getAsText("/state"));
        assertEquals("trusted", snapshot.resolvedRoot().getAsText("/inherited"));
        assertEquals(requestedBlueId, snapshot.canonicalRoot().getType().getBlueId());
        assertEquals(requestedBlueId, snapshot.resolvedRoot().getType().getBlueId());
        assertEquals(snapshot.blueId(), snapshot.frozenCanonicalRoot().blueId());
        assertEquals(snapshot.canonicalAt("/state").blueId(), snapshot.resolvedAt("/state").blueId());
        assertNull(snapshot.verifiedReferenceResolution());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    private static final class CountingSnapshotManager implements ProcessingSnapshotManager {
        private final ProcessingSnapshotManager delegate;
        private int fromDocumentCalls;
        private int applyPatchCalls;
        private int cacheSnapshotCalls;

        private CountingSnapshotManager(ProcessingSnapshotManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            fromDocumentCalls++;
            return delegate.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            applyPatchCalls++;
            return delegate.applyPatch(snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            cacheSnapshotCalls++;
            return delegate.cacheSnapshot(snapshot);
        }
    }
}
