package blue.language.matching;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrozenTypeIdentityParityTest {

    @Test
    void labelOnlyTypeNormalizationUsesExplicitSemanticEmptyObjects() {
        FrozenNode labelOnly = FrozenNode.fromResolvedNode(
                new Node()
                        .name("Label-only type")
                        .description("Compatibility-neutral description"));
        FrozenNode explicitEmpty = FrozenNode.fromResolvedNode(
                Nodes.emptyObject());
        FrozenNode nestedLabelOnly = FrozenNode.fromResolvedNode(
                new Node().type(
                        new Node().name("Label-only parent")));
        FrozenNode nestedExplicitEmpty = FrozenNode.fromResolvedNode(
                new Node().type(Nodes.emptyObject()));

        assertEquals(
                fingerprint(explicitEmpty),
                fingerprint(labelOnly));
        assertEquals(
                fingerprint(nestedExplicitEmpty),
                fingerprint(nestedLabelOnly));
    }

    @Test
    void exactInlineAndReferenceFormsConvergeWithResolverEvidenceColdAndWarm() {
        Node definition = new Node()
                .name("Exact inline type")
                .properties("kind", new Node().value("same"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(definition);
        FrozenNode completedInline = FrozenNode.fromResolvedNode(definition);
        FrozenNode reference = frozenReference(blueId);
        AtomicInteger materializations = new AtomicInteger();
        FrozenTypeMatcher matcher = FrozenTypeMatcher.withVerifiedTypeEvidence(
                requested -> {
                    materializations.incrementAndGet();
                    assertEquals(blueId, requested.getReferenceBlueId());
                    return materialization(definition, blueId);
                },
                lookup(completedInline, blueId));

        assertTrue(matcher.isSubtypeOrSame(
                completedInline, reference, 0L));
        assertTrue(matcher.isSubtypeOrSame(
                reference, completedInline, 0L));
        assertTrue(matcher.isSubtypeOrSame(
                reference, completedInline, 0L));
        assertEquals(1, materializations.get());
    }

    @Test
    void exactInlineComparisonFailsClosedWithoutCanonicalEvidence() {
        Node definition = new Node()
                .name("Unproven inline type")
                .properties("kind", new Node().value("same"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(definition);
        FrozenTypeMatcher matcher =
                FrozenTypeMatcher.withVerifiedReferenceMaterializer(
                        requested -> materialization(definition, blueId));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> matcher.isSubtypeOrSame(
                        FrozenNode.fromResolvedNode(definition),
                        frozenReference(blueId),
                        0L));

        assertTrue(failure.getMessage().contains(
                "Canonical identity evidence is unavailable"));
    }

    @Test
    void verifiedMaterializerCannotReturnContentWithoutRootIdentityEvidence() {
        Node definition = new Node().name("Evidence-free materialization");
        String blueId = DirectBlueIdCalculator.calculateBlueId(definition);
        FrozenTypeMatcher matcher =
                FrozenTypeMatcher.withVerifiedReferenceMaterializer(
                        requested -> new TypeEvidenceResolution(
                                FrozenNode.fromResolvedNode(
                                        definition.clone().blueId(blueId)),
                                CanonicalTypeIdentityLookup.incomplete()));

        assertThrows(
                IllegalStateException.class,
                () -> matcher.isSubtypeOrSame(
                        frozenReference(blueId),
                        frozenReference(blueId),
                        0L));
    }

    @Test
    void forgedBlueIdOnMaterializedTypeCannotReplaceResolverEvidence() {
        Node definition = new Node()
                .name("Materialized type body")
                .properties("kind", new Node().value("same"));
        String forgedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Different exact source"));
        FrozenNode forgedMaterialized = FrozenNode.fromResolvedNode(
                definition.clone().blueId(forgedBlueId));
        FrozenTypeMatcher matcher = FrozenTypeMatcher
                .withVerifiedTypeEvidence(
                        requested -> null,
                        CanonicalTypeIdentityLookup.incomplete());

        assertThrows(
                IllegalStateException.class,
                () -> matcher.isSubtypeOrSame(
                        forgedMaterialized,
                        frozenReference(forgedBlueId),
                        0L));
    }

    @Test
    void semanticCompatibilityMaterializesNestedReferencesWithoutPublishingBodyHashes() {
        Node parent = new Node()
                .name("Semantic parent")
                .properties("family", new Node().value("shared"));
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(parent);
        Node referencedDefinition = new Node()
                .name("Semantic child")
                .type(new Node().blueId(parentBlueId));
        String childBlueId = DirectBlueIdCalculator.calculateBlueId(
                referencedDefinition);
        Node inlineDefinition = new Node()
                .name("Semantic child")
                .type(parent.clone());
        Map<String, Node> definitions = new LinkedHashMap<>();
        definitions.put(parentBlueId, parent);
        definitions.put(childBlueId, referencedDefinition);
        AtomicInteger materializations = new AtomicInteger();
        FrozenTypeMatcher matcher =
                FrozenTypeMatcher.withVerifiedReferenceMaterializer(
                        requested -> {
                            materializations.incrementAndGet();
                            Node materialized = definitions.get(
                                    requested.getReferenceBlueId());
                            return materialized == null
                                    ? null
                                    : materialization(
                                            materialized,
                                            requested.getReferenceBlueId());
                        });
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node()
                        .type(inlineDefinition)
                        .properties("family", new Node().value("shared")));
        FrozenNode target = FrozenNode.fromResolvedNode(
                new Node().type(new Node().blueId(childBlueId)));

        assertTrue(matcher.matchesType(candidate, target));
        int coldMaterializations = materializations.get();
        assertTrue(coldMaterializations >= 2);
        assertTrue(matcher.matchesType(candidate, target));
        assertEquals(coldMaterializations, materializations.get());
    }

    @Test
    void shouldKeepInvocationEvidenceIsolatedAcrossConcurrentMatches()
            throws Exception {
        Node firstParent = new Node().name("Concurrent first parent");
        Node secondParent = new Node().name("Concurrent second parent");
        String firstParentBlueId =
                DirectBlueIdCalculator.calculateBlueId(firstParent);
        String secondParentBlueId =
                DirectBlueIdCalculator.calculateBlueId(secondParent);
        Node firstChild = new Node()
                .name("Concurrent first child")
                .type(new Node().blueId(firstParentBlueId));
        Node secondChild = new Node()
                .name("Concurrent second child")
                .type(new Node().blueId(secondParentBlueId));
        String firstChildBlueId =
                DirectBlueIdCalculator.calculateBlueId(firstChild);
        String secondChildBlueId =
                DirectBlueIdCalculator.calculateBlueId(secondChild);
        CountDownLatch firstLookupEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstLookup = new CountDownLatch(1);
        FrozenTypeMatcher matcher =
                FrozenTypeMatcher.withVerifiedReferenceMaterializer(
                        requested -> {
                            String requestedBlueId =
                                    requested.getReferenceBlueId();
                            if (firstChildBlueId.equals(requestedBlueId)) {
                                return concurrentChildMaterialization(
                                        firstChild,
                                        firstChildBlueId,
                                        firstParent,
                                        firstParentBlueId,
                                        firstLookupEntered,
                                        releaseFirstLookup);
                            }
                            if (secondChildBlueId.equals(requestedBlueId)) {
                                return concurrentChildMaterialization(
                                        secondChild,
                                        secondChildBlueId,
                                        secondParent,
                                        secondParentBlueId,
                                        null,
                                        null);
                            }
                            return null;
                        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> firstMatch = executor.submit(
                    () -> matcher.isSubtypeOrSame(
                            frozenReference(firstChildBlueId),
                            frozenReference(firstParentBlueId),
                            1L));
            assertTrue(firstLookupEntered.await(5, TimeUnit.SECONDS));

            Future<Boolean> secondMatch = executor.submit(
                    () -> matcher.isSubtypeOrSame(
                            frozenReference(secondChildBlueId),
                            frozenReference(secondParentBlueId),
                            1L));
            assertTrue(secondMatch.get(5, TimeUnit.SECONDS));
            releaseFirstLookup.countDown();

            assertTrue(firstMatch.get(5, TimeUnit.SECONDS));
        } finally {
            releaseFirstLookup.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldPropagateConflictingExternalIdentityEvidence() {
        Node completed = new Node().name("Conflicting external evidence");
        CanonicalTypeIdentityLookup conflicting =
                new CanonicalTypeIdentityLookup() {
                    @Override
                    public boolean hasCompleteCoverage() {
                        return true;
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(Node candidate) {
                        throw new IllegalStateException(
                                "conflicting external identity evidence");
                    }
                };
        FrozenTypeMatcher matcher = FrozenTypeMatcher
                .withVerifiedTypeEvidence(requested -> null, conflicting);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> matcher.isSubtypeOrSame(
                        FrozenNode.fromResolvedNode(completed),
                        FrozenNode.fromResolvedNode(completed.clone()),
                        0L));

        assertEquals(
                "conflicting external identity evidence",
                failure.getMessage());
    }

    private static CanonicalTypeIdentityLookup lookup(
            FrozenNode completedType,
            String canonicalBlueId) {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node candidate) {
                FrozenNode frozen = FrozenNode.fromResolvedNode(candidate);
                if (!completedType.resolvedStructuralKey().equals(
                        frozen.resolvedStructuralKey())) {
                    return Optional.empty();
                }
                return Optional.of(CanonicalTypeIdentityEvidence
                        .identityOnly(canonicalBlueId));
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(
                    Node candidate,
                    Node authoredTypeSource) {
                Optional<CanonicalTypeIdentityEvidence> evidence =
                        findCanonicalTypeIdentityEvidence(candidate);
                if (authoredTypeSource != null
                        && authoredTypeSource.isReferenceOnly()
                        && evidence.isPresent()
                        && !canonicalBlueId.equals(
                                authoredTypeSource.getBlueId())) {
                    throw new IllegalStateException(
                            "Authored reference conflicts with explicit test "
                                    + "identity evidence");
                }
                return evidence;
            }

            @Override
            public long approximateRetainedWeightBytes() {
                return completedType.approximateRetainedWeightBytes();
            }
        };
    }

    private static TypeEvidenceResolution concurrentChildMaterialization(
            Node childDefinition,
            String childBlueId,
            Node completedParent,
            String parentBlueId,
            CountDownLatch lookupEntered,
            CountDownLatch releaseLookup) {
        FrozenNode retained = FrozenNode.fromResolvedNode(
                childDefinition.clone()
                        .blueId(childBlueId)
                        .type(completedParent.clone()));
        CanonicalTypeIdentityLookup lookup =
                new CanonicalTypeIdentityLookup() {
                    @Override
                    public boolean hasCompleteCoverage() {
                        return true;
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(Node candidate) {
                        if (childDefinition.getName().equals(
                                candidate.getName())) {
                            return Optional.of(CanonicalTypeIdentityEvidence
                                    .identityOnly(childBlueId));
                        }
                        if (!completedParent.getName().equals(
                                candidate.getName())) {
                            return Optional.empty();
                        }
                        if (lookupEntered != null) {
                            lookupEntered.countDown();
                            await(releaseLookup);
                        }
                        return Optional.of(CanonicalTypeIdentityEvidence
                                .identityOnly(parentBlueId));
                    }
                };
        return new TypeEvidenceResolution(retained, lookup);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out awaiting concurrent matcher release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted awaiting concurrent matcher release",
                    interrupted);
        }
    }

    private static TypeEvidenceResolution materialization(
            Node definition,
            String requestedBlueId) {
        FrozenNode retained = FrozenNode.fromResolvedNode(
                definition.clone().blueId(requestedBlueId));
        return new TypeEvidenceResolution(
                retained,
                lookup(retained, requestedBlueId));
    }

    private static FrozenNode frozenReference(String blueId) {
        return FrozenNode.fromNode(new Node().blueId(blueId));
    }

    private static String fingerprint(FrozenNode type) {
        return LabelNeutralTypeIdentity.calculateCompatibilityFingerprint(
                type,
                requested -> {
                    throw new AssertionError(
                            "label-only normalization must not materialize "
                                    + "references");
                });
    }
}
