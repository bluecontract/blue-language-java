package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.ResolvedSnapshotStorageCodec;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.FrozenNodeStorageCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.*;

/** Storage retains the source operands used by the qualified occurrence selector. */
final class WorkingSourceContributionsStorageTest {
    private final ResolvedSnapshotStorageCodec snapshots =
            new ResolvedSnapshotStorageCodec(4 * 1024 * 1024, 128);
    private final FrozenNodeStorageCodec frozen =
            new FrozenNodeStorageCodec(4 * 1024 * 1024, 128);

    @ParameterizedTest
    @ValueSource(strings = {"inherited-field", "explicit-field", "inherited-slot-type", "untyped-replacement"})
    void coldSnapshotsPreserveExactFieldPresenceAndReplacementTypeContributions(String mode) {
        // given
        Node definition = new Node().properties("explicit", new Node().value("retained"));
        Node definitionType = new Node().properties("defaultOnly", new Node().value("not authored"));
        if ("explicit-field".equals(mode)) {
            definition.type(reference(definitionType));
        }
        Node slotType = new Node().name("Stored slot type")
                .properties("definition", reference(definition));
        BasicNodeProvider provider = new BasicNodeProvider(definitionType, definition, slotType);
        Node root;
        String pointer;
        if (mode.endsWith("field")) {
            Node library = new Node().properties("definition", reference(definition));
            Node base = new Node().properties("library", library);
            provider.addSingleNodes(base);
            root = "inherited-field".equals(mode)
                    ? new Node().type(reference(base)).properties("library",
                            new Node().properties("local", new Node().value("overlay")))
                    : new Node().properties("library", library);
            pointer = "/library/definition";
        } else {
            Node old = new Node().properties("obsolete", new Node().value("old"));
            if ("inherited-slot-type".equals(mode)) old.type(reference(slotType));
            else old.properties("definition", reference(definition));
            Node prefix = new Node().type(new Node().blueId(LIST_TYPE_BLUE_ID))
                    .items(Collections.singletonList(old));
            root = new Node().properties("entries", new Node().type(prefix)
                    .items(Collections.singletonList(new Node().position(0).properties("$replace",
                            new Node().properties("marker", new Node().value("new"))))));
            pointer = "/entries/0/definition";
        }
        byte[] retained;
        List<byte[]> contributions;
        try (BlueLanguage producer = BlueLanguage.builder().nodeProvider(provider).build();
             LanguageProcessing.Scope scope = producer.processing().openScope()) {
            LanguageProcessingSnapshotManager manager = new LanguageProcessingSnapshotManager(scope);
            DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                    manager.fromDocumentTransient(root), null, manager);
            try (WorkingDocument working = runtime.workingDocument("/")) {
                assertExpected(working, pointer, mode, definition);
                contributions = encoded(working.sourceContributionsAt(pointer));
                retained = snapshots.encode(working.snapshot());
            }
        }
        AtomicInteger reads = new AtomicInteger();
        NodeProvider coldProvider = blueId -> {
            reads.incrementAndGet();
            return provider.fetchByBlueId(blueId);
        };
        // when: restoration itself has no runtime/provider and performs no resolution.
        ResolvedSnapshot restored = snapshots.decode(retained);
        List<byte[]> restoredContributions = new java.util.ArrayList<>();
        for (byte[] bytes : contributions) restoredContributions.add(frozen.encode(frozen.decode(bytes)));
        // then
        assertEquals(0, reads.get());
        assertArrayEquals(retained, snapshots.encode(restored));
        assertByteLists(contributions, restoredContributions);
        try (BlueLanguage consumer = BlueLanguage.builder().nodeProvider(coldProvider).build();
             LanguageProcessing.Scope scope = consumer.processing().openScope()) {
            LanguageProcessingSnapshotManager manager = new LanguageProcessingSnapshotManager(scope);
            DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(restored, null, manager);
            try (WorkingDocument working = runtime.workingDocument("/")) {
                assertExpected(working, pointer, mode, definition);
                assertByteLists(contributions, encoded(working.sourceContributionsAt(pointer)));
            }
        }
    }

    private void assertExpected(WorkingDocument working, String pointer, String mode, Node definition) {
        if (!mode.endsWith("field")) {
            assertEquals("new", working.resolvedAt("/entries/0/marker").getValue());
            assertNull(working.resolvedAt("/entries/0/obsolete"));
        }
        if ("untyped-replacement".equals(mode)) {
            assertNull(working.resolvedAt(pointer));
            assertTrue(working.sourceContributionsAt(pointer).isEmpty());
        } else {
            assertEquals("retained", working.resolvedAt(pointer + "/explicit").getValue());
            List<FrozenNode> exact = working.sourceContributionsAt(pointer);
            assertEquals(1, exact.size());
            assertEquals(DirectBlueIdCalculator.calculateBlueId(definition), exact.get(0).blueId());
            assertNull(exact.get(0).property("defaultOnly"));
            assertEquals("retained", exact.get(0).property("explicit").getValue());
            if ("explicit-field".equals(mode)) {
                assertEquals("not authored", working.resolvedAt(pointer + "/defaultOnly").getValue());
            }
        }
    }

    private List<byte[]> encoded(List<FrozenNode> contributions) {
        List<byte[]> result = new java.util.ArrayList<>();
        for (FrozenNode contribution : contributions) result.add(frozen.encode(contribution));
        return result;
    }

    private static void assertByteLists(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertTrue(Arrays.equals(expected.get(index), actual.get(index)), "Exact contribution " + index);
        }
    }

    private static Node reference(Node node) {
        return new Node().blueId(DirectBlueIdCalculator.calculateBlueId(node));
    }
}
