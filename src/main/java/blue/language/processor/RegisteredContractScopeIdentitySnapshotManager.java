package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Collections;

/**
 * Short-lived Language pipeline for a standalone {@link DocumentProcessor}.
 * External content is available only when it was supplied explicitly with the
 * corresponding contract registration. Built-in Language and Contracts types
 * remain available through the normal {@link Blue} provider composition.
 */
final class RegisteredContractScopeIdentitySnapshotManager implements ProcessingSnapshotManager {

    private final Blue languageRuntime;
    private final ProcessingSnapshotManager delegate;

    RegisteredContractScopeIdentitySnapshotManager(ContractProcessorRegistry registry) {
        this.languageRuntime = new Blue(blueId -> {
            Node canonicalTypeNode = registry.canonicalTypeNode(blueId);
            if (canonicalTypeNode != null) {
                return Collections.singletonList(canonicalTypeNode);
            }
            if (registry.processors().containsKey(blueId)) {
                throw new IllegalArgumentException(
                        "Missing provider content for registered contract BlueId " + blueId);
            }
            return null;
        });
        this.delegate = languageRuntime.getDocumentProcessor()
                .snapshotManager()
                .transientSequence();
    }

    @Override
    public ResolvedSnapshot fromDocument(Node document) {
        return delegate.fromDocumentTransient(document);
    }

    @Override
    public ResolvedSnapshot fromDocumentTransient(Node document) {
        return delegate.fromDocumentTransient(document);
    }

    @Override
    public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
        return delegate.applyPatch(snapshot, patch);
    }

    @Override
    public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        return delegate.cacheSnapshot(snapshot);
    }

    @Override
    public void releaseTransientState() {
        try {
            delegate.releaseTransientState();
        } finally {
            languageRuntime.close();
        }
    }
}
