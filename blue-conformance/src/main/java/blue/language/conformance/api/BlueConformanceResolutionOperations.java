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


/** Executes resolution, validation, matching, and documentation operations. */
abstract class BlueConformanceResolutionOperations extends BlueConformanceGraphOperations {

    static void runExpandVariants(JsonNode spec) {
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

    static void runVerifyResolvedFormNotDirectIdentityInput(JsonNode spec) {
        LanguageFixtureRuntime blue = new LanguageFixtureRuntime(
                providerContext(spec, null).provider);
        Node source = readNode(requirePresent(spec, FixtureField.SOURCE));
        Node resolved = blue.resolve(blue.preprocess(source.clone()));
        Node canonical = blue.canonicalize(source);
        String contentBlueId = blue.calculateSourceDocumentBlueId(source);
        String canonicalIdentityInputBlueId =
                DirectBlueIdCalculator.calculateBlueId(canonical);
        assertEquals(spec.path(
                        FixtureField.EXPECTED_CONTENT_BLUE_ID_EQUALS_CANONICAL_IDENTITY_INPUT)
                        .asBoolean(false),
                contentBlueId.equals(canonicalIdentityInputBlueId));
        try {
            DirectBlueIdCalculator.calculateBlueId(resolved);
            throw new AssertionError(
                    "Resolved Form must not be accepted as direct Canonical Identity Input.");
        } catch (IllegalArgumentException expected) {
            // The strict direct boundary rejects expanded type metadata.
        }
    }

    static void runMinimizeAndResolve(JsonNode spec) {
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

    static Node sourceForResolvedItems(
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

    static void runResolveVariants(JsonNode spec) {
        for (JsonNode variant : requireArray(spec, FixtureField.VARIANTS)) {
            Node source = variant.has(FixtureField.SOURCE)
                    ? readNode(variant.get(FixtureField.SOURCE))
                    : readNode(requirePresent(variant, "overlay"));
            attachBaselineType(source, spec);
            runExpectedVariant(spec, variant, source);
        }
    }

    static void runValidate(JsonNode spec) {
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

    static void runValidateVariants(JsonNode spec) {
        for (JsonNode variant : requireArray(spec, FixtureField.VARIANTS)) {
            Node source = readNode(requirePresent(variant, FixtureField.SOURCE));
            attachBaselineType(source, spec);
            runExpectedVariant(spec, variant, source);
        }
    }

    static void runExpectedVariant(JsonNode fixture,
                                           JsonNode variant,
                                           Node source) {
        ProviderContext provider = providerContext(fixture, null);
        LanguageFixtureRuntime blue =
                new LanguageFixtureRuntime(provider.provider);
        Node actual;
        try {
            actual = blue.resolve(blue.preprocess(source));
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
        assertResolutionExpectations(variant, actual, blue, source);
    }

    static void runMatch(JsonNode spec) {
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

    static void runSemanticExists(JsonNode spec) {
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

    static void runVerifyDirectNode(JsonNode spec) {
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

    static void runVerifyDirectList(JsonNode spec) {
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

    static void runRetrieveDirectList(JsonNode spec) {
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

    static void runRegistryNodeHashesToPublishedBlueId(JsonNode spec) {
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

    static void runChangingRegistryDescriptionChangesBlueId(JsonNode spec) {
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

    static void runAssertViewPath(JsonNode spec) {
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

    static void runLintPublishableDocumentation(JsonNode spec) {
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

}
