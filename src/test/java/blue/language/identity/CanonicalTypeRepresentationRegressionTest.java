package blue.language.identity;

import blue.language.api.BlueCachePolicy;
import blue.language.merge.NodeResolver;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.NodeProvider;
import blue.language.provider.Types;
import blue.language.resolve.ResolutionLimits;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Desired regressions: effective type identity must not depend on representation. */
class CanonicalTypeRepresentationRegressionTest {

    @Test
    void shouldRejectDirectInlineEnumKeyWithoutResolverEvidence() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node inlineValue = typedScalar("same", fixture.authoredT);

        // when
        Executable canonicalization =
                () -> SchemaEnumCanonicalizer.canonicalKey(inlineValue);

        // then
        assertThrows(
                IllegalStateException.class,
                canonicalization);
    }

    @Test
    void shouldUseCanonicalEffectiveTypeIdentityForResolvedEnumKeys() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node inlineValue = typedScalar("same", fixture.completedT);
        Node referencedValue = typedScalar("same", new Node().blueId(fixture.tBlueId));

        // when
        String referencedKey = SchemaEnumCanonicalizer.canonicalKeyResolved(
                referencedValue, fixture.identities);
        String inlineKey = SchemaEnumCanonicalizer.canonicalKeyResolved(
                inlineValue, fixture.identities);

        // then
        assertEquals(referencedKey, inlineKey);
    }

    @Test
    void shouldFailResolvedScalarIdentityWithoutEffectiveTypeEvidence() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node inlineValue = typedScalar("same", fixture.completedT);
        CanonicalTypeIdentityLookup missing = new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                throw new IllegalStateException("missing test evidence");
            }

            @Override
            public String requireCanonicalTypeBlueId(Node completedType) {
                throw new IllegalStateException("missing test evidence");
            }
        };

        // when
        Executable normalization =
                () -> ScalarNodeIdentity.normalizedResolved(
                        inlineValue, missing);

        // then
        assertThrows(
                IllegalStateException.class,
                normalization);
    }

    @Test
    void shouldIntersectEquivalentInlineAndReferencedEnumValues() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node inlineValue = typedScalar("same", fixture.completedT);
        Node referencedValue = typedScalar("same", new Node().blueId(fixture.tBlueId));

        Node target = enumNode(inlineValue);
        Node source = enumNode(referencedValue);

        // when
        new SchemaPropagator().process(
                target,
                source,
                fixture.provider,
                fixture.resolver(),
                fixture.identities);

        // then
        assertEquals(1, target.getSchema().getEnum().size());
        assertEquals(
                fixture.tBlueId,
                target.getSchema().getEnum().get(0).getType().getBlueId());
    }

    @Test
    void shouldMatchCompletedInlineValueAgainstReferencedEnumType() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node allowed = typedScalar(
                "same", new Node().blueId(fixture.tBlueId));
        Node actual = typedScalar("same", fixture.completedT)
                .schema(new Schema().enumValues(
                        Collections.singletonList(allowed)));

        // when
        Executable validation = () -> new SchemaVerifier().validateCompleted(
                actual, true, "/value", fixture.identities);

        // then
        assertDoesNotThrow(validation);
    }

    @Test
    void shouldMatchPureReferenceEnumMemberByScalarIdentity() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node actual = typedScalar("same", fixture.completedT);
        String scalarBlueId = DirectBlueIdCalculator.calculateBlueId(
                typedScalar(
                        "same", new Node().blueId(fixture.tBlueId)));
        actual.schema(new Schema().enumValues(
                Collections.singletonList(
                        new Node().blueId(scalarBlueId))));

        // when
        Executable validation = () -> new SchemaVerifier().validateCompleted(
                actual, true, "/value", fixture.identities);

        // then
        assertDoesNotThrow(validation);
    }

    @Test
    void shouldMatchInlineCompletedTypeToItsCanonicalReference() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node referencedT = new Node().blueId(fixture.tBlueId);

        // when
        boolean subtype = Types.isSubtype(
                fixture.completedT,
                referencedT,
                fixture.provider,
                fixture.identities);

        // then
        assertTrue(subtype);
    }

    @Test
    void shouldMatchCanonicalReferenceToItsInlineCompletedType() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node referencedT = new Node().blueId(fixture.tBlueId);

        // when
        boolean subtype = Types.isSubtype(
                referencedT,
                fixture.completedT,
                fixture.provider,
                fixture.identities);

        // then
        assertTrue(subtype);
    }

    @Test
    void shouldTraverseCanonicalParentFromInlineCompletedType() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node referencedP = new Node().blueId(fixture.pBlueId);

        // when
        boolean subtype = Types.isSubtype(
                fixture.completedT,
                referencedP,
                fixture.provider,
                fixture.identities);

        // then
        assertTrue(subtype);
    }

    @Test
    void shouldFailSubtypeCheckWithIncompleteInlineIdentityEvidence() {
        // given
        TypeFixture fixture = TypeFixture.create();

        // when
        Executable subtypeCheck = () -> Types.isSubtype(
                fixture.completedT,
                new Node().blueId(fixture.tBlueId),
                fixture.provider,
                CanonicalTypeIdentityLookup.incomplete());

        // then
        assertThrows(
                IllegalStateException.class,
                subtypeCheck);
    }

    @Test
    void shouldTraversePureReferencesWithoutInlineIdentityEvidence() {
        // given
        TypeFixture fixture = TypeFixture.create();

        // when
        boolean subtype = Types.isSubtype(
                new Node().blueId(fixture.tBlueId),
                new Node().blueId(fixture.pBlueId),
                fixture.provider,
                CanonicalTypeIdentityLookup.incomplete());

        // then
        assertTrue(subtype);
    }

    @Test
    void shouldResolveExactChildTypeBeforeMergeSubtypeComparison() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node target = new Node().itemType(fixture.completedT.clone());
        Node source = new Node().items(Collections.singletonList(
                new Node().type(fixture.authoredT.clone())));
        boolean[] observed = new boolean[2];
        int[] detachedProofs = new int[1];
        FrozenNode completedT = FrozenNode.fromResolvedNode(
                fixture.completedT);
        CanonicalTypeIdentityLookup activeIdentities =
                new CanonicalTypeIdentityLookup() {
                    @Override
                    public boolean hasCompleteCoverage() {
                        return false;
                    }

                    @Override
                    public Optional<String> findCanonicalTypeBlueId(
                            Node completedType) {
                        return completedT.sameResolvedStructure(
                                FrozenNode.fromResolvedNode(completedType))
                                ? Optional.of(fixture.tBlueId)
                                : Optional.empty();
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(Node completedType) {
                        return findCanonicalTypeBlueId(completedType)
                                .map(CanonicalTypeIdentityEvidence
                                        ::identityOnly);
                    }

                    @Override
                    public String requireCanonicalTypeBlueId(
                            Node completedType) {
                        return findCanonicalTypeBlueId(completedType)
                                .orElseThrow(() -> new IllegalStateException(
                                        "No active test evidence"));
                    }
                };
        NodeResolver resolvingMetadata = new NodeResolver() {
            @Override
            public Node resolve(Node node, ResolutionLimits limits) {
                throw new AssertionError(
                        "Exact type classification must retain its evidence");
            }

            @Override
            public TypeEvidenceResolution resolveTypeEvidence(
                    Node node,
                    ResolutionLimits limits) {
                detachedProofs[0]++;
                observed[0] = node.getType() != null
                        && FrozenNode.fromResolvedNode(fixture.authoredT)
                        .sameResolvedStructure(
                                FrozenNode.fromResolvedNode(node.getType()));
                Node resolved = node.clone()
                        .type(fixture.completedT.clone());
                observed[1] = true;
                return new TypeEvidenceResolution(
                        FrozenNode.fromResolvedNode(resolved),
                        fixture.identities);
            }

        };

        // when
        new ListProcessor().process(
                target,
                source,
                fixture.provider,
                resolvingMetadata,
                activeIdentities);

        // then
        assertTrue(observed[0]);
        assertTrue(observed[1]);
        assertEquals(1, detachedProofs[0]);
    }

    @Test
    void shouldNotWidenExactChildTypeRequirementToCompletedParent() {
        // given
        TypeFixture fixture = TypeFixture.create();
        Node target = new Node().itemType(fixture.completedT.clone());
        Node source = new Node().items(Collections.singletonList(
                new Node().type(new Node().blueId(fixture.pBlueId))));

        // when
        Executable validation = () -> new ListProcessor().process(
                target,
                source,
                fixture.provider,
                fixture.resolver(),
                fixture.identities);

        // then
        assertThrows(IllegalArgumentException.class, validation);
    }

    @Test
    void shouldResolveBothSubtypeRepresentationsInOneRuntimeEvidenceScope() {
        // given
        TypeFixture fixture = TypeFixture.create();
        boolean[] results = new boolean[3];

        // when
        try (BlueLanguageRuntime runtime = BlueLanguageRuntime.create(
                fixture.provider,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap())) {
            results[0] = runtime.resolution().isSubtype(
                    fixture.authoredT,
                    new Node().blueId(fixture.tBlueId));
            results[1] = runtime.resolution().isSubtype(
                    new Node().blueId(fixture.tBlueId),
                    fixture.authoredT);
            results[2] = runtime.resolution().isSubtype(
                    fixture.authoredT,
                    new Node().blueId(fixture.pBlueId));
        }

        // then
        assertTrue(results[0]);
        assertTrue(results[1]);
        assertTrue(results[2]);
    }

    private static Node typedScalar(String value, Node type) {
        return new Node().value(value).type(type.clone());
    }

    private static Node enumNode(Node value) {
        return new Node().schema(new Schema().enumValues(Collections.singletonList(value)));
    }

    private static Map<String, Node> properties(String name, String value) {
        Map<String, Node> properties = new LinkedHashMap<>();
        properties.put(name, new Node().value(value));
        return properties;
    }

    private static final class TypeFixture {
        private final String pBlueId;
        private final String tBlueId;
        private final Node authoredT;
        private final Node completedT;
        private final NodeProvider provider;
        private final CanonicalTypeIdentityLookup identities;

        private TypeFixture(String pBlueId,
                            String tBlueId,
                            Node authoredT,
                            Node completedT,
                            NodeProvider provider,
                            CanonicalTypeIdentityLookup identities) {
            this.pBlueId = pBlueId;
            this.tBlueId = tBlueId;
            this.authoredT = authoredT;
            this.completedT = completedT;
            this.provider = provider;
            this.identities = identities;
        }

        private static TypeFixture create() {
            Node authoredP = new Node()
                    .name("P")
                    .properties(properties("baseMarker", "base"));
            String pBlueId = DirectBlueIdCalculator.calculateBlueId(authoredP);

            Node authoredT = new Node()
                    .name("T")
                    .type(new Node().blueId(pBlueId))
                    .properties(properties("derivedMarker", "derived"));
            String tBlueId = DirectBlueIdCalculator.calculateBlueId(authoredT);

            Node completedP = authoredP.clone();
            Map<String, Node> completedProperties = new LinkedHashMap<>();
            completedProperties.put("baseMarker", new Node().value("base"));
            completedProperties.put("derivedMarker", new Node().value("derived"));
            Node completedT = new Node()
                    .name("T")
                    .type(completedP.clone())
                    .properties(completedProperties);

            NodeProvider provider = blueId -> {
                if (pBlueId.equals(blueId)) {
                    return Collections.singletonList(authoredP.clone());
                }
                if (tBlueId.equals(blueId)) {
                    return Collections.singletonList(authoredT.clone());
                }
                return Collections.emptyList();
            };
            CanonicalTypeIdentityLookup identities =
                    new CanonicalTypeIdentityLookup() {
                        @Override
                        public boolean hasCompleteCoverage() {
                            return true;
                        }

                        @Override
                        public Optional<CanonicalTypeIdentityEvidence>
                        findCanonicalTypeIdentityEvidence(
                                Node completedType) {
                            return Optional.of(CanonicalTypeIdentityEvidence
                                    .identityOnly(
                                            requireCanonicalTypeBlueId(
                                                    completedType)));
                        }

                        @Override
                        public String requireCanonicalTypeBlueId(
                                Node completedType) {
                            if ("T".equals(completedType.getName())
                                    && completedType.getProperties() != null
                                    && completedType.getProperties()
                                    .containsKey("derivedMarker")) {
                                return tBlueId;
                            }
                            if ("P".equals(completedType.getName())
                                    && completedType.getProperties() != null
                                    && completedType.getProperties()
                                    .containsKey("baseMarker")) {
                                return pBlueId;
                            }
                            throw new IllegalStateException(
                                    "No test identity for completed type");
                        }
                    };
            return new TypeFixture(
                    pBlueId,
                    tBlueId,
                    authoredT,
                    completedT,
                    provider,
                    identities);
        }

        private NodeResolver resolver() {
            return new NodeResolver() {
                @Override
                public Node resolve(Node node, ResolutionLimits limits) {
                    return node;
                }

                @Override
                public TypeEvidenceResolution resolveTypeEvidence(
                        Node node,
                        ResolutionLimits limits) {
                    throw new AssertionError(
                            "fixture resolver must not request type evidence");
                }

            };
        }
    }
}
