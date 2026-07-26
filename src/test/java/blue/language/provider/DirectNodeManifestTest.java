package blue.language.provider;

import blue.language.BlueOperationOutcome;
import blue.language.BlueOperationResult;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DirectNodeManifestTest {

    @Test
    void completeManifestEstablishesAbsence() {
        DirectNodeManifest manifest = DirectNodeManifest.complete(
                new Node().properties("present", new Node().value("value")));

        BlueOperationResult<Node> result = manifest.semanticSelect("/missing");

        assertEquals(BlueOperationOutcome.ABSENT, result.outcome());
        assertFalse(result.providerOutcome().isPresent());
    }

    @Test
    void partialManifestCannotEstablishAbsence() {
        DirectNodeManifest manifest = DirectNodeManifest.partial(
                new Node().properties("present", new Node().value("value")));

        BlueOperationResult<Node> result = manifest.semanticSelect("/missing");

        assertEquals(BlueOperationOutcome.INCOMPLETE, result.outcome());
        assertFalse(result.providerOutcome().isPresent());
    }

    @Test
    void invalidPointerIsInvalidEvidenceRatherThanAbsence() {
        BlueOperationResult<Node> result = DirectNodeManifest.complete(new Node())
                .semanticSelect("/bad~2escape");

        assertEquals(BlueOperationOutcome.INVALID, result.outcome());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                result.providerOutcome().orElse(null));
    }

    @Test
    void completeDirectManifestCannotInferAbsenceBelowAReference() {
        String referencedBlueId =
                blue.language.utils.BlueIdCalculator.calculateBlueId(
                        new Node().properties(
                                "present",
                                new Node().value(true)));
        DirectNodeManifest manifest = DirectNodeManifest.complete(
                new Node().properties(
                        "lazy",
                        new Node().blueId(referencedBlueId)));

        BlueOperationResult<Node> result =
                manifest.semanticSelect("/lazy/missing");

        assertEquals(BlueOperationOutcome.INCOMPLETE, result.outcome());
        assertEquals(
                Collections.singleton(referencedBlueId),
                result.outstandingBlueIds());
    }

    @Test
    void referenceWrapperBlueIdRemainsSemanticAbsence() {
        String referencedBlueId =
                blue.language.utils.BlueIdCalculator.calculateBlueId(
                        new Node().value("content"));
        DirectNodeManifest manifest = DirectNodeManifest.complete(
                new Node().properties(
                        "lazy",
                        new Node().blueId(referencedBlueId)));

        BlueOperationResult<Node> result =
                manifest.semanticSelect("/lazy/blueId");

        assertEquals(BlueOperationOutcome.ABSENT, result.outcome());
        assertFalse(result.providerOutcome().isPresent());
    }
}
