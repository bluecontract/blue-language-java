package blue.language.conformance.contracts;

import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private final ContractsFixtureHarnessDataSupport.ProcessorBundle bundle;
    private final Set<String> requiredProviderLoads;

    private ClosureFixtureRuntime(JsonNode fixture) {
        JsonNode selected = Objects.requireNonNull(fixture, "fixture");
        JsonNode controls = selected.get("runtime");
        if (controls == null || !controls.isObject()) {
            throw new IllegalArgumentException(
                    "Closure fixture requires top-level runtime controls");
        }
        ContractsFixtureHarnessDataSupport.RegistryEnvironment registry =
                ContractsFixtureHarnessDataSupport.RegistryEnvironment.load();
        Map<String, Node> providerNodes = new LinkedHashMap<String, Node>(
                registry.nodesByBlueId);
        LinkedHashSet<String> unavailable = new LinkedHashSet<String>();
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
                Node previous = providerNodes.put(
                        entry.getKey(), node.clone());
                if (previous != null
                        && !NodeWireForm.get(previous).equals(
                                NodeWireForm.get(node))) {
                    throw new IllegalArgumentException(
                            "Closure fixture provider conflicts with the registry: "
                                    + entry.getKey());
                }
            });
            addTextValues(
                    providerHarness.get("expectedRequiredBlueIds"),
                    unavailable,
                    "expectedRequiredBlueIds");
            if (!Collections.disjoint(
                    unavailable, providerNodes.keySet())) {
                throw new IllegalArgumentException(
                        "Unavailable closure fixture provider nodes are present");
            }
        }
        ContractsFixtureHarnessDataSupport.FixturePhysicalProvider provider =
                new ContractsFixtureHarnessDataSupport.FixturePhysicalProvider(
                        providerNodes, "cold", "unbatched", unavailable);
        final BlueLanguageRuntime language =
                ContractsFixtureExecutionEngine.languageRuntime(provider);
        final ConformanceEngine conformance =
                language.newConformanceEngine();
        ProcessingSnapshotManager snapshots = snapshots(language);
        ScriptedContractsRuntime scripted =
                new ScriptedContractsRuntime(controls);
        DocumentProcessor processor = DocumentProcessor.builder()
                .matchingService(new ContractMatchingService(language))
                .conformanceEngine(conformance)
                .snapshotStore(snapshots)
                .gasSchedule(GasSchedule.contracts10())
                .runtimeRegistryIdentity(
                        BlueContractsConformanceReport
                                .CONTRACTS_REGISTRY_PACKAGE_IDENTITY)
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
                .build();
        this.bundle = new ContractsFixtureHarnessDataSupport.ProcessorBundle(
                processor,
                scripted,
                null,
                language,
                conformance,
                provider);
        this.requiredProviderLoads = Collections.unmodifiableSet(
                new LinkedHashSet<String>(unavailable));
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
        return new ClosureFixtureRuntime(selected);
    }

    /**
     * Returns the configured ordinary processor borrowed by the closure
     * composition root.
     *
     * @return live conformance-owned document processor
     */
    public DocumentProcessor processor() {
        return bundle.processor;
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
        bundle.provider.verifyExpectedLoads(
                new LinkedHashSet<String>(Objects.requireNonNull(
                        blueIds, "blueIds")));
    }

    /** Closes the processor, Language runtime, and conformance engine. */
    @Override
    public void close() {
        bundle.provider.verifyPreparation();
        bundle.provider.verifyExpectedLoads(requiredProviderLoads);
        bundle.close();
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

    private static ProcessingSnapshotManager snapshots(
            final BlueLanguageRuntime language) {
        return new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                return language.snapshots().resolve(document);
            }

            @Override
            public ResolvedSnapshot fromDocumentPreservingPaths(
                    Node document,
                    Collection<String> preservedPaths) {
                return language.snapshots().resolvePreservingPaths(
                        document, preservedPaths);
            }

            @Override
            public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                    Node document,
                    Collection<String> preservedPaths) {
                return language.snapshots().resolvePreservingPaths(
                        document, preservedPaths);
            }

            @Override
            public FrozenNode materializeVerifiedExactReference(
                    FrozenNode reference) {
                if (!reference.isReferenceOnly()) {
                    return reference;
                }
                return language.snapshots().load(
                        reference.getReferenceBlueId())
                        .frozenCanonicalRoot();
            }

            @Override
            public ResolvedSnapshot applyPatch(
                    ResolvedSnapshot snapshot,
                    JsonPatch patch) {
                return language.patching().apply(snapshot, patch);
            }

            @Override
            public ResolvedSnapshot cacheSnapshot(
                    ResolvedSnapshot snapshot) {
                language.snapshots().cache(snapshot);
                return snapshot;
            }
        };
    }
}
