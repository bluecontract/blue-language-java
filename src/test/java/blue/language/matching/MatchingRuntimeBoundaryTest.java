package blue.language.matching;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.matching.FrozenTypeMatcher;
import blue.language.matching.NodeTypeMatcher;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.merge.ResolvedSnapshot;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingRuntimeBoundaryTest {

    @Test
    void shouldUseOnlyTheFocusedRuntimeSurfaceForMutableMatching() {
        // given
        RecordingRuntime runtime = new RecordingRuntime();
        NodeTypeMatcher matcher = new NodeTypeMatcher(runtime);
        Node candidate = new Node().value("same");
        Node target = new Node().value("same");

        // when
        boolean matched = matcher.matchesType(candidate, target);

        // then
        assertTrue(matched);
        assertEquals(2, runtime.preprocessCalls);
        assertEquals(1, runtime.expandCalls);
        assertEquals(1, runtime.resolveCalls);
        assertEquals(0, runtime.materializationCalls);
    }

    @Test
    void shouldFailClosedWhenRuntimeTypeMaterializationFails() {
        // given
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.failMaterialization = true;
        FrozenTypeMatcher matcher = new FrozenTypeMatcher(runtime);
        FrozenNode candidate = FrozenNode.fromResolvedNode(new Node()
                .type(reference(typeBlueId("candidate type")))
                .value("candidate"));
        FrozenNode target = FrozenNode.fromResolvedNode(new Node()
                .type(reference(typeBlueId("target type"))));

        // when
        Executable match = () -> matcher.matchesType(candidate, target);

        // then
        assertThrows(IllegalArgumentException.class, match);
        assertTrue(runtime.materializationCalls > 0);
    }

    @Test
    void shouldUseResolverSnapshotTypeEvidenceForMutableMatching() {
        // given
        Node completedType = new Node()
                .name("Resolved enum type")
                .properties("marker", new Node().value("same"));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                completedType);
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.typeIdentities = lookup(completedType, typeBlueId);
        Node candidate = new Node()
                .value("same")
                .type(completedType.clone());
        Node pattern = new Node().schema(new Schema().enumValues(
                Collections.singletonList(new Node()
                        .value("same")
                        .type(new Node().blueId(typeBlueId)))));

        // when
        boolean matches = new NodeTypeMatcher(runtime).matchesType(
                candidate, pattern);

        // then
        assertTrue(matches);
    }

    @Test
    void shouldUseSnapshotEvidenceAndRejectBareFrozenMatching() {
        // given
        Node completedType = new Node()
                .name("Snapshot enum type")
                .properties("marker", new Node().value("same"));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                completedType);
        FrozenNode candidate = FrozenNode.fromResolvedNode(new Node()
                .value("same")
                .type(completedType.clone()));
        FrozenNode canonical = FrozenNode.fromNode(new Node()
                .value("same")
                .type(new Node().blueId(typeBlueId)));
        FrozenNode pattern = FrozenNode.fromResolvedNode(
                new Node().schema(new Schema().enumValues(
                        Collections.singletonList(new Node()
                                .value("same")
                                .type(new Node().blueId(typeBlueId))))));
        ResolvedSnapshot snapshot = ResolvedSnapshot.withDeferredResolution(
                canonical,
                candidate,
                lookup(completedType, typeBlueId));
        NodeTypeMatcher matcher = new NodeTypeMatcher(
                new RecordingRuntime());

        // when
        boolean snapshotMatches =
                matcher.matchesResolvedType(snapshot, "", pattern);
        boolean bareMatches = matcher.matchesResolvedType(candidate, pattern);

        // then
        assertTrue(snapshotMatches);
        assertFalse(bareMatches);
    }

    private String typeBlueId(String value) {
        return DirectBlueIdCalculator.calculateBlueId(new Node().value(value));
    }

    private Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static CanonicalTypeIdentityLookup lookup(
            Node completedType,
            String canonicalBlueId) {
        FrozenNode expected = FrozenNode.fromResolvedNode(completedType);
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node candidate) {
                return Optional.of(CanonicalTypeIdentityEvidence.identityOnly(
                        requireCanonicalTypeBlueId(candidate)));
            }

            @Override
            public String requireCanonicalTypeBlueId(Node candidate) {
                if (!expected.sameResolvedStructure(
                        FrozenNode.fromResolvedNode(candidate))) {
                    throw new IllegalStateException(
                            "Unexpected completed type");
                }
                return canonicalBlueId;
            }
        };
    }

    private static final class RecordingRuntime implements MatchingRuntime {
        private int preprocessCalls;
        private int expandCalls;
        private int resolveCalls;
        private int materializationCalls;
        private boolean failMaterialization;
        private CanonicalTypeIdentityLookup typeIdentities =
                CanonicalTypeIdentityLookup.incomplete();

        @Override
        public BlueCachePolicy matchingCachePolicy() {
            return BlueCachePolicy.boundedDefaults();
        }

        @Override
        public Node preprocessForMatching(Node source) {
            preprocessCalls++;
            return source;
        }

        @Override
        public void expandForMatching(Node source, ResolutionLimits limits) {
            expandCalls++;
        }

        @Override
        public TypeEvidenceResolution resolveTypeEvidenceForMatching(
                Node source,
                ResolutionLimits limits) {
            resolveCalls++;
            FrozenNode frozen = FrozenNode.fromResolvedNode(source);
            CanonicalTypeIdentityLookup identities = typeIdentities;
            return new TypeEvidenceResolution(frozen, identities);
        }

        @Override
        public TypeEvidenceResolution materializeTypeReferenceForMatching(
                FrozenNode reference) {
            materializationCalls++;
            if (failMaterialization) {
                throw new IllegalArgumentException(
                        "simulated unavailable type evidence");
            }
            return null;
        }
    }
}
