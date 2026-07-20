package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolvedSnapshotPatchTransactionTest {

    @Test
    void plainScalarReplacementKeepsSnapshotCoherentWithoutFullResolution() {
        Blue blue = new Blue();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                blue.resolveToSnapshot(new Node()
                        .properties("counter", new Node().value(0))),
                blue.conformanceEngine(),
                manager);

        runtime.applyPatch("/", JsonPatch.replace("/counter", new Node().value(1)));

        ResolvedSnapshot result = runtime.snapshot();
        assertEquals(1, result.canonicalRoot().getAsInteger("/counter"));
        assertEquals(1, result.resolvedRoot().getAsInteger("/counter"));
        assertEquals(1, runtime.document().getAsInteger("/counter"));
        assertEquals(0, manager.inputs.size(),
                "a plain value with no inherited contribution must stay on the immutable patch path");
        assertPlainPathViewsEqual(blue, result, "/counter");
    }

    @Test
    void snapshotPatchKeepsAuthoredCanonicalValueAndResolvedEffectiveValue() {
        Fixture fixture = new Fixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        ResolvedSnapshot input = fixture.blue.resolveToSnapshot(fixture.document());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                input, fixture.blue.conformanceEngine(), manager);
        String inputResolved = fixture.blue.nodeToJson(input.resolvedRoot());

        runtime.applyPatch("/", JsonPatch.replace("/status", reference(fixture.activeId)));

        ResolvedSnapshot result = runtime.snapshot();
        assertEquals(fixture.activeId, result.canonicalRoot().getAsText("/status/type/blueId"));
        assertEquals("active", result.resolvedRoot().getAsText("/status/mode"));
        assertMissing(result.resolvedRoot(), "/status/pendingOnly");
        assertEquals(fixture.blue.nodeToJson(result.resolvedRoot()),
                fixture.blue.nodeToJson(runtime.document()));
        assertEquals(BlueIdCalculator.calculateUncheckedBlueId(result.canonicalRoot()), result.blueId());
        assertEquals(fixture.blue.calculateSemanticBlueId(runtime.document()), result.blueId(),
                "the canonical identity companion must describe the returned resolved selection");

        assertEquals(1, manager.inputs.size());
        assertEquals(fixture.activeId, manager.inputs.get(0).getAsText("/status/type/blueId"));
        assertEquals(inputResolved, fixture.blue.nodeToJson(input.resolvedRoot()),
                "the input snapshot must remain immutable");
    }

    @Test
    void snapshotPatchRollsBackCanonicalResolvedAndSelectedViewsWhenValueResolutionFails() {
        Fixture fixture = new Fixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        manager.failResolution = true;
        ResolvedSnapshot input = fixture.blue.resolveToSnapshot(fixture.document());
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                input, fixture.blue.conformanceEngine(), manager);
        String selectedBefore = fixture.blue.nodeToJson(runtime.document());
        String canonicalBefore = fixture.blue.nodeToJson(runtime.snapshot().canonicalRoot());
        String resolvedBefore = fixture.blue.nodeToJson(runtime.snapshot().resolvedRoot());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.applyPatch("/", JsonPatch.replace("/status", reference(fixture.activeId))));

        assertEquals("patch value resolution failed", failure.getMessage());
        assertEquals(selectedBefore, fixture.blue.nodeToJson(runtime.document()));
        assertEquals(canonicalBefore, fixture.blue.nodeToJson(runtime.snapshot().canonicalRoot()));
        assertEquals(resolvedBefore, fixture.blue.nodeToJson(runtime.snapshot().resolvedRoot()));
        assertEquals(0, manager.cachedSnapshots);
    }

    @Test
    void snapshotAddToExistingMemberAlsoReplacesTheCompleteValue() {
        Fixture fixture = new Fixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.blue.resolveToSnapshot(fixture.document()),
                fixture.blue.conformanceEngine(),
                manager);

        DocumentProcessingRuntime.DocumentUpdateData update = runtime.applyPatch(
                "/", JsonPatch.add("/status", reference(fixture.activeId)));

        assertEquals(JsonPatch.Op.ADD, update.op());
        assertEquals("active", runtime.document().getAsText("/status/mode"));
        assertMissing(runtime.document(), "/status/pendingOnly");
        assertEquals(fixture.activeId,
                runtime.snapshot().canonicalRoot().getAsText("/status/type/blueId"));
    }

    @Test
    void snapshotListAddPreservesInsertionSemantics() {
        Fixture fixture = new Fixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.blue.resolveToSnapshot(listDocument(1, 3)),
                fixture.blue.conformanceEngine(),
                manager);

        runtime.applyPatch("/", JsonPatch.add("/values/1", new Node().value(2)));

        assertEquals(3, runtime.document().getAsNode("/values").getItems().size());
        assertEquals(1, runtime.document().getAsInteger("/values/0"));
        assertEquals(2, runtime.document().getAsInteger("/values/1"));
        assertEquals(3, runtime.document().getAsInteger("/values/2"));
        assertEquals(0, manager.inputs.size());
        assertPlainPathViewsEqual(fixture.blue, runtime.snapshot(), "/values");
    }

    @Test
    void snapshotListReplaceDoesNotInsertAnotherItem() {
        Fixture fixture = new Fixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.blue.resolveToSnapshot(listDocument(1, 3)),
                fixture.blue.conformanceEngine(),
                manager);

        runtime.applyPatch("/", JsonPatch.replace("/values/1", new Node().value(2)));

        assertEquals(2, runtime.document().getAsNode("/values").getItems().size());
        assertEquals(1, runtime.document().getAsInteger("/values/0"));
        assertEquals(2, runtime.document().getAsInteger("/values/1"));
        assertEquals(0, manager.inputs.size());
        assertPlainPathViewsEqual(fixture.blue, runtime.snapshot(), "/values");
    }

    @Test
    void snapshotRemoveKeepsAllViewsCoherent() {
        Fixture fixture = new Fixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.blue.resolveToSnapshot(new Node()
                        .properties("obsolete", new Node().value(true))),
                fixture.blue.conformanceEngine(),
                manager);

        runtime.applyPatch("/", JsonPatch.remove("/obsolete"));

        assertMissing(runtime.document(), "/obsolete");
        assertMissing(runtime.snapshot().canonicalRoot(), "/obsolete");
        assertMissing(runtime.snapshot().resolvedRoot(), "/obsolete");
        assertEquals(0, manager.inputs.size());
        assertEquals(BlueIdCalculator.calculateUncheckedBlueId(runtime.snapshot().canonicalRoot()),
                runtime.snapshot().blueId());
    }

    @Test
    void snapshotReplacementRetainsConstraintsInheritedFromTheDocumentPath() {
        ParentConstraintFixture fixture = new ParentConstraintFixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.snapshot(), fixture.blue.conformanceEngine(), manager);

        runtime.applyPatch("/", JsonPatch.replace("/state",
                new Node().properties("local", new Node().value("replacement"))));

        assertEquals("required-by-parent", runtime.document().getAsText("/state/inherited"),
                "the effective replacement must still include constraints contributed by the root type");
        assertEquals("replacement", runtime.document().getAsText("/state/local"));
    }

    @Test
    void sequentialSnapshotPatchesCommitOneAuthoritativeFinalResult() {
        ParentConstraintFixture fixture = new ParentConstraintFixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.snapshot(), fixture.blue.conformanceEngine(), manager);

        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/state",
                        new Node().properties("local", new Node().value("first"))),
                JsonPatch.replace("/state/local", new Node().value("second"))));

        assertEquals("required-by-parent", runtime.document().getAsText("/state/inherited"));
        assertEquals("second", runtime.document().getAsText("/state/local"));
        assertEquals(1, manager.inputs.size(),
                "dependent patches and generalization must precede one authoritative final resolution");
        assertEquals("second", manager.inputs.get(0).getAsText("/state/local"));
    }

    @Test
    void observableSequenceRefreezesSuffixAfterAuthoritativeCanonicalModeTransition() {
        Blue blue = new Blue();
        Node source = new Node().properties(
                "first", new Node().value("initial"),
                "second", new Node().value("initial"));
        ResolvedSnapshot initial = blue.resolveToSnapshot(source);
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/first", new Node()
                        .name("forces authoritative resolution")
                        .properties("kept", new Node().value(true))),
                JsonPatch.replace("/second", new Node().properties(
                        "empty", new Node(),
                        "kept", new Node().value("value"))));

        DocumentProcessingRuntime optimized = new DocumentProcessingRuntime(
                initial, null, new RecordingSnapshotManager(blue));
        try (DocumentProcessingRuntime.PreparedPatchSequence sequence =
                     optimized.preparePatchSequence("/", patches, null)) {
            sequence.applyNext(0);
            sequence.applyNext(1);
        }

        DocumentProcessingRuntime reference = new DocumentProcessingRuntime(
                initial, null, new RecordingSnapshotManager(blue));
        reference.applyPatch("/", patches.get(0));
        reference.applyPatch("/", patches.get(1));

        assertEquals(blue.nodeToJson(reference.snapshot().canonicalRoot()),
                blue.nodeToJson(optimized.snapshot().canonicalRoot()));
        assertEquals(reference.snapshot().blueId(), optimized.snapshot().blueId());
        assertMissing(optimized.snapshot().canonicalRoot(), "/second/empty");
        assertEquals("value", optimized.snapshot().canonicalRoot().getAsText("/second/kept"));
    }

    @Test
    void snapshotDirectWriteRetainsParentConstraints() {
        ParentConstraintFixture fixture = new ParentConstraintFixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.snapshot(), fixture.blue.conformanceEngine(), manager);

        runtime.directWrite("/state",
                new Node().properties("local", new Node().value("direct")));

        assertEquals("required-by-parent", runtime.document().getAsText("/state/inherited"));
        assertEquals("direct", runtime.document().getAsText("/state/local"));
        assertEquals(1, manager.inputs.size());
    }

    @Test
    void invalidReplacementRollsBackAllSnapshotViews() {
        ParentConstraintFixture fixture = new ParentConstraintFixture();
        RecordingSnapshotManager manager = new RecordingSnapshotManager(fixture.blue);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                fixture.snapshot(), fixture.blue.conformanceEngine(), manager);
        String selectedBefore = fixture.blue.nodeToJson(runtime.document());
        String canonicalBefore = fixture.blue.nodeToJson(runtime.snapshot().canonicalRoot());

        assertThrows(IllegalArgumentException.class, () -> runtime.applyPatch("/",
                JsonPatch.replace("/state", new Node()
                        .properties("inherited", new Node().value("contradiction")))));

        assertEquals(selectedBefore, fixture.blue.nodeToJson(runtime.document()));
        assertEquals(canonicalBefore, fixture.blue.nodeToJson(runtime.snapshot().canonicalRoot()));
        assertEquals(0, manager.cachedSnapshots);
    }

    private static void assertMissing(Node node, String path) {
        assertNull(ImmutablePatchPlanner.readNode(node, path));
    }

    private static void assertPlainPathViewsEqual(Blue blue, ResolvedSnapshot snapshot, String path) {
        assertEquals(blue.nodeToJson(ImmutablePatchPlanner.readNode(snapshot.canonicalRoot(), path)),
                blue.nodeToJson(ImmutablePatchPlanner.readNode(snapshot.resolvedRoot(), path)));
        assertEquals(snapshot.frozenCanonicalRoot().blueId(), snapshot.blueId());
    }

    private static Node listDocument(int... values) {
        List<Node> items = new ArrayList<>(values.length);
        for (int value : values) {
            items.add(new Node().value(value));
        }
        return new Node().properties("values", new Node().items(items)).contracts(new Node());
    }

    private static Node reference(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static final class Fixture {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final String pendingId;
        private final String activeId;
        private final Blue blue;

        private Fixture() {
            provider.addSingleNodes(new Node()
                    .name("Snapshot Status")
                    .properties("mode", new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID))));
            String statusId = provider.getBlueIdByName("Snapshot Status");
            provider.addSingleNodes(new Node()
                    .name("Snapshot Pending")
                    .type(new Node().blueId(statusId))
                    .properties("mode", new Node().value("pending"))
                    .properties("pendingOnly", new Node().value(true)));
            pendingId = provider.getBlueIdByName("Snapshot Pending");
            provider.addSingleNodes(new Node()
                    .name("Snapshot Active")
                    .type(new Node().blueId(statusId))
                    .properties("mode", new Node().value("active")));
            activeId = provider.getBlueIdByName("Snapshot Active");
            blue = new Blue(provider);
        }

        private Node document() {
            return new Node()
                    .properties("status", reference(pendingId))
                    .contracts(new Node());
        }
    }

    private static final class ParentConstraintFixture {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final Blue blue;
        private final String documentTypeId;

        private ParentConstraintFixture() {
            provider.addSingleNodes(new Node()
                    .name("Parent Constrained Document")
                    .properties("state", new Node()
                            .properties("inherited", new Node().value("required-by-parent"))));
            documentTypeId = provider.getBlueIdByName("Parent Constrained Document");
            blue = new Blue(provider);
        }

        private ResolvedSnapshot snapshot() {
            return blue.resolveToSnapshot(new Node()
                    .type(new Node().blueId(documentTypeId))
                    .contracts(new Node()));
        }
    }

    private static final class RecordingSnapshotManager implements ProcessingSnapshotManager {
        private final Blue blue;
        private final List<Node> inputs = new ArrayList<>();
        private boolean failResolution;
        private int cachedSnapshots;

        private RecordingSnapshotManager(Blue blue) {
            this.blue = blue;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            inputs.add(document.clone());
            if (failResolution) {
                throw new IllegalStateException("patch value resolution failed");
            }
            return blue.resolveToSnapshot(document);
        }

        @Override
        public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
            throw new AssertionError("snapshot-selected writes must rebuild through fromDocument");
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
            cachedSnapshots++;
            return snapshot;
        }
    }
}
