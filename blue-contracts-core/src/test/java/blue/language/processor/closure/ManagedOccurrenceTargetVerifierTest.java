package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedOccurrenceTargetVerifierTest {

    @Test
    void acceptsPureAndExactMaterializedFormsButRejectsTampering() {
        Node body = new Node()
                .properties("documentId", new Node().value("a"))
                .properties("state", new Node().value("current"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(body);
        ManagedDocumentSnapshot target = new ManagedDocumentSnapshot(
                new DocumentId("a"),
                blueId,
                body,
                true,
                false,
                true,
                0L,
                1L);

        assertTrue(ManagedOccurrenceTargetVerifier.establishesExactTarget(
                new Node().blueId(blueId), target));
        assertTrue(ManagedOccurrenceTargetVerifier.establishesExactTarget(
                body.clone(), target));

        Node tampered = body.clone();
        tampered.properties("state", new Node().value("tampered"));
        assertFalse(ManagedOccurrenceTargetVerifier.establishesExactTarget(
                tampered, target));
        assertFalse(ManagedOccurrenceTargetVerifier.establishesExactTarget(
                new Node().blueId(
                        "9XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8"),
                target));
    }
}
