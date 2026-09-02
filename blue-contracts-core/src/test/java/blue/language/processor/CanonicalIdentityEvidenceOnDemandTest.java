package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CanonicalIdentityEvidenceOnDemandTest {

    private static final long COMPLETE_LOOKUP_WEIGHT = 128L;

    @Test
    void shouldKeepPureReferenceLookupCold() {
        Node referencedType = new Node().name("Referenced type");
        String blueId = DirectBlueIdCalculator.calculateBlueId(
                referencedType);
        AtomicInteger materializations = new AtomicInteger();
        CanonicalTypeIdentityLookup lookup = CanonicalIdentityEvidence.onDemand(
                CanonicalTypeIdentityLookup.incomplete(),
                () -> {
                    materializations.incrementAndGet();
                    return completeLookup(referencedType, blueId);
                });

        String actual = lookup.requireCanonicalTypeBlueId(
                new Node().blueId(blueId));

        assertEquals(blueId, actual);
        assertEquals(0, materializations.get());
        assertFalse(lookup.hasCompleteCoverage());
    }

    @Test
    void shouldMaterializeInlineEvidenceOnlyOnceOnFirstDemand() {
        Node completedType = new Node().name("Completed type");
        String blueId = DirectBlueIdCalculator.calculateBlueId(
                completedType);
        AtomicInteger materializations = new AtomicInteger();
        CanonicalTypeIdentityLookup lookup = CanonicalIdentityEvidence.onDemand(
                CanonicalTypeIdentityLookup.incomplete(),
                () -> {
                    materializations.incrementAndGet();
                    return completeLookup(completedType, blueId);
                });
        assertEquals(Long.MAX_VALUE,
                lookup.approximateRetainedWeightBytes());

        String first = lookup.requireCanonicalTypeBlueId(completedType);
        String second = lookup.requireCanonicalTypeBlueId(
                completedType.clone());

        assertEquals(blueId, first);
        assertEquals(blueId, second);
        assertEquals(1, materializations.get());
        assertTrue(lookup.hasCompleteCoverage());
        assertEquals(
                COMPLETE_LOOKUP_WEIGHT,
                lookup.approximateRetainedWeightBytes());
    }

    @Test
    void shouldMaterializeEvidenceWhenWholeGraphCoverageIsRequired() {
        Node completedType = new Node().name("Coverage type");
        String blueId = DirectBlueIdCalculator.calculateBlueId(
                completedType);
        AtomicInteger materializations = new AtomicInteger();
        CanonicalTypeIdentityLookup lookup = CanonicalIdentityEvidence.onDemand(
                CanonicalTypeIdentityLookup.incomplete(),
                () -> {
                    materializations.incrementAndGet();
                    return completeLookup(completedType, blueId);
                });

        lookup.requireCompleteCoverage();

        assertEquals(1, materializations.get());
        assertTrue(lookup.hasCompleteCoverage());
    }

    private static CanonicalTypeIdentityLookup completeLookup(
            Node completedType,
            String blueId) {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node candidate) {
                return candidate != null
                        && completedType.getName().equals(candidate.getName())
                        ? Optional.of(CanonicalTypeIdentityEvidence
                                .identityOnly(blueId))
                        : Optional.empty();
            }

            @Override
            public long approximateRetainedWeightBytes() {
                return COMPLETE_LOOKUP_WEIGHT;
            }
        };
    }
}
