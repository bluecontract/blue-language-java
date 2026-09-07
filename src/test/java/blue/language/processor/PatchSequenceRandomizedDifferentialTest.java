package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;

class PatchSequenceRandomizedDifferentialTest {

    private static final UpdateMaterializationMetrics NOOP_METRICS =
            new UpdateMaterializationMetrics() {
                @Override
                public void recordBeforeNodeMaterialization() {
                }

                @Override
                public void recordAfterNodeMaterialization() {
                }
            };

    @Test
    void shouldVerifyRandomizedSequentialCheckpointsUpdatesAndFinalIdsMatchPublicSingletonPatching() {
        // given
        int[] counts = {0, 1, 2, 4, 8, 16, 32, 64, 128};
        // when
        for (int count : counts) {
            for (int scenario = 0; scenario < 8; scenario++) {
                long seed = 0x5E0A11A1L + 1_009L * count + scenario;
                verifySequence(count, seed);
            }
        }
    // then
    }

    private void verifySequence(int count, long seed) {
        Node initial = initialDocument();
        List<JsonPatch> patches = randomizedValidPatches(count, seed);
        FrozenNode initialRoot = FrozenNode.fromResolvedNode(initial.clone());
        SequentialPatchPlanningSession session = new SequentialPatchPlanningSession("/",
                PatchPlanningContextFactory.create(
                        initialRoot, initialRoot, false, null),
                null,
                null,
                NOOP_METRICS);
        DocumentProcessingRuntime reference = new DocumentProcessingRuntime(initial.clone());

        for (int index = 0; index < patches.size(); index++) {
            JsonPatch patch = patches.get(index);
            String context = "seed=" + seed + ", count=" + count + ", patch=" + index
                    + ", op=" + patch.getOp() + ", path=" + patch.getPath();

            SequentialPatchPlanningSession.PlannedStep planned = session.planNext(patch);
            List<DocumentUpdateData> plannedUpdates =
                    planned.result().updates();
            DocumentUpdateData referenceUpdate =
                    reference.applyPatch("/", patch);

            assertEquals(1, plannedUpdates.size(), context + " update count");
            assertNotNull(referenceUpdate, context + " reference update");
            assertUpdateEquals(referenceUpdate, plannedUpdates.get(0), context);

            FrozenNode referenceRoot = FrozenNode.fromResolvedNode(reference.document().clone());
            assertRootEquals(referenceRoot, session.canonicalRoot(), context + " canonical checkpoint");
            assertRootEquals(referenceRoot, session.resolvedRoot(), context + " resolved checkpoint");
        }

        FrozenNode finalReference = FrozenNode.fromResolvedNode(reference.document().clone());
        assertEquals(finalReference.blueId(), session.canonicalRoot().blueId(),
                "final canonical BlueId for seed " + seed + " and count " + count);
        assertEquals(finalReference.blueId(), session.resolvedRoot().blueId(),
                "final resolved BlueId for seed " + seed + " and count " + count);
    }

    private void assertUpdateEquals(DocumentUpdateData expected,
                                    DocumentUpdateData actual,
                                    String context) {
        assertEquals(expected.path(), actual.path(), context + " update path");
        assertEquals(expected.op(), actual.op(), context + " update op");
        assertEquals(expected.originScope(), actual.originScope(), context + " update origin");
        assertEquals(expected.cascadeScopes(), actual.cascadeScopes(), context + " cascades");
        assertEquals(structuralKey(expected.before()), structuralKey(actual.before()),
                context + " before value");
        assertEquals(structuralKey(expected.after()), structuralKey(actual.after()),
                context + " after value");
    }

    private void assertRootEquals(FrozenNode expected, FrozenNode actual, String context) {
        assertEquals(expected.blueId(), actual.blueId(), context + " BlueId");
        assertEquals(JSON_MAPPER.writeValueAsString(expected.toNode()),
                JSON_MAPPER.writeValueAsString(actual.toNode()),
                context + " materialized document");
    }

    private Object structuralKey(Node node) {
        return node == null ? null : FrozenNode.fromResolvedNode(node).resolvedStructuralKey();
    }

    private Node initialDocument() {
        return new Node()
                .properties("object", new Node()
                        .properties("seed", objectValue(-1, 0)))
                .properties("array", new Node().items(
                        new Node().value(10),
                        objectValue(-1, 1),
                        new Node().value(30),
                        objectValue(-1, 2)))
                .properties("stable", new Node().value("untouched"));
    }

    private List<JsonPatch> randomizedValidPatches(int count, long seed) {
        Random random = new Random(seed);
        List<JsonPatch> patches = new ArrayList<>(count);
        List<String> objectKeys = new ArrayList<>(Arrays.asList("seed"));
        int nextKey = 0;
        int arraySize = 4;

        for (int index = 0; index < count; index++) {
            int action = index < 6 ? index : random.nextInt(6);
            switch (action) {
                case 0: {
                    String key = "key" + nextKey++;
                    objectKeys.add(key);
                    patches.add(JsonPatch.add("/object/" + key,
                            randomValue(random, index)));
                    break;
                }
                case 1: {
                    if (objectKeys.isEmpty()) {
                        String key = "key" + nextKey++;
                        objectKeys.add(key);
                        patches.add(JsonPatch.add("/object/" + key,
                                randomValue(random, index)));
                    } else {
                        String key = objectKeys.get(random.nextInt(objectKeys.size()));
                        patches.add(JsonPatch.replace("/object/" + key,
                                randomValue(random, index)));
                    }
                    break;
                }
                case 2: {
                    if (objectKeys.isEmpty()) {
                        String key = "key" + nextKey++;
                        objectKeys.add(key);
                        patches.add(JsonPatch.add("/object/" + key,
                                randomValue(random, index)));
                    } else {
                        int removed = random.nextInt(objectKeys.size());
                        String key = objectKeys.remove(removed);
                        patches.add(JsonPatch.remove("/object/" + key));
                    }
                    break;
                }
                case 3: {
                    String target = random.nextBoolean() || arraySize == 0
                            ? "-"
                            : String.valueOf(random.nextInt(arraySize + 1));
                    patches.add(JsonPatch.add("/array/" + target,
                            randomValue(random, index)));
                    arraySize++;
                    break;
                }
                case 4: {
                    if (arraySize == 0) {
                        patches.add(JsonPatch.add("/array/-", randomValue(random, index)));
                        arraySize++;
                    } else {
                        patches.add(JsonPatch.replace("/array/" + random.nextInt(arraySize),
                                randomValue(random, index)));
                    }
                    break;
                }
                case 5: {
                    if (arraySize == 0) {
                        patches.add(JsonPatch.add("/array/-", randomValue(random, index)));
                        arraySize++;
                    } else {
                        patches.add(JsonPatch.remove("/array/" + random.nextInt(arraySize)));
                        arraySize--;
                    }
                    break;
                }
                default:
                    throw new AssertionError("Unknown action " + action);
            }
        }
        return patches;
    }

    private Node randomValue(Random random, int index) {
        if (random.nextBoolean()) {
            return new Node().value("value-" + index + '-' + random.nextInt(10_000));
        }
        return objectValue(index, random.nextInt(10_000));
    }

    private Node objectValue(int index, int nonce) {
        return new Node()
                .properties("index", new Node().value(index))
                .properties("nested", new Node()
                        .properties("nonce", new Node().value(nonce))
                        .properties("label", new Node().value("object-" + index)));
    }
}
