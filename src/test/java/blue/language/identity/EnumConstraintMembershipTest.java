package blue.language.identity;

import blue.language.merge.Merger;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.resolve.ResolutionLimits;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

class EnumConstraintMembershipTest {

    @Test
    void shouldSatisfyBareAndExplicitTextEntriesWithoutLosingCustomIdentity() {
        // Given a completed custom Text scalar and its exact identity.
        Fixture fixture = new Fixture();
        TypeEvidenceResolution proof = fixture.complete(fixture.gender, "female");
        Node candidate = proof.resolvedRoot().toNode();
        String before = ScalarNodeIdentity.resolvedBlueId(
                candidate, proof.canonicalTypeIdentities());

        // When enum membership is evaluated, then only the allowed payload matches.
        assertTrue(matches(candidate, scalar("female"), proof));
        assertTrue(matches(candidate, typed(TEXT_TYPE_BLUE_ID, "female"), proof));
        assertFalse(matches(candidate, scalar("male"), proof));
        assertFalse(matches(candidate, scalar(BigInteger.ONE), proof));
        assertEquals(before, ScalarNodeIdentity.resolvedBlueId(
                candidate, proof.canonicalTypeIdentities()));
        assertNotEquals(ScalarNodeIdentity.blueId(scalar("female")), before);

        candidate.schema(new Schema().enumValues(Collections.singletonList(scalar("female"))));
        assertDoesNotThrow(() -> new SchemaVerifier().validateCompleted(
                candidate, true, "/", proof.canonicalTypeIdentities()));
    }

    @Test
    void shouldAcceptSubtypeForCustomEntryButRejectParentAndUnrelatedType() {
        // Given one explicit custom domain and completed subtype/sibling values.
        Fixture fixture = new Fixture();
        Node entry = typed(fixture.genderId, "female");
        TypeEvidenceResolution narrow = fixture.complete(fixture.narrow, "female");
        TypeEvidenceResolution unrelated = fixture.complete(fixture.unrelated, "female");

        // When testing membership, then the subtype restriction remains effective.
        assertTrue(matches(narrow.resolvedRoot().toNode(), entry, narrow));
        assertFalse(matches(unrelated.resolvedRoot().toNode(), entry, unrelated));
        assertFalse(EnumConstraintMembership.matches(scalar("female"), entry,
                CanonicalTypeIdentityLookup.incomplete()));
    }

    @Test
    void shouldNotWidenExactValueReferenceToSubtypeValues() {
        // Given an exact value reference and a value with a narrower declared type.
        Fixture fixture = new Fixture();
        TypeEvidenceResolution narrow = fixture.complete(fixture.narrow, "female");
        Node reference = ref(ScalarNodeIdentity.blueId(typed(fixture.genderId, "female")));

        // When testing the reference, then exact scalar identity is required.
        assertTrue(EnumConstraintMembership.matches(typed(fixture.genderId, "female"),
                reference, CanonicalTypeIdentityLookup.incomplete()));
        assertFalse(matches(narrow.resolvedRoot().toNode(), reference, narrow));
    }

    @Test
    void shouldPreserveLargeIntegersAndSeparateIntegerDoubleAndQuotedText() {
        // Given exact integers beyond binary64 precision and distinct primitive kinds.
        BigInteger huge = new BigInteger("900719925474099312345678901234567890");
        CanonicalTypeIdentityLookup none = CanonicalTypeIdentityLookup.incomplete();

        // When comparing enum payloads, then primitive kinds and all integer digits survive.
        assertTrue(EnumConstraintMembership.matches(scalar(huge), scalar(huge), none));
        assertFalse(EnumConstraintMembership.matches(scalar(huge), scalar(huge.add(BigInteger.ONE)), none));
        assertFalse(EnumConstraintMembership.matches(scalar(BigInteger.ONE), scalar(BigDecimal.ONE), none));
        assertFalse(EnumConstraintMembership.matches(scalar("1"), scalar(BigInteger.ONE), none));
        assertTrue(EnumConstraintMembership.matches(scalar(new BigDecimal("1.00")),
                scalar(BigDecimal.ONE), none));
    }

    @Test
    void shouldNotInferMissingCustomAncestryFromPayloadOrName() {
        // Given unavailable custom ancestry and a name resembling a core type.
        Fixture fixture = new Fixture();
        // When evidence is absent, then no primitive membership may be inferred.
        assertThrows(IllegalStateException.class, () -> EnumConstraintMembership.matches(
                typed(fixture.genderId, "female"), scalar("female"),
                CanonicalTypeIdentityLookup.incomplete()));
        assertThrows(IllegalStateException.class, () -> EnumConstraintMembership.matches(
                scalar("female").type(new Node().name("Text").description("Custom type")),
                scalar("female"), CanonicalTypeIdentityLookup.incomplete()));

        Node misleading = new Node().name("Text").type(ref(INTEGER_TYPE_BLUE_ID));
        TypeEvidenceResolution proof = fixture.complete(misleading, BigInteger.ONE);
        assertTrue(matches(proof.resolvedRoot().toNode(), scalar(BigInteger.ONE), proof));
        assertFalse(matches(proof.resolvedRoot().toNode(), scalar("1"), proof));
    }

    @Test
    void shouldRetainNarrowerCustomDomainWhenIntersectingInEitherOrder() {
        // Given broad and custom domains with the same payload.
        Fixture fixture = new Fixture();
        Node bare = scalar("female");
        Node custom = typed(fixture.genderId, "female");

        // When intersecting either order, then the narrower declared type survives.
        assertEquals(fixture.genderId, fixture.intersect(bare, custom).get(0).getType().getBlueId());
        assertEquals(fixture.genderId, fixture.intersect(custom, bare).get(0).getType().getBlueId());
        Node narrower = typed(fixture.narrowId, "female");
        assertEquals(fixture.narrowId, fixture.intersect(custom, narrower).get(0).getType().getBlueId());
        assertEquals(fixture.narrowId, fixture.intersect(narrower, custom).get(0).getType().getBlueId());
    }

    @Test
    void shouldRejectUnrelatedTypesPayloadsAndNumericKindsInIntersection() {
        // Given restrictions with no common subtype/payload member.
        Fixture fixture = new Fixture();
        // When intersected, then each restriction pair is empty.
        assertTrue(fixture.intersect(typed(fixture.genderId, "female"),
                typed(fixture.unrelatedId, "female")).isEmpty());
        assertTrue(fixture.intersect(scalar("female"), scalar("male")).isEmpty());
        assertTrue(fixture.intersect(scalar(BigInteger.ONE), scalar(BigDecimal.ONE)).isEmpty());
        assertTrue(fixture.intersect(scalar("1"), scalar(BigInteger.ONE)).isEmpty());
    }

    @Test
    void shouldKeepCustomRestrictionsAndNormalizeOrderAndExactDuplicates() {
        // Given redundant membership domains with distinct declared identities.
        Fixture fixture = new Fixture();
        Node bare = scalar("female");
        Node custom = typed(fixture.genderId, "female");
        // When source enum normalization sorts and removes exact duplicates.
        List<Node> first = SchemaEnumCanonicalizer.canonicalize(Arrays.asList(custom, bare, custom));
        List<Node> second = SchemaEnumCanonicalizer.canonicalize(Arrays.asList(bare, custom));

        // Then explicit custom restrictions retain their own source identity.
        assertEquals(2, first.size());
        assertEquals(SchemaEnumCanonicalizer.canonicalKey(first.get(0)),
                SchemaEnumCanonicalizer.canonicalKey(second.get(0)));
        assertEquals(SchemaEnumCanonicalizer.canonicalKey(first.get(1)),
                SchemaEnumCanonicalizer.canonicalKey(second.get(1)));
        assertNotEquals(SchemaEnumCanonicalizer.canonicalKey(bare),
                SchemaEnumCanonicalizer.canonicalKey(custom));
    }

    @Test
    void shouldKeepSingletonRestrictionWhenIntersectingExactReferences() {
        // Given verified exact custom scalar content.
        Fixture fixture = new Fixture();
        Node genderValue = typed(fixture.genderId, "female");
        fixture.provider.addSingleNodes(genderValue);
        Node exact = ref(ScalarNodeIdentity.blueId(genderValue));

        // When intersected with a domain, then its exact identity remains the restriction.
        assertEquals(exact.getBlueId(), fixture.intersect(exact, scalar("female")).get(0).getBlueId());
        assertEquals(exact.getBlueId(), fixture.intersect(scalar("female"), exact).get(0).getBlueId());
        assertTrue(fixture.intersect(exact, scalar("male")).isEmpty());
        Node coreExact = ref(ScalarNodeIdentity.blueId(scalar("female")));
        assertEquals(coreExact.getBlueId(), fixture.intersect(coreExact, scalar("female")).get(0).getBlueId());
    }

    @Test
    void shouldRequireReferenceContentForIntersectionAndAcceptLaterProvision() {
        // Given an exact scalar reference whose provider content is initially missing.
        Fixture fixture = new Fixture();
        Node value = typed(fixture.genderId, "female");
        Node unavailable = ref(ScalarNodeIdentity.blueId(value));
        // When evidence is absent, then intersection cannot be certified.
        assertThrows(RuntimeException.class, () -> fixture.intersect(unavailable, scalar("female")));
        // When the same provider receives it, then a later operation succeeds.
        fixture.provider.addSingleNodes(value);
        assertEquals(unavailable.getBlueId(), fixture.intersect(unavailable, scalar("female"))
                .get(0).getBlueId());
    }

    @Test
    void shouldUseConstrainedDefinitionAndCustomNarrowingWithoutSampleValues() {
        // Given constrained Gender and a subtype with an explicit Gender enum entry.
        Fixture fixture = new Fixture();
        Node gender = fixture.gender.clone().schema(new Schema().enumValues(
                Arrays.asList(scalar("female"), scalar("male"))));
        String genderId = DirectBlueIdCalculator.calculateBlueId(gender);
        fixture.provider.addSingleNodes(gender);
        Node female = new Node().name("Female").type(ref(genderId))
                .schema(new Schema().enumValues(Collections.singletonList(typed(genderId, "female"))));
        String femaleId = DirectBlueIdCalculator.calculateBlueId(female);
        fixture.provider.addSingleNodes(female);

        // When preparing the subtype as a declaration.
        TypeEvidenceResolution declaration = fixture.merger.resolveTypeDeclarationEvidence(
                female.clone(), ResolutionLimits.NO_LIMITS);
        // Then no sample is inserted, and actual instances enforce retained obligations.
        assertNull(declaration.resolvedRoot().getValue());
        assertEquals(genderId, declaration.resolvedRoot().toNode().getSchema()
                .getEnum().get(0).getType().getBlueId());
        assertDoesNotThrow(() -> fixture.merger.resolve(typed(femaleId, "female")));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.merger.resolve(typed(femaleId, "male")));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.merger.resolve(typed(femaleId, BigInteger.ONE)));
    }

    @Test
    void shouldRejectInvalidFixedCustomEnumValue() {
        // Given an enum entry contradicting the fixed value of its custom type.
        Fixture fixture = new Fixture();
        Node fixed = fixture.gender.clone().name("FixedFemale").value("female");
        String fixedId = DirectBlueIdCalculator.calculateBlueId(fixed);
        fixture.provider.addSingleNodes(fixed);
        Node invalidDefinition = new Node().type(ref(TEXT_TYPE_BLUE_ID))
                .schema(new Schema().enumValues(Collections.singletonList(typed(fixedId, "male"))));

        // When preparing the declaration, then the fixed-value contradiction fails.
        assertThrows(IllegalArgumentException.class,
                () -> fixture.merger.resolveTypeDeclarationEvidence(
                        invalidDefinition, ResolutionLimits.NO_LIMITS));
    }

    @Test
    void shouldRejectAuthoredEnumMetadataBeforeTypeCompletion() {
        // Given authored enum metadata outside the scalar-entry grammar.
        Fixture fixture = new Fixture();
        Node malformed = new Node().type(ref(TEXT_TYPE_BLUE_ID)).schema(new Schema()
                .enumValues(Collections.singletonList(typed(fixture.genderId, "female").name("label"))));
        // When parsing or resolving, then the invalid authored shape is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> fixture.merger.resolveTypeDeclarationEvidence(malformed, ResolutionLimits.NO_LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> YAML_MAPPER.readValue("type: Text\nschema:\n  enum:\n    - name: label\n      value: female\n", Node.class));
    }

    @Test
    void shouldParseCustomTypedAndExactReferenceEnumEntries() {
        // Given actual custom type and exact scalar identities.
        Fixture fixture = new Fixture();
        String scalarId = ScalarNodeIdentity.blueId(typed(fixture.genderId, "female"));
        // When their supported enum entry forms are parsed.
        Node parsed = YAML_MAPPER.readValue("type: Text\nschema:\n  enum:\n"
                + "    - type:\n        blueId: " + fixture.genderId + "\n      value: female\n"
                + "    - blueId: " + scalarId + "\n", Node.class);
        // Then both references are retained exactly.
        assertEquals(fixture.genderId, parsed.getSchema().getEnum().get(0).getType().getBlueId());
        assertEquals(scalarId, parsed.getSchema().getEnum().get(1).getBlueId());
    }

    @Test
    void shouldRejectProvablyDisjointPrimitiveEnumWithoutAnySample() {
        // Given declarations whose enum entries all have a different known primitive kind.
        Fixture fixture = new Fixture();
        Node textWithIntegerEnum = new Node().type(ref(TEXT_TYPE_BLUE_ID))
                .schema(new Schema().enumValues(Collections.singletonList(scalar(BigInteger.ONE))));
        Node integerWithDoubleEnum = new Node().type(ref(INTEGER_TYPE_BLUE_ID))
                .schema(new Schema().enumValues(Collections.singletonList(scalar(BigDecimal.ONE))));
        // When preparing definitions, then these cheap contradictions fail without a payload.
        assertThrows(IllegalArgumentException.class, () -> fixture.merger.resolveTypeDeclarationEvidence(
                textWithIntegerEnum, ResolutionLimits.NO_LIMITS));
        assertThrows(IllegalArgumentException.class, () -> fixture.merger.resolveTypeDeclarationEvidence(
                integerWithDoubleEnum, ResolutionLimits.NO_LIMITS));

        Node mixed = new Node().type(ref(TEXT_TYPE_BLUE_ID)).schema(new Schema().enumValues(
                Arrays.asList(scalar(BigInteger.ONE), scalar("one"))));
        assertDoesNotThrow(() -> fixture.merger.resolveTypeDeclarationEvidence(mixed, ResolutionLimits.NO_LIMITS));
    }

    @Test
    void shouldDeferOpaqueEnumReferencesDuringDeclarationDomainConsistency() {
        // Given an opaque enum reference with no provider content.
        Fixture fixture = new Fixture();
        Node scalarReference = ref(ScalarNodeIdentity.blueId(typed(fixture.genderId, "female")));
        Node definition = new Node().type(ref(TEXT_TYPE_BLUE_ID))
                .schema(new Schema().enumValues(Arrays.asList(scalar(BigInteger.ONE), scalarReference)));

        // When checking known-domain consistency, then unknown evidence is deferred.
        assertDoesNotThrow(() -> fixture.merger.resolveTypeDeclarationEvidence(definition, ResolutionLimits.NO_LIMITS));
    }

    @Test
    void shouldRespectPublicReferenceExpansionBudgetForEnumIntersectionEvidence() {
        // Given an intersection requiring both a type and an exact scalar reference.
        Node gender = new Node().name("Gender").type(ref(TEXT_TYPE_BLUE_ID))
                .schema(new Schema().enumValues(Arrays.asList(scalar("female"), scalar("male"))));
        String genderId = DirectBlueIdCalculator.calculateBlueId(gender);
        Node female = typed(genderId, "female");
        String femaleId = ScalarNodeIdentity.blueId(female);
        BasicNodeProvider provider = new BasicNodeProvider(gender, female);
        Node instance = female.clone().schema(new Schema().enumValues(Collections.singletonList(ref(femaleId))));
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // When the public operation has insufficient or sufficient expansion budget.
            BlueOperationLimits one = BlueOperationLimits.demandedPath("").withMaxReferenceExpansions(1);
            // Then detached enum evidence work shares that same budget.
            assertEquals(BlueOperationOutcome.INCOMPLETE,
                    language.resolution().resolveLimited(instance, one).outcome());
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    language.resolution().resolveLimited(instance, one.withMaxReferenceExpansions(2)).outcome());
        }
    }

    @Test
    void shouldInterpretCustomIntegerCanonicalStringWithoutChangingIdentity() {
        // Given a custom Integer with its canonical large-integer string spelling.
        Fixture fixture = new Fixture();
        BigInteger huge = new BigInteger("900719925474099312345678901234567890");
        Node integerType = new Node().name("LargeInteger").type(ref(INTEGER_TYPE_BLUE_ID));
        String integerId = DirectBlueIdCalculator.calculateBlueId(integerType);
        fixture.provider.addSingleNodes(integerType);
        TypeEvidenceResolution proof = fixture.complete(integerType, huge.toString());
        Node candidate = proof.resolvedRoot().toNode();
        String exactId = ScalarNodeIdentity.resolvedBlueId(candidate, proof.canonicalTypeIdentities());

        // When membership uses the proven primitive domain, then exact stored content stays intact.
        assertTrue(matches(candidate, scalar(huge), proof));
        assertFalse(matches(candidate, scalar(huge.toString()), proof));
        assertEquals(huge.toString(), candidate.getRawValue());
        assertEquals(exactId, ScalarNodeIdentity.resolvedBlueId(candidate, proof.canonicalTypeIdentities()));
        assertEquals(integerId, fixture.intersect(typed(integerId, huge.toString()), scalar(huge))
                .get(0).getType().getBlueId());
    }

    @Test
    void shouldNotTreatFullDocumentReferenceWithMetadataAsScalarIdentitySingleton() {
        // Given a full document reference whose identity includes a label.
        Fixture fixture = new Fixture();
        Node labeled = scalar("female").name("Label");
        fixture.provider.addSingleNodes(labeled);
        Node documentReference = ref(DirectBlueIdCalculator.calculateBlueId(labeled));

        // When used as an exact scalar enum restriction, then it has no matching scalar identity.
        assertFalse(EnumConstraintMembership.matches(labeled, documentReference,
                CanonicalTypeIdentityLookup.incomplete()));
        assertTrue(fixture.intersect(documentReference, scalar("female")).isEmpty());
    }

    @Test
    void shouldMakeIntersectionCommutativeAndEquivalentToConjunctionForSampledDomains() {
        // Given primitive, custom, subtype and sibling restrictions and completed candidates.
        Fixture fixture = new Fixture();
        List<Node> entries = Arrays.asList(scalar("female"), scalar("male"),
                typed(fixture.genderId, "female"), typed(fixture.narrowId, "female"),
                typed(fixture.unrelatedId, "female"));
        List<TypeEvidenceResolution> candidates = Arrays.asList(
                fixture.complete(fixture.gender, "female"),
                fixture.complete(fixture.narrow, "female"),
                fixture.complete(fixture.unrelated, "female"),
                fixture.complete(new Node().blueId(TEXT_TYPE_BLUE_ID), "female"),
                fixture.complete(new Node().blueId(TEXT_TYPE_BLUE_ID), "male"));

        // When every pair is intersected, then order and conjunction agree for every candidate.
        for (Node left : entries) {
            for (Node right : entries) {
                List<Node> intersection = fixture.intersect(left, right);
                List<Node> reversed = fixture.intersect(right, left);
                assertEquals(intersection.size(), reversed.size());
                if (!intersection.isEmpty()) {
                    assertEquals(SchemaEnumCanonicalizer.canonicalKey(intersection.get(0)),
                            SchemaEnumCanonicalizer.canonicalKey(reversed.get(0)));
                }
                for (TypeEvidenceResolution proof : candidates) {
                    Node candidate = proof.resolvedRoot().toNode();
                    boolean conjunction = matches(candidate, left, proof) && matches(candidate, right, proof);
                    boolean intersected = intersection.stream().anyMatch(entry -> matches(candidate, entry, proof));
                    assertEquals(conjunction, intersected);
                }
            }
        }
    }

    private static boolean matches(Node candidate, Node entry, TypeEvidenceResolution proof) {
        return EnumConstraintMembership.matches(candidate, entry, proof.canonicalTypeIdentities());
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }

    private static Node typed(String type, Object value) {
        return scalar(value).type(ref(type));
    }

    private static Node ref(String id) {
        return new Node().blueId(id);
    }

    private static final class Fixture {
        private final Node gender = new Node().name("Gender").type(ref(TEXT_TYPE_BLUE_ID));
        private final String genderId = DirectBlueIdCalculator.calculateBlueId(gender);
        private final Node narrow = new Node().name("NarrowGender").type(ref(genderId));
        private final String narrowId = DirectBlueIdCalculator.calculateBlueId(narrow);
        private final Node unrelated = new Node().name("UnrelatedText").type(ref(TEXT_TYPE_BLUE_ID));
        private final String unrelatedId = DirectBlueIdCalculator.calculateBlueId(unrelated);
        private final BasicNodeProvider provider = new BasicNodeProvider(gender, narrow, unrelated);
        private final Merger merger = new Merger(new SequentialMergingProcessor(Arrays.asList(
                new ValuePropagator(), new TypeAssigner(), new ListProcessor(),
                new DictionaryProcessor(), new SchemaPropagator(),
                new SchemaVerifier(), new BasicTypesVerifier())), provider);

        private TypeEvidenceResolution complete(Node type, Object value) {
            return merger.resolveTypeEvidence(new Node().type(type.clone()).value(value),
                    ResolutionLimits.NO_LIMITS);
        }

        private List<Node> intersect(Node left, Node right) {
            Node target = new Node().schema(new Schema().enumValues(Collections.singletonList(left)));
            Node source = new Node().schema(new Schema().enumValues(Collections.singletonList(right)));
            new SchemaPropagator().process(target, source, provider, merger,
                    CanonicalTypeIdentityLookup.incomplete());
            return target.getSchema().getEnum();
        }
    }
}
