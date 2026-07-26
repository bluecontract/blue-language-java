package blue.language;

import blue.language.model.Node;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.DirectNodeManifest;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.CircularBlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.Nodes;
import blue.language.utils.Properties;
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
import java.util.Set;

/**
 * Fail-closed executable harness for the exact Blue Language 1.0 fixture
 * package. Every behavior fixture is executed; unsupported data is a failure.
 */
public final class BlueConformanceSuiteRunner {

    private static final String FIXTURE_ROOT = "blue-language-1.0/fixtures/";
    private static final String MANIFEST_RESOURCE = FIXTURE_ROOT + "manifest.yaml";

    private static final Set<String> OPERATIONS = immutableSet(
            "assertViewPath",
            "calculateBlueId",
            "calculateBlueIdPair",
            "calculateCircularSetBlueIds",
            "canonicalize",
            "canonicalizeLimitedResult",
            "changingRegistryDescriptionChangesBlueId",
            "collapse",
            "compareContentAndDirectResolvedBlueId",
            "compareExpansionStrategies",
            "compareGraphEquivalentInputs",
            "compareLimitedAndCompleteResolution",
            "expand",
            "expandCyclicMember",
            "expandLimited",
            "expandThenCollapse",
            "expandVariants",
            "lintPublishableDocumentation",
            "match",
            "minimizeAndResolve",
            "parseBlueIdInput",
            "parseSource",
            "preprocess",
            "registryNodeHashesToPublishedBlueId",
            "resolve",
            "resolveLimited",
            "resolveVariants",
            "retrieveDirectList",
            "semanticExists",
            "suiteAssertion",
            "validate",
            "validateVariants",
            "verifyDirectList",
            "verifyDirectNode"
    );

    private static final Set<String> ALLOWED_FIXTURE_FIELDS = immutableSet(
            "alsoDifferentFrom", "alsoEquivalentTo", "assertions", "base",
            "candidate", "category", "description", "directElementIdentitiesOnly",
            "directNode", "document", "documents", "expectBlueIdChanged",
            "expectError", "expected", "expectedAbsent", "expectedBlueIds",
            "expectedCanonicalContainsControls", "expectedCanonicalItems",
            "expectedCanonicalOverlay", "expectedCanonicalizationErrorCategory",
            "expectedCollapsed", "expectedCollapsedRoot",
            "expectedContentBlueIdEqualsCanonicalIdentityInput",
            "expectedDescendantRequests", "expectedDirectResolvedBlueIdMayDiffer",
            "expectedDirectResultStillContainsAllOrderedElementIdentities",
            "expectedEffectiveType", "expectedEffectiveTypes",
            "expectedElementBodyRequests", "expectedEqual", "expectedErrorCategory",
            "expectedExpanded", "expectedExpandedDescendantRequests",
            "expectedFieldCount", "expectedIdentityEqual", "expectedMatch",
            "expectedMergePolicy", "expectedMinimizedMayContain",
            "expectedNodeBlueId", "expectedNotRequestedBlueIds",
            "expectedOutcome", "expectedOutstandingBlueIds",
            "expectedParsed", "expectedPreprocessed", "expectedProviderOutcome",
            "expectedPublishedBlueId", "expectedReason",
            "expectedRequestedBlueIds", "expectedResolutionOutcome",
            "expectedResolved", "expectedResolvedItems", "expectedRoundTripEqual",
            "expectedRoundTripItems", "expectedSameAsCompleteResolution",
            "expectedSameNodeBlueId", "expectedSameRootNodeBlueId",
            "expectedSameSemanticCoverage", "expectedSameSemanticResult",
            "expectedSourceReferencePreservedByCanonicalization",
            "expectedValid", "expectedValue", "expectedVerified",
            "expectedWithVerifiedSetContext",
            "expectedWithoutSetContextErrorCategory", "fieldDeclaration",
            "forbiddenJoinedTerms", "fullList", "id", "input", "left",
            "limits", "matchRule", "mutation", "note", "operation", "parent",
            "path", "pattern", "provider", "providerNode", "providerResult",
            "publishableFiles", "registryKey", "registryKind",
            "requestedBlueId", "requiredHeadings", "requiresVectorPrefixes",
            "resolvedItems", "right", "semanticDescriptionIdentityBearing",
            "source", "storedOptimization", "variants"
    );

    private BlueConformanceSuiteRunner() {
    }

    public static BlueConformanceReport run(Blue blue) {
        BlueConformanceReport metadata = blue.conformanceReport();
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

    public static Set<String> knownOperations() {
        return OPERATIONS;
    }

    public static void validateFixtureMetadataForTest(JsonNode spec) {
        validateFixtureMetadata(spec);
    }

    public static void runFixtureForTest(JsonNode spec) {
        validateFixtureMetadata(spec);
        String operation = requireText(spec, "operation");
        if (expectsTopLevelError(spec, operation)) {
            try {
                runOperation(spec, operation, fixtureEntries());
            } catch (RuntimeException expected) {
                if (spec.hasNonNull("expectedErrorCategory")) {
                    assertExpectedErrorCategory(
                            spec, "expectedErrorCategory", expected);
                }
                return;
            }
            throw new AssertionError("Fixture expected an error but operation succeeded: "
                    + requireText(spec, "id"));
        }
        runOperation(spec, operation, fixtureEntries());
    }

    private static void runFixture(FixtureEntry fixture,
                                   List<FixtureEntry> allFixtures) {
        JsonNode spec = readYamlResource(FIXTURE_ROOT + fixture.path);
        validateFixtureMetadata(spec);
        assertEquals(fixture.id, requireText(spec, "id"));
        assertEquals(fixture.category,
                BlueFixtureCategory.fromLabel(requireText(spec, "category")));

        String operation = requireText(spec, "operation");
        if (expectsTopLevelError(spec, operation)) {
            try {
                runOperation(spec, operation, allFixtures);
            } catch (RuntimeException expected) {
                if (spec.hasNonNull("expectedErrorCategory")) {
                    assertExpectedErrorCategory(
                            spec, "expectedErrorCategory", expected);
                }
                return;
            }
            throw new AssertionError("Fixture expected an error but operation succeeded: "
                    + fixture.id);
        }
        runOperation(spec, operation, allFixtures);
    }

    private static boolean expectsTopLevelError(JsonNode spec, String operation) {
        if ("resolveVariants".equals(operation)
                || "validateVariants".equals(operation)
                || "canonicalizeLimitedResult".equals(operation)
                || "expandCyclicMember".equals(operation)
                || "expandVariants".equals(operation)) {
            return false;
        }
        return spec.path("expectError").asBoolean(false)
                || spec.hasNonNull("expectedErrorCategory");
    }

    private static void runOperation(JsonNode spec,
                                     String operation,
                                     List<FixtureEntry> allFixtures) {
        switch (operation) {
            case "assertViewPath":
                runAssertViewPath(spec);
                return;
            case "calculateBlueId":
                runCalculateBlueId(spec);
                return;
            case "calculateBlueIdPair":
                runCalculateBlueIdPair(spec);
                return;
            case "calculateCircularSetBlueIds":
                runCalculateCircularSetBlueIds(spec);
                return;
            case "canonicalize":
                runCanonicalize(spec);
                return;
            case "canonicalizeLimitedResult":
                runCanonicalizeLimitedResult(spec);
                return;
            case "changingRegistryDescriptionChangesBlueId":
                runChangingRegistryDescriptionChangesBlueId(spec);
                return;
            case "collapse":
                runCollapse(spec);
                return;
            case "compareContentAndDirectResolvedBlueId":
                runCompareContentAndDirectResolvedBlueId(spec);
                return;
            case "compareExpansionStrategies":
                runCompareExpansionStrategies(spec);
                return;
            case "compareGraphEquivalentInputs":
                runCompareGraphEquivalentInputs(spec);
                return;
            case "compareLimitedAndCompleteResolution":
                runCompareLimitedAndCompleteResolution(spec);
                return;
            case "expand":
                runExpand(spec);
                return;
            case "expandCyclicMember":
                runExpandCyclicMember(spec);
                return;
            case "expandLimited":
                runExpandLimited(spec);
                return;
            case "expandThenCollapse":
                runExpandThenCollapse(spec);
                return;
            case "expandVariants":
                runExpandVariants(spec);
                return;
            case "lintPublishableDocumentation":
                runLintPublishableDocumentation(spec);
                return;
            case "match":
                runMatch(spec);
                return;
            case "minimizeAndResolve":
                runMinimizeAndResolve(spec);
                return;
            case "parseBlueIdInput":
                runParseBlueIdInput(spec);
                return;
            case "parseSource":
                runParseSource(spec);
                return;
            case "preprocess":
                runPreprocess(spec);
                return;
            case "registryNodeHashesToPublishedBlueId":
                runRegistryNodeHashesToPublishedBlueId(spec);
                return;
            case "resolve":
                runResolve(spec);
                return;
            case "resolveLimited":
                runResolveLimited(spec);
                return;
            case "resolveVariants":
                runResolveVariants(spec);
                return;
            case "retrieveDirectList":
                runRetrieveDirectList(spec);
                return;
            case "semanticExists":
                runSemanticExists(spec);
                return;
            case "suiteAssertion":
                runSuiteAssertion(spec, allFixtures);
                return;
            case "validate":
                runValidate(spec);
                return;
            case "validateVariants":
                runValidateVariants(spec);
                return;
            case "verifyDirectList":
                runVerifyDirectList(spec);
                return;
            case "verifyDirectNode":
                runVerifyDirectNode(spec);
                return;
            default:
                throw new IllegalArgumentException(
                        "Unsupported fixture operation: " + operation);
        }
    }

    private static void runCalculateBlueId(JsonNode spec) {
        String actual = BlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, "input")));
        if (spec.has("expectedNodeBlueId")) {
            assertEquals(requireText(spec, "expectedNodeBlueId"), actual);
        }
        assertEquivalentInputs(actual, spec.get("alsoEquivalentTo"));
        assertDifferentInputs(actual, spec.get("alsoDifferentFrom"));
    }

    private static void runCalculateBlueIdPair(JsonNode spec) {
        String left = BlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, "left")));
        String right = BlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, "right")));
        assertEquals(requirePresent(spec, "expectedEqual").asBoolean(), left.equals(right));
    }

    private static void runCalculateCircularSetBlueIds(JsonNode spec) {
        Node documents = readNode(requirePresent(spec, "documents"));
        if (documents == null || documents.getItems() == null) {
            throw new IllegalArgumentException(
                    "calculateCircularSetBlueIds requires a documents list.");
        }
        List<String> actual = CircularBlueIdCalculator.calculateCircularSetBlueIds(
                documents.getItems());
        assertTextList(requirePresent(spec, "expectedBlueIds"), actual);
    }

    private static void runParseBlueIdInput(JsonNode spec) {
        Blue blue = new Blue();
        Node actual = blue.parseBlueIdInputYaml(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(
                        requirePresent(spec, "input")));
        if (spec.has("expectedParsed")) {
            assertNodeEquals(readNode(spec.get("expectedParsed")), actual);
        }
    }

    private static void runParseSource(JsonNode spec) {
        Blue blue = new Blue();
        Node actual = blue.parseSourceYaml(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(
                        requirePresent(spec, "source")));
        assertExpectedNodeIfPresent(spec, "expectedParsed", actual);
    }

    private static void runPreprocess(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        Blue blue = new Blue(provider.provider);
        Node actual = blue.preprocess(readNode(requirePresent(spec, "source")));
        assertExpectedNodeIfPresent(spec, "expectedPreprocessed", actual);
        assertEffectiveTypes(spec.get("expectedEffectiveTypes"), actual);
    }

    private static void runResolve(JsonNode spec) {
        SymbolicTypeCycle symbolicCycle = symbolicTypeCycle(spec);
        if (symbolicCycle != null) {
            Blue blue = new Blue(symbolicCycle.provider);
            blue.resolve(blue.preprocess(symbolicCycle.rootContent));
            return;
        }
        ProviderContext provider = providerContext(spec, null);
        Blue blue = new Blue(provider.provider);
        Node source = sourceWithParent(spec);
        Node actual = blue.resolve(blue.preprocess(source));
        assertResolutionExpectations(spec, actual, blue, source);
    }

    private static void runCanonicalize(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        Blue blue = new Blue(provider.provider);
        Node source = sourceWithParent(spec);
        Node actual = blue.canonicalize(source);
        assertExpectedNodeIfPresent(spec, "expectedCanonicalOverlay", actual);
        if (spec.has("expectedCanonicalItems")) {
            assertItemValues(spec.get("expectedCanonicalItems"), actual.getItems());
        }
        if (spec.has("expectedCanonicalContainsControls")) {
            assertEquals(spec.get("expectedCanonicalContainsControls").asBoolean(),
                    containsListControls(actual));
        }
        BlueIdCalculator.calculateBlueId(actual);
    }

    private static void runCollapse(JsonNode spec) {
        Blue blue = new Blue();
        Node source = readNode(requirePresent(spec, "source"));
        Node actual = blue.collapse(source);
        assertExpectedNodeIfPresent(spec, "expectedCollapsed", actual);
        String expectedId = requireText(spec, "expectedNodeBlueId");
        assertEquals(expectedId, actual.getBlueId());
        assertEquals(expectedId, BlueIdCalculator.calculateBlueId(source));
        assertTrue(actual.isReferenceOnly(), "Collapse must emit a pure reference.");
    }

    private static void runExpand(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        Blue blue = new Blue(provider.provider);
        Node source = readNode(requirePresent(spec, "source"));
        Node actual = blue.expand(source);
        assertExpectedNodeIfPresent(spec, "expectedExpanded", actual);
        if (spec.has("expectedNodeBlueId")) {
            String expected = requireText(spec, "expectedNodeBlueId");
            assertEquals(expected, BlueIdCalculator.calculateBlueId(source));
            assertEquals(expected, BlueIdCalculator.calculateBlueId(actual));
        }
    }

    private static void runExpandLimited(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        Blue blue = new Blue(provider.provider);
        BlueOperationLimits limits = operationLimits(spec);
        BlueOperationResult<Node> result = blue.expandLimited(
                readNode(requirePresent(spec, "source")), limits);
        assertOutcome(spec, "expectedOutcome", result.outcome());
        assertDemandedValue(spec, result, limits);
        assertRequestedIds(spec.get("expectedRequestedBlueIds"),
                provider.provider.requestedBlueIds, true);
        assertRequestedIds(spec.get("expectedNotRequestedBlueIds"),
                provider.provider.requestedBlueIds, false);
    }

    private static void runResolveLimited(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        Blue blue = new Blue(provider.provider);
        BlueOperationLimits limits = operationLimits(spec);
        BlueOperationResult<Node> result = blue.resolveLimited(
                readNode(requirePresent(spec, "source")), limits);
        assertOutcome(spec, "expectedOutcome", result.outcome());
        if (spec.has("expectedAbsent")) {
            assertEquals(spec.get("expectedAbsent").asBoolean(), result.isAbsent());
        }
        if (spec.has("expectedOutstandingBlueIds")) {
            assertTextSet(spec.get("expectedOutstandingBlueIds"),
                    result.outstandingBlueIds());
        }
        if (spec.has("expectedProviderOutcome")) {
            assertEquals(providerOutcome(requireText(spec, "expectedProviderOutcome")),
                    result.providerOutcome().orElse(null));
        }
    }

    private static void runCanonicalizeLimitedResult(JsonNode spec) {
        Blue blue = new Blue(providerContext(spec, null).provider);
        BlueOperationResult<Node> limited = blue.resolveLimited(
                readNode(requirePresent(spec, "source")), operationLimits(spec));
        assertOutcome(spec, "expectedResolutionOutcome", limited.outcome());
        try {
            blue.canonicalize(limited);
        } catch (RuntimeException expected) {
            assertExpectedErrorCategory(
                    spec, "expectedCanonicalizationErrorCategory", expected);
            return;
        }
        throw new AssertionError("Incomplete result was accepted for canonicalization.");
    }

    private static void runCompareLimitedAndCompleteResolution(JsonNode spec) {
        ProviderContext limitedProvider = providerContext(spec, null);
        ProviderContext completeProvider = providerContext(spec, null);
        Blue limitedBlue = new Blue(limitedProvider.provider);
        Blue completeBlue = new Blue(completeProvider.provider);
        BlueOperationLimits limits = operationLimits(spec);
        Node source = readNode(requirePresent(spec, "source"));
        BlueOperationResult<Node> limited = limitedBlue.resolveLimited(source, limits);
        assertOutcome(spec, "expectedOutcome", limited.outcome());
        Node complete = completeBlue.resolve(completeBlue.preprocess(source.clone()));
        for (String path : limits.demandedPaths()) {
            Node limitedValue = BlueViewPath.select(limited.requireEstablished(), path);
            Node completeValue = BlueViewPath.select(complete, path);
            assertNodeEquals(completeValue, limitedValue);
            if (spec.has("expectedValue")) {
                assertSemanticScalar(spec.get("expectedValue"), limitedValue);
            }
        }
        assertTrue(requirePresent(spec, "expectedSameAsCompleteResolution").asBoolean(),
                "Fixture must require complete-resolution parity.");
    }

    private static void runCompareGraphEquivalentInputs(JsonNode spec) {
        JsonNode variants = requireArray(spec, "variants");
        Map<String, NodeProviderResult> derived = new LinkedHashMap<>(globalProviderCatalog());
        for (JsonNode variant : variants) {
            Node source = readNode(requirePresent(variant, "source"));
            if (!source.isReferenceOnly()) {
                derived.put(BlueIdCalculator.calculateBlueId(source),
                        NodeProviderResult.found(Collections.singletonList(source)));
            }
        }
        BlueOperationLimits limits = operationLimits(spec);
        List<BlueOperationResult<Node>> results = new ArrayList<>();
        List<Node> selected = new ArrayList<>();
        List<String> rootIds = new ArrayList<>();
        for (JsonNode variant : variants) {
            ProviderContext provider = providerContextWithoutFixtureProvider(derived);
            Node source = readNode(requirePresent(variant, "source"));
            BlueOperationResult<Node> result =
                    new Blue(provider.provider).expandLimited(source, limits);
            results.add(result);
            assertOutcome(spec, "expectedOutcome", result.outcome());
            selected.add(selectFirstDemand(result.requireEstablished(), limits));
            rootIds.add(BlueIdCalculator.calculateBlueId(source));
        }
        assertAllNodeEqual(selected);
        assertAllEqual(rootIds);
        assertEquals(requireText(spec, "expectedSameRootNodeBlueId"), rootIds.get(0));
        assertSemanticScalar(spec.get("expectedValue"), selected.get(0));
        assertTrue(spec.path("expectedSameSemanticResult").asBoolean(false),
                "Fixture must require semantic-result parity.");
    }

    private static void runCompareExpansionStrategies(JsonNode spec) {
        JsonNode variants = requireArray(spec, "variants");
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
            Node source = readNode(requirePresent(spec, "source"));
            BlueOperationResult<Node> result =
                    new Blue(provider.provider).expandLimited(source, limits);
            assertOutcome(spec, "expectedOutcome", result.outcome());
            selected.add(selectFirstDemand(result.requireEstablished(), limits));
            rootIds.add(BlueIdCalculator.calculateBlueId(source));
        }
        assertAllNodeEqual(selected);
        assertAllEqual(rootIds);
        assertSemanticScalar(spec.get("expectedValue"), selected.get(0));
        assertEquals(requireText(spec, "expectedSameNodeBlueId"), rootIds.get(0));
        assertTrue(spec.path("expectedSameSemanticCoverage").asBoolean(false),
                "Fixture must require semantic-coverage parity.");
    }

    private static void runExpandThenCollapse(JsonNode spec) {
        ProviderContext provider = providerContext(spec, globalProviderCatalog());
        Blue blue = new Blue(provider.provider);
        Node source = readNode(requirePresent(spec, "source"));
        BlueOperationResult<Node> expanded =
                blue.expandLimited(source, operationLimits(spec));
        Node collapsed = blue.collapse(expanded.requireEstablished());
        assertExpectedNodeIfPresent(spec, "expectedCollapsedRoot", collapsed);
        List<String> descendants = new ArrayList<>(provider.provider.requestedBlueIds);
        descendants.remove(source.getBlueId());
        assertTextList(requirePresent(spec, "expectedExpandedDescendantRequests"),
                descendants);
        assertRequestedIds(spec.get("expectedNotRequestedBlueIds"),
                provider.provider.requestedBlueIds, false);
    }

    private static void runExpandCyclicMember(JsonNode spec) {
        String illustrativeRequested = requireText(spec, "requestedBlueId");
        int memberSeparator = illustrativeRequested.lastIndexOf('#');
        if (memberSeparator < 0) {
            throw new IllegalArgumentException(
                    "Illustrative cyclic member BlueId must select a member.");
        }
        int requestedMember = Integer.parseInt(
                illustrativeRequested.substring(memberSeparator + 1));
        Node content = readNode(requirePresent(spec, "providerNode"));
        Node companion = new Node()
                .name("generated fixture companion")
                .properties("peer", new Node().blueId("this#0"));
        List<Node> members = Arrays.asList(content, companion);
        List<String> calculated = CircularBlueIdCalculator
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
                    spec, "expectedWithoutSetContextErrorCategory", expected);
        }

        Node verifiedContent = content.clone();
        replaceThisReferences(verifiedContent, calculated);
        VerifiedCyclicFixtureProvider verified =
                new VerifiedCyclicFixtureProvider(requested, verifiedContent);
        List<Node> nodes = new VerifyingNodeProvider(verified).fetchByBlueId(requested);
        assertTrue(nodes != null && nodes.size() == 1,
                "Verified cyclic-set context did not return the member.");
        assertEquals("success", requireText(spec, "expectedWithVerifiedSetContext"));
    }

    private static void replaceThisReferences(Node node, List<String> memberBlueIds) {
        if (node == null) return;
        String blueId = node.getBlueId();
        if (blueId != null && blueId.startsWith("this#")) {
            int index = Integer.parseInt(blueId.substring("this#".length()));
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

    private static void runExpandVariants(JsonNode spec) {
        String requested = requireText(spec, "requestedBlueId");
        Node providerNode = readNode(requirePresent(spec, "providerNode"));
        for (JsonNode variant : requireArray(spec, "variants")) {
            String mode = requireText(variant, "providerMode");
            if ("BlueIdInput".equals(mode)) {
                try {
                    ProviderEvidenceVerifier.verify(requested, providerNode,
                            ProviderMode.BLUE_ID_INPUT, new Blue(), null);
                } catch (RuntimeException expected) {
                    assertExpectedErrorCategory(
                            variant, "expectedErrorCategory", expected);
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
                        ProviderMode.SOURCE_DOCUMENT, new Blue(), null);
            } catch (IllegalArgumentException expected) {
                rejectedWithoutEnvironment = true;
            }
            assertTrue(rejectedWithoutEnvironment,
                    "SourceDocument mode accepted undeclared preprocessing.");
            // Verify the same evidence succeeds once it is explicitly bound.
            Blue sourceBlue = new Blue();
            ProviderEvidenceVerifier.verify(requested, providerNode,
                    ProviderMode.SOURCE_DOCUMENT, sourceBlue,
                    new SourceProviderEnvironment(
                            sourceBlue.languageVersion(),
                            SourceProviderEnvironment.LANGUAGE_1_0_RELEASE_IDENTITY,
                            ProviderEvidenceVerifier.preprocessingEnvironmentIdentity(
                                    sourceBlue),
                            BlueCoreTypeRegistry.INSTANCE.packageIdentity(),
                            ProviderEvidenceVerifier.sourceEvidenceIdentity(
                                    providerNode)));
        }
    }

    private static void runCompareContentAndDirectResolvedBlueId(JsonNode spec) {
        Blue blue = new Blue(providerContext(spec, null).provider);
        Node source = readNode(requirePresent(spec, "source"));
        Node resolved = blue.resolve(blue.preprocess(source.clone()));
        Node canonical = blue.canonicalize(source);
        String contentBlueId = blue.calculateSemanticBlueId(source);
        String canonicalIdentityInputBlueId =
                BlueIdCalculator.calculateBlueId(canonical);
        String directResolvedBlueId = BlueIdCalculator.calculateBlueId(resolved);
        assertEquals(spec.path(
                        "expectedContentBlueIdEqualsCanonicalIdentityInput")
                        .asBoolean(false),
                contentBlueId.equals(canonicalIdentityInputBlueId));
        assertEquals(spec.path("expectedDirectResolvedBlueIdMayDiffer")
                        .asBoolean(false),
                !directResolvedBlueId.equals(contentBlueId));
    }

    private static void runMinimizeAndResolve(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        Blue blue = new Blue(provider.provider);
        Node originalResolved;
        Node minimized;
        if (spec.has("source")) {
            Node source = readNode(spec.get("source"));
            originalResolved = blue.resolve(blue.preprocess(source));
            assertExpectedResolvedIfPresent(spec, "expectedResolved",
                    originalResolved, blue);
            minimized = blue.minimize(source.clone());
        } else {
            // Build the synthetic complete source in the same preprocessed
            // representation used by list-anchor validation. In particular,
            // an append-only $previous anchor identifies inherited typed
            // items, not their pre-inference source spelling.
            Node parent = blue.preprocess(
                    readNode(requirePresent(spec, "parent")));
            Node desired = blue.preprocess(
                    readNode(requirePresent(spec, "resolvedItems")));
            Node completeOverlay = sourceForResolvedItems(
                    parent, desired.getItems());
            originalResolved = blue.resolve(blue.preprocess(completeOverlay));
            minimized = blue.minimize(completeOverlay.clone());
        }
        Node roundTrip = blue.resolve(blue.preprocess(minimized.clone()));
        if (spec.path("expectedRoundTripEqual").asBoolean(false)) {
            assertNodeEquals(originalResolved, roundTrip);
        }
        if (spec.has("expectedRoundTripItems")) {
            assertItemValues(spec.get("expectedRoundTripItems"),
                    roundTrip.getItems());
        }
        if (spec.has("expectedMinimizedMayContain")) {
            assertOnlyAllowedMinimizationControls(
                    minimized, textValues(spec.get("expectedMinimizedMayContain")));
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
        if (Properties.LIST_MERGE_POLICY_APPEND_ONLY.equals(
                parent.getMergePolicy())) {
            for (int index = 0; index < parent.getItems().size(); index++) {
                if (!BlueIdCalculator.calculateBlueId(
                                parent.getItems().get(index))
                        .equals(BlueIdCalculator.calculateBlueId(
                                desiredItems.get(index)))) {
                    throw new IllegalArgumentException(
                            "An append-only resolved list cannot modify inherited items.");
                }
            }
            overlayItems.add(new Node().previousBlueId(
                    BlueIdCalculator.calculateBlueId(parent.getItems())));
            for (int index = parent.getItems().size();
                 index < desiredItems.size(); index++) {
                overlayItems.add(desiredItems.get(index).clone());
            }
            return new Node().type(parent).items(overlayItems);
        }
        for (int index = 0; index < parent.getItems().size(); index++) {
            Node inherited = parent.getItems().get(index);
            Node desired = desiredItems.get(index);
            if (BlueIdCalculator.calculateBlueId(inherited)
                    .equals(BlueIdCalculator.calculateBlueId(desired))) {
                continue;
            }
            overlayItems.add(new Node()
                    .position(index)
                    .properties(Properties.LIST_CONTROL_REPLACE,
                            desired.clone()));
        }
        for (int index = parent.getItems().size();
             index < desiredItems.size(); index++) {
            overlayItems.add(desiredItems.get(index).clone());
        }
        return new Node().type(parent).items(overlayItems);
    }

    private static void runResolveVariants(JsonNode spec) {
        for (JsonNode variant : requireArray(spec, "variants")) {
            Node source = variant.has("source")
                    ? readNode(variant.get("source"))
                    : readNode(requirePresent(variant, "overlay"));
            attachBaselineType(source, spec);
            runExpectedVariant(spec, variant, source);
        }
    }

    private static void runValidate(JsonNode spec) {
        ProviderContext provider = providerContext(spec, null);
        Blue blue = new Blue(provider.provider);
        Node source = readNode(requirePresent(spec, "source"));
        Node resolved = blue.resolve(blue.preprocess(source));
        if (spec.has("expectedValid")) {
            assertEquals(spec.get("expectedValid").asBoolean(), true);
        }
        if (spec.has("expectedFieldCount")) {
            int fieldCount = resolved.getProperties() == null
                    ? 0 : resolved.getProperties().size();
            assertEquals(spec.get("expectedFieldCount").asInt(), fieldCount);
        }
        if (spec.has("alsoEquivalentTo")) {
            Node equivalent = readNode(spec.get("alsoEquivalentTo"));
            Node equivalentResolved = blue.resolve(blue.preprocess(equivalent));
            assertNodeEquals(resolved, equivalentResolved);
        }
    }

    private static void runValidateVariants(JsonNode spec) {
        for (JsonNode variant : requireArray(spec, "variants")) {
            Node source = readNode(requirePresent(variant, "source"));
            attachBaselineType(source, spec);
            runExpectedVariant(spec, variant, source);
        }
    }

    private static void runExpectedVariant(JsonNode fixture,
                                           JsonNode variant,
                                           Node source) {
        ProviderContext provider = providerContext(fixture, null);
        Blue blue = new Blue(provider.provider);
        try {
            blue.resolve(blue.preprocess(source));
        } catch (RuntimeException failure) {
            if (!variant.hasNonNull("expectedErrorCategory")) {
                throw failure;
            }
            assertExpectedErrorCategory(
                    variant, "expectedErrorCategory", failure);
            return;
        }
        if (variant.hasNonNull("expectedErrorCategory")) {
            throw new AssertionError("Variant expected an error but succeeded.");
        }
        assertTrue(variant.path("expectedValid").asBoolean(false),
                "Successful variant must declare expectedValid: true.");
    }

    private static void runMatch(JsonNode spec) {
        Blue blue = new Blue(providerContext(spec, null).provider);
        Node pattern = readNode(requirePresent(spec, "pattern"));
        Node candidate = readNode(requirePresent(spec, "candidate"));
        boolean matches = blue.nodeMatchesType(candidate, pattern);
        assertEquals(spec.get("expectedMatch").asBoolean(), matches);
        boolean identityEqual = BlueIdCalculator.calculateBlueId(pattern)
                .equals(BlueIdCalculator.calculateBlueId(candidate));
        assertEquals(spec.get("expectedIdentityEqual").asBoolean(), identityEqual);
    }

    private static void runSemanticExists(JsonNode spec) {
        BlueOperationResult<Node> result;
        if (spec.has("providerResult")) {
            JsonNode providerResult = spec.get("providerResult");
            Node partial = readNode(requirePresent(providerResult, "partialObject"));
            boolean complete = providerResult.path(
                    "completeDirectManifest").asBoolean(false);
            DirectNodeManifest manifest = complete
                    ? DirectNodeManifest.complete(partial)
                    : DirectNodeManifest.partial(partial);
            result = manifest.semanticSelect(requireText(spec, "path"));
        } else {
            ProviderContext provider = providerContext(spec, null);
            Blue blue = new Blue(provider.provider);
            Node source = readNode(requirePresent(spec, "source"));
            result = DirectNodeManifest.complete(source)
                    .semanticSelect(requireText(spec, "path"));
        }
        assertOutcome(spec, "expectedOutcome", result.outcome());
        if (spec.has("expectedAbsent")) {
            assertEquals(spec.get("expectedAbsent").asBoolean(), result.isAbsent());
        }
        if (spec.has("expectedReason")) {
            assertEquals(requireText(spec, "expectedReason"),
                    result.reason().orElse(null));
        }
    }

    private static void runVerifyDirectNode(JsonNode spec) {
        Node direct = readNode(requirePresent(spec, "directNode"));
        DirectNodeManifest manifest = DirectNodeManifest.complete(direct);
        BlueOperationResult<Node> result =
                manifest.verify(requireText(spec, "requestedBlueId"));
        assertEquals(spec.get("expectedVerified").asBoolean(),
                result.isEstablished());
        assertEquals(requireText(spec, "expectedNodeBlueId"),
                BlueIdCalculator.calculateBlueId(direct));
        assertTextList(requirePresent(spec, "expectedDescendantRequests"),
                Collections.<String>emptyList());
    }

    private static void runVerifyDirectList(JsonNode spec) {
        assertTrue(requirePresent(spec, "directElementIdentitiesOnly").asBoolean(),
                "Direct list verification fixture must use element identities only.");
        Node list = readNode(requirePresent(spec, "fullList"));
        List<Node> directIdentities = new ArrayList<>();
        for (Node item : list.getItems()) {
            directIdentities.add(new Node().blueId(
                    BlueIdCalculator.calculateBlueId(item)));
        }
        for (Node identity : directIdentities) {
            assertTrue(identity.isReferenceOnly(),
                    "Direct list manifest unexpectedly contains an element body.");
        }
        DirectNodeManifest manifest = DirectNodeManifest.complete(
                new Node().items(directIdentities));
        BlueOperationResult<List<String>> identities =
                manifest.orderedListElementIdentities();
        assertEquals(spec.get("expectedVerified").asBoolean(),
                identities.isEstablished());
        assertEquals(list.getItems().size(),
                identities.requireEstablished().size());
        assertTextList(requirePresent(spec, "expectedElementBodyRequests"),
                Collections.<String>emptyList());
    }

    private static void runRetrieveDirectList(JsonNode spec) {
        JsonNode optimization = requirePresent(spec, "storedOptimization");
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
                        "expectedDirectResultStillContainsAllOrderedElementIdentities")
                        .asBoolean(false),
                requiresCompleteManifest);
    }

    private static void runRegistryNodeHashesToPublishedBlueId(JsonNode spec) {
        requireRegistryKind(spec);
        String key = requireText(spec, "registryKey");
        String expected = requireText(spec, "expectedPublishedBlueId");
        BlueCoreTypeRegistry registry = BlueCoreTypeRegistry.INSTANCE;
        Node registryNode = registry.node(key);
        assertEquals(expected, BlueIdCalculator.calculateBlueId(registryNode));
        assertEquals(expected, registry.blueId(key));
        assertEquals(expected, Properties.CORE_TYPE_NAME_TO_BLUE_ID_MAP.get(key));
        if (spec.has("semanticDescriptionIdentityBearing")) {
            Node withoutDescription = registryNode.clone().description(null);
            boolean identityBearing = !BlueIdCalculator.calculateBlueId(withoutDescription)
                    .equals(BlueIdCalculator.calculateBlueId(registryNode));
            assertEquals(spec.get("semanticDescriptionIdentityBearing").asBoolean(),
                    identityBearing);
        }
    }

    private static void runChangingRegistryDescriptionChangesBlueId(JsonNode spec) {
        requireRegistryKind(spec);
        Node original = BlueCoreTypeRegistry.INSTANCE.node(
                requireText(spec, "registryKey"));
        Node mutated = original.clone();
        JsonNode mutation = requirePresent(spec, "mutation");
        if (!"description".equals(requireText(mutation, "field"))) {
            throw new IllegalArgumentException(
                    "Unsupported registry mutation field.");
        }
        mutated.description((mutated.getDescription() == null
                ? "" : mutated.getDescription())
                + requireText(mutation, "append"));
        boolean changed = !BlueIdCalculator.calculateBlueId(original)
                .equals(BlueIdCalculator.calculateBlueId(mutated));
        assertEquals(spec.get("expectBlueIdChanged").asBoolean(), changed);
    }

    private static void runAssertViewPath(JsonNode spec) {
        Node document = readNode(requirePresent(spec, "document"));
        for (JsonNode assertion : requireArray(spec, "assertions")) {
            String path = requireString(assertion, "path");
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
                requireText(spec, "matchRule").replace('\n', ' '));
        for (JsonNode file : requireArray(spec, "publishableFiles")) {
            String content = readPublishableResource(file.asText());
            JsonNode headings = spec.get("requiredHeadings");
            if (headings != null) {
                for (JsonNode heading : headings) {
                    assertTrue(content.contains(heading.asText()),
                            "Missing required heading in " + file.asText());
                }
            }
            JsonNode forbidden = spec.get("forbiddenJoinedTerms");
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
                requirePresent(spec, "requiresVectorPrefixes"));
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
        assertEquals("pass", requireText(spec, "expected"));
    }

    private static void assertResolutionExpectations(JsonNode spec,
                                                     Node actual,
                                                     Blue blue,
                                                     Node source) {
        assertExpectedResolvedIfPresent(spec, "expectedResolved", actual, blue);
        if (spec.has("expectedResolvedItems")) {
            assertItemValues(spec.get("expectedResolvedItems"),
                    actual.getItems());
        }
        if (spec.has("expectedMergePolicy")) {
            String effective = actual.getMergePolicy() == null
                    ? Properties.LIST_MERGE_POLICY_POSITIONAL
                    : actual.getMergePolicy();
            assertEquals(requireText(spec, "expectedMergePolicy"), effective);
        }
        assertEffectiveTypes(singletonPathMap(
                spec, "expectedEffectiveType"), actual);
        assertExpectedValues(spec.get("expectedValue"), actual);
        if (spec.path(
                "expectedSourceReferencePreservedByCanonicalization")
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
        Node source = readNode(requirePresent(spec, "source"));
        attachBaselineType(source, spec);
        return source;
    }

    private static void attachBaselineType(Node source, JsonNode fixture) {
        Node baseline = null;
        if (fixture.has("parent")) {
            baseline = readNode(fixture.get("parent"));
        } else if (fixture.has("base")) {
            baseline = readNode(fixture.get("base"));
        } else if (fixture.has("fieldDeclaration")) {
            baseline = readNode(fixture.get("fieldDeclaration"));
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
        if (!spec.has("expectedValue")) return;
        Node selected = selectFirstDemand(result.requireEstablished(), limits);
        assertSemanticScalar(spec.get("expectedValue"), selected);
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
                Properties.CORE_TYPE_NAME_TO_BLUE_ID_MAP.entrySet()) {
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
        if (node.getPreviousBlueId() != null) controls.add("$previous");
        if (node.getPosition() != null) controls.add("$pos");
        if (node.getProperties() != null) {
            if (node.getProperties().containsKey("$replace")) {
                controls.add("$replace");
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
                value.replace("-", "_").toUpperCase(java.util.Locale.ROOT));
    }

    private static BlueOperationLimits operationLimits(JsonNode spec) {
        JsonNode limits = requirePresent(spec, "limits");
        List<String> demanded = new ArrayList<>();
        JsonNode paths = limits.get("demandedPaths");
        if (paths == null || !paths.isArray() || paths.size() == 0) {
            demanded.add("");
        } else {
            for (JsonNode path : paths) demanded.add(path.asText());
        }
        int max = limits.has("maxReferenceExpansions")
                ? limits.get("maxReferenceExpansions").asInt()
                : Integer.MAX_VALUE;
        return new BlueOperationLimits(demanded, max);
    }

    private static void assertEquivalentInputs(String actual,
                                               JsonNode inputs) {
        if (inputs == null || inputs.isNull()) return;
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                assertEquals(actual,
                        BlueIdCalculator.calculateBlueId(readNode(input)));
            }
        } else {
            assertEquals(actual,
                    BlueIdCalculator.calculateBlueId(readNode(inputs)));
        }
    }

    private static void assertDifferentInputs(String actual,
                                              JsonNode inputs) {
        if (inputs == null || inputs.isNull()) return;
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                assertTrue(!actual.equals(
                                BlueIdCalculator.calculateBlueId(readNode(input))),
                        "Expected a different BlueId.");
            }
        } else {
            assertTrue(!actual.equals(
                            BlueIdCalculator.calculateBlueId(readNode(inputs))),
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
            JsonNode spec, String field, Node actual, Blue blue) {
        if (spec.has(field)) {
            Node expected = blue.preprocess(readNode(spec.get(field)));
            assertNodeEquals(expected, actual);
        }
    }

    private static void assertNodeEquals(Node expected, Node actual) {
        JsonNode expectedTree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeToMapListOrValue.get(expected));
        JsonNode actualTree = UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeToMapListOrValue.get(actual));
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
                        path + "/" + field.replace("~", "~0").replace("/", "~1"));
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
        Map<String, NodeProviderResult> entries = new LinkedHashMap<>();
        if (!spec.has("provider")) {
            entries.putAll(absentProviderFallback == null
                    ? globalProviderCatalog() : absentProviderFallback);
        } else {
            JsonNode provider = spec.get("provider");
            if (!provider.isArray()) {
                throw new IllegalArgumentException(
                        "Fixture provider must be a list.");
            }
            for (JsonNode entry : provider) {
                addProviderEntry(entries, entry);
            }
        }
        return providerContextWithoutFixtureProvider(entries);
    }

    /**
     * The published type-cycle vector uses readable symbolic IDs. Convert any
     * closed symbolic type-reference graph into a verified cyclic set without
     * keying behavior to the fixture ID or to hard-coded replacement values.
     */
    private static SymbolicTypeCycle symbolicTypeCycle(JsonNode spec) {
        JsonNode sourceNode = spec.get("source");
        JsonNode providerNode = spec.get("provider");
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
            if (entry.has("outcome")) return null;
            String symbolic = entry.has("requestedBlueId")
                    ? requireText(entry, "requestedBlueId")
                    : requireText(entry, "blueId");
            JsonNode returned = entry.has("node")
                    ? entry.get("node") : entry.get("returnedNode");
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
            placeholder.getType().blueId("this#" + target);
            placeholders.add(placeholder);
        }
        List<String> calculated =
                CircularBlueIdCalculator.calculateCircularSetBlueIds(placeholders);
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
                new VerifiedCyclicFixtureProvider(verifiedEntries));
    }

    private static ProviderContext providerContextWithoutFixtureProvider(
            Map<String, NodeProviderResult> entries) {
        FixtureProvider provider = new FixtureProvider(entries);
        return new ProviderContext(provider);
    }

    private static void addProviderEntry(
            Map<String, NodeProviderResult> entries, JsonNode entry) {
        String requested = entry.has("requestedBlueId")
                ? entry.get("requestedBlueId").asText()
                : requireText(entry, "blueId");
        if (entry.has("outcome")) {
            String outcome = entry.get("outcome").asText();
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
        JsonNode node = entry.has("returnedNode")
                ? entry.get("returnedNode") : entry.get("node");
        if (node == null) {
            throw new IllegalArgumentException(
                    "Provider entry requires node/returnedNode or outcome.");
        }
        entries.put(requested, NodeProviderResult.found(
                Collections.singletonList(readNode(node))));
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
                JsonNode provider = spec.get("provider");
                if (provider == null || !provider.isArray()) continue;
                for (JsonNode entry : provider) {
                    if (entry.has("outcome")) continue;
                    String requested = entry.has("requestedBlueId")
                            ? entry.get("requestedBlueId").asText()
                            : null;
                    JsonNode node = entry.has("node")
                            ? entry.get("node") : entry.get("returnedNode");
                    if (requested == null || node == null) continue;
                    try {
                        Node content = readNode(node);
                        if (requested.equals(
                                BlueIdCalculator.calculateBlueId(content))) {
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
        assertEquals(125, manifest.path("behaviorFixtureCount").asInt());
        assertEquals(125,
                BlueConformanceReport.requiredFixtureIdsForBlueLanguage10().size());
        JsonNode files = requireArray(manifest, "files");
        List<FixtureEntry> result = new ArrayList<>();
        String previousPath = null;
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode file : files) {
            String path = requireText(file, "path");
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
            String id = requireText(fixture, "id");
            if (!ids.add(id)) {
                throw new IllegalStateException(
                        "Duplicate Language fixture id: " + id);
            }
            result.add(new FixtureEntry(id,
                    BlueFixtureCategory.fromLabel(
                            requireText(fixture, "category")), path));
        }
        assertEquals(125, result.size());
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
        requireText(spec, "id");
        BlueFixtureCategory.fromLabel(requireText(spec, "category"));
        String operation = requireText(spec, "operation");
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException(
                    "Unsupported fixture operation: " + operation);
        }
        if (spec.has("profile")) {
            throw new IllegalArgumentException(
                    "Language fixtures use category, not profile.");
        }
        if (spec.has("expectedErrorCategory")) {
            BlueLanguageErrorCategory.valueOf(
                    requireText(spec, "expectedErrorCategory"));
        }
        boolean hasAssertion = spec.path("expectError").asBoolean(false);
        java.util.Iterator<String> fields = spec.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            hasAssertion |= field.startsWith("expected")
                    || field.startsWith("also")
                    || "assertions".equals(field)
                    || "variants".equals(field)
                    || "requiredHeadings".equals(field)
                    || "forbiddenJoinedTerms".equals(field)
                    || "expectBlueIdChanged".equals(field);
        }
        if (!hasAssertion) {
            throw new IllegalArgumentException(
                    "Fixture has no expected result assertion: "
                            + requireText(spec, "id"));
        }
    }

    private static BlueConformanceFailure failure(
            FixtureEntry fixture, Throwable throwable) {
        String operation = null;
        try {
            operation = requireText(
                    readYamlResource(FIXTURE_ROOT + fixture.path), "operation");
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
                requireText(spec, "registryKind"));
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

        private VerifiedCyclicFixtureProvider(String blueId, Node content) {
            this(Collections.singletonMap(
                    blueId, NodeProviderResult.found(
                            Collections.singletonList(content))));
        }

        private VerifiedCyclicFixtureProvider(
                Map<String, NodeProviderResult> entries) {
            super(entries);
            this.verifiedBlueIds =
                    Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            return verifiedBlueIds.contains(blueId);
        }
    }
}
