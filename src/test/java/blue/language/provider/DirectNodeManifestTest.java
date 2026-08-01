package blue.language.provider;

import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DirectNodeManifestTest {

    @Test
    void shouldEstablishAbsenceWithCompleteManifest() {
        // given
        DirectNodeManifest manifest = DirectNodeManifest.complete(
                new Node().properties("present", new Node().value("value")));

        // when
        BlueOperationResult<Node> result = manifest.semanticSelect("/missing");

        // then
        assertEquals(BlueOperationOutcome.ABSENT, result.outcome());
        assertFalse(result.providerOutcome().isPresent());
    }

    @Test
    void shouldNotEstablishAbsenceWithPartialManifest() {
        // given
        DirectNodeManifest manifest = DirectNodeManifest.partial(
                new Node().properties("present", new Node().value("value")));

        // when
        BlueOperationResult<Node> result = manifest.semanticSelect("/missing");

        // then
        assertEquals(BlueOperationOutcome.INCOMPLETE, result.outcome());
        assertFalse(result.providerOutcome().isPresent());
    }

    @Test
    void shouldTreatInvalidPointerAsInvalidEvidenceRatherThanAbsence() {
        // given
        DirectNodeManifest manifest = DirectNodeManifest.complete(new Node());
        String invalidPointer = "/bad~2escape";

        // when
        BlueOperationResult<Node> result = manifest.semanticSelect(invalidPointer);

        // then
        assertEquals(BlueOperationOutcome.INVALID, result.outcome());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                result.providerOutcome().orElse(null));
    }

    @Test
    void shouldNotInferAbsenceBelowReferenceWithCompleteDirectManifest() {
        // given
        String referencedBlueId =
                blue.language.utils.BlueIdCalculator.calculateBlueId(
                        new Node().properties(
                                "present",
                                new Node().value(true)));
        DirectNodeManifest manifest = DirectNodeManifest.complete(
                new Node().properties(
                        "lazy",
                        new Node().blueId(referencedBlueId)));

        // when
        BlueOperationResult<Node> result =
                manifest.semanticSelect("/lazy/missing");

        // then
        assertEquals(BlueOperationOutcome.INCOMPLETE, result.outcome());
        assertEquals(
                Collections.singleton(referencedBlueId),
                result.outstandingBlueIds());
    }

    @Test
    void shouldTreatReferenceWrapperBlueIdAsSemanticAbsence() {
        // given
        String referencedBlueId =
                blue.language.utils.BlueIdCalculator.calculateBlueId(
                        new Node().value("content"));
        DirectNodeManifest manifest = DirectNodeManifest.complete(
                new Node().properties(
                        "lazy",
                        new Node().blueId(referencedBlueId)));

        // when
        BlueOperationResult<Node> result =
                manifest.semanticSelect("/lazy/blueId");

        // then
        assertEquals(BlueOperationOutcome.ABSENT, result.outcome());
        assertFalse(result.providerOutcome().isPresent());
    }
}
