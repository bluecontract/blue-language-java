package blue.language.processor.closure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual interpreter capture plus structural negative controls at the cold transport boundary. */
class SourcePatchTopologyCodecTest {
    @Test
    void creatingPatchRetainsItsOriginalRowsInsteadOfTheCompletedImportPin() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            SourceObservationProgram program = fixture.install(fixture.initialization).operations().get(0).sourceProgram().get();
            Map<String, byte[]> store = new LinkedHashMap<>();
            String root = SourceObservationProgramCodec.encode(program, store::put, Limits.defaults());
            SourceObservationProgram cold = SourceObservationProgramCodec.decode(root, store::get, Limits.defaults());
            boolean sawOriginalCreation = false;
            for (int i = 0; i < program.steps().size(); i++) {
                SourceObservationProgram.Step hotStep = program.steps().get(i), coldStep = cold.steps().get(i);
                for (int j = 0; j < hotStep.actions().size(); j++) {
                    if (!(hotStep.actions().get(j) instanceof SourceObservationProgram.Patch)) continue;
                    SourceObservationProgram.Patch hotPatch = (SourceObservationProgram.Patch) hotStep.actions().get(j);
                    SourceObservationProgram.Patch coldPatch = (SourceObservationProgram.Patch) coldStep.actions().get(j);
                    assertNotNull(hotPatch.resultingBindings());
                    assertEquals(hotPatch.siteIdentity(), coldPatch.siteIdentity());
                    assertEquals(hotPatch.transitionIdentity(), coldPatch.transitionIdentity());
                    assertEquals(hotPatch.resultingBindings().size(), coldPatch.resultingBindings().size());
                    for (int k = 0; k < hotPatch.resultingBindings().size(); k++) {
                        ManagedOccurrenceBinding row = hotPatch.resultingBindings().get(k);
                        assertEquals(hotStep.targetDocumentId(), row.sourceDocumentId());
                        assertEquals(row.bindingIdentity(), coldPatch.resultingBindings().get(k).bindingIdentity());
                        if (row.occurrenceIdentity().equals(fixture.binding.occurrenceIdentity())
                                && fixture.binding.expectedTargetBlueId().equals(row.expectedTargetBlueId())) {
                            sawOriginalCreation = true;
                        }
                    }
                }
            }
            assertTrue(sawOriginalCreation, "The original creating patch precedes the separate completed init0 pin");
            assertEquals(Long.valueOf(0L), program.sourceAfterBindings().get(0).pendingHistoricalEpoch());
            assertEquals(root, SourceObservationProgramCodec.encode(cold, (key, value) -> { }, Limits.defaults()));
        }
    }

    @Test
    void coldPatchCannotOmitItsExactTopologyEvenWithRecomputedTransportHashes() {
        rejectChangedPatch(false);
    }

    @Test
    void coldPatchCannotSubstituteAnotherSourcesWellFormedRows() {
        rejectChangedPatch(true);
    }

    private static void rejectChangedPatch(boolean foreignRows) {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            Map<String, byte[]> store = new LinkedHashMap<>();
            String key = SourceObservationProgramCodec.encode(fixture.initialization.program(), store::put, Limits.defaults());
            ObjectNode root = (ObjectNode) json(store.get(key));
            ArrayNode steps = (ArrayNode) root.get("steps");
            boolean changed = false;
            for (int i = 0; i < steps.size() && !changed; i++) {
                ObjectNode step = (ObjectNode) json(store.get(steps.get(i).asText()));
                for (JsonNode action : step.get("actions")) {
                    if (!"patch".equals(action.get("kind").asText())) continue;
                    if (!foreignRows) ((ObjectNode) action).remove("resultingBindings");
                    else {
                        ManagedOccurrenceBinding row = fixture.binding;
                        ObjectNode edge = ((ArrayNode) action.get("resultingBindings")).addObject();
                        edge.put("occurrence", row.occurrenceIdentity()); edge.put("binding", row.bindingIdentity());
                        edge.put("policy", row.bindingPolicyIdentity()); edge.put("source", row.sourceDocumentId().value());
                        edge.put("path", row.sourcePath()); edge.put("activation", row.activationGeneration());
                        edge.put("target", row.targetDocumentId().value()); edge.put("expected", row.expectedTargetBlueId());
                        edge.put("active", row.active()); edge.putNull("pendingEpoch");
                    }
                    byte[] encodedStep = bytes(step); String stepKey = digest(encodedStep);
                    store.put(stepKey, encodedStep); steps.set(i, steps.textNode(stepKey)); changed = true; break;
                }
            }
            assertTrue(changed);
            byte[] encodedRoot = bytes(root); String changedRoot = digest(encodedRoot); store.put(changedRoot, encodedRoot);
            blue.language.processor.InvalidExecutionEvidenceException failure = assertThrows(blue.language.processor.InvalidExecutionEvidenceException.class,
                    () -> SourceObservationProgramCodec.decode(changedRoot, store::get, Limits.defaults()));
            assertTrue(failure.getMessage().contains(foreignRows ? "another source" : "resultingBindings"), failure::getMessage);
        }
    }
}
