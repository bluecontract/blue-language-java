package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalTypeIdentityEvidenceTest {

    @Test
    void ownsAnImmutableCopyOfAuthoredInlineSource() {
        Node authored = new Node()
                .name("Exact type")
                .properties("kind", new Node().value("fixed"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(authored);

        CanonicalTypeIdentityEvidence evidence =
                CanonicalTypeIdentityEvidence.authoredInline(
                        blueId, authored, authored);
        authored.name("mutated input");
        Node proof = evidence.canonicalTypeIdentityInput();
        Node firstRead = evidence.authoredTypeSource();
        firstRead.name("mutated result");
        Node secondRead = evidence.authoredTypeSource();

        assertNotSame(firstRead, secondRead);
        assertEquals("Exact type", proof.getName());
        assertEquals("Exact type", secondRead.getName());
        assertEquals(blueId,
                DirectBlueIdCalculator.calculateBlueId(secondRead));
    }

    @Test
    void rejectsAuthoredSourceThatDoesNotProveTheClaimedIdentity() {
        Node authored = new Node().name("Exact type");
        String differentBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Different type"));

        assertThrows(IllegalArgumentException.class,
                () -> CanonicalTypeIdentityEvidence.authoredInline(
                        differentBlueId, authored, authored));
    }

    @Test
    void rejectsNoncanonicalNestedTypePositionsInAuthoredEvidence() {
        Node inlineNestedType = new Node().name("Nested inline type");
        Node ordinaryNestedType = new Node()
                .name("Outer type")
                .properties(
                        "ordinary", new Node().type(
                                inlineNestedType.clone()));
        Node schemaNestedType = new Node()
                .name("Schema outer type")
                .schema(new Schema().required(
                        new Node().value(true).itemType(
                                inlineNestedType.clone())));
        String ordinaryBlueId = DirectBlueIdCalculator.calculateBlueId(
                ordinaryNestedType);
        String schemaBlueId = DirectBlueIdCalculator.calculateBlueId(
                schemaNestedType);

        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalTypeIdentityEvidence.authoredInline(
                        ordinaryBlueId,
                        ordinaryNestedType,
                        ordinaryNestedType));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalTypeIdentityEvidence.authoredInline(
                        schemaBlueId,
                        schemaNestedType,
                        schemaNestedType));
    }

    @Test
    void keepsCanonicalProofSeparateFromSelfContainedNestedInlineSource() {
        Node nestedAuthoredType = new Node().name("Nested inline type");
        String nestedBlueId = DirectBlueIdCalculator.calculateBlueId(
                nestedAuthoredType);
        Node canonicalProof = new Node()
                .name("Outer type")
                .properties(
                        "member",
                        new Node().type(new Node().blueId(nestedBlueId)));
        Node authoredSource = new Node()
                .name("Outer type")
                .properties(
                        "member",
                        new Node().type(nestedAuthoredType));
        String outerBlueId = DirectBlueIdCalculator.calculateBlueId(
                canonicalProof);

        CanonicalTypeIdentityEvidence evidence =
                CanonicalTypeIdentityEvidence.authoredInline(
                        outerBlueId,
                        canonicalProof,
                        authoredSource);

        Node retainedProof = evidence.canonicalTypeIdentityInput();
        Node retainedSource = evidence.authoredTypeSource();
        Node proofType = retainedProof.getProperties()
                .get("member").getType();
        Node sourceType = retainedSource.getProperties()
                .get("member").getType();
        assertTrue(proofType.isReferenceOnly());
        assertEquals(nestedBlueId, proofType.getBlueId());
        assertFalse(sourceType.isReferenceOnly());
        assertEquals("Nested inline type", sourceType.getName());
        assertEquals(
                outerBlueId,
                DirectBlueIdCalculator.calculateBlueId(retainedProof));
    }
}
