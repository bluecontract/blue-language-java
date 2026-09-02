package blue.language.matching;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static blue.language.identity.DirectBlueIdCalculator.calculateBlueId;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MatchingCanonicalTypeIdentityEvidenceTest {

    @Test
    void shouldIncludeRetainedTypeEvidenceGraphInCacheAdmissionWeight() {
        // given
        Node materialized = new Node().name("Materialized Type");
        String blueId = calculateBlueId(materialized);
        CanonicalTypeIdentityLookup heavyweightEvidence =
                new CanonicalTypeIdentityLookup() {
                    @Override
                    public boolean hasCompleteCoverage() {
                        return true;
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(Node completedType) {
                        return Optional.of(
                                CanonicalTypeIdentityEvidence.identityOnly(
                                        blueId));
                    }

                    @Override
                    public long approximateRetainedWeightBytes() {
                        return 32_768L;
                    }
                };
        MatchingPlanCache cache = new MatchingPlanCache(
                BlueCachePolicy.builder()
                        .conformancePlans(8, 4_096L)
                        .maximumDerivedEntryWeightBytes(4_096L)
                        .build());
        MatchingCanonicalTypeIdentityEvidence evidence =
                new MatchingCanonicalTypeIdentityEvidence(cache, null);

        // when
        evidence.beginInvocation();
        try {
            evidence.retainMaterializedTypeEvidence(
                    blueId,
                    FrozenNode.fromResolvedNode(materialized),
                    heavyweightEvidence);
        } finally {
            evidence.endInvocation();
        }

        // then
        assertFalse(evidence.hasRetainedMaterializationEvidence(blueId));
    }
}
