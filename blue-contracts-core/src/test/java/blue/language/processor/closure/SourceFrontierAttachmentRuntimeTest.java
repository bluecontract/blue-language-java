package blue.language.processor.closure;

import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceFrontierAttachmentRuntimeTest {
    @Test
    void actualCreationInstallsOnlySelectedFrontierAndLeavesItsHistoryLanePending() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(
                    SameOriginAttachmentPolicy.Mode.FROM_FRONTIER, fixture.binding.sourceDocumentId(),
                    fixture.binding.occurrenceIdentity(), fixture.binding.targetDocumentId(),
                    fixture.binding.expectedTargetBlueId(), ExternalOrderKey.of(Arrays.asList(5L, fixture.binding.expectedTargetBlueId())));
            SourceObservationProgram source = fixture.initialization.program();
            SourceObservationProgram.SourceState installed = source.sourceResults().get(0);
            ManagedReadPin pin = ManagedReadPin.fromExactEvidence(installed.documentId(), installed.blueId(), installed.document(), null);
            SourceFrontierView frontier = SourceFrontierView.fromRetainedEvidence(selection, source, Optional.empty(), pin);
            SameOriginProcessAttempt attempt = fixture.contracts.processSameOrigin(fixture.input,
                    new SameOriginAttachmentPolicy(Collections.singleton(selection)), Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList(), Collections.emptyList(), Collections.singletonList(frontier));
            assertTrue(attempt.complete());
            assertEquals(1, attempt.operations().size());
            SameOriginOperationResult result = attempt.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, result.status());
            assertEquals(BigInteger.valueOf(5), result.resultingDocuments().get(0).document().getProperties().get("creatorRead").getValue());
            assertEquals(BigInteger.ZERO, result.resultingDocuments().get(0).document().getProperties().get("counterB").getValue(),
                    "Selecting a frontier does not replay its prior initialization event");
            assertEquals(1, fixture.initializerExecutions);
            assertTrue(result.consumedSourceOperations().isEmpty());
            ManagedOccurrenceBinding after = result.occurrenceBindings().get(0);
            assertFalse(after.active()); assertEquals(Long.valueOf(0L), after.pendingHistoricalEpoch());
            assertEquals(pin.blueId(), after.expectedTargetBlueId());
            SourceObservationProgram program = result.sourceProgram().get();
            assertEquals(1, program.acceptedViews().size());
            assertEquals(frontier.identity(), program.acceptedViews().get(0).frontierView().get().identity());
            assertTrue(program.acceptedInitializations().isEmpty());
            Map<String, byte[]> fragments = new HashMap<>();
            String hash = SourceObservationProgramCodec.encode(program, fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
            SourceObservationProgram cold = SourceObservationProgramCodec.decode(hash, fragments::get, FrozenNodeEvidenceCodec.Limits.defaults());
            assertEquals(frontier.identity(), cold.acceptedViews().get(0).frontierView().get().identity());
        }
    }
}
