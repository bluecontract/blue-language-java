package blue.language.conformance.contracts;

import blue.language.conformance.ConformanceEngine;
import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifiedNodeProvider;
import blue.language.registry.BootstrapProvider;
import blue.language.runtime.BlueLanguage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.file.Path;

/**
 * Conformance-owned ordinary processor runtime for closure fixtures.
 *
 * <p>This bridge exposes only the configured {@link DocumentProcessor}. The
 * closure harness still parses normative invocation input independently, and
 * the fixture {@code expected} subtree is never supplied to execution.
 * Document-qualified Handler controls are selected from the executing Root's
 * own authored {@code /documentId}; they do not add a parent, container, or
 * reverse-occurrence value to the ordinary processor context.</p>
 */
public final class ClosureFixtureRuntime
        implements AutoCloseable {

    private final DocumentProcessor processor;
    private final ConformanceEngine conformance;
    private final LanguageProcessingScopeSnapshotManager snapshots;
    private final BlueLanguage language;
    private final ContractsFixturePhysicalProvider
            provider;
    private final Set<String> expectedProviderLoads;
    private final boolean explicitPhysicalMode;
    private final ScriptedContractsRuntime scripted;

    private ClosureFixtureRuntime(
            JsonNode fixture,
            ContractsFixtureRegistryEnvironment registry) {
        JsonNode selected = Objects.requireNonNull(fixture, "fixture");
        JsonNode controls = selected.get("runtime");
        if (controls == null || !controls.isObject()) {
            throw new IllegalArgumentException(
                    "Closure fixture requires top-level runtime controls");
        }
        Map<String, Node> providerNodes = new LinkedHashMap<String, Node>();
        LinkedHashSet<String> unavailable = new LinkedHashSet<String>();
        LinkedHashSet<String> expectedLoads = new LinkedHashSet<String>();
        String cacheMode = "cold";
        String batchingMode = "unbatched";
        boolean explicitMode = false;
        JsonNode providerHarness = selected.get("provider");
        if (providerHarness != null && providerHarness.isObject()) {
            JsonNode nodes = providerHarness.get("nodes");
            if (nodes == null || !nodes.isObject()) {
                throw new IllegalArgumentException(
                        "Closure fixture provider nodes must be an object");
            }
            nodes.fields().forEachRemaining(entry -> {
                Node node = UncheckedObjectMapper.JSON_MAPPER.convertValue(
                        entry.getValue(), Node.class);
                if (!entry.getKey().equals(
                        DirectBlueIdCalculator.calculateBlueId(node))) {
                    throw new IllegalArgumentException(
                            "Closure fixture provider node has the wrong BlueId: "
                                    + entry.getKey());
                }
                if (registry.nodesByBlueId.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Closure fixture provider duplicates the registry: "
                                    + entry.getKey());
                }
                Node previous = providerNodes.put(
                        entry.getKey(), node.clone());
                if (previous != null
                        && !NodeWireForm.get(previous).equals(
                                NodeWireForm.get(node))) {
                    throw new IllegalArgumentException(
                            "Closure fixture provider contains a BlueId collision: "
                                    + entry.getKey());
                }
            });
            addTextValues(
                    providerHarness.get("expectedRequiredBlueIds"),
                    unavailable,
                    "expectedRequiredBlueIds");
            addTextValues(
                    providerHarness.get("expectedLoads"),
                    expectedLoads,
                    "expectedLoads");
            cacheMode = optionalText(
                    providerHarness, "cache", "cold");
            batchingMode = optionalText(
                    providerHarness, "batching", "unbatched");
            explicitMode = providerHarness.has("cache")
                    || providerHarness.has("batching");
            if (!Collections.disjoint(
                    unavailable, registry.nodesByBlueId.keySet())
                    || !Collections.disjoint(
                    unavailable, providerNodes.keySet())) {
                throw new IllegalArgumentException(
                        "Unavailable closure fixture provider nodes are present");
            }
            if (!expectedLoads.containsAll(unavailable)) {
                throw new IllegalArgumentException(
                        "Closure fixture provider expectedLoads must include "
                                + "every expectedRequiredBlueId");
            }
            LinkedHashSet<String> availableExpected =
                    new LinkedHashSet<String>(expectedLoads);
            availableExpected.removeAll(unavailable);
            LinkedHashSet<String> availableNodes =
                    new LinkedHashSet<String>(registry.nodesByBlueId.keySet());
            availableNodes.addAll(providerNodes.keySet());
            if (explicitMode
                    && !availableNodes.containsAll(availableExpected)) {
                LinkedHashSet<String> missing =
                        new LinkedHashSet<String>(availableExpected);
                missing.removeAll(availableNodes);
                throw new IllegalArgumentException(
                        "Closure fixture physical provider expectedLoads "
                                + "names absent backing content in "
                                + selected.path("id").asText("<unknown>")
                                + ": " + missing);
            }
        }
        ContractsFixturePhysicalProvider provider =
                new ContractsFixturePhysicalProvider(
                        providerNodes, cacheMode, batchingMode, unavailable);
        final BlueLanguage language = productionLanguage(
                registry.nodesByBlueId, provider);
        final LanguageProcessingScopeSnapshotManager snapshots =
                new LanguageProcessingScopeSnapshotManager(
                        language.processing().openScope());
        final ConformanceEngine conformance =
                language.processing().newConformanceEngine();
        ScriptedContractsRuntime scripted =
                new ScriptedContractsRuntime(controls);
        DocumentProcessor processor = DocumentProcessor.builder()
                .matchingService(new ContractMatchingService(
                        language.processing().runtimeAccess()))
                .conformanceEngine(conformance)
                .snapshotStore(snapshots)
                .gasSchedule(GasSchedule.contracts10())
                .runtimeRegistryIdentity(registry.runtimeRegistryIdentity)
                .registerContractType(
                        RuntimeBlueIds.FIXTURE_EVENT,
                        FixtureNonChannelContract.Value.class)
                .registerContractProcessor(
                        MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                        registry.require(
                                MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL),
                        new MockExternalChannelProcessor())
                .registerContractProcessor(
                        MockTypeBlueIds.MOCK_HANDLER,
                        registry.require(MockTypeBlueIds.MOCK_HANDLER),
                        new MockHandlerProcessor(scripted))
                .registerContractProcessor(
                        MockTypeBlueIds.MOCK_OPERATION,
                        registry.require(MockTypeBlueIds.MOCK_OPERATION),
                        new MockOperationProcessor(scripted))
                .build();
        this.processor = processor;
        this.conformance = conformance;
        this.snapshots = snapshots;
        this.language = language;
        this.provider = provider;
        this.expectedProviderLoads = Collections.unmodifiableSet(
                new LinkedHashSet<String>(expectedLoads));
        this.explicitPhysicalMode = explicitMode;
        this.scripted = scripted;
    }

    /**
     * Creates a runtime from the closed top-level controls of one closure
     * fixture.
     *
     * @param fixture complete closure fixture envelope
     * @return owned runtime using the released conformance registry
     */
    public static ClosureFixtureRuntime fromFixture(JsonNode fixture) {
        JsonNode selected = Objects.requireNonNull(fixture, "fixture");
        JsonNode runtime = selected.get("runtime");
        if (runtime == null || !runtime.isObject()) {
            throw new IllegalArgumentException(
                    "Closure fixture requires top-level runtime controls");
        }
        return new ClosureFixtureRuntime(
                selected,
                ContractsFixtureRegistryEnvironment.load());
    }

    /**
     * Creates a runtime whose exact provider and registry identity are loaded
     * exclusively from an explicit candidate Contracts package.
     *
     * @param fixture complete closure fixture envelope
     * @param packageRoot absolute root of the candidate closure package
     * @return owned runtime bound to that candidate package
     */
    public static ClosureFixtureRuntime fromFixture(
            JsonNode fixture,
            Path packageRoot) {
        JsonNode selected = Objects.requireNonNull(fixture, "fixture");
        JsonNode runtime = selected.get("runtime");
        if (runtime == null || !runtime.isObject()) {
            throw new IllegalArgumentException(
                    "Closure fixture requires top-level runtime controls");
        }
        return new ClosureFixtureRuntime(
                selected,
                ContractsFixtureRegistryEnvironment.load(
                        Objects.requireNonNull(packageRoot, "packageRoot")));
    }

    /**
     * Returns the configured ordinary processor borrowed by the closure
     * composition root.
     *
     * @return live conformance-owned document processor
     */
    public DocumentProcessor processor() {
        return processor;
    }

    /**
     * Verifies that execution physically requested each named exact node.
     *
     * <p>This assertion is transport-only test evidence. It does not expose
     * provider requests to the processor's semantic result.</p>
     *
     * @param blueIds exact provider nodes expected to have been loaded
     */
    void verifyExactProviderLoads(Collection<String> blueIds) {
        provider.verifyExpectedLoads(
                new LinkedHashSet<String>(Objects.requireNonNull(
                        blueIds, "blueIds")));
    }

    /**
     * Returns harness-local tentative effects recorded by scripted ordinary
     * handlers during this runtime's execution.
     *
     * <p>The projection is intentionally package-private and never enters the
     * closure result or observer evidence.</p>
     */
    JsonNode tentativeTranscript() {
        return scripted.tentativeTranscript();
    }

    /** Closes the processor, Language runtime, and conformance engine. */
    @Override
    public void close() {
        provider.verifyPreparation();
        if (explicitPhysicalMode) {
            provider.verifyExactPhysicalLoads(expectedProviderLoads);
        } else {
            provider.verifyExpectedLoads(expectedProviderLoads);
        }
        try {
            processor.close();
        } finally {
            try {
                snapshots.releaseTransientState();
            } finally {
                try {
                    conformance.close();
                } finally {
                    language.close();
                }
            }
        }
    }

    private static void addTextValues(
            JsonNode values,
            Set<String> target,
            String field) {
        if (values == null || !values.isArray()) {
            throw new IllegalArgumentException(
                    "Closure fixture provider " + field + " must be an array");
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isEmpty()
                    || !target.add(value.textValue())) {
                throw new IllegalArgumentException(
                        "Closure fixture provider " + field
                                + " must contain unique non-empty text");
            }
        }
    }

    private static String optionalText(
            JsonNode parent,
            String field,
            String fallback) {
        JsonNode value = parent.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException(
                    "Closure fixture provider " + field
                            + " must be non-empty text");
        }
        return value.textValue();
    }

    private static BlueLanguage productionLanguage(
            Map<String, Node> registryNodes,
            NodeProvider provider) {
        NodeProvider candidateRegistry = blueId -> {
            Node exact = registryNodes.get(blueId);
            return exact == null ? null
                    : Collections.singletonList(exact.clone());
        };
        NodeProvider processorLanguageProvider =
                new SequentialNodeProvider(
                        BootstrapProvider.INSTANCE,
                        new VerifiedNodeProvider(
                                BlueRuntimeTypeRegistry.getDefault()
                                        .asProcessorSnapshotProvider()),
                        candidateRegistry,
                        provider);
        return BlueLanguage.builder()
                .nodeProvider(processorLanguageProvider)
                .cachePolicy(BlueCachePolicy.boundedDefaults())
                .build();
    }
}
