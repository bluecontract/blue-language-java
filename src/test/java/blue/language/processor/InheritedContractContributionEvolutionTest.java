package blue.language.processor;

import blue.language.Blue;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.registry.BootstrapProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Frozen-Language proof for direct overlays on inherited contract content. */
final class InheritedContractContributionEvolutionTest {

    @Test
    void shouldExposeAlreadyEffectiveInheritedContractAfterRemovingOverlay() {
        // given
        BasicNodeProvider application = new BasicNodeProvider();
        application.addSingleDocs(
                "name: Contract-contributing document\n" +
                "note:\n" +
                "  type: Text\n" +
                "contracts:\n" +
                "  workflow:\n" +
                "    inherited: true");
        try (Blue blue = new Blue(new SequentialNodeProvider(
                BootstrapProvider.INSTANCE,
                BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider(),
                application))) {
            String typeBlueId = application.getBlueIdByName(
                    "Contract-contributing document");
            Node document = blue.yamlToNode(
                    "name: Inherited contribution instance\n" +
                    "type:\n" +
                    "  blueId: " + typeBlueId + "\n" +
                    "note: retained\n" +
                    "contracts:\n" +
                    "  workflow:\n" +
                    "    direct: true\n" +
                    "  generalization:\n" +
                    "    type:\n" +
                    "      blueId: "
                            + RuntimeBlueIds.TYPE_GENERALIZATION_POLICY
                            + "\n" +
                    "    defaultMode: nearest-valid-ancestor");
            DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                    document,
                    blue.conformanceEngine(),
                    snapshotManager(blue));

            // when
            List<DocumentUpdateData> updates = runtime.applyPatches(
                    "/", Collections.singletonList(
                            JsonPatch.remove("/contracts/workflow")));

            // then
            assertEquals(1, updates.size());
            assertEquals("/contracts/workflow", updates.get(0).path());
            assertEquals(typeBlueId, document.getType().getBlueId(),
                    "an already-valid ancestor contribution needs no widening");
            assertNull(runtime.snapshot().canonicalRoot()
                    .getContracts().getProperties().get("workflow"),
                    "the direct instance overlay must be gone");
            assertEquals(Boolean.TRUE, runtime.snapshot().resolvedRoot()
                    .get("/contracts/workflow/inherited"),
                    "the frozen Language merge keeps the ancestor contribution");
        }
    }

    private static ProcessingSnapshotManager snapshotManager(Blue blue) {
        return new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                return blue.resolveToSnapshot(document);
            }

            @Override
            public ResolvedSnapshot applyPatch(
                    ResolvedSnapshot snapshot,
                    JsonPatch patch) {
                return blue.applyCanonicalPatch(snapshot, patch);
            }

            @Override
            public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
                blue.cacheResolvedSnapshot(snapshot);
                return snapshot;
            }
        };
    }
}
