package blue.language.merge;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.processor.TypeAssigner;
import blue.language.model.Node;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class TypeEvidenceResolutionTest {

    @Test
    void limitedResolutionRetainsReachedTypeEvidenceWithoutClaimingCanonicalRoot() {
        Node inlineType = new Node().name("Reached Inline Type");
        String inlineTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                inlineType);
        Node source = new Node()
                .type(inlineType.clone())
                .value("instance");
        ResolutionLimits targetLimited =
                ResolutionLimits.withSinglePath("/value");

        TypeEvidenceResolution result = new Merger(
                new TypeAssigner(),
                blueId -> null)
                .resolveTypeEvidence(source, targetLimited);

        assertFalse(result.canonicalTypeIdentities()
                .hasCompleteCoverage());
        assertEquals(
                inlineTypeBlueId,
                result.canonicalTypeIdentities()
                        .requireCanonicalTypeBlueId(
                                result.resolvedRoot().getType().toNode()));
    }
}
