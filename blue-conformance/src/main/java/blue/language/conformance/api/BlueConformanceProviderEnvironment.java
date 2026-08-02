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


/** Builds verified provider environments for one Language fixture. */
abstract class BlueConformanceProviderEnvironment extends BlueConformanceFixturePackage {

    static ProviderContext providerContext(
            JsonNode spec, Map<String, NodeProviderResult> absentProviderFallback) {
        return providerContext(
                spec,
                absentProviderFallback,
                Collections.emptySet());
    }

    static ProviderContext preprocessingProviderContext(
            JsonNode spec) {
        return providerContext(
                spec,
                null,
                preprocessingDirectiveBlueIds(spec));
    }

    static ProviderContext providerContext(
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

    static Map<String, String> preprocessingAliases(
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

    static Set<String> preprocessingDirectiveBlueIds(
            JsonNode spec) {
        Set<String> result = new LinkedHashSet<>(
                preprocessingAliases(spec).values());
        addPreprocessingDirectiveBlueId(
                result, spec.get(FixtureField.SOURCE));
        addPreprocessingDirectiveBlueId(
                result, spec.get(FixtureField.ALSO_EQUIVALENT_TO));
        return Collections.unmodifiableSet(result);
    }

    static void addPreprocessingDirectiveBlueId(
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
    static SymbolicTypeCycle symbolicTypeCycle(JsonNode spec) {
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

    static ProviderContext providerContextWithoutFixtureProvider(
            Map<String, NodeProviderResult> entries) {
        FixtureProvider provider = new FixtureProvider(entries);
        return new ProviderContext(provider);
    }

    static void addProviderEntry(
            Map<String, NodeProviderResult> entries, JsonNode entry) {
        addProviderEntry(entries, entry, Collections.emptySet());
    }

    static void addProviderEntry(
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

    static volatile Map<String, NodeProviderResult> providerCatalog;

    static Map<String, NodeProviderResult> globalProviderCatalog() {
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

}
