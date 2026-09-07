package blue.language.matching;

import blue.language.api.BlueCachePolicy;
import blue.language.merge.Merger;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.resolve.ResolutionLimits;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class NodeTypeMatcherLimitedResolutionTest {

    @Test
    void positiveMutableMatchUsesResolvedOnlyEvidenceUnderTargetLimits() {
        MatchingRuntime runtime = new MatchingRuntime() {
            @Override
            public BlueCachePolicy matchingCachePolicy() {
                return BlueCachePolicy.boundedDefaults();
            }

            @Override
            public Node preprocessForMatching(Node source) {
                return source;
            }

            @Override
            public void expandForMatching(
                    Node source,
                    ResolutionLimits limits) {
                // No provider-backed references in this focused candidate.
            }

            @Override
            public TypeEvidenceResolution resolveTypeEvidenceForMatching(
                    Node source,
                    ResolutionLimits limits) {
                return new Merger(
                        new SequentialMergingProcessor(Arrays.asList(
                                new ValuePropagator(),
                                new TypeAssigner())),
                        blueId -> null)
                        .resolveTypeEvidence(source, limits);
            }

            @Override
            public TypeEvidenceResolution materializeTypeReferenceForMatching(
                    FrozenNode reference) {
                return null;
            }
        };

        assertTrue(new NodeTypeMatcher(runtime).matchesType(
                new Node().value("same"),
                new Node().value("same")));
    }
}
