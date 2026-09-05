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
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePath;
import blue.language.registry.NodeProviderWrapper;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.codec.jackson.UncheckedObjectMapper;
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
 * Shared vocabulary and low-level values for the closed Blue Language 1.0
 * fixture engine.
 */
abstract class BlueConformanceFixturePrimitives {

    /**
     * Canonical names of operations understood by the fixture DSL.
     *
     * <p>Keeping the operation vocabulary in one owner prevents the manifest
     * allow-list and dispatcher from drifting apart.</p>
     */
    static final class FixtureOperation {

        static final String ASSERT_VIEW_PATH = "assertViewPath";
        static final String CALCULATE_BLUE_ID = "calculateBlueId";
        static final String CALCULATE_BLUE_ID_PAIR = "calculateBlueIdPair";
        static final String CALCULATE_CIRCULAR_SET_BLUE_IDS =
                "calculateCircularSetBlueIds";
        static final String CANONICALIZE = "canonicalize";
        static final String CANONICALIZE_LIMITED_RESULT =
                "canonicalizeLimitedResult";
        static final String CHANGING_REGISTRY_DESCRIPTION_CHANGES_BLUE_ID =
                "changingRegistryDescriptionChangesBlueId";
        static final String COLLAPSE = "collapse";
        static final String VERIFY_RESOLVED_FORM_NOT_DIRECT_IDENTITY_INPUT =
                "verifyResolvedFormNotDirectIdentityInput";
        static final String COMPARE_EXPANSION_STRATEGIES =
                "compareExpansionStrategies";
        static final String COMPARE_GRAPH_EQUIVALENT_INPUTS =
                "compareGraphEquivalentInputs";
        static final String COMPARE_LIMITED_AND_COMPLETE_RESOLUTION =
                "compareLimitedAndCompleteResolution";
        static final String EXPAND = "expand";
        static final String EXPAND_CYCLIC_MEMBER = "expandCyclicMember";
        static final String EXPAND_LIMITED = "expandLimited";
        static final String EXPAND_THEN_COLLAPSE = "expandThenCollapse";
        static final String EXPAND_VARIANTS = "expandVariants";
        static final String LINT_PUBLISHABLE_DOCUMENTATION =
                "lintPublishableDocumentation";
        static final String MATCH = "match";
        static final String MINIMIZE_AND_RESOLVE = "minimizeAndResolve";
        static final String PARSE_BLUE_ID_INPUT = "parseBlueIdInput";
        static final String PARSE_SOURCE = "parseSource";
        static final String PREPROCESS = "preprocess";
        static final String REGISTRY_NODE_HASHES_TO_PUBLISHED_BLUE_ID =
                "registryNodeHashesToPublishedBlueId";
        static final String RESOLVE = "resolve";
        static final String RESOLVE_DEFINITION = "resolveDefinition";
        static final String RESOLVE_LIMITED = "resolveLimited";
        static final String RESOLVE_VARIANTS = "resolveVariants";
        static final String RETRIEVE_DIRECT_LIST = "retrieveDirectList";
        static final String SEMANTIC_EXISTS = "semanticExists";
        static final String SPLIT_EXACT_GRAPH_FRAGMENTS =
                "splitExactGraphFragments";
        static final String SUITE_ASSERTION = "suiteAssertion";
        static final String VALIDATE = "validate";
        static final String VALIDATE_VARIANTS = "validateVariants";
        static final String VERIFY_DIRECT_LIST = "verifyDirectList";
        static final String VERIFY_DIRECT_NODE = "verifyDirectNode";
        static final String VERIFY_OPAQUE_CYCLIC_FRAGMENT =
                "verifyOpaqueCyclicFragment";

        FixtureOperation() {
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
    static final class FixtureField {

        static final String ALSO_DIFFERENT_FROM = "alsoDifferentFrom";
        static final String ALSO_EQUIVALENT_TO = "alsoEquivalentTo";
        static final String ASSERTIONS = "assertions";
        static final String BASE = "base";
        static final String CANDIDATE = "candidate";
        static final String CATEGORY = "category";
        static final String CUTS = "cuts";
        static final String DIRECT_ELEMENT_IDENTITIES_ONLY =
                "directElementIdentitiesOnly";
        static final String DIRECT_NODE = "directNode";
        static final String DOCUMENT = "document";
        static final String DOCUMENTS = "documents";
        static final String EXPECT_BLUE_ID_CHANGED = "expectBlueIdChanged";
        static final String EXPECT_ERROR = "expectError";
        static final String EXPECTED = "expected";
        static final String EXPECTED_ABSENT = "expectedAbsent";
        static final String EXPECTED_BLUE_IDS = "expectedBlueIds";
        static final String EXPECTED_CANONICAL_CONTAINS_CONTROLS =
                "expectedCanonicalContainsControls";
        static final String EXPECTED_CANONICAL_ITEMS =
                "expectedCanonicalItems";
        static final String EXPECTED_CANONICAL_OVERLAY =
                "expectedCanonicalOverlay";
        static final String EXPECTED_CANONICALIZATION_ERROR_CATEGORY =
                "expectedCanonicalizationErrorCategory";
        static final String EXPECTED_COLLAPSED = "expectedCollapsed";
        static final String EXPECTED_COLLAPSED_ROOT =
                "expectedCollapsedRoot";
        static final String
                EXPECTED_CONTENT_BLUE_ID_EQUALS_CANONICAL_IDENTITY_INPUT =
                "expectedContentBlueIdEqualsCanonicalIdentityInput";
        static final String EXPECTED_DEFENSIVE_COPIES =
                "expectedDefensiveCopies";
        static final String EXPECTED_DESCENDANT_REQUESTS =
                "expectedDescendantRequests";
        static final String
                EXPECTED_DIRECT_RESULT_STILL_CONTAINS_ALL_ORDERED_ELEMENT_IDENTITIES =
                "expectedDirectResultStillContainsAllOrderedElementIdentities";
        static final String EXPECTED_EFFECTIVE_TYPE =
                "expectedEffectiveType";
        static final String EXPECTED_EFFECTIVE_TYPES =
                "expectedEffectiveTypes";
        static final String EXPECTED_ELEMENT_BODY_REQUESTS =
                "expectedElementBodyRequests";
        static final String EXPECTED_EQUAL = "expectedEqual";
        static final String EXPECTED_ERROR_CATEGORY =
                "expectedErrorCategory";
        static final String EXPECTED_EXPANDED = "expectedExpanded";
        static final String EXPECTED_EXPANDED_DESCENDANT_REQUESTS =
                "expectedExpandedDescendantRequests";
        static final String EXPECTED_FIELD_COUNT = "expectedFieldCount";
        static final String EXPECTED_FRAGMENT_BLUE_IDS =
                "expectedFragmentBlueIds";
        static final String EXPECTED_FRAGMENT_COUNT =
                "expectedFragmentCount";
        static final String EXPECTED_IDEMPOTENT =
                "expectedIdempotent";
        static final String EXPECTED_IDENTITY_EQUAL =
                "expectedIdentityEqual";
        static final String EXPECTED_LOCAL_PROVIDER_OUTCOME =
                "expectedLocalProviderOutcome";
        static final String EXPECTED_MATCH = "expectedMatch";
        static final String EXPECTED_MERGE_POLICY =
                "expectedMergePolicy";
        static final String EXPECTED_MINIMIZED_MAY_CONTAIN =
                "expectedMinimizedMayContain";
        static final String EXPECTED_NODE_BLUE_ID = "expectedNodeBlueId";
        static final String EXPECTED_NOT_REQUESTED_BLUE_IDS =
                "expectedNotRequestedBlueIds";
        static final String EXPECTED_OPAQUE_EDGES =
                "expectedOpaqueEdges";
        static final String EXPECTED_OUTCOME = "expectedOutcome";
        static final String EXPECTED_OUTSTANDING_BLUE_IDS =
                "expectedOutstandingBlueIds";
        static final String EXPECTED_PARSED = "expectedParsed";
        static final String EXPECTED_PREPROCESSED =
                "expectedPreprocessed";
        static final String EXPECTED_PROVIDER_OUTCOME =
                "expectedProviderOutcome";
        static final String EXPECTED_PUBLISHED_BLUE_ID =
                "expectedPublishedBlueId";
        static final String EXPECTED_REASON = "expectedReason";
        static final String EXPECTED_REFERENCE_PATHS =
                "expectedReferencePaths";
        static final String EXPECTED_REQUESTED_BLUE_IDS =
                "expectedRequestedBlueIds";
        static final String EXPECTED_RESOLUTION_OUTCOME =
                "expectedResolutionOutcome";
        static final String EXPECTED_RESOLVED = "expectedResolved";
        static final String EXPECTED_RESOLVED_ITEMS =
                "expectedResolvedItems";
        static final String EXPECTED_ROUND_TRIP_EQUAL =
                "expectedRoundTripEqual";
        static final String EXPECTED_ROUND_TRIP_ITEMS =
                "expectedRoundTripItems";
        static final String EXPECTED_SAME_AS_COMPLETE_RESOLUTION =
                "expectedSameAsCompleteResolution";
        static final String EXPECTED_SAME_NODE_BLUE_ID =
                "expectedSameNodeBlueId";
        static final String EXPECTED_SAME_ROOT_NODE_BLUE_ID =
                "expectedSameRootNodeBlueId";
        static final String
                EXPECTED_SAME_CONTENT_BLUE_ID_THROUGH_PIPELINE =
                "expectedSameContentBlueIdThroughPipeline";
        static final String EXPECTED_SAME_SEMANTIC_COVERAGE =
                "expectedSameSemanticCoverage";
        static final String EXPECTED_SAME_SEMANTIC_RESULT =
                "expectedSameSemanticResult";
        static final String
                EXPECTED_SOURCE_REFERENCE_PRESERVED_BY_CANONICALIZATION =
                "expectedSourceReferencePreservedByCanonicalization";
        static final String EXPECTED_VALID = "expectedValid";
        static final String EXPECTED_VALUE = "expectedValue";
        static final String EXPECTED_VERIFIED = "expectedVerified";
        static final String EXPECTED_WITH_VERIFIED_SET_CONTEXT =
                "expectedWithVerifiedSetContext";
        static final String
                EXPECTED_WITHOUT_SET_CONTEXT_ERROR_CATEGORY =
                "expectedWithoutSetContextErrorCategory";
        static final String FIELD_DECLARATION = "fieldDeclaration";
        static final String FORBIDDEN_JOINED_TERMS =
                "forbiddenJoinedTerms";
        static final String FULL_LIST = "fullList";
        static final String ID = "id";
        static final String INPUT = "input";
        static final String LEFT = "left";
        static final String LIMITS = "limits";
        static final String MATCH_RULE = "matchRule";
        static final String MAX_REFERENCE_EXPANSIONS =
                "maxReferenceExpansions";
        static final String MUTATION = "mutation";
        static final String NEXT = "next";
        static final String NODE = "node";
        static final String OPERATION = "operation";
        static final String OUTCOME = "outcome";
        static final String PARENT = "parent";
        static final String PATH = "path";
        static final String PATTERN = "pattern";
        static final String PROVIDER = "provider";
        static final String PROVIDER_NODE = "providerNode";
        static final String PROVIDER_RESULT = "providerResult";
        static final String PREPROCESSING_ALIASES =
                "preprocessingAliases";
        static final String PUBLISHABLE_FILES = "publishableFiles";
        static final String REGISTRY_KEY = "registryKey";
        static final String REGISTRY_KIND = "registryKind";
        static final String REQUESTED_BLUE_ID = "requestedBlueId";
        static final String REQUIRED_HEADINGS = "requiredHeadings";
        static final String REQUIRES_VECTOR_PREFIXES =
                "requiresVectorPrefixes";
        static final String RESOLVED_ITEMS = "resolvedItems";
        static final String RETURNED_NODE = "returnedNode";
        static final String RIGHT = "right";
        static final String SEMANTIC_DESCRIPTION_IDENTITY_BEARING =
                "semanticDescriptionIdentityBearing";
        static final String SOURCE = "source";
        static final String STORED_OPTIMIZATION = "storedOptimization";
        static final String VARIANTS = "variants";
        static final String VERIFY_COLLAPSE_REFERENCE_EXPAND =
                "verifyCollapseReferenceExpand";

        FixtureField() {
        }
    }

    static final String FIXTURE_ROOT = "blue-language-1.0/fixtures/";
    static final String MANIFEST_RESOURCE = FIXTURE_ROOT + "manifest.yaml";
    static final String PREPROCESSING_REGISTRY_ROOT =
            FIXTURE_ROOT + "preprocessing/registry/";
    static final String PREPROCESSING_REGISTRY_MANIFEST_RESOURCE =
            PREPROCESSING_REGISTRY_ROOT + "manifest.yaml";
    static final int EXPECTED_BEHAVIOR_FIXTURE_COUNT = 184;

    static final Set<String> OPERATIONS = immutableSet(
            FixtureOperation.ASSERT_VIEW_PATH,
            FixtureOperation.CALCULATE_BLUE_ID,
            FixtureOperation.CALCULATE_BLUE_ID_PAIR,
            FixtureOperation.CALCULATE_CIRCULAR_SET_BLUE_IDS,
            FixtureOperation.CANONICALIZE,
            FixtureOperation.CANONICALIZE_LIMITED_RESULT,
            FixtureOperation.CHANGING_REGISTRY_DESCRIPTION_CHANGES_BLUE_ID,
            FixtureOperation.COLLAPSE,
            FixtureOperation.VERIFY_RESOLVED_FORM_NOT_DIRECT_IDENTITY_INPUT,
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
            FixtureOperation.RESOLVE_DEFINITION,
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

    static final Set<String> ALLOWED_FIXTURE_FIELDS = immutableSet(
            FixtureField.ALSO_DIFFERENT_FROM, FixtureField.ALSO_EQUIVALENT_TO, FixtureField.ASSERTIONS, FixtureField.BASE,
            FixtureField.CANDIDATE, FixtureField.CATEGORY, "description", FixtureField.DIRECT_ELEMENT_IDENTITIES_ONLY,
            FixtureField.DIRECT_NODE, FixtureField.DOCUMENT, FixtureField.DOCUMENTS, FixtureField.EXPECT_BLUE_ID_CHANGED,
            FixtureField.EXPECT_ERROR, FixtureField.EXPECTED, FixtureField.EXPECTED_ABSENT, FixtureField.EXPECTED_BLUE_IDS,
            FixtureField.EXPECTED_CANONICAL_CONTAINS_CONTROLS, FixtureField.EXPECTED_CANONICAL_ITEMS,
            FixtureField.EXPECTED_CANONICAL_OVERLAY, FixtureField.EXPECTED_CANONICALIZATION_ERROR_CATEGORY,
            FixtureField.EXPECTED_COLLAPSED, FixtureField.EXPECTED_COLLAPSED_ROOT,
            FixtureField.EXPECTED_CONTENT_BLUE_ID_EQUALS_CANONICAL_IDENTITY_INPUT,
            FixtureField.EXPECTED_DESCENDANT_REQUESTS,
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
            FixtureField.SOURCE, FixtureField.STORED_OPTIMIZATION, FixtureField.VARIANTS,
            FixtureField.VERIFY_COLLAPSE_REFERENCE_EXPAND
    );

    static JsonNode readYamlResource(String resource) {
        return UncheckedObjectMapper.YAML_MAPPER.readTree(
                new String(readResourceBytes(resource), StandardCharsets.UTF_8));
    }

    static byte[] readResourceBytes(String resource) {
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

    static String readPublishableResource(String path) {
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

    static Node readNode(JsonNode value) {
        return UncheckedObjectMapper.YAML_MAPPER.treeToValue(value, Node.class);
    }

    static JsonNode requirePresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Fixture is missing required field: " + field);
        }
        return value;
    }

    static JsonNode requireArray(JsonNode node, String field) {
        JsonNode value = requirePresent(node, field);
        if (!value.isArray()) {
            throw new IllegalArgumentException(
                    "Fixture field must be a list: " + field);
        }
        return value;
    }

    static String requireText(JsonNode node, String field) {
        JsonNode value = requirePresent(node, field);
        if (!value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException(
                    "Fixture field must be non-empty text: " + field);
        }
        return value.asText();
    }

    static String requireString(JsonNode node, String field) {
        JsonNode value = requirePresent(node, field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(
                    "Fixture field must be text: " + field);
        }
        return value.asText();
    }

    static List<String> textValues(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("Expected a text list.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : array) result.add(value.asText());
        return result;
    }

    static void assertTextList(JsonNode expected,
                                       List<String> actual) {
        assertEquals(textValues(expected), actual);
    }

    static void assertTextSet(JsonNode expected,
                                      Set<String> actual) {
        assertEquals(new LinkedHashSet<>(textValues(expected)),
                new LinkedHashSet<>(actual));
    }

    static void validateRelativePath(String path) {
        if (path.startsWith("/") || path.contains("\\")
                || Arrays.asList(path.split("/", -1)).contains("..")) {
            throw new IllegalStateException(
                    "Unsafe fixture manifest path: " + path);
        }
    }

    static byte[] normalizeLineEndings(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    static String sha256Hex(byte[] bytes) {
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

    static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(values)));
    }

    static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    static void assertEquals(Object expected,
                                     Object actual,
                                     String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    (message == null ? "" : message + ": ")
                            + "Expected " + expected + " but was " + actual);
        }
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static final class FixtureEntry {
        final String id;
        final BlueFixtureCategory category;
        final String path;

        FixtureEntry(String id,
                             BlueFixtureCategory category,
                             String path) {
            this.id = id;
            this.category = category;
            this.path = path;
        }
    }

    static final class ProviderContext {
        final FixtureProvider provider;

        ProviderContext(FixtureProvider provider) {
            this.provider = provider;
        }
    }

    static final class SymbolicTypeCycle {
        final Node rootContent;
        final NodeProvider provider;

        SymbolicTypeCycle(Node rootContent, NodeProvider provider) {
            this.rootContent = rootContent;
            this.provider = provider;
        }
    }

    static class FixtureProvider implements NodeProvider {
        final Map<String, NodeProviderResult> entries;
        final Map<String, NodeProviderResult> physicalCache =
                new LinkedHashMap<>();
        final List<String> requestedBlueIds = new ArrayList<>();

        FixtureProvider(Map<String, NodeProviderResult> entries) {
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

    static final class VerifiedCyclicFixtureProvider
            extends FixtureProvider implements CyclicAwareNodeProvider {
        final Set<String> verifiedBlueIds;
        final CyclicSetProof proof;

        VerifiedCyclicFixtureProvider(
                String blueId,
                Node content,
                List<Node> placeholders) {
            this(Collections.singletonMap(
                    blueId, NodeProviderResult.found(
                            Collections.singletonList(content))),
                    placeholders);
        }

        VerifiedCyclicFixtureProvider(
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
