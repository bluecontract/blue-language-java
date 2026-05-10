package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;

/**
 * Bridges the mutable processor runtime to the canonical immutable snapshot layer.
 */
public interface ProcessingSnapshotManager {

    ResolvedSnapshot fromDocument(Node document);

    ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch);

    default ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        return snapshot;
    }
}
