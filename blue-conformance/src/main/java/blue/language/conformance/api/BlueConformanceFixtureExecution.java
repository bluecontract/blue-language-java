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


/** Dispatches and executes one validated Language fixture. */
abstract class BlueConformanceFixtureExecution extends BlueConformanceResolutionOperations {

    static void runFixture(FixtureEntry fixture,
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

    static boolean expectsTopLevelError(JsonNode spec, String operation) {
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

    static void runOperation(JsonNode spec,
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
            case FixtureOperation.VERIFY_RESOLVED_FORM_NOT_DIRECT_IDENTITY_INPUT:
                runVerifyResolvedFormNotDirectIdentityInput(spec);
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
            case FixtureOperation.RESOLVE_DEFINITION:
                runResolveDefinition(spec);
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

    static void runSuiteAssertion(JsonNode spec,
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

}
