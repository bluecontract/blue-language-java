package blue.language;

import blue.language.model.Node;
import blue.language.processor.DocumentProcessingRuntime;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LimitedCanonicalPatchTest {

    @Test
    void directPatchPreservesCanonicalContentOutsideResolutionLimit() {
        Blue blue = limitedBlue();
        // The input is already authoritative Canonical Identity Input. Its identity
        // remains complete even though the materialized resolved view is limited.
        ResolvedSnapshot before = blue.loadSnapshot(source());
        assertLimitedSnapshot(before);

        ResolvedSnapshot after = blue.applyCanonicalPatch(
                before, JsonPatch.replace("/a", new Node().value("new")));

        assertEquals("new", after.canonicalNodeAt("/a").getValue());
        assertLimitedSnapshot(after);
    }

    @Test
    void processingPatchPreservesCanonicalContentOutsideResolutionLimit() {
        // Processing starts from authoritative Canonical Identity Input, not Source.
        ResolvedSnapshot limited = limitedBlue().loadSnapshot(source());
        assertLimitedSnapshot(limited);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(limited, null, passThroughManager());

        runtime.applyPatch("/", JsonPatch.replace("/a", new Node().value("new")));

        ResolvedSnapshot after = runtime.snapshot();
        assertEquals("new", after.canonicalNodeAt("/a").getValue());
        assertLimitedSnapshot(after);
    }

    @Test
    void processingPatchStructurallySharesLargeUntouchedCanonicalSubtree() {
        List<Node> items = new ArrayList<>();
        for (int index = 0; index < 20_000; index++) {
            items.add(new Node().value(index));
        }
        Node source = new Node().properties(
                "changed", new Node().value("old"),
                "untouched", new Node().items(items));
        ResolvedSnapshot before = new Blue().loadSnapshot(source);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(before, null, passThroughManager());

        runtime.applyPatch("/",
                JsonPatch.replace("/changed", new Node().value("new")));

        ResolvedSnapshot after = runtime.snapshot();
        assertEquals("new", after.canonicalNodeAt("/changed").getValue());
        assertSame(before.canonicalAt("/untouched"), after.canonicalAt("/untouched"));
        assertSame(before.resolvedAt("/untouched"), after.resolvedAt("/untouched"));
    }

    private static Blue limitedBlue() {
        Blue blue = new Blue();
        blue.setGlobalLimits(PathLimits.withSinglePath("/a"));
        return blue;
    }

    private static Node source() {
        return new Node().properties(
                "a", new Node().value("old"),
                "b", new Node().value("keep"));
    }

    private static ProcessingSnapshotManager passThroughManager() {
        return new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                throw new AssertionError("Full re-resolution was not expected");
            }

            @Override
            public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
                throw new AssertionError("Manager patching was not expected");
            }

            @Override
            public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
                return snapshot;
            }
        };
    }

    private static void assertLimitedSnapshot(ResolvedSnapshot snapshot) {
        assertNotNull(snapshot.canonicalAt("/b"));
        assertEquals("keep", snapshot.canonicalNodeAt("/b").getValue());
        assertNull(snapshot.resolvedAt("/b"));
    }
}
