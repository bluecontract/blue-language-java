package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.merge.ResolvedSnapshot;

/** Package bridge for black-box tests of the package-private invocation runtime. */
public final class DocumentProcessingRuntimeTestAccess {

    private DocumentProcessingRuntimeTestAccess() {
    }

    /** Applies one patch and returns the runtime's authoritative snapshot. */
    public static ResolvedSnapshot applyPatch(
            ResolvedSnapshot snapshot,
            ProcessingSnapshotManager snapshotManager,
            String scopePath,
            JsonPatch patch) {
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                snapshot, null, snapshotManager);
        runtime.applyPatch(scopePath, patch);
        return runtime.snapshot();
    }

    /** Captures the selected document and snapshot from one node-backed runtime. */
    public static RuntimeSnapshot snapshot(
            Node document,
            ProcessingSnapshotManager snapshotManager) {
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document, null, snapshotManager);
        return new RuntimeSnapshot(runtime.document(), runtime.snapshot());
    }

    /** Immutable test projection of runtime-owned selected and snapshot views. */
    public static final class RuntimeSnapshot {
        private final Node document;
        private final ResolvedSnapshot snapshot;

        private RuntimeSnapshot(Node document, ResolvedSnapshot snapshot) {
            this.document = document;
            this.snapshot = snapshot;
        }

        public Node document() {
            return document;
        }

        public ResolvedSnapshot snapshot() {
            return snapshot;
        }
    }
}
