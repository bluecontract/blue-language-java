package blue.language.conformance.api;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.NodeDeserializer;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.DirectNodeManifest;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePath;
import blue.language.registry.NodeProviderWrapper;
import blue.language.model.NodeWireForm;
import blue.language.utils.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fail-closed executable harness for the exact Blue Language 1.0 fixture
 * package. Every behavior fixture is executed; unsupported data is a failure.
 */
public final class BlueConformanceSuiteRunner {

    /**
     * Canonical names of operations understood by the fixture DSL.
     *
     * <p>Keeping the operation vocabulary in one owner prevents the manifest
     * allow-list and dispatcher from drifting apart.</p>
     */
    private static final class FixtureOperation {

        private static final String ASSERT_VIEW_PATH = "assertViewPath";
        private static final String CALCULATE_BLUE_ID = "calculateBlueId";
        private static final String CALCULATE_BLUE_ID_PAIR = "calculateBlueIdPair";
        private static final String CALCULATE_CIRCULAR_SET_BLUE_IDS =
                "calculateCircularSetBlueIds";
        private static final String CANONICALIZE = "canonicalize";
        private static final String CANONICALIZE_LIMITED_RESULT =
                "canonicalizeLimitedResult";
        private static final String CHANGING_REGISTRY_DESCRIPTION_CHANGES_BLUE_ID =
                "changingRegistryDescriptionChangesBlueId";
        private static final String COLLAPSE = "collapse";
        private static final String COMPARE_CONTENT_AND_DIRECT_RESOLVED_BLUE_ID =
                "compareContentAndDirectResolvedBlueId";
        private static final String COMPARE_EXPANSION_STRATEGIES =
                "compareExpansionStrategies";
        private static final String COMPARE_GRAPH_EQUIVALENT_INPUTS =
                "compareGraphEquivalentInputs";
        private static final String COMPARE_LIMITED_AND_COMPLETE_RESOLUTION =
                "compareLimitedAndCompleteResolution";
        private static final String EXPAND = "expand";
        private static final String EXPAND_CYCLIC_MEMBER = "expandCyclicMember";
        private static final String EXPAND_LIMITED = "expandLimited";
        private static final String EXPAND_THEN_COLLAPSE = "expandThenCollapse";
        private static final String EXPAND_VARIANTS = "expandVariants";
        private static final String LINT_PUBLISHABLE_DOCUMENTATION =
                "lintPublishableDocumentation";
        private static final String MATCH = "match";
        private static final String MINIMIZE_AND_RESOLVE = "minimizeAndResolve";
        private static final String PARSE_BLUE_ID_INPUT = "parseBlueIdInput";
        private static final String PARSE_SOURCE = "parseSource";
        private static final String PREPROCESS = "preprocess";
        private static final String REGISTRY_NODE_HASHES_TO_PUBLISHED_BLUE_ID =
                "registryNodeHashesToPublishedBlueId";
        private static final String RESOLVE = "resolve";
        private static final String RESOLVE_LIMITED = "resolveLimited";
        private static final String RESOLVE_VARIANTS = "resolveVariants";
        private static final String RETRIEVE_DIRECT_LIST = "retrieveDirectList";
        private static final String SEMANTIC_EXISTS = "semanticExists";
        private static final String SPLIT_EXACT_GRAPH_FRAGMENTS =
                "splitExactGraphFragments";
        private static final String SUITE_ASSERTION = "suiteAssertion";
        private static final String VALIDATE = "validate";
        private static final String VALIDATE_VARIANTS = "validateVariants";
        private static final String VERIFY_DIRECT_LIST = "verifyDirectList";
        private static final String VERIFY_DIRECT_NODE = "verifyDirectNode";
        private static final String VERIFY_OPAQUE_CYCLIC_FRAGMENT =
                "verifyOpaqueCyclicFragment";

        private FixtureOperation() {
        }
    }

    /**
     * Shared field names used by fixture envelopes and their nested DSL
     * structures.
     *
     * <p>Fields used only once to declare the top-level schema remain inline
     * in {@link #ALLOWED_FIXTURE_FIELDS}; every field shared with executable
     * fixture handling is named here.</p>
     */
    private static final class FixtureField {

        private static final String ALSO_DIFFERENT_FROM = "alsoDifferentFrom";
        private static final String ALSO_EQUIVALENT_TO = "alsoEquivalentTo";
        private static final String ASSERTIONS = "assertions";
        private static final String BASE = "base";
        private static final String CANDIDATE = "candidate";
        private static final String CATEGORY = "category";
        private static final String CUTS = "cuts";
        private static final String DIRECT_ELEMENT_IDENTITIES_ONLY =
                "directElementIdentitiesOnly";
        private static final String DIRECT_NODE = "directNode";
        private static final String DOCUMENT = "document";
        private static final String DOCUMENTS = "documents";
        private static final String EXPECT_BLUE_ID_CHANGED = "expectBlueIdChanged";
        private static final String EXPECT_ERROR = "expectError";
        private static final String EXPECTED = "expected";
        private static final String EXPECTED_ABSENT = "expectedAbsent";
        private static final String EXPECTED_BLUE_IDS = "expectedBlueIds";
        private static final String EXPECTED_CANONICAL_CONTAINS_CONTROLS =
                "expectedCanonicalContainsControls";
        private static final String EXPECTED_CANONICAL_ITEMS =
                "expectedCanonicalItems";
        private static final String EXPECTED_CANONICAL_OVERLAY =
                "expectedCanonicalOverlay";
        private static final String EXPECTED_CANONICALIZATION_ERROR_CATEGORY =
                "expectedCanonicalizationErrorCategory";
        private static final String EXPECTED_COLLAPSED = "expectedCollapsed";
        private static final String EXPECTED_COLLAPSED_ROOT =
                "expectedCollapsedRoot";
        private static final String
                EXPECTED_CONTENT_BLUE_ID_EQUALS_CANONICAL_IDENTITY_INPUT =
                "expectedContentBlueIdEqualsCanonicalIdentityInput";
        private static final String EXPECTED_DEFENSIVE_COPIES =
                "expectedDefensiveCopies";
        private static final String EXPECTED_DESCENDANT_REQUESTS =
                "expectedDescendantRequests";
        private static final String EXPECTED_DIRECT_RESOLVED_BLUE_ID_MAY_DIFFER =
                "expectedDirectResolvedBlueIdMayDiffer";
        private static final String
                EXPECTED_DIRECT_RESULT_STILL_CONTAINS_ALL_ORDERED_ELEMENT_IDENTITIES =
                "expectedDirectResultStillContainsAllOrderedElementIdentities";
        private static final String EXPECTED_EFFECTIVE_TYPE =
                "expectedEffectiveType";
        private static final String EXPECTED_EFFECTIVE_TYPES =
                "expectedEffectiveTypes";
        private static final String EXPECTED_ELEMENT_BODY_REQUESTS =
                "expectedElementBodyRequests";
        private static final String EXPECTED_EQUAL = "expectedEqual";
        private static final String EXPECTED_ERROR_CATEGORY =
                "expectedErrorCategory";
        private static final String EXPECTED_EXPANDED = "expectedExpanded";
        private static final String EXPECTED_EXPANDED_DESCENDANT_REQUESTS =
                "expectedExpandedDescendantRequests";
        private static final String EXPECTED_FIELD_COUNT = "expectedFieldCount";
        private static final String EXPECTED_FRAGMENT_BLUE_IDS =
                "expectedFragmentBlueIds";
        private static final String EXPECTED_FRAGMENT_COUNT =
                "expectedFragmentCount";
        private static final String EXPECTED_IDEMPOTENT =
                "expectedIdempotent";
        private static final String EXPECTED_IDENTITY_EQUAL =
                "expectedIdentityEqual";
        private static final String EXPECTED_LOCAL_PROVIDER_OUTCOME =
                "expectedLocalProviderOutcome";
        private static final String EXPECTED_MATCH = "expectedMatch";
        private static final String EXPECTED_MERGE_POLICY =
                "expectedMergePolicy";
        private static final String EXPECTED_MINIMIZED_MAY_CONTAIN =
                "expectedMinimizedMayContain";
        private static final String EXPECTED_NODE_BLUE_ID = "expectedNodeBlueId";
        private static final String EXPECTED_NOT_REQUESTED_BLUE_IDS =
                "expectedNotRequestedBlueIds";
        private static final String EXPECTED_OPAQUE_EDGES =
                "expectedOpaqueEdges";
        private static final String EXPECTED_OUTCOME = "expectedOutcome";
        private static final String EXPECTED_OUTSTANDING_BLUE_IDS =
                "expectedOutstandingBlueIds";
        private static final String EXPECTED_PARSED = "expectedParsed";
        private static final String EXPECTED_PREPROCESSED =
                "expectedPreprocessed";
        private static final String EXPECTED_PROVIDER_OUTCOME =
                "expectedProviderOutcome";
        private static final String EXPECTED_PUBLISHED_BLUE_ID =
                "expectedPublishedBlueId";
        private static final String EXPECTED_REASON = "expectedReason";
        private static final String EXPECTED_REFERENCE_PATHS =
                "expectedReferencePaths";
        private static final String EXPECTED_REQUESTED_BLUE_IDS =
                "expectedRequestedBlueIds";
        private static final String EXPECTED_RESOLUTION_OUTCOME =
                "expectedResolutionOutcome";
        private static final String EXPECTED_RESOLVED = "expectedResolved";
        private static final String EXPECTED_RESOLVED_ITEMS =
                "expectedResolvedItems";
        private static final String EXPECTED_ROUND_TRIP_EQUAL =
                "expectedRoundTripEqual";
        private static final String EXPECTED_ROUND_TRIP_ITEMS =
                "expectedRoundTripItems";
        private static final String EXPECTED_SAME_AS_COMPLETE_RESOLUTION =
                "expectedSameAsCompleteResolution";
        private static final String EXPECTED_SAME_NODE_BLUE_ID =
                "expectedSameNodeBlueId";
        private static final String EXPECTED_SAME_ROOT_NODE_BLUE_ID =
                "expectedSameRootNodeBlueId";
        private static final String
                EXPECTED_SAME_CONTENT_BLUE_ID_THROUGH_PIPELINE =
                "expectedSameContentBlueIdThroughPipeline";
        private static final String EXPECTED_SAME_SEMANTIC_COVERAGE =
                "expectedSameSemanticCoverage";
        private static final String EXPECTED_SAME_SEMANTIC_RESULT =
                "expectedSameSemanticResult";
        private static final String
                EXPECTED_SOURCE_REFERENCE_PRESERVED_BY_CANONICALIZATION =
                "expectedSourceReferencePreservedByCanonicalization";
        private static final String EXPECTED_VALID = "expectedValid";
        private static final String EXPECTED_VALUE = "expectedValue";
        private static final String EXPECTED_VERIFIED = "expectedVerified";
        private static final String EXPECTED_WITH_VERIFIED_SET_CONTEXT =
                "expectedWithVerifiedSetContext";
        private static final String
                EXPECTED_WITHOUT_SET_CONTEXT_ERROR_CATEGORY =
                "expectedWithoutSetContextErrorCategory";
        private static final String FIELD_DECLARATION = "fieldDeclaration";
        private static final String FORBIDDEN_JOINED_TERMS =
                "forbiddenJoinedTerms";
        private static final String FULL_LIST = "fullList";
        private static final String ID = "id";
        private static final String INPUT = "input";
        private static final String LEFT = "left";
        private static final String LIMITS = "limits";
        private static final String MATCH_RULE = "matchRule";
        private static final String MAX_REFERENCE_EXPANSIONS =
                "maxReferenceExpansions";
        private static final String MUTATION = "mutation";
        private static final String NEXT = "next";
        private static final String NODE = "node";
        private static final String OPERATION = "operation";
        private static final String OUTCOME = "outcome";
        private static final String PARENT = "parent";
        private static final String PATH = "path";
        private static final String PATTERN = "pattern";
        private static final String PROVIDER = "provider";
        private static final String PROVIDER_NODE = "providerNode";
        private static final String PROVIDER_RESULT = "providerResult";
        private static final String PREPROCESSING_ALIASES =
                "preprocessingAliases";
        private static final String PUBLISHABLE_FILES = "publishableFiles";
        private static final String REGISTRY_KEY = "registryKey";
        private static final String REGISTRY_KIND = "registryKind";
        private static final String REQUESTED_BLUE_ID = "requestedBlueId";
        private static final String REQUIRED_HEADINGS = "requiredHeadings";
        private static final String REQUIRES_VECTOR_PREFIXES =
                "requiresVectorPrefixes";
        private static final String RESOLVED_ITEMS = "resolvedItems";
        private static final String RETURNED_NODE = "returnedNode";
        private static final String RIGHT = "right";
        private static final String SEMANTIC_DESCRIPTION_IDENTITY_BEARING =
                "semanticDescriptionIdentityBearing";
        private static final String SOURCE = "source";
        private static final String STORED_OPTIMIZATION = "storedOptimization";
        private static final String VARIANTS = "variants";

        private FixtureField() {
        }
    }

    private static final String FIXTURE_ROOT = "blue-language-1.0/fixtures/";
    private static final String MANIFEST_RESOURCE = FIXTURE_ROOT + "manifest.yaml";
    private static final String PREPROCESSING_REGISTRY_ROOT =
            FIXTURE_ROOT + "preprocessing/registry/";
    private static final String PREPROCESSING_REGISTRY_MANIFEST_RESOURCE =
            PREPROCESSING_REGISTRY_ROOT + "manifest.yaml";
    private static final int EXPECTED_BEHAVIOR_FIXTURE_COUNT = 153;

    private static final Set<String> OPERATIONS = immutableSet(
            FixtureOperation.ASSERT_VIEW_PATH,
            FixtureOperation.CALCULATE_BLUE_ID,
            FixtureOperation.CALCULATE_BLUE_ID_PAIR,
            FixtureOperation.CALCULATE_CIRCULAR_SET_BLUE_IDS,
            FixtureOperation.CANONICALIZE,
            FixtureOperation.CANONICALIZE_LIMITED_RESULT,
            FixtureOperation.CHANGING_REGISTRY_DESCRIPTION_CHANGES_BLUE_ID,
            FixtureOperation.COLLAPSE,
            FixtureOperation.COMPARE_CONTENT_AND_DIRECT_RESOLVED_BLUE_ID,
            FixtureOperation.COMPARE_EXPANSION_STRATEGIES,
            FixtureOperation.COMPARE_GRAPH_EQUIVALENT_INPUTS,
            FixtureOperation.COMPARE_LIMITED_AND_COMPLETE_RESOLUTION,
            FixtureOperation.EXPAND,
            FixtureOperation.EXPAND_CYCLIC_MEMBER,
            FixtureOperation.EXPAND_LIMITED,
            FixtureOperation.EXPAND_THEN_COLLAPSE,
            FixtureOperation.EXPAND_VARIANTS,
            FixtureOperation.LINT_PUBLISHABLE_DOCUMENTATION,
            FixtureOperation.MATCH,
            FixtureOperation.MINIMIZE_AND_RESOLVE,
            FixtureOperation.PARSE_BLUE_ID_INPUT,
            FixtureOperation.PARSE_SOURCE,
            FixtureOperation.PREPROCESS,
            FixtureOperation.REGISTRY_NODE_HASHES_TO_PUBLISHED_BLUE_ID,
            FixtureOperation.RESOLVE,
            FixtureOperation.RESOLVE_LIMITED,
            FixtureOperation.RESOLVE_VARIANTS,
            FixtureOperation.RETRIEVE_DIRECT_LIST,
            FixtureOperation.SEMANTIC_EXISTS,
            FixtureOperation.SPLIT_EXACT_GRAPH_FRAGMENTS,
            FixtureOperation.SUITE_ASSERTION,
            FixtureOperation.VALIDATE,
            FixtureOperation.VALIDATE_VARIANTS,
            FixtureOperation.VERIFY_DIRECT_LIST,
            FixtureOperation.VERIFY_DIRECT_NODE,
            FixtureOperation.VERIFY_OPAQUE_CYCLIC_FRAGMENT
    );

    private static final Set<String> ALLOWED_FIXTURE_FIELDS = immutableSet(
            FixtureField.ALSO_DIFFERENT_FROM, FixtureField.ALSO_EQUIVALENT_TO, FixtureField.ASSERTIONS, FixtureField.BASE,
            FixtureField.CANDIDATE, FixtureField.CATEGORY, "description", FixtureField.DIRECT_ELEMENT_IDENTITIES_ONLY,
            FixtureField.DIRECT_NODE, FixtureField.DOCUMENT, FixtureField.DOCUMENTS, FixtureField.EXPECT_BLUE_ID_CHANGED,
            FixtureField.EXPECT_ERROR, FixtureField.EXPECTED, FixtureField.EXPECTED_ABSENT, FixtureField.EXPECTED_BLUE_IDS,
            FixtureField.EXPECTED_CANONICAL_CONTAINS_CONTROLS, FixtureField.EXPECTED_CANONICAL_ITEMS,
            FixtureField.EXPECTED_CANONICAL_OVERLAY, FixtureField.EXPECTED_CANONICALIZATION_ERROR_CATEGORY,
            FixtureField.EXPECTED_COLLAPSED, FixtureField.EXPECTED_COLLAPSED_ROOT,
            FixtureField.EXPECTED_CONTENT_BLUE_ID_EQUALS_CANONICAL_IDENTITY_INPUT,
            FixtureField.EXPECTED_DESCENDANT_REQUESTS, FixtureField.EXPECTED_DIRECT_RESOLVED_BLUE_ID_MAY_DIFFER,
            FixtureField.EXPECTED_DIRECT_RESULT_STILL_CONTAINS_ALL_ORDERED_ELEMENT_IDENTITIES,
            FixtureField.EXPECTED_EFFECTIVE_TYPE, FixtureField.EXPECTED_EFFECTIVE_TYPES,
            FixtureField.EXPECTED_ELEMENT_BODY_REQUESTS, FixtureField.EXPECTED_EQUAL, FixtureField.EXPECTED_ERROR_CATEGORY,
            FixtureField.EXPECTED_EXPANDED, FixtureField.EXPECTED_EXPANDED_DESCENDANT_REQUESTS,
            FixtureField.EXPECTED_FIELD_COUNT, FixtureField.EXPECTED_FRAGMENT_BLUE_IDS,
            FixtureField.EXPECTED_FRAGMENT_COUNT, FixtureField.EXPECTED_IDEMPOTENT,
            FixtureField.EXPECTED_IDENTITY_EQUAL,
            FixtureField.EXPECTED_LOCAL_PROVIDER_OUTCOME, FixtureField.EXPECTED_MATCH,
            FixtureField.EXPECTED_MERGE_POLICY, FixtureField.EXPECTED_MINIMIZED_MAY_CONTAIN,
            FixtureField.EXPECTED_NODE_BLUE_ID, FixtureField.EXPECTED_NOT_REQUESTED_BLUE_IDS,
            FixtureField.EXPECTED_OPAQUE_EDGES,
            FixtureField.EXPECTED_OUTCOME, FixtureField.EXPECTED_OUTSTANDING_BLUE_IDS,
            FixtureField.EXPECTED_PARSED, FixtureField.EXPECTED_PREPROCESSED, FixtureField.EXPECTED_PROVIDER_OUTCOME,
            FixtureField.EXPECTED_PUBLISHED_BLUE_ID, FixtureField.EXPECTED_REASON,
            FixtureField.EXPECTED_REFERENCE_PATHS,
            FixtureField.EXPECTED_REQUESTED_BLUE_IDS, FixtureField.EXPECTED_RESOLUTION_OUTCOME,
            FixtureField.EXPECTED_RESOLVED, FixtureField.EXPECTED_RESOLVED_ITEMS, FixtureField.EXPECTED_ROUND_TRIP_EQUAL,
            FixtureField.EXPECTED_ROUND_TRIP_ITEMS, FixtureField.EXPECTED_SAME_AS_COMPLETE_RESOLUTION,
            FixtureField.EXPECTED_SAME_NODE_BLUE_ID, FixtureField.EXPECTED_SAME_ROOT_NODE_BLUE_ID,
            FixtureField.EXPECTED_SAME_CONTENT_BLUE_ID_THROUGH_PIPELINE,
            FixtureField.EXPECTED_SAME_SEMANTIC_COVERAGE, FixtureField.EXPECTED_SAME_SEMANTIC_RESULT,
            FixtureField.EXPECTED_SOURCE_REFERENCE_PRESERVED_BY_CANONICALIZATION,
            FixtureField.EXPECTED_VALID, FixtureField.EXPECTED_VALUE, FixtureField.EXPECTED_VERIFIED,
            FixtureField.EXPECTED_DEFENSIVE_COPIES,
            FixtureField.EXPECTED_WITH_VERIFIED_SET_CONTEXT,
            FixtureField.EXPECTED_WITHOUT_SET_CONTEXT_ERROR_CATEGORY, FixtureField.FIELD_DECLARATION,
            FixtureField.FORBIDDEN_JOINED_TERMS, FixtureField.FULL_LIST, FixtureField.ID, FixtureField.INPUT, FixtureField.LEFT,
            FixtureField.CUTS, FixtureField.LIMITS, FixtureField.MATCH_RULE, FixtureField.MUTATION, "note",
            FixtureField.OPERATION, FixtureField.PARENT,
            FixtureField.PATH, FixtureField.PATTERN, FixtureField.PROVIDER, FixtureField.PROVIDER_NODE, FixtureField.PROVIDER_RESULT,
            FixtureField.PREPROCESSING_ALIASES,
            FixtureField.PUBLISHABLE_FILES, FixtureField.REGISTRY_KEY, FixtureField.REGISTRY_KIND,
            FixtureField.REQUESTED_BLUE_ID, FixtureField.REQUIRED_HEADINGS, FixtureField.REQUIRES_VECTOR_PREFIXES,
            FixtureField.RESOLVED_ITEMS, FixtureField.RIGHT, FixtureField.SEMANTIC_DESCRIPTION_IDENTITY_BEARING,
            FixtureField.SOURCE, FixtureField.STORED_OPTIMIZATION, FixtureField.VARIANTS
    );

    private BlueConformanceSuiteRunner() {
    }

    /**
     * Executes every bundled Blue Language fixture.
     *
     * @return complete conformance report
     */
    public static BlueConformanceReport run() {
        BlueConformanceReport metadata = unexecutedReport();
        List<FixtureEntry> entries = fixtureEntries();
        List<String> passed = new ArrayList<>(entries.size());
        List<BlueConformanceFailure> failures = new ArrayList<>();
        for (FixtureEntry fixture : entries) {
            try {
                runFixture(fixture, entries);
                passed.add(fixture.id);
            } catch (RuntimeException | AssertionError failure) {
                failures.add(failure(fixture, failure));
            }
        }
        return new BlueConformanceReport(
                metadata.getSpecVersion(),
                metadata.getCoreRegistryBlueIds(),
                metadata.getFixturePackageIdentity(),
                metadata.getFixtureIds(),
                passed,
                Collections.<String>emptyList(),
                metadata.getFixtureCategories(),
                failures);
    }

    /**
     * Describes the packaged Language fixture inventory without executing it.
     *
     * @return report containing package metadata and no outcomes
     */
    public static BlueConformanceReport unexecutedReport() {
        return new BlueConformanceReport(
                "1.0",
                new LinkedHashMap<>(
                        BlueCoreTypeRegistry.INSTANCE.blueIdsByName()),
                BlueConformanceReport.loadFixturePackageIdentity(
                        "blue-language-1.0-fixtures:unavailable"),
                BlueConformanceReport.loadFixtureIds(),
                Collections.emptyList(),
                Collections.emptyList(),
                BlueConformanceReport.loadFixtureCategories());
    }

    /**

     * Returns supported fixture operations.

     *

     * @return immutable operation set

     */
    public static Set<String> knownOperations() {
        return OPERATIONS;
    }

    /**
     * Validates fixture metadata for focused tests.
     *
     * @param spec parsed fixture envelope
     * @throws IllegalArgumentException when metadata is invalid
     */
    public static void validateFixtureMetadataForTest(JsonNode spec) {
        validateFixtureMetadata(spec);
    }

    /**
     * Executes one parsed fixture for focused tests.
     *
     * @param spec parsed fixture envelope
     * @throws AssertionError when a fixture assertion fails
     */
    public static void runFixtureForTest(JsonNode spec) {
        validateFixtureMetadata(spec);
        String operation = requireText(spec, FixtureField.OPERATION);
        if (expectsTopLevelError(spec, operation)) {
            try {
                runOperation(spec, operation, fixtureEntries());
            } catch (RuntimeException expected) {
                if (spec.hasNonNull(FixtureField.EXPECTED_ERROR_CATEGORY)) {
                    assertExpectedErrorCategory(
                            spec, FixtureField.EXPECTED_ERROR_CATEGORY, expected);
                }
                return;
            }
            throw new AssertionError("Fixture expected an error but operation succeeded: "
                    + requireText(spec, FixtureField.ID));
        }
        runOperation(spec, operation, fixtureEntries());
    }

    private static void runFixture(FixtureEntry fixture,
                                   List<FixtureEntry> allFixtures) {
        JsonNode spec = readYamlResource(FIXTURE_ROOT + fixture.path);
        validateFixtureMetadata(spec);
        assertEquals(fixture.id, requireText(spec, FixtureField.ID));
        assertEquals(fixture.category,
                BlueFixtureCategory.fromLabel(requireText(spec, FixtureField.CATEGORY)));

        String operation = requireText(spec, FixtureField.OPERATION);
        if (expectsTopLevelError(spec, operation)) {
            try {
                runOperation(spec, operation, allFixtures);
            } catch (RuntimeException expected) {
                if (spec.hasNonNull(FixtureField.EXPECTED_ERROR_CATEGORY)) {
                    assertExpectedErrorCategory(
                            spec, FixtureField.EXPECTED_ERROR_CATEGORY, expected);
                }
                return;
            }
            throw new AssertionError("Fixture expected an error but operation succeeded: "
                    + fixture.id);
        }
        runOperation(spec, operation, allFixtures);
    }

    private static boolean expectsTopLevelError(JsonNode spec, String operation) {
        if (FixtureOperation.RESOLVE_VARIANTS.equals(operation)
                || FixtureOperation.VALIDATE_VARIANTS.equals(operation)
                || FixtureOperation.CANONICALIZE_LIMITED_RESULT.equals(operation)
                || FixtureOperation.EXPAND_CYCLIC_MEMBER.equals(operation)
                || FixtureOperation.EXPAND_VARIANTS.equals(operation)) {
            return false;
        }
        return spec.path(FixtureField.EXPECT_ERROR).asBoolean(false)
                || spec.hasNonNull(FixtureField.EXPECTED_ERROR_CATEGORY);
    }

    private static void runOperation(JsonNode spec,
                                     String operation,
                                     List<FixtureEntry> allFixtures) {
        switch (operation) {
            case FixtureOperation.ASSERT_VIEW_PATH:
                runAssertViewPath(spec);
                return;
            case FixtureOperation.CALCULATE_BLUE_ID:
                runCalculateBlueId(spec);
                return;
            case FixtureOperation.CALCULATE_BLUE_ID_PAIR:
                runCalculateBlueIdPair(spec);
                return;
            case FixtureOperation.CALCULATE_CIRCULAR_SET_BLUE_IDS:
                runCalculateCircularSetBlueIds(spec);
                return;
            case FixtureOperation.CANONICALIZE:
                runCanonicalize(spec);
                return;
            case FixtureOperation.CANONICALIZE_LIMITED_RESULT:
                runCanonicalizeLimitedResult(spec);
                return;
            case FixtureOperation.CHANGING_REGISTRY_DESCRIPTION_CHANGES_BLUE_ID:
                runChangingRegistryDescriptionChangesBlueId(spec);
                return;
            case FixtureOperation.COLLAPSE:
                runCollapse(spec);
                return;
            case FixtureOperation.COMPARE_CONTENT_AND_DIRECT_RESOLVED_BLUE_ID:
                runCompareContentAndDirectResolvedBlueId(spec);
                return;
            case FixtureOperation.COMPARE_EXPANSION_STRATEGIES:
                runCompareExpansionStrategies(spec);
                return;
            case FixtureOperation.COMPARE_GRAPH_EQUIVALENT_INPUTS:
                runCompareGraphEquivalentInputs(spec);
                return;
            case FixtureOperation.COMPARE_LIMITED_AND_COMPLETE_RESOLUTION:
                runCompareLimitedAndCompleteResolution(spec);
                return;
            case FixtureOperation.EXPAND:
                runExpand(spec);
                return;
            case FixtureOperation.EXPAND_CYCLIC_MEMBER:
                runExpandCyclicMember(spec);
                return;
            case FixtureOperation.EXPAND_LIMITED:
                runExpandLimited(spec);
                return;
            case FixtureOperation.EXPAND_THEN_COLLAPSE:
                runExpandThenCollapse(spec);
                return;
            case FixtureOperation.EXPAND_VARIANTS:
                runExpandVariants(spec);
                return;
            case FixtureOperation.LINT_PUBLISHABLE_DOCUMENTATION:
                runLintPublishableDocumentation(spec);
                return;
            case FixtureOperation.MATCH:
                runMatch(spec);
                return;
            case FixtureOperation.MINIMIZE_AND_RESOLVE:
                runMinimizeAndResolve(spec);
                return;
            case FixtureOperation.PARSE_BLUE_ID_INPUT:
                runParseBlueIdInput(spec);
                return;
            case FixtureOperation.PARSE_SOURCE:
                runParseSource(spec);
                return;
            case FixtureOperation.PREPROCESS:
                runPreprocess(spec);
                return;
            case FixtureOperation.REGISTRY_NODE_HASHES_TO_PUBLISHED_BLUE_ID:
                runRegistryNodeHashesToPublishedBlueId(spec);
                return;
            case FixtureOperation.RESOLVE:
                runResolve(spec);
                return;
            case FixtureOperation.RESOLVE_LIMITED:
                runResolveLimited(spec);
                return;
            case FixtureOperation.RESOLVE_VARIANTS:
                runResolveVariants(spec);
                return;
            case FixtureOperation.RETRIEVE_DIRECT_LIST:
                runRetrieveDirectList(spec);
                return;
            case FixtureOperation.SEMANTIC_EXISTS:
                runSemanticExists(spec);
                return;
            case FixtureOperation.SPLIT_EXACT_GRAPH_FRAGMENTS:
                runSplitExactGraphFragments(spec);
                return;
            case FixtureOperation.SUITE_ASSERTION:
                runSuiteAssertion(spec, allFixtures);
                return;
            case FixtureOperation.VALIDATE:
                runValidate(spec);
                return;
            case FixtureOperation.VALIDATE_VARIANTS:
                runValidateVariants(spec);
                return;
            case FixtureOperation.VERIFY_DIRECT_LIST:
                runVerifyDirectList(spec);
                return;
            case FixtureOperation.VERIFY_DIRECT_NODE:
                runVerifyDirectNode(spec);
                return;
            case FixtureOperation.VERIFY_OPAQUE_CYCLIC_FRAGMENT:
                runVerifyOpaqueCyclicFragment(spec);
                return;
            default:
                throw new IllegalArgumentException(
                        "Unsupported fixture operation: " + operation);
        }
    }

    private static void runCalculateBlueId(JsonNode spec) {
        String actual = DirectBlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, FixtureField.INPUT)));
        if (spec.has(FixtureField.EXPECTED_NODE_BLUE_ID)) {
            assertEquals(requireText(spec, FixtureField.EXPECTED_NODE_BLUE_ID), actual);
        }
        assertEquivalentInputs(actual, spec.get(FixtureField.ALSO_EQUIVALENT_TO));
        assertDifferentInputs(actual, spec.get(FixtureField.ALSO_DIFFERENT_FROM));
    }

    private static void runCalculateBlueIdPair(JsonNode spec) {
        String left = DirectBlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, FixtureField.LEFT)));
        String right = DirectBlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, FixtureField.RIGHT)));
        assertEquals(requirePresent(spec, FixtureField.EXPECTED_EQUAL).asBoolean(), left.equals(right));
    }

    private static void runCalculateCircularSetBlueIds(JsonNode spec) {
        Node documents = readNode(requirePresent(spec, FixtureField.DOCUMENTS));
        if (documents == null || documents.getItems() == null) {
            throw new IllegalArgumentException(
                    "calculateCircularSetBlueIds requires a documents list.");
        }
        List<String> actual = CircularSetIdentityCalculator.calculateCircularSetBlueIds(
                documents.getItems());
        assertTextList(requirePresent(spec, FixtureField.EXPECTED_BLUE_IDS), actual);
    }

    private static void runParseBlueIdInput(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime();
        Node actual = blue.parseBlueIdInputYaml(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(
                        requirePresent(spec, FixtureField.INPUT)));
        if (spec.has(FixtureField.EXPECTED_PARSED)) {
            assertNodeEquals(readNode(spec.get(FixtureField.EXPECTED_PARSED)), actual);
        }
    }

    private static void runParseSource(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime();
        Node actual = blue.parseSourceYaml(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(
                        requirePresent(spec, FixtureField.SOURCE)));
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_PARSED, actual);
    }

    private static void runPreprocess(JsonNode spec) {
        ProviderContext provider = preprocessingProviderContext(spec);
        Map<String, String> aliases = preprocessingAliases(spec);
        TransformationProcessorProvider transformations =
                FixtureTransformationRegistry.INSTANCE;
        Node actual = new Preprocessor(
                transformations,
                provider.provider,
                aliases,
                Collections.emptyMap())
                .preprocess(readNode(requirePresent(
                        spec, FixtureField.SOURCE)));
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_PREPROCESSED, actual);
        assertEffectiveTypes(spec.get(FixtureField.EXPECTED_EFFECTIVE_TYPES), actual);
        if (spec.has(FixtureField.ALSO_EQUIVALENT_TO)) {
            Node equivalent = new Preprocessor(
                    transformations,
                    provider.provider,
                    aliases,
                    Collections.emptyMap())
                    .preprocess(readNode(spec.get(
                            FixtureField.ALSO_EQUIVALENT_TO)));
            assertNodeEquals(actual, equivalent);
        }
        if (spec.path(FixtureField.EXPECTED_IDEMPOTENT)
                .asBoolean(false)) {
            Node repeated = new Preprocessor(
                    transformations,
                    provider.provider,
                    aliases,
                    Collections.emptyMap())
                    .preprocess(actual.clone());
            assertNodeEquals(actual, repeated);
        }
    }

    private static void runResolve(JsonNode spec) {
        SymbolicTypeCycle symbolicCycle = symbolicTypeCycle(spec);
        if (symbolicCycle != null) {
            LanguageFixtureRuntime blue =
                    new LanguageFixtureRuntime(symbolicCycle.provider);
            blue.resolve(blue.preprocess(symbolicCycle.rootContent));
            return;
        }
        ProviderContext provider = providerContext(spec, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        Node source = sourceWithParent(spec);
        Node actual = blue.resolve(blue.preprocess(source));
        assertResolutionExpectations(spec, actual, blue, source);
    }

    private static void runCanonicalize(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        Node source = sourceWithParent(spec);
        Node actual = blue.canonicalize(source);
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_CANONICAL_OVERLAY, actual);
        if (spec.has(FixtureField.EXPECTED_CANONICAL_ITEMS)) {
            assertItemValues(spec.get(FixtureField.EXPECTED_CANONICAL_ITEMS), actual.getItems());
        }
        if (spec.has(FixtureField.EXPECTED_CANONICAL_CONTAINS_CONTROLS)) {
            assertEquals(spec.get(FixtureField.EXPECTED_CANONICAL_CONTAINS_CONTROLS).asBoolean(),
                    containsListControls(actual));
        }
        DirectBlueIdCalculator.calculateBlueId(actual);
    }

    private static void runCollapse(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime();
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        Node actual = blue.collapse(source);
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_COLLAPSED, actual);
        String expectedId = requireText(spec, FixtureField.EXPECTED_NODE_BLUE_ID);
        assertEquals(expectedId, actual.getBlueId());
        assertEquals(expectedId, DirectBlueIdCalculator.calculateBlueId(source));
        assertTrue(actual.isReferenceOnly(), "Collapse must emit a pure reference.");
    }

    private static void runExpand(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        Node actual = blue.expand(source);
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_EXPANDED, actual);
        if (spec.has(FixtureField.EXPECTED_NODE_BLUE_ID)) {
            String expected = requireText(spec, FixtureField.EXPECTED_NODE_BLUE_ID);
            assertEquals(expected, DirectBlueIdCalculator.calculateBlueId(source));
            assertEquals(expected, DirectBlueIdCalculator.calculateBlueId(actual));
        }
    }

    private static void runExpandLimited(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        BlueOperationLimits limits = operationLimits(spec);
        BlueOperationResult<Node> result = blue.expandLimited(
                readNode(requirePresent(spec, FixtureField.SOURCE)), limits);
        assertOutcome(spec, FixtureField.EXPECTED_OUTCOME, result.outcome());
        assertDemandedValue(spec, result, limits);
        assertRequestedIds(spec.get(FixtureField.EXPECTED_REQUESTED_BLUE_IDS),
                provider.provider.requestedBlueIds, true);
        assertRequestedIds(spec.get(FixtureField.EXPECTED_NOT_REQUESTED_BLUE_IDS),
                provider.provider.requestedBlueIds, false);
    }

    private static void runResolveLimited(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        BlueOperationLimits limits = operationLimits(spec);
        BlueOperationResult<Node> result = blue.resolveLimited(
                readNode(requirePresent(spec, FixtureField.SOURCE)), limits);
        assertOutcome(spec, FixtureField.EXPECTED_OUTCOME, result.outcome());
        if (spec.has(FixtureField.EXPECTED_ABSENT)) {
            assertEquals(spec.get(FixtureField.EXPECTED_ABSENT).asBoolean(), result.isAbsent());
        }
        if (spec.has(FixtureField.EXPECTED_OUTSTANDING_BLUE_IDS)) {
            assertTextSet(spec.get(FixtureField.EXPECTED_OUTSTANDING_BLUE_IDS),
                    result.outstandingBlueIds());
        }
        if (spec.has(FixtureField.EXPECTED_PROVIDER_OUTCOME)) {
            assertEquals(providerOutcome(requireText(spec, FixtureField.EXPECTED_PROVIDER_OUTCOME)),
                    result.providerOutcome().orElse(null));
        }
    }

    private static void runCanonicalizeLimitedResult(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime(
                providerContext(spec, null).provider);
        BlueOperationResult<Node> limited = blue.resolveLimited(
                readNode(requirePresent(spec, FixtureField.SOURCE)), operationLimits(spec));
        assertOutcome(spec, FixtureField.EXPECTED_RESOLUTION_OUTCOME, limited.outcome());
        try {
            blue.canonicalize(limited);
        } catch (RuntimeException expected) {
            assertExpectedErrorCategory(
                    spec, FixtureField.EXPECTED_CANONICALIZATION_ERROR_CATEGORY, expected);
            return;
        }
        throw new AssertionError("Incomplete result was accepted for canonicalization.");
    }

    private static void runCompareLimitedAndCompleteResolution(JsonNode spec) {
        ProviderContext limitedProvider = providerContext(spec, null);
        ProviderContext completeProvider = providerContext(spec, null);
        LanguageFixtureRuntime limitedBlue =
                new LanguageFixtureRuntime(limitedProvider.provider);
        LanguageFixtureRuntime completeBlue =
                new LanguageFixtureRuntime(completeProvider.provider);
        BlueOperationLimits limits = operationLimits(spec);
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        BlueOperationResult<Node> limited = limitedBlue.resolveLimited(source, limits);
        assertOutcome(spec, FixtureField.EXPECTED_OUTCOME, limited.outcome());
        Node complete = completeBlue.resolve(completeBlue.preprocess(source.clone()));
        for (String path : limits.demandedPaths()) {
            Node limitedValue = BlueViewPath.select(limited.requireEstablished(), path);
            Node completeValue = BlueViewPath.select(complete, path);
            assertNodeEquals(completeValue, limitedValue);
            if (spec.has(FixtureField.EXPECTED_VALUE)) {
                assertSemanticScalar(spec.get(FixtureField.EXPECTED_VALUE), limitedValue);
            }
        }
        assertTrue(requirePresent(spec, FixtureField.EXPECTED_SAME_AS_COMPLETE_RESOLUTION).asBoolean(),
                "Fixture must require complete-resolution parity.");
    }

    private static void runCompareGraphEquivalentInputs(JsonNode spec) {
        JsonNode variants = requireArray(spec, FixtureField.VARIANTS);
        Map<String, NodeProviderResult> derived = new LinkedHashMap<>(globalProviderCatalog());
        for (JsonNode variant : variants) {
            Node source = readNode(requirePresent(variant, FixtureField.SOURCE));
            if (!source.isReferenceOnly()) {
                derived.put(DirectBlueIdCalculator.calculateBlueId(source),
                        NodeProviderResult.found(Collections.singletonList(source)));
            }
        }
        BlueOperationLimits limits = operationLimits(spec);
        List<BlueOperationResult<Node>> results = new ArrayList<>();
        List<Node> selected = new ArrayList<>();
        List<String> rootIds = new ArrayList<>();
        for (JsonNode variant : variants) {
            ProviderContext provider = providerContextWithoutFixtureProvider(derived);
            Node source = readNode(requirePresent(variant, FixtureField.SOURCE));
            BlueOperationResult<Node> result =
                    new LanguageFixtureRuntime(provider.provider)
                            .expandLimited(source, limits);
            results.add(result);
            assertOutcome(spec, FixtureField.EXPECTED_OUTCOME, result.outcome());
            selected.add(selectFirstDemand(result.requireEstablished(), limits));
            rootIds.add(DirectBlueIdCalculator.calculateBlueId(source));
        }
        assertAllNodeEqual(selected);
        assertAllEqual(rootIds);
        assertEquals(requireText(spec, FixtureField.EXPECTED_SAME_ROOT_NODE_BLUE_ID), rootIds.get(0));
        assertSemanticScalar(spec.get(FixtureField.EXPECTED_VALUE), selected.get(0));
        assertTrue(spec.path(FixtureField.EXPECTED_SAME_SEMANTIC_RESULT).asBoolean(false),
                "Fixture must require semantic-result parity.");
    }

    private static void runCompareExpansionStrategies(JsonNode spec) {
        JsonNode variants = requireArray(spec, FixtureField.VARIANTS);
        BlueOperationLimits limits = operationLimits(spec);
        List<Node> selected = new ArrayList<>();
        List<String> rootIds = new ArrayList<>();
        for (JsonNode variant : variants) {
            ProviderContext provider = providerContext(spec, globalProviderCatalog());
            JsonNode prefetched = variant.get("physicallyPrefetchedBlueIds");
            if (prefetched != null) {
                for (JsonNode blueId : prefetched) {
                    provider.provider.fetchResultByBlueId(blueId.asText());
                }
            }
            Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
            BlueOperationResult<Node> result =
                    new LanguageFixtureRuntime(provider.provider)
                            .expandLimited(source, limits);
            assertOutcome(spec, FixtureField.EXPECTED_OUTCOME, result.outcome());
            selected.add(selectFirstDemand(result.requireEstablished(), limits));
            rootIds.add(DirectBlueIdCalculator.calculateBlueId(source));
        }
        assertAllNodeEqual(selected);
        assertAllEqual(rootIds);
        assertSemanticScalar(spec.get(FixtureField.EXPECTED_VALUE), selected.get(0));
        assertEquals(requireText(spec, FixtureField.EXPECTED_SAME_NODE_BLUE_ID), rootIds.get(0));
        assertTrue(spec.path(FixtureField.EXPECTED_SAME_SEMANTIC_COVERAGE).asBoolean(false),
                "Fixture must require semantic-coverage parity.");
    }

    private static void runExpandThenCollapse(JsonNode spec) {
        ProviderContext provider = providerContext(spec, globalProviderCatalog());
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        BlueOperationResult<Node> expanded =
                blue.expandLimited(source, operationLimits(spec));
        Node collapsed = blue.collapse(expanded.requireEstablished());
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_COLLAPSED_ROOT, collapsed);
        List<String> descendants = new ArrayList<>(provider.provider.requestedBlueIds);
        descendants.remove(source.getBlueId());
        assertTextList(requirePresent(spec, FixtureField.EXPECTED_EXPANDED_DESCENDANT_REQUESTS),
                descendants);
        assertRequestedIds(spec.get(FixtureField.EXPECTED_NOT_REQUESTED_BLUE_IDS),
                provider.provider.requestedBlueIds, false);
    }

    private static void runExpandCyclicMember(JsonNode spec) {
        String illustrativeRequested = requireText(spec, FixtureField.REQUESTED_BLUE_ID);
        int memberSeparator = illustrativeRequested.lastIndexOf(
                BlueIds.CYCLIC_MEMBER_SEPARATOR);
        if (memberSeparator < 0) {
            throw new IllegalArgumentException(
                    "Illustrative cyclic member BlueId must select a member.");
        }
        int requestedMember = Integer.parseInt(
                illustrativeRequested.substring(memberSeparator + 1));
        Node content = readNode(requirePresent(spec, FixtureField.PROVIDER_NODE));
        Node companion = new Node()
                .name("generated fixture companion")
                .properties(
                        "peer",
                        new Node().blueId(
                                BlueIds.indexedThisPlaceholder(0)));
        List<Node> members = Arrays.asList(content, companion);
        List<String> calculated = CircularSetIdentityCalculator
                .calculateCircularSetBlueIds(members);
        if (requestedMember < 0 || requestedMember >= calculated.size()) {
            throw new IllegalArgumentException(
                    "Illustrative cyclic member index is outside the generated set.");
        }
        String requested = calculated.get(requestedMember);
        FixtureProvider ordinary = new FixtureProvider(Collections.singletonMap(
                requested, NodeProviderResult.found(Collections.singletonList(content))));
        try {
            new VerifyingNodeProvider(ordinary).fetchByBlueId(requested);
            throw new AssertionError(
                    "Cyclic member verification succeeded without verified set context.");
        } catch (RuntimeException expected) {
            assertExpectedErrorCategory(
                    spec, FixtureField.EXPECTED_WITHOUT_SET_CONTEXT_ERROR_CATEGORY, expected);
        }

        Node verifiedContent = content.clone();
        replaceThisReferences(verifiedContent, calculated);
        VerifiedCyclicFixtureProvider verified =
                new VerifiedCyclicFixtureProvider(
                        requested, verifiedContent, members);
        List<Node> nodes = new VerifyingNodeProvider(verified).fetchByBlueId(requested);
        assertTrue(nodes != null && nodes.size() == 1,
                "Verified cyclic-set context did not return the member.");
        assertEquals("success", requireText(spec, FixtureField.EXPECTED_WITH_VERIFIED_SET_CONTEXT));
    }

    private static void replaceThisReferences(Node node, List<String> memberBlueIds) {
        if (node == null) return;
        String blueId = node.getBlueId();
        if (blueId != null
                && blueId.startsWith(
                BlueIds.THIS_MEMBER_PREFIX)) {
            int index = Integer.parseInt(
                    blueId.substring(
                            BlueIds.THIS_MEMBER_PREFIX
                                    .length()));
            if (index < 0 || index >= memberBlueIds.size()) {
                throw new IllegalArgumentException(
                        "Cyclic fixture reference points outside the generated set.");
            }
            node.blueId(memberBlueIds.get(index));
        }
        replaceThisReferences(node.getType(), memberBlueIds);
        replaceThisReferences(node.getItemType(), memberBlueIds);
        replaceThisReferences(node.getKeyType(), memberBlueIds);
        replaceThisReferences(node.getValueType(), memberBlueIds);
        replaceThisReferences(node.getBlue(), memberBlueIds);
        replaceThisReferences(node.getContracts(), memberBlueIds);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                replaceThisReferences(item, memberBlueIds);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                replaceThisReferences(child, memberBlueIds);
            }
        }
        if (node.getSchema() != null) {
            replaceThisReferences(node.getSchema().getRequired(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMinLength(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMaxLength(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMinimum(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMaximum(), memberBlueIds);
            replaceThisReferences(node.getSchema().getExclusiveMinimum(), memberBlueIds);
            replaceThisReferences(node.getSchema().getExclusiveMaximum(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMultipleOf(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMinItems(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMaxItems(), memberBlueIds);
            replaceThisReferences(node.getSchema().getUniqueItems(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMinFields(), memberBlueIds);
            replaceThisReferences(node.getSchema().getMaxFields(), memberBlueIds);
            if (node.getSchema().getEnum() != null) {
                for (Node value : node.getSchema().getEnum()) {
                    replaceThisReferences(value, memberBlueIds);
                }
            }
        }
    }

    private static void runSplitExactGraphFragments(JsonNode spec) {
        Node input = readNode(requirePresent(spec, FixtureField.INPUT));
        List<ExactNodeGraphFragments> graphs = new ArrayList<>();
        graphs.add(ExactNodeGraphFragments.split(
                input, textValues(requireArray(spec, FixtureField.CUTS))));

        JsonNode variants = spec.get(FixtureField.VARIANTS);
        if (variants != null) {
            if (!variants.isArray()) {
                throw new IllegalArgumentException(
                        "Exact graph fragment variants must be a list.");
            }
            for (JsonNode variant : variants) {
                graphs.add(ExactNodeGraphFragments.split(
                        input,
                        textValues(requireArray(variant, FixtureField.CUTS))));
            }
        }

        String inputBlueId = DirectBlueIdCalculator.calculateBlueId(input);
        for (ExactNodeGraphFragments graph : graphs) {
            assertFragmentRootIdentity(spec, graph, inputBlueId);
            assertExpectedReferencePaths(spec, graph);
        }

        ExactNodeGraphFragments primary = graphs.get(0);
        if (spec.has(FixtureField.EXPECTED_FRAGMENT_COUNT)) {
            assertEquals(spec.get(FixtureField.EXPECTED_FRAGMENT_COUNT).asInt(),
                    primary.fragments().size());
        }
        if (spec.has(FixtureField.EXPECTED_FRAGMENT_BLUE_IDS)) {
            assertTextList(spec.get(FixtureField.EXPECTED_FRAGMENT_BLUE_IDS),
                    primary.blueIds());
        }
        if (spec.has(FixtureField.EXPECTED_LOCAL_PROVIDER_OUTCOME)) {
            assertLocalProviderOutcomes(
                    spec.get(FixtureField.EXPECTED_LOCAL_PROVIDER_OUTCOME), primary);
        }
        if (spec.has(FixtureField.EXPECTED_DEFENSIVE_COPIES)) {
            assertEquals(spec.get(FixtureField.EXPECTED_DEFENSIVE_COPIES).asBoolean(),
                    hasDefensiveFragmentCopies(primary));
        }

        List<Node> roundTrips = new ArrayList<>(graphs.size());
        for (ExactNodeGraphFragments graph : graphs) {
            roundTrips.add(expandFragmentRoot(graph));
        }
        if (spec.path(FixtureField.EXPECTED_ROUND_TRIP_EQUAL).asBoolean(false)) {
            for (Node roundTrip : roundTrips) {
                assertNodeEquals(input, roundTrip);
            }
        }
        if (spec.path(FixtureField.EXPECTED_SAME_SEMANTIC_RESULT).asBoolean(false)) {
            assertAllNodeEqual(roundTrips);
            for (int index = 1; index < graphs.size(); index++) {
                assertEquals(primary.blueIds(),
                        graphs.get(index).blueIds());
            }
        }
    }

    private static void runVerifyOpaqueCyclicFragment(JsonNode spec) {
        Node input = readNode(requirePresent(spec, FixtureField.INPUT));
        ExactNodeGraphFragments graph = ExactNodeGraphFragments.split(
                input, textValues(requireArray(spec, FixtureField.CUTS)));
        assertFragmentRootIdentity(
                spec, graph, DirectBlueIdCalculator.calculateBlueId(input));
        assertLocalProviderOutcomes(
                requirePresent(spec, FixtureField.EXPECTED_LOCAL_PROVIDER_OUTCOME), graph);

        Set<String> opaqueBlueIds = new LinkedHashSet<>();
        for (JsonNode expected
                : requireArray(spec, FixtureField.EXPECTED_OPAQUE_EDGES)) {
            String path = requireString(expected, FixtureField.PATH);
            String blueId = requireText(expected, BlueLanguageConstants.OBJECT_BLUE_ID);
            Node edge = selectFragmentReference(graph, path);
            assertTrue(edge != null && edge.isReferenceOnly(),
                    "Expected an opaque pure-reference edge at " + path + ".");
            assertEquals(blueId, edge.getBlueId());
            assertTrue(!graph.fragments().containsKey(blueId),
                    "Ordinary exact fragments must not claim cyclic member "
                            + blueId + ".");
            opaqueBlueIds.add(blueId);
        }

        for (String opaqueBlueId : opaqueBlueIds) {
            try {
                new LanguageFixtureRuntime(graph.provider()).expand(
                        new Node().blueId(opaqueBlueId));
                throw new AssertionError(
                        "Opaque cyclic member expanded without set proof: "
                                + opaqueBlueId);
            } catch (RuntimeException unavailable) {
                assertExpectedErrorCategory(
                        spec,
                        FixtureField.EXPECTED_WITHOUT_SET_CONTEXT_ERROR_CATEGORY,
                        unavailable);
            }
        }

        BasicNodeProvider cyclicProof = fragmentCyclicProof();
        String verifiedMemberBlueId = cyclicProof.getBlueIdByName(
                "Fragment Cyclic A");
        ExactNodeGraphFragments proofBoundary =
                ExactNodeGraphFragments.split(
                        new Node().properties(
                                "member",
                                new Node().blueId(
                                        verifiedMemberBlueId)),
                        Collections.<String>emptyList());
        assertEquals(NodeProviderOutcome.NOT_FOUND,
                proofBoundary.provider()
                        .fetchResultByBlueId(verifiedMemberBlueId)
                        .outcome());
        NodeProvider composed = NodeProviderWrapper.wrap(
                new SequentialNodeProvider(
                        proofBoundary.provider(), cyclicProof));
        NodeProviderResult verified =
                composed.fetchResultByBlueId(verifiedMemberBlueId);
        assertEquals(
                spec.get(FixtureField.EXPECTED_WITH_VERIFIED_SET_CONTEXT)
                        .asBoolean(false),
                verified.outcome() == NodeProviderOutcome.FOUND);
    }

    private static void assertFragmentRootIdentity(
            JsonNode spec,
            ExactNodeGraphFragments graph,
            String expectedBlueId) {
        if (!spec.path(FixtureField.EXPECTED_SAME_ROOT_NODE_BLUE_ID)
                .asBoolean(false)) {
            return;
        }
        ExactNodeGraphFragments.RootRepresentation root =
                graph.roots().get(0);
        assertEquals(expectedBlueId, root.blueId());
        assertEquals(expectedBlueId,
                DirectBlueIdCalculator.calculateBlueId(root.original()));
        assertEquals(expectedBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        root.directFragment()));
        assertEquals(expectedBlueId,
                root.pureReference().getBlueId());
    }

    private static void assertExpectedReferencePaths(
            JsonNode spec,
            ExactNodeGraphFragments graph) {
        JsonNode paths = spec.get(FixtureField.EXPECTED_REFERENCE_PATHS);
        if (paths == null) {
            return;
        }
        for (JsonNode path : paths) {
            Node reference = selectFragmentReference(
                    graph, path.asText());
            assertTrue(reference != null
                            && reference.isReferenceOnly(),
                    "Expected exact fragment reference at "
                            + path.asText() + ".");
        }
    }

    private static Node selectFragmentReference(
            ExactNodeGraphFragments graph,
            String path) {
        Object selected = NodePath.get(
                graph.roots().get(0).directFragment(),
                path,
                node -> {
                    if (node == null || !node.isReferenceOnly()) {
                        return node;
                    }
                    List<Node> fragments = graph.provider()
                            .fetchByBlueId(node.getBlueId());
                    if (fragments == null || fragments.isEmpty()) {
                        throw new IllegalArgumentException(
                                "No local exact fragment for "
                                        + node.getBlueId()
                                        + " while traversing " + path + ".");
                    }
                    return fragments.get(0);
                },
                false);
        return selected instanceof Node ? (Node) selected : null;
    }

    private static Node expandFragmentRoot(
            ExactNodeGraphFragments graph) {
        return new LanguageFixtureRuntime(graph.provider()).expand(
                graph.roots().get(0).pureReference());
    }

    private static void assertLocalProviderOutcomes(
            JsonNode expected,
            ExactNodeGraphFragments graph) {
        if (expected == null || !expected.isObject()) {
            throw new IllegalArgumentException(
                    "expectedLocalProviderOutcome must be an object.");
        }
        expected.fields().forEachRemaining(entry ->
                assertEquals(
                        providerOutcome(entry.getValue().asText()),
                        graph.provider()
                                .fetchResultByBlueId(entry.getKey())
                                .outcome()));
    }

    private static boolean hasDefensiveFragmentCopies(
            ExactNodeGraphFragments graph) {
        String blueId = graph.blueIds().get(0);
        Node firstSnapshot = graph.fragments().get(blueId);
        Node secondSnapshot = graph.fragments().get(blueId);
        if (firstSnapshot == secondSnapshot) {
            return false;
        }
        firstSnapshot.name("mutated fixture snapshot");
        if (!blueId.equals(DirectBlueIdCalculator.calculateBlueId(
                graph.fragments().get(blueId)))) {
            return false;
        }

        List<Node> firstFetch =
                graph.provider().fetchByBlueId(blueId);
        List<Node> secondFetch =
                graph.provider().fetchByBlueId(blueId);
        if (firstFetch == null || secondFetch == null
                || firstFetch.isEmpty() || secondFetch.isEmpty()
                || firstFetch.get(0) == secondFetch.get(0)) {
            return false;
        }
        firstFetch.get(0).name("mutated fixture provider result");
        return blueId.equals(DirectBlueIdCalculator.calculateBlueId(
                graph.provider().fetchByBlueId(blueId).get(0)));
    }

    private static BasicNodeProvider fragmentCyclicProof() {
        return new BasicNodeProvider(new Node().items(
                new Node()
                        .name("Fragment Cyclic A")
                        .properties(
                                FixtureField.NEXT,
                                new Node().type(
                                        new Node().blueId(
                                                BlueIds
                                                        .indexedThisPlaceholder(
                                                                1)))),
                new Node()
                        .name("Fragment Cyclic B")
                        .properties(
                                FixtureField.NEXT,
                                new Node().type(
                                        new Node().blueId(
                                                BlueIds
                                                        .indexedThisPlaceholder(
                                                                0))))));
    }

    private static void runExpandVariants(JsonNode spec) {
        String requested = requireText(spec, FixtureField.REQUESTED_BLUE_ID);
        Node providerNode = readNode(requirePresent(spec, FixtureField.PROVIDER_NODE));
        for (JsonNode variant : requireArray(spec, FixtureField.VARIANTS)) {
            String mode = requireText(variant, "providerMode");
            if ("BlueIdInput".equals(mode)) {
                try {
                    ProviderEvidenceVerifier.verify(requested, providerNode,
                            ProviderMode.BLUE_ID_INPUT,
                            new LanguageFixtureRuntime().access(), null);
                } catch (RuntimeException expected) {
                    assertExpectedErrorCategory(
                            variant, FixtureField.EXPECTED_ERROR_CATEGORY, expected);
                    continue;
                }
                throw new AssertionError("BlueIdInput mode accepted Source evidence.");
            }
            if (!"SourceDocument".equals(mode)) {
                throw new IllegalArgumentException("Unknown providerMode: " + mode);
            }
            assertTrue(variant.path(
                    "expectedRequiresDeclaredLanguageAndPreprocessingEnvironment")
                    .asBoolean(false), "SourceDocument mode must require an environment.");
            boolean rejectedWithoutEnvironment = false;
            try {
                ProviderEvidenceVerifier.verify(requested, providerNode,
                        ProviderMode.SOURCE_DOCUMENT,
                        new LanguageFixtureRuntime().access(), null);
            } catch (IllegalArgumentException expected) {
                rejectedWithoutEnvironment = true;
            }
            assertTrue(rejectedWithoutEnvironment,
                    "SourceDocument mode accepted undeclared preprocessing.");
            // Verify the same evidence succeeds once it is explicitly bound.
            LanguageFixtureRuntime sourceBlue =
                    new LanguageFixtureRuntime();
            ProviderEvidenceVerifier.verify(requested, providerNode,
                    ProviderMode.SOURCE_DOCUMENT, sourceBlue.access(),
                    new SourceProviderEnvironment(
                            sourceBlue.languageVersion(),
                            SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY,
                            ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(
                                    sourceBlue.access()),
                            BlueCoreTypeRegistry.INSTANCE.packageIdentity(),
                            ProviderEvidenceVerifier.sourceEvidenceIdentity(
                                    providerNode)));
        }
    }

    private static void runCompareContentAndDirectResolvedBlueId(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime(
                providerContext(spec, null).provider);
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        Node resolved = blue.resolve(blue.preprocess(source.clone()));
        Node canonical = blue.canonicalize(source);
        String contentBlueId = blue.calculateSourceDocumentBlueId(source);
        String canonicalIdentityInputBlueId =
                DirectBlueIdCalculator.calculateBlueId(canonical);
        String directResolvedBlueId = DirectBlueIdCalculator.calculateBlueId(resolved);
        assertEquals(spec.path(
                        FixtureField.EXPECTED_CONTENT_BLUE_ID_EQUALS_CANONICAL_IDENTITY_INPUT)
                        .asBoolean(false),
                contentBlueId.equals(canonicalIdentityInputBlueId));
        assertEquals(spec.path(FixtureField.EXPECTED_DIRECT_RESOLVED_BLUE_ID_MAY_DIFFER)
                        .asBoolean(false),
                !directResolvedBlueId.equals(contentBlueId));
    }

    private static void runMinimizeAndResolve(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        Node originalSource;
        Node originalResolved;
        Node minimized;
        if (spec.has(FixtureField.SOURCE)) {
            originalSource = readNode(spec.get(FixtureField.SOURCE));
            originalResolved = blue.resolve(blue.preprocess(
                    originalSource.clone()));
            assertExpectedResolvedIfPresent(spec, FixtureField.EXPECTED_RESOLVED,
                    originalResolved, blue);
            minimized = blue.minimize(originalSource.clone());
        } else {
            // Build the synthetic complete source in the same preprocessed
            // representation used by list-anchor validation. In particular,
            // an append-only $previous anchor identifies inherited typed
            // items, not their pre-inference source spelling.
            Node parent = blue.preprocess(
                    readNode(requirePresent(spec, FixtureField.PARENT)));
            Node desired = blue.preprocess(
                    readNode(requirePresent(spec, FixtureField.RESOLVED_ITEMS)));
            originalSource = sourceForResolvedItems(
                    parent, desired.getItems());
            originalResolved = blue.resolve(blue.preprocess(
                    originalSource.clone()));
            minimized = blue.minimize(originalSource.clone());
        }
        Node roundTrip = blue.resolve(blue.preprocess(minimized.clone()));
        if (spec.path(FixtureField.EXPECTED_ROUND_TRIP_EQUAL).asBoolean(false)) {
            assertNodeEquals(originalResolved, roundTrip);
        }
        if (spec.has(FixtureField.EXPECTED_ROUND_TRIP_ITEMS)) {
            assertItemValues(spec.get(FixtureField.EXPECTED_ROUND_TRIP_ITEMS),
                    roundTrip.getItems());
        }
        if (spec.has(FixtureField.EXPECTED_MINIMIZED_MAY_CONTAIN)) {
            assertOnlyAllowedMinimizationControls(
                    minimized, textValues(spec.get(FixtureField.EXPECTED_MINIMIZED_MAY_CONTAIN)));
        }
        if (spec.path(
                FixtureField.EXPECTED_SAME_CONTENT_BLUE_ID_THROUGH_PIPELINE)
                .asBoolean(false)) {
            assertEquals(
                    blue.calculateSourceDocumentBlueId(originalSource.clone()),
                    blue.calculateSourceDocumentBlueId(minimized.clone()));
        }
    }

    private static Node sourceForResolvedItems(
            Node parent, List<Node> desiredItems) {
        if (parent.getItems() == null || desiredItems == null) {
            throw new IllegalArgumentException(
                    "List minimization fixtures require parent and resolved item lists.");
        }
        if (desiredItems.size() < parent.getItems().size()) {
            throw new IllegalArgumentException(
                    "A resolved list cannot remove inherited items.");
        }
        List<Node> overlayItems = new ArrayList<>();
        if (BlueLanguageConstants.LIST_MERGE_POLICY_APPEND_ONLY.equals(
                parent.getMergePolicy())) {
            for (int index = 0; index < parent.getItems().size(); index++) {
                if (!DirectBlueIdCalculator.calculateBlueId(
                                parent.getItems().get(index))
                        .equals(DirectBlueIdCalculator.calculateBlueId(
                                desiredItems.get(index)))) {
                    throw new IllegalArgumentException(
                            "An append-only resolved list cannot modify inherited items.");
                }
            }
            overlayItems.add(new Node().previousBlueId(
                    DirectBlueIdCalculator.calculateBlueId(parent.getItems())));
            for (int index = parent.getItems().size();
                 index < desiredItems.size(); index++) {
                overlayItems.add(desiredItems.get(index).clone());
            }
            return new Node().type(parent).items(overlayItems);
        }
        for (int index = 0; index < parent.getItems().size(); index++) {
            Node inherited = parent.getItems().get(index);
            Node desired = desiredItems.get(index);
            if (DirectBlueIdCalculator.calculateBlueId(inherited)
                    .equals(DirectBlueIdCalculator.calculateBlueId(desired))) {
                continue;
            }
            overlayItems.add(new Node()
                    .position(index)
                    .properties(BlueLanguageConstants.LIST_CONTROL_REPLACE,
                            desired.clone()));
        }
        for (int index = parent.getItems().size();
             index < desiredItems.size(); index++) {
            overlayItems.add(desiredItems.get(index).clone());
        }
        return new Node().type(parent).items(overlayItems);
    }

    private static void runResolveVariants(JsonNode spec) {
        for (JsonNode variant : requireArray(spec, FixtureField.VARIANTS)) {
            Node source = variant.has(FixtureField.SOURCE)
                    ? readNode(variant.get(FixtureField.SOURCE))
                    : readNode(requirePresent(variant, "overlay"));
            attachBaselineType(source, spec);
            runExpectedVariant(spec, variant, source);
        }
    }

    private static void runValidate(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        Node resolved = blue.resolve(blue.preprocess(source));
        if (spec.has(FixtureField.EXPECTED_VALID)) {
            assertEquals(spec.get(FixtureField.EXPECTED_VALID).asBoolean(), true);
        }
        if (spec.has(FixtureField.EXPECTED_FIELD_COUNT)) {
            int fieldCount = resolved.getProperties() == null
                    ? 0 : resolved.getProperties().size();
            assertEquals(spec.get(FixtureField.EXPECTED_FIELD_COUNT).asInt(), fieldCount);
        }
        if (spec.has(FixtureField.ALSO_EQUIVALENT_TO)) {
            Node equivalent = readNode(spec.get(FixtureField.ALSO_EQUIVALENT_TO));
            Node equivalentResolved = blue.resolve(blue.preprocess(equivalent));
            assertNodeEquals(resolved, equivalentResolved);
        }
    }

    private static void runValidateVariants(JsonNode spec) {
        for (JsonNode variant : requireArray(spec, FixtureField.VARIANTS)) {
            Node source = readNode(requirePresent(variant, FixtureField.SOURCE));
            attachBaselineType(source, spec);
            runExpectedVariant(spec, variant, source);
        }
    }

    private static void runExpectedVariant(JsonNode fixture,
                                           JsonNode variant,
                                           Node source) {
        ProviderContext provider = providerContext(fixture, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        try {
            blue.resolve(blue.preprocess(source));
        } catch (RuntimeException failure) {
            if (!variant.hasNonNull(FixtureField.EXPECTED_ERROR_CATEGORY)) {
                throw failure;
            }
            assertExpectedErrorCategory(
                    variant, FixtureField.EXPECTED_ERROR_CATEGORY, failure);
            return;
        }
        if (variant.hasNonNull(FixtureField.EXPECTED_ERROR_CATEGORY)) {
            throw new AssertionError("Variant expected an error but succeeded.");
        }
        assertTrue(variant.path(FixtureField.EXPECTED_VALID).asBoolean(false),
                "Successful variant must declare expectedValid: true.");
    }

    private static void runMatch(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime(
                providerContext(spec, null).provider);
        Node pattern = readNode(requirePresent(spec, FixtureField.PATTERN));
        Node candidate = readNode(requirePresent(spec, FixtureField.CANDIDATE));
        boolean matches = blue.nodeMatchesType(candidate, pattern);
        assertEquals(spec.get(FixtureField.EXPECTED_MATCH).asBoolean(), matches);
        boolean identityEqual = DirectBlueIdCalculator.calculateBlueId(pattern)
                .equals(DirectBlueIdCalculator.calculateBlueId(candidate));
        assertEquals(spec.get(FixtureField.EXPECTED_IDENTITY_EQUAL).asBoolean(), identityEqual);
    }

    private static void runSemanticExists(JsonNode spec) {
        BlueOperationResult<Node> result;
        if (spec.has(FixtureField.PROVIDER_RESULT)) {
            JsonNode providerResult = spec.get(FixtureField.PROVIDER_RESULT);
            Node partial = readNode(requirePresent(providerResult, "partialObject"));
            boolean complete = providerResult.path(
                    "completeDirectManifest").asBoolean(false);
            DirectNodeManifest manifest = complete
                    ? DirectNodeManifest.complete(partial)
                    : DirectNodeManifest.partial(partial);
            result = manifest.semanticSelect(requireText(spec, FixtureField.PATH));
        } else {
            ProviderContext provider = providerContext(spec, null);
            LanguageFixtureRuntime blue =
                    new LanguageFixtureRuntime(provider.provider);
            Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
            result = DirectNodeManifest.complete(source)
                    .semanticSelect(requireText(spec, FixtureField.PATH));
        }
        assertOutcome(spec, FixtureField.EXPECTED_OUTCOME, result.outcome());
        if (spec.has(FixtureField.EXPECTED_ABSENT)) {
            assertEquals(spec.get(FixtureField.EXPECTED_ABSENT).asBoolean(), result.isAbsent());
        }
        if (spec.has(FixtureField.EXPECTED_REASON)) {
            assertEquals(requireText(spec, FixtureField.EXPECTED_REASON),
                    result.reason().orElse(null));
        }
    }

    private static void runVerifyDirectNode(JsonNode spec) {
        Node direct = readNode(requirePresent(spec, FixtureField.DIRECT_NODE));
        DirectNodeManifest manifest = DirectNodeManifest.complete(direct);
        BlueOperationResult<Node> result =
                manifest.verify(requireText(spec, FixtureField.REQUESTED_BLUE_ID));
        assertEquals(spec.get(FixtureField.EXPECTED_VERIFIED).asBoolean(),
                result.isEstablished());
        assertEquals(requireText(spec, FixtureField.EXPECTED_NODE_BLUE_ID),
                DirectBlueIdCalculator.calculateBlueId(direct));
        assertTextList(requirePresent(spec, FixtureField.EXPECTED_DESCENDANT_REQUESTS),
                Collections.<String>emptyList());
    }

    private static void runVerifyDirectList(JsonNode spec) {
        assertTrue(requirePresent(spec, FixtureField.DIRECT_ELEMENT_IDENTITIES_ONLY).asBoolean(),
                "Direct list verification fixture must use element identities only.");
        Node list = readNode(requirePresent(spec, FixtureField.FULL_LIST));
        List<Node> directIdentities = new ArrayList<>();
        for (Node item : list.getItems()) {
            directIdentities.add(new Node().blueId(
                    DirectBlueIdCalculator.calculateBlueId(item)));
        }
        for (Node identity : directIdentities) {
            assertTrue(identity.isReferenceOnly(),
                    "Direct list manifest unexpectedly contains an element body.");
        }
        DirectNodeManifest manifest = DirectNodeManifest.complete(
                new Node().items(directIdentities));
        BlueOperationResult<List<String>> identities =
                manifest.orderedListElementIdentities();
        assertEquals(spec.get(FixtureField.EXPECTED_VERIFIED).asBoolean(),
                identities.isEstablished());
        assertEquals(list.getItems().size(),
                identities.requireEstablished().size());
        assertTextList(requirePresent(spec, FixtureField.EXPECTED_ELEMENT_BODY_REQUESTS),
                Collections.<String>emptyList());
    }

    private static void runRetrieveDirectList(JsonNode spec) {
        JsonNode optimization = requirePresent(spec, FixtureField.STORED_OPTIMIZATION);
        assertTrue(optimization.path("prefixFoldAvailable").asBoolean(false),
                "Fixture requires a stored prefix fold.");
        int known = optimization.path("appendedElementIdentities").asInt();
        List<Node> prefix = new ArrayList<>();
        for (int i = 0; i < known; i++) {
            prefix.add(new Node().value(i));
        }
        BlueOperationResult<List<String>> result =
                DirectNodeManifest.partial(new Node().items(prefix))
                        .orderedListElementIdentities();
        boolean requiresCompleteManifest =
                result.outcome() == BlueOperationOutcome.INCOMPLETE;
        assertEquals(spec.path(
                        FixtureField.EXPECTED_DIRECT_RESULT_STILL_CONTAINS_ALL_ORDERED_ELEMENT_IDENTITIES)
                        .asBoolean(false),
                requiresCompleteManifest);
    }

    private static void runRegistryNodeHashesToPublishedBlueId(JsonNode spec) {
        requireRegistryKind(spec);
        String key = requireText(spec, FixtureField.REGISTRY_KEY);
        String expected = requireText(spec, FixtureField.EXPECTED_PUBLISHED_BLUE_ID);
        BlueCoreTypeRegistry registry = BlueCoreTypeRegistry.INSTANCE;
        Node registryNode = registry.node(key);
        assertEquals(expected, DirectBlueIdCalculator.calculateBlueId(registryNode));
        assertEquals(expected, registry.blueId(key));
        assertEquals(expected, BlueLanguageConstants.CORE_TYPE_NAME_TO_BLUE_ID_MAP.get(key));
        if (spec.has(FixtureField.SEMANTIC_DESCRIPTION_IDENTITY_BEARING)) {
            Node withoutDescription = registryNode.clone().description(null);
            boolean identityBearing = !DirectBlueIdCalculator.calculateBlueId(withoutDescription)
                    .equals(DirectBlueIdCalculator.calculateBlueId(registryNode));
            assertEquals(spec.get(FixtureField.SEMANTIC_DESCRIPTION_IDENTITY_BEARING).asBoolean(),
                    identityBearing);
        }
    }

    private static void runChangingRegistryDescriptionChangesBlueId(JsonNode spec) {
        requireRegistryKind(spec);
        Node original = BlueCoreTypeRegistry.INSTANCE.node(
                requireText(spec, FixtureField.REGISTRY_KEY));
        Node mutated = original.clone();
        JsonNode mutation = requirePresent(spec, FixtureField.MUTATION);
        if (!BlueLanguageConstants.OBJECT_DESCRIPTION.equals(
                requireText(mutation, "field"))) {
            throw new IllegalArgumentException(
                    "Unsupported registry mutation field.");
        }
        mutated.description((mutated.getDescription() == null
                ? "" : mutated.getDescription())
                + requireText(mutation, "append"));
        boolean changed = !DirectBlueIdCalculator.calculateBlueId(original)
                .equals(DirectBlueIdCalculator.calculateBlueId(mutated));
        assertEquals(spec.get(FixtureField.EXPECT_BLUE_ID_CHANGED).asBoolean(), changed);
    }

    private static void runAssertViewPath(JsonNode spec) {
        Node document = readNode(requirePresent(spec, FixtureField.DOCUMENT));
        for (JsonNode assertion : requireArray(spec, FixtureField.ASSERTIONS)) {
            String path = requireString(assertion, FixtureField.PATH);
            Node selected = BlueViewPath.select(document, path);
            if (assertion.path("expectedRoot").asBoolean(false)) {
                assertNodeEquals(document, selected);
            }
            assertExpectedNodeIfPresent(assertion, "expectedNode", selected);
        }
    }

    private static void runLintPublishableDocumentation(JsonNode spec) {
        assertEquals(
                "Join tokens with the listed joiner and reject any case-sensitive match in publishableFiles.",
                requireText(spec, FixtureField.MATCH_RULE).replace('\n', ' '));
        for (JsonNode file : requireArray(spec, FixtureField.PUBLISHABLE_FILES)) {
            String content = readPublishableResource(file.asText());
            JsonNode headings = spec.get(FixtureField.REQUIRED_HEADINGS);
            if (headings != null) {
                for (JsonNode heading : headings) {
                    assertTrue(content.contains(heading.asText()),
                            "Missing required heading in " + file.asText());
                }
            }
            JsonNode forbidden = spec.get(FixtureField.FORBIDDEN_JOINED_TERMS);
            if (forbidden != null) {
                for (JsonNode entry : forbidden) {
                    StringBuilder term = new StringBuilder();
                    String joiner = requireText(entry, "joiner");
                    for (JsonNode token : requireArray(entry, "tokens")) {
                        if (term.length() > 0) term.append(joiner);
                        term.append(token.asText());
                    }
                    assertTrue(!content.contains(term.toString()),
                            "Forbidden term in " + file.asText()
                                    + ": " + term);
                }
            }
        }
    }

    private static void runSuiteAssertion(JsonNode spec,
                                          List<FixtureEntry> allFixtures) {
        List<String> prefixes = textValues(
                requirePresent(spec, FixtureField.REQUIRES_VECTOR_PREFIXES));
        int executed = 0;
        for (FixtureEntry entry : allFixtures) {
            boolean required = false;
            for (String prefix : prefixes) {
                required |= entry.id.startsWith(prefix + "_");
            }
            if (!required) continue;
            runFixture(entry, allFixtures);
            executed++;
        }
        assertTrue(executed > 0,
                "suiteAssertion did not select any behavior fixtures.");
        assertEquals("pass", requireText(spec, FixtureField.EXPECTED));
    }

    private static void assertResolutionExpectations(JsonNode spec,
                                                     Node actual,
                                                     LanguageFixtureRuntime blue,
                                                     Node source) {
        assertExpectedResolvedIfPresent(spec, FixtureField.EXPECTED_RESOLVED, actual, blue);
        if (spec.has(FixtureField.EXPECTED_RESOLVED_ITEMS)) {
            assertItemValues(spec.get(FixtureField.EXPECTED_RESOLVED_ITEMS),
                    actual.getItems());
        }
        if (spec.has(FixtureField.EXPECTED_MERGE_POLICY)) {
            String effective = actual.getMergePolicy() == null
                    ? BlueLanguageConstants.LIST_MERGE_POLICY_POSITIONAL
                    : actual.getMergePolicy();
            assertEquals(requireText(spec, FixtureField.EXPECTED_MERGE_POLICY), effective);
        }
        assertEffectiveTypes(singletonPathMap(
                spec, FixtureField.EXPECTED_EFFECTIVE_TYPE), actual);
        assertExpectedValues(spec.get(FixtureField.EXPECTED_VALUE), actual);
        if (spec.path(
                FixtureField.EXPECTED_SOURCE_REFERENCE_PRESERVED_BY_CANONICALIZATION)
                .asBoolean(false)) {
            Node canonical = blue.canonicalize(source);
            assertEquals(source.getContracts().getBlueId(),
                    canonical.getContracts().getBlueId());
            assertTrue(canonical.getContracts().isReferenceOnly(),
                    "Canonical contracts reference was not preserved.");
        }
    }

    private static JsonNode singletonPathMap(JsonNode spec, String field) {
        return spec.get(field);
    }

    private static Node sourceWithParent(JsonNode spec) {
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        attachBaselineType(source, spec);
        return source;
    }

    private static void attachBaselineType(Node source, JsonNode fixture) {
        Node baseline = null;
        if (fixture.has(FixtureField.PARENT)) {
            baseline = readNode(fixture.get(FixtureField.PARENT));
        } else if (fixture.has(FixtureField.BASE)) {
            baseline = readNode(fixture.get(FixtureField.BASE));
        } else if (fixture.has(FixtureField.FIELD_DECLARATION)) {
            baseline = readNode(fixture.get(FixtureField.FIELD_DECLARATION));
        }
        if (baseline == null) return;
        if (source.getType() == null) {
            source.type(baseline);
        } else if (source.getType().getType() == null) {
            source.getType().type(baseline);
        } else {
            Node cursor = source.getType();
            while (cursor.getType() != null) cursor = cursor.getType();
            cursor.type(baseline);
        }
    }

    private static void assertDemandedValue(JsonNode spec,
                                            BlueOperationResult<Node> result,
                                            BlueOperationLimits limits) {
        if (!spec.has(FixtureField.EXPECTED_VALUE)) return;
        Node selected = selectFirstDemand(result.requireEstablished(), limits);
        assertSemanticScalar(spec.get(FixtureField.EXPECTED_VALUE), selected);
    }

    private static Node selectFirstDemand(Node root,
                                          BlueOperationLimits limits) {
        String path = limits.demandedPaths().iterator().next();
        return BlueViewPath.select(root, path);
    }

    private static void assertExpectedValues(JsonNode expected, Node actual) {
        if (expected == null || expected.isNull()) return;
        if (expected.isObject()) {
            expected.fields().forEachRemaining(entry -> {
                Node selected = BlueViewPath.select(actual, entry.getKey());
                assertSemanticScalar(entry.getValue(), selected);
            });
        } else {
            assertSemanticScalar(expected, actual);
        }
    }

    private static void assertEffectiveTypes(JsonNode expected, Node actual) {
        if (expected == null || expected.isNull()) return;
        if (!expected.isObject()) {
            throw new AssertionError(
                    "Expected effective types must be a path map.");
        }
        expected.fields().forEachRemaining(entry -> {
            Node selected = BlueViewPath.select(actual, entry.getKey());
            assertEquals(entry.getValue().asText(),
                    coreTypeName(selected.getType()));
        });
    }

    private static String coreTypeName(Node type) {
        if (type == null) return null;
        String blueId = type.getBlueId();
        for (Map.Entry<String, String> entry :
                BlueLanguageConstants.CORE_TYPE_NAME_TO_BLUE_ID_MAP.entrySet()) {
            if (entry.getValue().equals(blueId)) return entry.getKey();
        }
        return blueId;
    }

    private static void assertSemanticScalar(JsonNode expected, Node actual) {
        if (actual == null) {
            throw new AssertionError("Expected semantic value but path was absent.");
        }
        Object value = actual.getValue();
        if (expected.isTextual()) {
            assertEquals(expected.asText(),
                    value == null ? null : value.toString());
        } else if (expected.isBoolean()) {
            assertEquals(expected.asBoolean(), value);
        } else if (expected.isIntegralNumber()) {
            assertEquals(expected.bigIntegerValue(),
                    value instanceof BigInteger
                            ? value
                            : new BigInteger(value.toString()));
        } else if (expected.isFloatingPointNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(
                    value instanceof BigDecimal
                            ? (BigDecimal) value
                            : new BigDecimal(value.toString())));
        } else {
            assertNodeEquals(readNode(expected), actual);
        }
    }

    private static void assertItemValues(JsonNode expected,
                                         List<Node> actual) {
        if (actual == null) {
            throw new AssertionError("Expected list items but actual was not a list.");
        }
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSemanticScalar(expected.get(i), actual.get(i));
        }
    }

    private static void assertOnlyAllowedMinimizationControls(
            Node minimized, Collection<String> allowed) {
        Set<String> controls = new LinkedHashSet<>();
        collectControls(minimized, controls);
        assertTrue(allowed.containsAll(controls),
                "Minimized overlay used undeclared controls: " + controls);
    }

    private static void collectControls(Node node, Set<String> controls) {
        if (node == null) return;
        if (node.getPreviousBlueId() != null) {
            controls.add(BlueLanguageConstants.LIST_CONTROL_PREVIOUS);
        }
        if (node.getPosition() != null) {
            controls.add(BlueLanguageConstants.LIST_CONTROL_POS);
        }
        if (node.getProperties() != null) {
            if (node.getProperties().containsKey(
                    BlueLanguageConstants.LIST_CONTROL_REPLACE)) {
                controls.add(BlueLanguageConstants.LIST_CONTROL_REPLACE);
            }
            for (Node child : node.getProperties().values()) {
                collectControls(child, controls);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) collectControls(child, controls);
        }
        collectControls(node.getType(), controls);
        collectControls(node.getContracts(), controls);
    }

    private static boolean containsListControls(Node node) {
        Set<String> controls = new HashSet<>();
        collectControls(node, controls);
        return !controls.isEmpty();
    }

    private static void assertOutcome(JsonNode spec,
                                      String field,
                                      BlueOperationOutcome actual) {
        String expected = requireText(spec, field);
        assertEquals(BlueOperationOutcome.valueOf(
                expected.toUpperCase(java.util.Locale.ROOT)), actual);
    }

    private static NodeProviderOutcome providerOutcome(String value) {
        return NodeProviderOutcome.valueOf(
                value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                        .replace("-", "_")
                        .toUpperCase(java.util.Locale.ROOT));
    }

    private static BlueOperationLimits operationLimits(JsonNode spec) {
        JsonNode limits = requirePresent(spec, FixtureField.LIMITS);
        List<String> demanded = new ArrayList<>();
        JsonNode paths = limits.get("demandedPaths");
        if (paths == null || !paths.isArray() || paths.size() == 0) {
            demanded.add("");
        } else {
            for (JsonNode path : paths) demanded.add(path.asText());
        }
        int max = limits.has(FixtureField.MAX_REFERENCE_EXPANSIONS)
                ? limits.get(FixtureField.MAX_REFERENCE_EXPANSIONS).asInt()
                : Integer.MAX_VALUE;
        return new BlueOperationLimits(demanded, max);
    }

    private static void assertEquivalentInputs(String actual,
                                               JsonNode inputs) {
        if (inputs == null || inputs.isNull()) return;
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                assertEquals(actual,
                        DirectBlueIdCalculator.calculateBlueId(readNode(input)));
            }
        } else {
            assertEquals(actual,
                    DirectBlueIdCalculator.calculateBlueId(readNode(inputs)));
        }
    }

    private static void assertDifferentInputs(String actual,
                                              JsonNode inputs) {
        if (inputs == null || inputs.isNull()) return;
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                assertTrue(!actual.equals(
                                DirectBlueIdCalculator.calculateBlueId(readNode(input))),
                        "Expected a different BlueId.");
            }
        } else {
            assertTrue(!actual.equals(
                            DirectBlueIdCalculator.calculateBlueId(readNode(inputs))),
                    "Expected a different BlueId.");
        }
    }

    private static void assertRequestedIds(JsonNode expected,
                                           List<String> actual,
                                           boolean requested) {
        if (expected == null || expected.isNull()) return;
        for (JsonNode blueId : expected) {
            assertEquals(requested, actual.contains(blueId.asText()));
        }
        if (requested) {
            assertTextList(expected, actual);
        }
    }

    private static void assertAllNodeEqual(List<Node> nodes) {
        for (int i = 1; i < nodes.size(); i++) {
            assertNodeEquals(nodes.get(0), nodes.get(i));
        }
    }

    private static <T> void assertAllEqual(List<T> values) {
        for (int i = 1; i < values.size(); i++) {
            assertEquals(values.get(0), values.get(i));
        }
    }

    private static void assertExpectedErrorCategory(
            JsonNode spec, String field, Throwable failure) {
        BlueLanguageErrorCategory expected =
                BlueLanguageErrorCategory.valueOf(requireText(spec, field));
        BlueLanguageErrorCategory actual =
                BlueLanguageErrorClassifier.classify(failure);
        assertEquals(expected, actual);
    }

    private static void assertExpectedNodeIfPresent(
            JsonNode spec, String field, Node actual) {
        if (spec.has(field)) {
            assertNodeEquals(readNode(spec.get(field)), actual);
        }
    }

    private static void assertExpectedResolvedIfPresent(
            JsonNode spec, String field, Node actual,
            LanguageFixtureRuntime blue) {
        if (spec.has(field)) {
            Node expected = blue.preprocess(readNode(spec.get(field)));
            assertNodeEquals(expected, actual);
        }
    }

    private static void assertNodeEquals(Node expected, Node actual) {
        JsonNode expectedTree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(expected));
        JsonNode actualTree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(actual));
        assertJsonNodeEquals(expectedTree, actualTree, "/");
    }

    private static void assertJsonNodeEquals(JsonNode expected,
                                             JsonNode actual,
                                             String path) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, "Node mismatch at " + path);
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            Set<String> expectedFields = new LinkedHashSet<>();
            expected.fieldNames().forEachRemaining(expectedFields::add);
            Set<String> actualFields = new LinkedHashSet<>();
            actual.fieldNames().forEachRemaining(actualFields::add);
            assertEquals(expectedFields, actualFields,
                    "Object field mismatch at " + path);
            for (String field : expectedFields) {
                assertJsonNodeEquals(expected.get(field), actual.get(field),
                        JsonPointer.append(path, field));
            }
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            assertEquals(expected.size(), actual.size(),
                    "Array length mismatch at " + path);
            for (int index = 0; index < expected.size(); index++) {
                assertJsonNodeEquals(expected.get(index), actual.get(index),
                        path + "/" + index);
            }
            return;
        }
        if (expected.isIntegralNumber() && actual.isIntegralNumber()) {
            assertEquals(expected.bigIntegerValue(), actual.bigIntegerValue(),
                    "Integer mismatch at " + path);
            return;
        }
        if (expected.isFloatingPointNumber() && actual.isFloatingPointNumber()) {
            assertEquals(0,
                    expected.decimalValue().compareTo(actual.decimalValue()),
                    "Double mismatch at " + path);
            return;
        }
        assertEquals(expected, actual, "Node mismatch at " + path);
    }

    private static ProviderContext providerContext(
            JsonNode spec, Map<String, NodeProviderResult> absentProviderFallback) {
        return providerContext(
                spec,
                absentProviderFallback,
                Collections.emptySet());
    }

    private static ProviderContext preprocessingProviderContext(
            JsonNode spec) {
        return providerContext(
                spec,
                null,
                preprocessingDirectiveBlueIds(spec));
    }

    private static ProviderContext providerContext(
            JsonNode spec,
            Map<String, NodeProviderResult> absentProviderFallback,
            Set<String> preprocessingDirectiveBlueIds) {
        Map<String, NodeProviderResult> entries = new LinkedHashMap<>();
        if (!spec.has(FixtureField.PROVIDER)) {
            entries.putAll(absentProviderFallback == null
                    ? globalProviderCatalog() : absentProviderFallback);
        } else {
            JsonNode provider = spec.get(FixtureField.PROVIDER);
            if (!provider.isArray()) {
                throw new IllegalArgumentException(
                        "Fixture provider must be a list.");
            }
            for (JsonNode entry : provider) {
                addProviderEntry(
                        entries,
                        entry,
                        preprocessingDirectiveBlueIds);
            }
        }
        return providerContextWithoutFixtureProvider(entries);
    }

    private static Map<String, String> preprocessingAliases(
            JsonNode spec) {
        JsonNode declared = spec.get(
                FixtureField.PREPROCESSING_ALIASES);
        if (declared == null) {
            return Collections.emptyMap();
        }
        if (!declared.isObject()) {
            throw new IllegalArgumentException(
                    "Fixture preprocessingAliases must be an object.");
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        declared.fields().forEachRemaining(entry -> {
            if (entry.getKey().isEmpty()
                    || !entry.getValue().isTextual()) {
                throw new IllegalArgumentException(
                        "Fixture preprocessingAliases must map non-empty names to exact BlueIds.");
            }
            aliases.put(
                    entry.getKey(),
                    BlueIds.requirePlainBlueId(
                            entry.getValue().asText(),
                            FixtureField.PREPROCESSING_ALIASES
                                    + "." + entry.getKey()));
        });
        return Collections.unmodifiableMap(aliases);
    }

    private static Set<String> preprocessingDirectiveBlueIds(
            JsonNode spec) {
        Set<String> result = new LinkedHashSet<>(
                preprocessingAliases(spec).values());
        addPreprocessingDirectiveBlueId(
                result, spec.get(FixtureField.SOURCE));
        addPreprocessingDirectiveBlueId(
                result, spec.get(FixtureField.ALSO_EQUIVALENT_TO));
        return Collections.unmodifiableSet(result);
    }

    private static void addPreprocessingDirectiveBlueId(
            Set<String> destination,
            JsonNode source) {
        if (source == null || !source.isObject()) {
            return;
        }
        JsonNode directive = source.get(BlueLanguageConstants.OBJECT_BLUE);
        if (directive == null || !directive.isObject()) {
            return;
        }
        JsonNode blueId = directive.get(BlueLanguageConstants.OBJECT_BLUE_ID);
        if (blueId != null && blueId.isTextual()) {
            destination.add(BlueIds.requirePlainBlueId(
                    blueId.asText(),
                    BlueLanguageConstants.OBJECT_BLUE + "."
                            + BlueLanguageConstants.OBJECT_BLUE_ID));
        }
    }

    /**
     * The published type-cycle vector uses readable symbolic IDs. Convert any
     * closed symbolic type-reference graph into a verified cyclic set without
     * keying behavior to the fixture ID or to hard-coded replacement values.
     */
    private static SymbolicTypeCycle symbolicTypeCycle(JsonNode spec) {
        JsonNode sourceNode = spec.get(FixtureField.SOURCE);
        JsonNode providerNode = spec.get(FixtureField.PROVIDER);
        if (sourceNode == null || providerNode == null || !providerNode.isArray()) {
            return null;
        }
        Node source = readNode(sourceNode);
        if (!source.isReferenceOnly() || providerNode.size() < 2) {
            return null;
        }

        List<String> symbolicIds = new ArrayList<>();
        List<Node> documents = new ArrayList<>();
        Map<String, Integer> indexBySymbol = new LinkedHashMap<>();
        for (JsonNode entry : providerNode) {
            if (entry.has(FixtureField.OUTCOME)) return null;
            String symbolic = entry.has(FixtureField.REQUESTED_BLUE_ID)
                    ? requireText(entry, FixtureField.REQUESTED_BLUE_ID)
                    : requireText(entry, BlueLanguageConstants.OBJECT_BLUE_ID);
            JsonNode returned = entry.has(FixtureField.NODE)
                    ? entry.get(FixtureField.NODE) : entry.get(FixtureField.RETURNED_NODE);
            if (returned == null) return null;
            Node document = readNode(returned);
            if (document.getType() == null
                    || !document.getType().isReferenceOnly()) {
                return null;
            }
            indexBySymbol.put(symbolic, symbolicIds.size());
            symbolicIds.add(symbolic);
            documents.add(document);
        }
        Integer rootIndex = indexBySymbol.get(source.getBlueId());
        if (rootIndex == null) return null;

        List<Node> placeholders = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            Node placeholder = documents.get(index).clone()
                    .name("generated symbolic cycle member " + index);
            Integer target = indexBySymbol.get(
                    placeholder.getType().getBlueId());
            if (target == null) return null;
            placeholder.getType().blueId(
                    BlueIds.indexedThisPlaceholder(target));
            placeholders.add(placeholder);
        }
        List<String> calculated =
                CircularSetIdentityCalculator.calculateCircularSetBlueIds(placeholders);
        Map<String, NodeProviderResult> verifiedEntries = new LinkedHashMap<>();
        List<Node> materialized = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            Node document = documents.get(index).clone()
                    .name("generated symbolic cycle member " + index);
            int target = indexBySymbol.get(document.getType().getBlueId());
            document.getType().blueId(calculated.get(target));
            materialized.add(document);
            verifiedEntries.put(calculated.get(index),
                    NodeProviderResult.found(
                            Collections.singletonList(document)));
        }
        return new SymbolicTypeCycle(
                materialized.get(rootIndex),
                new VerifiedCyclicFixtureProvider(
                        verifiedEntries, placeholders));
    }

    private static ProviderContext providerContextWithoutFixtureProvider(
            Map<String, NodeProviderResult> entries) {
        FixtureProvider provider = new FixtureProvider(entries);
        return new ProviderContext(provider);
    }

    private static void addProviderEntry(
            Map<String, NodeProviderResult> entries, JsonNode entry) {
        addProviderEntry(entries, entry, Collections.emptySet());
    }

    private static void addProviderEntry(
            Map<String, NodeProviderResult> entries,
            JsonNode entry,
            Set<String> preprocessingDirectiveBlueIds) {
        String requested = entry.has(FixtureField.REQUESTED_BLUE_ID)
                ? entry.get(FixtureField.REQUESTED_BLUE_ID).asText()
                : requireText(entry, BlueLanguageConstants.OBJECT_BLUE_ID);
        if (entry.has(FixtureField.OUTCOME)) {
            String outcome = entry.get(FixtureField.OUTCOME).asText();
            if ("NotFound".equals(outcome)) {
                entries.put(requested, NodeProviderResult.notFound());
            } else if ("Unavailable".equals(outcome)) {
                entries.put(requested,
                        NodeProviderResult.unavailable(
                                "Fixture provider unavailable for " + requested));
            } else if ("InvalidEvidence".equals(outcome)) {
                entries.put(requested,
                        NodeProviderResult.invalidEvidence(
                                "Fixture provider returned invalid evidence for "
                                        + requested));
            } else {
                throw new IllegalArgumentException(
                        "Unsupported provider outcome: " + outcome);
            }
            return;
        }
        JsonNode node = entry.has(FixtureField.RETURNED_NODE)
                ? entry.get(FixtureField.RETURNED_NODE) : entry.get(FixtureField.NODE);
        if (node == null) {
            throw new IllegalArgumentException(
                    "Provider entry requires node/returnedNode or outcome.");
        }
        Node content = preprocessingDirectiveBlueIds.contains(requested)
                ? NodeDeserializer.parsePreprocessingDirective(node)
                : readNode(node);
        entries.put(requested, NodeProviderResult.found(
                Collections.singletonList(content)));
    }

    private static volatile Map<String, NodeProviderResult> providerCatalog;

    private static Map<String, NodeProviderResult> globalProviderCatalog() {
        Map<String, NodeProviderResult> current = providerCatalog;
        if (current != null) return current;
        synchronized (BlueConformanceSuiteRunner.class) {
            if (providerCatalog != null) return providerCatalog;
            Map<String, NodeProviderResult> discovered = new LinkedHashMap<>();
            for (FixtureEntry fixture : fixtureEntries()) {
                JsonNode spec = readYamlResource(FIXTURE_ROOT + fixture.path);
                JsonNode provider = spec.get(FixtureField.PROVIDER);
                if (provider == null || !provider.isArray()) continue;
                for (JsonNode entry : provider) {
                    if (entry.has(FixtureField.OUTCOME)) continue;
                    String requested = entry.has(FixtureField.REQUESTED_BLUE_ID)
                            ? entry.get(FixtureField.REQUESTED_BLUE_ID).asText()
                            : null;
                    JsonNode node = entry.has(FixtureField.NODE)
                            ? entry.get(FixtureField.NODE) : entry.get(FixtureField.RETURNED_NODE);
                    if (requested == null || node == null) continue;
                    try {
                        Node content = readNode(node);
                        if (requested.equals(
                                DirectBlueIdCalculator.calculateBlueId(content))) {
                            discovered.put(requested,
                                    NodeProviderResult.found(
                                            Collections.singletonList(content)));
                        }
                    } catch (RuntimeException invalidDirectInput) {
                        // Source-mode and deliberately invalid evidence are not
                        // eligible for the package-wide verified catalog.
                    }
                }
            }
            providerCatalog = Collections.unmodifiableMap(discovered);
            return providerCatalog;
        }
    }

    private static List<FixtureEntry> fixtureEntries() {
        JsonNode manifest = readYamlResource(MANIFEST_RESOURCE);
        assertEquals(BlueConformanceReport.FIXTURE_PACKAGE_IDENTITY,
                manifest.path("packageIdentity").asText());
        assertEquals(EXPECTED_BEHAVIOR_FIXTURE_COUNT,
                manifest.path("behaviorFixtureCount").asInt());
        assertEquals(EXPECTED_BEHAVIOR_FIXTURE_COUNT,
                BlueConformanceReport.requiredFixtureIdsForBlueLanguage10().size());
        JsonNode files = requireArray(manifest, "files");
        List<FixtureEntry> result = new ArrayList<>();
        String previousPath = null;
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode file : files) {
            String path = requireText(file, FixtureField.PATH);
            validateRelativePath(path);
            if (previousPath != null && previousPath.compareTo(path) >= 0) {
                throw new IllegalStateException(
                        "Fixture manifest files must be sorted by path.");
            }
            previousPath = path;
            byte[] bytes = readResourceBytes(FIXTURE_ROOT + path);
            assertEquals(file.path("bytes").asLong(),
                    (long) normalizeLineEndings(bytes).length);
            assertEquals(requireText(file, "sha256"),
                    sha256Hex(normalizeLineEndings(bytes)));
            String role = requireText(file, "role");
            if ("support".equals(role)) continue;
            if (!"behavior-fixture".equals(role)) {
                throw new IllegalStateException(
                        "Unknown Language fixture file role: " + role);
            }
            JsonNode fixture = UncheckedObjectMapper.YAML_MAPPER.readTree(
                    new String(bytes, StandardCharsets.UTF_8));
            validateFixtureMetadata(fixture);
            String id = requireText(fixture, FixtureField.ID);
            if (!ids.add(id)) {
                throw new IllegalStateException(
                        "Duplicate Language fixture id: " + id);
            }
            result.add(new FixtureEntry(id,
                    BlueFixtureCategory.fromLabel(
                            requireText(fixture, FixtureField.CATEGORY)), path));
        }
        assertEquals(EXPECTED_BEHAVIOR_FIXTURE_COUNT, result.size());
        return Collections.unmodifiableList(result);
    }

    private static void validateFixtureMetadata(JsonNode spec) {
        if (spec == null || !spec.isObject()) {
            throw new IllegalArgumentException(
                    "Language fixture must be an object.");
        }
        spec.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIXTURE_FIELDS.contains(field)) {
                throw new IllegalArgumentException(
                        "Unknown Language fixture field: " + field);
            }
        });
        requireText(spec, FixtureField.ID);
        BlueFixtureCategory.fromLabel(requireText(spec, FixtureField.CATEGORY));
        String operation = requireText(spec, FixtureField.OPERATION);
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException(
                    "Unsupported fixture operation: " + operation);
        }
        if (spec.has("profile")) {
            throw new IllegalArgumentException(
                    "Language fixtures use category, not profile.");
        }
        if (spec.has(FixtureField.EXPECTED_ERROR_CATEGORY)) {
            BlueLanguageErrorCategory.valueOf(
                    requireText(spec, FixtureField.EXPECTED_ERROR_CATEGORY));
        }
        boolean hasAssertion = spec.path(FixtureField.EXPECT_ERROR).asBoolean(false);
        java.util.Iterator<String> fields = spec.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            hasAssertion |= field.startsWith(FixtureField.EXPECTED)
                    || field.startsWith("also")
                    || FixtureField.ASSERTIONS.equals(field)
                    || FixtureField.VARIANTS.equals(field)
                    || FixtureField.REQUIRED_HEADINGS.equals(field)
                    || FixtureField.FORBIDDEN_JOINED_TERMS.equals(field)
                    || FixtureField.EXPECT_BLUE_ID_CHANGED.equals(field);
        }
        if (!hasAssertion) {
            throw new IllegalArgumentException(
                    "Fixture has no expected result assertion: "
                            + requireText(spec, FixtureField.ID));
        }
    }

    private static BlueConformanceFailure failure(
            FixtureEntry fixture, Throwable throwable) {
        String operation = null;
        try {
            operation = requireText(
                    readYamlResource(FIXTURE_ROOT + fixture.path), FixtureField.OPERATION);
        } catch (RuntimeException ignored) {
            // Keep manifest-level failure details.
        }
        return new BlueConformanceFailure(
                fixture.id, fixture.category, operation,
                throwable.getClass().getName(), throwable.getMessage(),
                BlueLanguageErrorClassifier.classify(throwable));
    }

    private static void requireRegistryKind(JsonNode spec) {
        assertEquals("Blue Language core type registry",
                requireText(spec, FixtureField.REGISTRY_KIND));
    }

    private static JsonNode readYamlResource(String resource) {
        return UncheckedObjectMapper.YAML_MAPPER.readTree(
                new String(readResourceBytes(resource), StandardCharsets.UTF_8));
    }

    private static byte[] readResourceBytes(String resource) {
        try (InputStream input =
                     BlueConformanceSuiteRunner.class.getClassLoader()
                             .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "Missing fixture resource: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Unable to read fixture resource: " + resource, failure);
        }
    }

    private static String readPublishableResource(String path) {
        validateRelativePath(path);
        String resource;
        if ("specifications/language/1.0/spec.md".equals(path)) {
            resource = "language/1.0/spec.md";
        } else if (path.startsWith("specifications/")) {
            resource = path.substring("specifications/".length());
        } else {
            resource = path;
        }
        return new String(readResourceBytes(resource), StandardCharsets.UTF_8);
    }

    private static Node readNode(JsonNode value) {
        return UncheckedObjectMapper.YAML_MAPPER.treeToValue(value, Node.class);
    }

    private static JsonNode requirePresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Fixture is missing required field: " + field);
        }
        return value;
    }

    private static JsonNode requireArray(JsonNode node, String field) {
        JsonNode value = requirePresent(node, field);
        if (!value.isArray()) {
            throw new IllegalArgumentException(
                    "Fixture field must be a list: " + field);
        }
        return value;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = requirePresent(node, field);
        if (!value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException(
                    "Fixture field must be non-empty text: " + field);
        }
        return value.asText();
    }

    private static String requireString(JsonNode node, String field) {
        JsonNode value = requirePresent(node, field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(
                    "Fixture field must be text: " + field);
        }
        return value.asText();
    }

    private static List<String> textValues(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("Expected a text list.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : array) result.add(value.asText());
        return result;
    }

    private static void assertTextList(JsonNode expected,
                                       List<String> actual) {
        assertEquals(textValues(expected), actual);
    }

    private static void assertTextSet(JsonNode expected,
                                      Set<String> actual) {
        assertEquals(new LinkedHashSet<>(textValues(expected)),
                new LinkedHashSet<>(actual));
    }

    private static void validateRelativePath(String path) {
        if (path.startsWith("/") || path.contains("\\")
                || Arrays.asList(path.split("/", -1)).contains("..")) {
            throw new IllegalStateException(
                    "Unsafe fixture manifest path: " + path);
        }
    }

    private static byte[] normalizeLineEndings(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    private static void assertEquals(Object expected,
                                     Object actual,
                                     String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    (message == null ? "" : message + ": ")
                            + "Expected " + expected + " but was " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * Field vocabulary for the conformance-only transformation registry.
     */
    private static final class FixtureTransformationField {

        private static final String REGISTRY = "registry";
        private static final String REGISTRY_KIND = "registryKind";
        private static final String SPECIFICATION_VERSION =
                "specificationVersion";
        private static final String ENTRIES = "entries";
        private static final String KEY = "key";
        private static final String FROM = "from";
        private static final String TO = "to";
        private static final String FIELD = "field";
        private static final String SUFFIX = "suffix";

        private FixtureTransformationField() {
        }
    }

    /**
     * Exact manifest keys and paths for the three fixture-only types.
     */
    private static final class FixtureTransformationDefinition {

        private static final String REGISTRY_NAME =
                "blue-language-conformance-preprocessing-transformations";
        private static final String REGISTRY_KIND =
                "fixture-only-transformation-type";
        private static final String SPECIFICATION_VERSION = "1.0";
        private static final String RENAME_ROOT_FIELD_KEY =
                "RenameRootFieldTransformation";
        private static final String RENAME_ROOT_FIELD_PATH =
                "RenameRootFieldTransformation.blue";
        private static final String SET_ROOT_FIELD_KEY =
                "SetRootFieldTransformation";
        private static final String SET_ROOT_FIELD_PATH =
                "SetRootFieldTransformation.blue";
        private static final String APPEND_ROOT_TEXT_KEY =
                "AppendRootTextTransformation";
        private static final String APPEND_ROOT_TEXT_PATH =
                "AppendRootTextTransformation.blue";
        private static final int ENTRY_COUNT = 3;

        private FixtureTransformationDefinition() {
        }
    }

    /**
     * Closed transformation registry loaded only by the fixture harness.
     */
    private static final class FixtureTransformationRegistry
            implements TransformationProcessorProvider {

        private static final FixtureTransformationRegistry INSTANCE =
                new FixtureTransformationRegistry();

        private final Map<String, FixtureTransformationFactory>
                factoriesByBlueId;

        private FixtureTransformationRegistry() {
            Map<String, FixtureTransformationFactory> factoriesByKey =
                    new LinkedHashMap<>();
            factoriesByKey.put(
                    FixtureTransformationDefinition.RENAME_ROOT_FIELD_KEY,
                    RenameRootFieldProcessor::new);
            factoriesByKey.put(
                    FixtureTransformationDefinition.SET_ROOT_FIELD_KEY,
                    SetRootFieldProcessor::new);
            factoriesByKey.put(
                    FixtureTransformationDefinition.APPEND_ROOT_TEXT_KEY,
                    AppendRootTextProcessor::new);

            Map<String, String> pathsByKey = new LinkedHashMap<>();
            pathsByKey.put(
                    FixtureTransformationDefinition.RENAME_ROOT_FIELD_KEY,
                    FixtureTransformationDefinition.RENAME_ROOT_FIELD_PATH);
            pathsByKey.put(
                    FixtureTransformationDefinition.SET_ROOT_FIELD_KEY,
                    FixtureTransformationDefinition.SET_ROOT_FIELD_PATH);
            pathsByKey.put(
                    FixtureTransformationDefinition.APPEND_ROOT_TEXT_KEY,
                    FixtureTransformationDefinition.APPEND_ROOT_TEXT_PATH);

            JsonNode manifest = readYamlResource(
                    PREPROCESSING_REGISTRY_MANIFEST_RESOURCE);
            assertEquals(
                    FixtureTransformationDefinition.REGISTRY_NAME,
                    requireText(
                            manifest,
                            FixtureTransformationField.REGISTRY));
            assertEquals(
                    FixtureTransformationDefinition.REGISTRY_KIND,
                    requireText(
                            manifest,
                            FixtureTransformationField.REGISTRY_KIND));
            assertEquals(
                    FixtureTransformationDefinition.SPECIFICATION_VERSION,
                    requireText(
                            manifest,
                            FixtureTransformationField.SPECIFICATION_VERSION));

            JsonNode entries = requireArray(
                    manifest, FixtureTransformationField.ENTRIES);
            assertEquals(
                    FixtureTransformationDefinition.ENTRY_COUNT,
                    entries.size());
            Map<String, FixtureTransformationFactory> discovered =
                    new LinkedHashMap<>();
            Set<String> discoveredKeys = new LinkedHashSet<>();
            for (JsonNode entry : entries) {
                String key = requireText(
                        entry, FixtureTransformationField.KEY);
                FixtureTransformationFactory factory =
                        factoriesByKey.get(key);
                if (factory == null || !discoveredKeys.add(key)) {
                    throw new IllegalStateException(
                            "Unknown or duplicate fixture transformation key: "
                                    + key);
                }
                String path = requireText(entry, FixtureField.PATH);
                assertEquals(pathsByKey.get(key), path);
                validateRelativePath(path);
                String declaredBlueId = BlueIds.requirePlainBlueId(
                        requireText(entry, BlueLanguageConstants.OBJECT_BLUE_ID),
                        "preprocessing.registry." + key);
                Node typeDefinition = readNode(readYamlResource(
                        PREPROCESSING_REGISTRY_ROOT + path));
                assertEquals(
                        declaredBlueId,
                        DirectBlueIdCalculator.calculateBlueId(typeDefinition));
                if (discovered.put(declaredBlueId, factory) != null) {
                    throw new IllegalStateException(
                            "Duplicate fixture transformation BlueId: "
                                    + declaredBlueId);
                }
            }
            assertEquals(factoriesByKey.keySet(), discoveredKeys);
            this.factoriesByBlueId = Collections.unmodifiableMap(
                    discovered);
        }

        @Override
        public Optional<TransformationProcessor> getProcessor(
                Node transformation) {
            if (transformation == null
                    || transformation.getType() == null
                    || !transformation.getType().isReferenceOnly()) {
                return Optional.empty();
            }
            return processorFor(
                    transformation.getType().getBlueId(),
                    transformation);
        }

        @Override
        public Optional<TransformationProcessor> processorFor(
                String exactTypeBlueId,
                Node exactTransformationNode) {
            FixtureTransformationFactory factory =
                    factoriesByBlueId.get(exactTypeBlueId);
            if (factory == null) {
                return Optional.empty();
            }
            return Optional.of(factory.create(
                    exactTransformationNode.clone()));
        }
    }

    /** Creates one immutable fixture transformation processor. */
    private interface FixtureTransformationFactory {

        TransformationProcessor create(Node configuration);
    }

    /** Moves one existing direct root field to an absent destination. */
    private static final class RenameRootFieldProcessor
            implements TransformationProcessor {

        private final String from;
        private final String to;

        private RenameRootFieldProcessor(Node configuration) {
            validateFixtureTransformationConfiguration(
                    configuration,
                    immutableSet(
                            FixtureTransformationField.FROM,
                            FixtureTransformationField.TO));
            this.from = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.FROM),
                    FixtureTransformationField.FROM);
            this.to = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.TO),
                    FixtureTransformationField.TO);
        }

        @Override
        public Node process(Node document) {
            Node result = requireObjectSourceRoot(document);
            if (!hasDirectRootField(result, from)) {
                throw new IllegalArgumentException(
                        "Reserved fixture transformation source field is absent: "
                                + from);
            }
            if (hasDirectRootField(result, to)) {
                throw new IllegalArgumentException(
                        "Reserved fixture transformation destination field already exists: "
                                + to);
            }
            Node value = readDirectRootField(result, from);
            removeDirectRootField(result, from);
            writeDirectRootField(result, to, value);
            return result;
        }
    }

    /** Writes a defensive configuration-node copy to one direct root field. */
    private static final class SetRootFieldProcessor
            implements TransformationProcessor {

        private final String field;
        private final Node value;

        private SetRootFieldProcessor(Node configuration) {
            validateFixtureTransformationConfiguration(
                    configuration,
                    immutableSet(
                            FixtureTransformationField.FIELD,
                            BlueLanguageConstants.OBJECT_VALUE));
            this.field = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.FIELD),
                    FixtureTransformationField.FIELD);
            this.value = configuration.getProperties().get(
                    BlueLanguageConstants.OBJECT_VALUE).clone();
        }

        @Override
        public Node process(Node document) {
            Node result = requireObjectSourceRoot(document);
            writeDirectRootField(result, field, value.clone());
            return result;
        }
    }

    /** Appends one configured suffix to an existing direct Text field. */
    private static final class AppendRootTextProcessor
            implements TransformationProcessor {

        private final String field;
        private final String suffix;

        private AppendRootTextProcessor(Node configuration) {
            validateFixtureTransformationConfiguration(
                    configuration,
                    immutableSet(
                            FixtureTransformationField.FIELD,
                            FixtureTransformationField.SUFFIX));
            this.field = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.FIELD),
                    FixtureTransformationField.FIELD);
            this.suffix = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.SUFFIX),
                    FixtureTransformationField.SUFFIX);
        }

        @Override
        public Node process(Node document) {
            Node result = requireObjectSourceRoot(document);
            if (!hasDirectRootField(result, field)) {
                throw new IllegalArgumentException(
                        "Reserved fixture transformation Text field is absent: "
                                + field);
            }
            Node current = readDirectRootField(result, field);
            String text = requireTextScalar(current, field);
            current.value(text + suffix);
            writeDirectRootField(result, field, current);
            return result;
        }
    }

    private static void validateFixtureTransformationConfiguration(
            Node configuration,
            Set<String> expectedFields) {
        if (configuration == null
                || configuration.getType() == null
                || !configuration.getType().isReferenceOnly()
                || configuration.getName() != null
                || configuration.getDescription() != null
                || configuration.getItemType() != null
                || configuration.getKeyType() != null
                || configuration.getValueType() != null
                || configuration.getRawValue() != null
                || configuration.getItems() != null
                || configuration.getContracts() != null
                || configuration.getBlueId() != null
                || configuration.getSchema() != null
                || configuration.getMergePolicy() != null
                || configuration.getPreviousBlueId() != null
                || configuration.getPosition() != null
                || configuration.getBlue() != null
                || configuration.getProperties() == null
                || !expectedFields.equals(
                        configuration.getProperties().keySet())) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation configuration has an invalid shape.");
        }
    }

    private static Node requireObjectSourceRoot(Node document) {
        if (document == null
                || document.getRawValue() != null
                || document.getItems() != null
                || document.getBlueId() != null
                || document.getPreviousBlueId() != null
                || document.getPosition() != null) {
            throw new IllegalArgumentException(
                    "Reserved preprocessing transformation requires an object Source root.");
        }
        return document.clone();
    }

    private static String requireTextScalar(
            Node node,
            String role) {
        if (node == null
                || !(node.getRawValue() instanceof String)
                || node.getItems() != null
                || node.getProperties() != null
                || node.getBlueId() != null
                || node.getBlue() != null
                || !hasTextCompatibleType(node.getType())) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation " + role
                            + " must be Text.");
        }
        return (String) node.getRawValue();
    }

    private static boolean hasTextCompatibleType(Node type) {
        if (type == null) {
            return true;
        }
        if (type.isReferenceOnly()) {
            return BlueLanguageConstants.TEXT_TYPE_BLUE_ID.equals(
                    type.getBlueId());
        }
        return BlueLanguageConstants.TEXT_TYPE.equals(type.getRawValue())
                && type.getItems() == null
                && type.getProperties() == null
                && type.getBlueId() == null;
    }

    private static boolean hasDirectRootField(
            Node root,
            String field) {
        switch (field) {
            case BlueLanguageConstants.OBJECT_NAME:
                return root.getName() != null;
            case BlueLanguageConstants.OBJECT_DESCRIPTION:
                return root.getDescription() != null;
            case BlueLanguageConstants.OBJECT_TYPE:
                return root.getType() != null;
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                return root.getItemType() != null;
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                return root.getKeyType() != null;
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                return root.getValueType() != null;
            case BlueLanguageConstants.OBJECT_VALUE:
                return root.getRawValue() != null;
            case BlueLanguageConstants.OBJECT_ITEMS:
                return root.getItems() != null;
            case BlueLanguageConstants.OBJECT_BLUE_ID:
                return root.getBlueId() != null;
            case BlueLanguageConstants.OBJECT_BLUE:
                return root.getBlue() != null;
            case BlueLanguageConstants.OBJECT_SCHEMA:
                return root.getSchema() != null;
            case BlueLanguageConstants.OBJECT_MERGE_POLICY:
                return root.getMergePolicy() != null;
            case BlueLanguageConstants.OBJECT_CONTRACTS:
                return root.getContracts() != null;
            case BlueLanguageConstants.LIST_CONTROL_PREVIOUS:
                return root.getPreviousBlueId() != null;
            case BlueLanguageConstants.LIST_CONTROL_POS:
                return root.getPosition() != null;
            default:
                return root.getProperties() != null
                        && root.getProperties().containsKey(field);
        }
    }

    private static Node readDirectRootField(
            Node root,
            String field) {
        switch (field) {
            case BlueLanguageConstants.OBJECT_NAME:
                return inlineScalar(root.getName());
            case BlueLanguageConstants.OBJECT_DESCRIPTION:
                return inlineScalar(root.getDescription());
            case BlueLanguageConstants.OBJECT_TYPE:
                return cloneNode(root.getType());
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                return cloneNode(root.getItemType());
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                return cloneNode(root.getKeyType());
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                return cloneNode(root.getValueType());
            case BlueLanguageConstants.OBJECT_VALUE:
                return inlineScalar(root.getRawValue());
            case BlueLanguageConstants.OBJECT_ITEMS:
                return new Node().items(cloneNodes(root.getItems()));
            case BlueLanguageConstants.OBJECT_BLUE_ID:
                return inlineScalar(root.getBlueId());
            case BlueLanguageConstants.OBJECT_BLUE:
                return cloneNode(root.getBlue());
            case BlueLanguageConstants.OBJECT_SCHEMA:
                return new Node().schema(root.getSchema().clone());
            case BlueLanguageConstants.OBJECT_MERGE_POLICY:
                return inlineScalar(root.getMergePolicy());
            case BlueLanguageConstants.OBJECT_CONTRACTS:
                return cloneNode(root.getContracts());
            case BlueLanguageConstants.LIST_CONTROL_PREVIOUS:
                return new Node().blueId(root.getPreviousBlueId());
            case BlueLanguageConstants.LIST_CONTROL_POS:
                return inlineScalar(BigInteger.valueOf(
                        root.getPosition()));
            default:
                return cloneNode(root.getProperties().get(field));
        }
    }

    private static void removeDirectRootField(
            Node root,
            String field) {
        switch (field) {
            case BlueLanguageConstants.OBJECT_NAME:
                root.name(null);
                return;
            case BlueLanguageConstants.OBJECT_DESCRIPTION:
                root.description(null);
                return;
            case BlueLanguageConstants.OBJECT_TYPE:
                root.type((Node) null);
                return;
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                root.itemType((Node) null);
                return;
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                root.keyType((Node) null);
                return;
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                root.valueType((Node) null);
                return;
            case BlueLanguageConstants.OBJECT_VALUE:
                root.value((Object) null);
                return;
            case BlueLanguageConstants.OBJECT_ITEMS:
                root.items((List<Node>) null);
                return;
            case BlueLanguageConstants.OBJECT_BLUE_ID:
                root.blueId(null);
                return;
            case BlueLanguageConstants.OBJECT_BLUE:
                root.blue(null);
                return;
            case BlueLanguageConstants.OBJECT_SCHEMA:
                root.schema(null);
                return;
            case BlueLanguageConstants.OBJECT_MERGE_POLICY:
                root.mergePolicy(null);
                return;
            case BlueLanguageConstants.OBJECT_CONTRACTS:
                root.contracts(null);
                return;
            case BlueLanguageConstants.LIST_CONTROL_PREVIOUS:
                root.previousBlueId(null);
                return;
            case BlueLanguageConstants.LIST_CONTROL_POS:
                root.position(null);
                return;
            default:
                Map<String, Node> properties = new LinkedHashMap<>(
                        root.getProperties());
                properties.remove(field);
                root.properties(properties.isEmpty()
                        ? null : properties);
        }
    }

    private static void writeDirectRootField(
            Node root,
            String field,
            Node value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation field value is missing: "
                            + field);
        }
        switch (field) {
            case BlueLanguageConstants.OBJECT_NAME:
                root.name(requireTextScalar(value, field));
                return;
            case BlueLanguageConstants.OBJECT_DESCRIPTION:
                root.description(requireTextScalar(value, field));
                return;
            case BlueLanguageConstants.OBJECT_TYPE:
                root.type(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                root.itemType(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                root.keyType(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                root.valueType(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_VALUE:
                requireScalarPayload(value, field);
                root.value(value.getRawValue());
                return;
            case BlueLanguageConstants.OBJECT_ITEMS:
                if (value.getItems() == null) {
                    throw new IllegalArgumentException(
                            "Reserved fixture transformation items value must be a list.");
                }
                root.items(cloneNodes(value.getItems()));
                return;
            case BlueLanguageConstants.OBJECT_BLUE_ID:
                root.blueId(requireTextScalar(value, field));
                return;
            case BlueLanguageConstants.OBJECT_BLUE:
                root.blue(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_SCHEMA:
                if (value.getSchema() == null) {
                    throw new IllegalArgumentException(
                            "Reserved fixture transformation schema value must be a schema.");
                }
                root.schema(value.getSchema().clone());
                return;
            case BlueLanguageConstants.OBJECT_MERGE_POLICY:
                root.mergePolicy(requireTextScalar(value, field));
                return;
            case BlueLanguageConstants.OBJECT_CONTRACTS:
                root.contracts(value.clone());
                return;
            case BlueLanguageConstants.LIST_CONTROL_PREVIOUS:
                if (!value.isReferenceOnly()) {
                    throw new IllegalArgumentException(
                            "Reserved fixture transformation $previous value must be a pure reference.");
                }
                root.previousBlueId(value.getBlueId());
                return;
            case BlueLanguageConstants.LIST_CONTROL_POS:
                root.position(requireNonNegativeInteger(value, field));
                return;
            default:
                root.properties(field, value.clone());
        }
    }

    private static void requireScalarPayload(
            Node value,
            String field) {
        if (value.getRawValue() == null
                || value.getItems() != null
                || value.getProperties() != null
                || value.getBlueId() != null) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation " + field
                            + " value must be a scalar.");
        }
    }

    private static int requireNonNegativeInteger(
            Node value,
            String field) {
        requireScalarPayload(value, field);
        if (!(value.getRawValue() instanceof BigInteger)) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation " + field
                            + " value must be an integer.");
        }
        BigInteger integer = (BigInteger) value.getRawValue();
        if (integer.signum() < 0
                || integer.compareTo(
                        BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation " + field
                            + " value is outside the supported range.");
        }
        return integer.intValue();
    }

    private static Node inlineScalar(Object value) {
        return new Node().value(value).inlineValue(true);
    }

    private static Node cloneNode(Node node) {
        return node == null ? null : node.clone();
    }

    private static List<Node> cloneNodes(List<Node> nodes) {
        List<Node> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(node.clone());
        }
        return result;
    }

    private static final class FixtureEntry {
        private final String id;
        private final BlueFixtureCategory category;
        private final String path;

        private FixtureEntry(String id,
                             BlueFixtureCategory category,
                             String path) {
            this.id = id;
            this.category = category;
            this.path = path;
        }
    }

    private static final class ProviderContext {
        private final FixtureProvider provider;

        private ProviderContext(FixtureProvider provider) {
            this.provider = provider;
        }
    }

    private static final class SymbolicTypeCycle {
        private final Node rootContent;
        private final NodeProvider provider;

        private SymbolicTypeCycle(Node rootContent, NodeProvider provider) {
            this.rootContent = rootContent;
            this.provider = provider;
        }
    }

    private static class FixtureProvider implements NodeProvider {
        private final Map<String, NodeProviderResult> entries;
        private final Map<String, NodeProviderResult> physicalCache =
                new LinkedHashMap<>();
        private final List<String> requestedBlueIds = new ArrayList<>();

        private FixtureProvider(Map<String, NodeProviderResult> entries) {
            this.entries = new LinkedHashMap<>(entries);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            NodeProviderResult result = fetchResultByBlueId(blueId);
            if (result.outcome() == NodeProviderOutcome.FOUND) {
                return result.nodes();
            }
            if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
                throw new IllegalStateException(result.diagnostic().orElse(
                        "Provider unavailable for " + blueId));
            }
            if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
                throw new IllegalArgumentException(result.diagnostic().orElse(
                        "Provider returned invalid evidence for " + blueId));
            }
            return null;
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            requestedBlueIds.add(blueId);
            NodeProviderResult cached = physicalCache.get(blueId);
            if (cached != null) {
                return cached;
            }
            NodeProviderResult result = entries.get(blueId);
            NodeProviderResult established =
                    result == null ? NodeProviderResult.notFound() : result;
            if (established.outcome() == NodeProviderOutcome.FOUND
                    || established.outcome()
                    == NodeProviderOutcome.NOT_FOUND) {
                physicalCache.put(blueId, established);
            }
            return established;
        }
    }

    private static final class VerifiedCyclicFixtureProvider
            extends FixtureProvider implements CyclicAwareNodeProvider {
        private final Set<String> verifiedBlueIds;
        private final CyclicSetProof proof;

        private VerifiedCyclicFixtureProvider(
                String blueId,
                Node content,
                List<Node> placeholders) {
            this(Collections.singletonMap(
                    blueId, NodeProviderResult.found(
                            Collections.singletonList(content))),
                    placeholders);
        }

        private VerifiedCyclicFixtureProvider(
                Map<String, NodeProviderResult> entries,
                List<Node> placeholders) {
            super(entries);
            this.verifiedBlueIds =
                    Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
            this.proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                    placeholders);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return verifiedBlueIds.contains(blueId)
                    ? CyclicSetProofResult.found(proof)
                    : CyclicSetProofResult.notFound();
        }
    }
}
