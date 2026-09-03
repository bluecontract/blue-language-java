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
import blue.language.identity.NodeToBlueIdInput;
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


/** Executes identity, parsing, graph, and fragmentation fixture operations. */
abstract class BlueConformanceGraphOperations extends BlueConformanceFixtureTransformations {

    static void runCalculateBlueId(JsonNode spec) {
        String actual = DirectBlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, FixtureField.INPUT)));
        if (spec.has(FixtureField.EXPECTED_NODE_BLUE_ID)) {
            assertEquals(requireText(spec, FixtureField.EXPECTED_NODE_BLUE_ID), actual);
        }
        assertEquivalentInputs(actual, spec.get(FixtureField.ALSO_EQUIVALENT_TO));
        assertDifferentInputs(actual, spec.get(FixtureField.ALSO_DIFFERENT_FROM));
    }

    static void runCalculateBlueIdPair(JsonNode spec) {
        String left = DirectBlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, FixtureField.LEFT)));
        String right = DirectBlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, FixtureField.RIGHT)));
        assertEquals(requirePresent(spec, FixtureField.EXPECTED_EQUAL).asBoolean(), left.equals(right));
    }

    static void runCalculateCircularSetBlueIds(JsonNode spec) {
        Node documents = readNode(requirePresent(spec, FixtureField.DOCUMENTS));
        if (documents == null || documents.getItems() == null) {
            throw new IllegalArgumentException(
                    "calculateCircularSetBlueIds requires a documents list.");
        }
        List<String> actual = CircularSetIdentityCalculator.calculateCircularSetBlueIds(
                documents.getItems());
        assertTextList(requirePresent(spec, FixtureField.EXPECTED_BLUE_IDS), actual);
    }

    static void runParseBlueIdInput(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime();
        Node actual = blue.parseBlueIdInputYaml(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(
                        requirePresent(spec, FixtureField.INPUT)));
        if (spec.has(FixtureField.EXPECTED_PARSED)) {
            assertNodeEquals(readNode(spec.get(FixtureField.EXPECTED_PARSED)), actual);
        }
    }

    static void runParseSource(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime();
        Node actual = blue.parseSourceYaml(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(
                        requirePresent(spec, FixtureField.SOURCE)));
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_PARSED, actual);
    }

    static void runPreprocess(JsonNode spec) {
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

    static void runResolve(JsonNode spec) {
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

    static void runCanonicalize(JsonNode spec) {
        Node source = sourceWithParent(spec);
        Node actual = canonicalizeInFreshRuntime(spec, source);
        assertCanonicalTypeReferences(actual);
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_CANONICAL_OVERLAY, actual);
        if (spec.has(FixtureField.EXPECTED_CANONICAL_ITEMS)) {
            assertItemValues(spec.get(FixtureField.EXPECTED_CANONICAL_ITEMS), actual.getItems());
        }
        if (spec.has(FixtureField.EXPECTED_CANONICAL_CONTAINS_CONTROLS)) {
            assertEquals(spec.get(FixtureField.EXPECTED_CANONICAL_CONTAINS_CONTROLS).asBoolean(),
                    containsListControls(actual));
        }
        String actualBlueId = DirectBlueIdCalculator.calculateBlueId(actual);
        String sourceBlueId = sourceBlueIdInFreshRuntime(spec, source);
        assertEquals(actualBlueId, sourceBlueId);
        if (spec.has(FixtureField.EXPECTED_NODE_BLUE_ID)) {
            assertEquals(requireText(spec, FixtureField.EXPECTED_NODE_BLUE_ID),
                    sourceBlueId);
        }
        if (spec.has(FixtureField.ALSO_EQUIVALENT_TO)) {
            Node equivalentSource = readNode(spec.get(FixtureField.ALSO_EQUIVALENT_TO));
            attachBaselineType(equivalentSource, spec);
            Node equivalent = canonicalizeInFreshRuntime(spec, equivalentSource);
            assertCanonicalTypeReferences(equivalent);
            assertNodeEquals(actual, equivalent);
            assertEquals(actualBlueId,
                    DirectBlueIdCalculator.calculateBlueId(equivalent));
            assertEquals(sourceBlueId,
                    sourceBlueIdInFreshRuntime(spec, equivalentSource));
            assertEquals(
                    resolvedSemanticProjectionInFreshRuntime(spec, source),
                    resolvedSemanticProjectionInFreshRuntime(
                            spec, equivalentSource),
                    "Equivalent Source forms produced different semantic Resolved Forms");

            assertEquivalentWithWarmCacheInBothOrders(
                    spec,
                    source,
                    equivalentSource,
                    actual,
                    sourceBlueId);

            // Repeat the original form in another isolated runtime as a
            // process-local determinism check independent of the warm-cache
            // A/B and B/A checks above.
            Node repeated = canonicalizeInFreshRuntime(spec, source.clone());
            assertCanonicalTypeReferences(repeated);
            assertNodeEquals(actual, repeated);
            assertEquals(actualBlueId,
                    DirectBlueIdCalculator.calculateBlueId(repeated));
            assertEquals(sourceBlueId,
                    sourceBlueIdInFreshRuntime(spec, source.clone()));
        }
        if (spec.has(FixtureField.ALSO_DIFFERENT_FROM)) {
            Node differentSource = readNode(spec.get(FixtureField.ALSO_DIFFERENT_FROM));
            attachBaselineType(differentSource, spec);
            Node different = canonicalizeInFreshRuntime(spec, differentSource);
            assertCanonicalTypeReferences(different);
            String differentBlueId = sourceBlueIdInFreshRuntime(
                    spec, differentSource);
            assertEquals(DirectBlueIdCalculator.calculateBlueId(different),
                    differentBlueId);
            assertTrue(!sourceBlueId.equals(differentBlueId),
                    "Distinct Source meanings converged on one canonical identity.");
            assertTrue(!nodesEqual(actual, different),
                    "Distinct Source meanings produced one Canonical Identity Input.");
        }
    }

    private static void assertEquivalentWithWarmCacheInBothOrders(
            JsonNode spec,
            Node source,
            Node equivalentSource,
            Node expectedCanonical,
            String expectedBlueId) {
        assertEquivalentWithWarmCacheOrder(
                spec,
                source,
                equivalentSource,
                expectedCanonical,
                expectedBlueId);
        assertEquivalentWithWarmCacheOrder(
                spec,
                equivalentSource,
                source,
                expectedCanonical,
                expectedBlueId);
    }

    private static void assertEquivalentWithWarmCacheOrder(
            JsonNode spec,
            Node firstSource,
            Node secondSource,
            Node expectedCanonical,
            String expectedBlueId) {
        ProviderContext provider = providerContext(spec, null);
        try (LanguageFixtureRuntime blue =
                     new LanguageFixtureRuntime(provider.provider)) {
            Node first = blue.canonicalize(firstSource.clone());
            Node second = blue.canonicalize(secondSource.clone());
            Node repeatedFirst = blue.canonicalize(firstSource.clone());
            assertCanonicalTypeReferences(first);
            assertCanonicalTypeReferences(second);
            assertCanonicalTypeReferences(repeatedFirst);
            assertNodeEquals(expectedCanonical, first);
            assertNodeEquals(expectedCanonical, second);
            assertNodeEquals(expectedCanonical, repeatedFirst);
            assertEquals(expectedBlueId,
                    blue.calculateSourceDocumentBlueId(firstSource.clone()));
            assertEquals(expectedBlueId,
                    blue.calculateSourceDocumentBlueId(secondSource.clone()));
            assertEquals(expectedBlueId,
                    DirectBlueIdCalculator.calculateBlueId(first));
            assertEquals(expectedBlueId,
                    DirectBlueIdCalculator.calculateBlueId(second));
            assertEquals(expectedBlueId,
                    DirectBlueIdCalculator.calculateBlueId(repeatedFirst));
        }
    }

    private static String sourceBlueIdInFreshRuntime(
            JsonNode spec, Node source) {
        ProviderContext provider = providerContext(spec, null);
        try (LanguageFixtureRuntime blue =
                     new LanguageFixtureRuntime(provider.provider)) {
            return blue.calculateSourceDocumentBlueId(source.clone());
        }
    }

    private static Node canonicalizeInFreshRuntime(JsonNode spec, Node source) {
        ProviderContext provider = providerContext(spec, null);
        try (LanguageFixtureRuntime blue =
                     new LanguageFixtureRuntime(provider.provider)) {
            return blue.canonicalize(source.clone());
        }
    }

    private static Object resolvedSemanticProjectionInFreshRuntime(
            JsonNode spec, Node source) {
        ProviderContext provider = providerContext(spec, null);
        try (LanguageFixtureRuntime blue =
                     new LanguageFixtureRuntime(provider.provider)) {
            Node resolved = blue.resolve(source.clone());
            return NodeToBlueIdInput.getWithResolvedBlueIdMetadata(resolved);
        }
    }

    static void runCollapse(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime();
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        Node actual = blue.collapse(source);
        assertExpectedNodeIfPresent(spec, FixtureField.EXPECTED_COLLAPSED, actual);
        String expectedId = requireText(spec, FixtureField.EXPECTED_NODE_BLUE_ID);
        assertEquals(expectedId, actual.getBlueId());
        assertEquals(expectedId, DirectBlueIdCalculator.calculateBlueId(source));
        assertTrue(actual.isReferenceOnly(), "Collapse must emit a pure reference.");
    }

    static void runExpand(JsonNode spec) {
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

    static void runExpandLimited(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        BlueOperationLimits limits = operationLimits(spec);
        BlueOperationResult<Node> result = blue.expandLimited(
                readNode(requirePresent(spec, FixtureField.SOURCE)), limits);
        assertOutcome(spec, FixtureField.EXPECTED_OUTCOME, result.outcome());
        assertDemandedValue(spec, result, limits);
        if (spec.has(FixtureField.EXPECTED_ABSENT)) {
            assertEquals(spec.get(FixtureField.EXPECTED_ABSENT).asBoolean(),
                    result.isAbsent());
        }
        if (spec.has(FixtureField.EXPECTED_OUTSTANDING_BLUE_IDS)) {
            assertTextSet(
                    spec.get(FixtureField.EXPECTED_OUTSTANDING_BLUE_IDS),
                    result.outstandingBlueIds());
        }
        if (spec.has(FixtureField.EXPECTED_PROVIDER_OUTCOME)) {
            assertEquals(
                    providerOutcome(requireText(
                            spec,
                            FixtureField.EXPECTED_PROVIDER_OUTCOME)),
                    result.providerOutcome().orElse(null));
        }
        assertRequestedIds(spec.get(FixtureField.EXPECTED_REQUESTED_BLUE_IDS),
                provider.provider.requestedBlueIds, true);
        assertRequestedIds(spec.get(FixtureField.EXPECTED_NOT_REQUESTED_BLUE_IDS),
                provider.provider.requestedBlueIds, false);
    }

    static void runResolveLimited(JsonNode spec) {
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

    static void runCanonicalizeLimitedResult(JsonNode spec) {
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        ProviderContext provider = providerContext(spec, null);
        try (LanguageFixtureRuntime blue =
                     new LanguageFixtureRuntime(provider.provider)) {
            BlueOperationResult<Node> limited = blue.resolveLimited(
                    source.clone(), operationLimits(spec));
            assertOutcome(spec, FixtureField.EXPECTED_RESOLUTION_OUTCOME,
                    limited.outcome());
            try {
                blue.canonicalize(limited);
            } catch (RuntimeException expected) {
                assertExpectedErrorCategory(
                        spec,
                        FixtureField.EXPECTED_CANONICALIZATION_ERROR_CATEGORY,
                        expected);

                // The rejected limited attempt must not poison the runtime.
                // Retrying the authored Source with complete evidence in that
                // same runtime must converge with a clean eager run.
                Node retried = blue.canonicalize(source.clone());
                Node eager = canonicalizeInFreshRuntime(spec, source.clone());
                assertCanonicalTypeReferences(retried);
                assertCanonicalTypeReferences(eager);
                assertNodeEquals(eager, retried);
                String eagerBlueId = DirectBlueIdCalculator.calculateBlueId(eager);
                assertEquals(eagerBlueId,
                        DirectBlueIdCalculator.calculateBlueId(retried));
                assertEquals(eagerBlueId,
                        blue.calculateSourceDocumentBlueId(source.clone()));
                if (spec.has(FixtureField.EXPECTED_CANONICAL_OVERLAY)) {
                    assertNodeEquals(readNode(spec.get(
                            FixtureField.EXPECTED_CANONICAL_OVERLAY)), retried);
                }
                if (spec.has(FixtureField.EXPECTED_NODE_BLUE_ID)) {
                    assertEquals(requireText(
                                    spec, FixtureField.EXPECTED_NODE_BLUE_ID),
                            eagerBlueId);
                }
                return;
            }
        }
        throw new AssertionError("Incomplete result was accepted for canonicalization.");
    }

    static void runCompareLimitedAndCompleteResolution(JsonNode spec) {
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

    static void runCompareGraphEquivalentInputs(JsonNode spec) {
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

    static void runCompareExpansionStrategies(JsonNode spec) {
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

    static void runExpandThenCollapse(JsonNode spec) {
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

    static void runExpandCyclicMember(JsonNode spec) {
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

    static void replaceThisReferences(Node node, List<String> memberBlueIds) {
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

    static void runSplitExactGraphFragments(JsonNode spec) {
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

    static void runVerifyOpaqueCyclicFragment(JsonNode spec) {
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

    static void assertFragmentRootIdentity(
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

    static void assertExpectedReferencePaths(
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

    static Node selectFragmentReference(
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

    static Node expandFragmentRoot(
            ExactNodeGraphFragments graph) {
        return new LanguageFixtureRuntime(graph.provider()).expand(
                graph.roots().get(0).pureReference());
    }

    static void assertLocalProviderOutcomes(
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

    static boolean hasDefensiveFragmentCopies(
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

    static BasicNodeProvider fragmentCyclicProof() {
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

}
