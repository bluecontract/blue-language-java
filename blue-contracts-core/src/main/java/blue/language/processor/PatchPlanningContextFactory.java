package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.Map;

/** Creates working-root planning contexts with explicit conservative defaults. */
final class PatchPlanningContextFactory {

    private PatchPlanningContextFactory() {
    }

    static PatchPlanningContext create(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean exactReplacement,
            ProcessingSnapshotManager snapshotManager) {
        return create(
                canonicalRoot,
                resolvedRoot,
                exactReplacement,
                snapshotManager,
                Collections.<String, EmbeddedScopePlan>emptyMap());
    }

    static PatchPlanningContext create(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot,
            boolean exactReplacement,
            ProcessingSnapshotManager snapshotManager,
            Map<String, EmbeddedScopePlan> entryEmbeddedScopePlans) {
        return DocumentProcessingRuntime.workingPlanningContext(
                canonicalRoot,
                resolvedRoot,
                exactReplacement,
                snapshotManager,
                Collections.<String>emptySet(),
                Collections.<String, java.util.List<String>>emptyMap(),
                entryEmbeddedScopePlans,
                true,
                false,
                CanonicalTypeIdentityLookup.incomplete(),
                false);
    }
}
