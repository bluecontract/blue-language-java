package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.mapping.TypeClassResolver;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractDiscoveryServicesTest {

    @Test
    void shouldCollectSourceContributionIdentitiesInAncestorToDescendantOrder() {
        // given
        Node baseContribution = new Node().properties(
                "program", new Node().value("base"));
        Node derivedContribution = new Node().properties(
                "program", new Node().value("derived"));
        Node baseType = new Node()
                .name("Discovery base")
                .contracts(new Node().properties("run", baseContribution));
        String baseTypeBlueId = BlueIdCalculator.calculateBlueId(baseType);
        Node derivedType = new Node()
                .name("Discovery derived")
                .type(new Node().blueId(baseTypeBlueId))
                .contracts(new Node().properties("run", derivedContribution));
        String derivedTypeBlueId = BlueIdCalculator.calculateBlueId(derivedType);
        ContractContributionCollector collector =
                new ContractContributionCollector(
                        new BasicNodeProvider(baseType, derivedType));

        // when
        ContractContributionResolver.BindingResolution resolution =
                collector.collect(
                        new Node().type(new Node().blueId(derivedTypeBlueId)),
                        null,
                        "run",
                        true,
                        Collections.singletonList("program"));

        // then
        assertEquals(
                Arrays.asList(
                        BlueIdCalculator.calculateBlueId(baseContribution),
                        BlueIdCalculator.calculateBlueId(derivedContribution)),
                resolution.sourceContributions());
        assertEquals(
                resolution.sourceContributions().get(1),
                resolution.executableBodySources()
                        .get("program")
                        .owningContributionBlueId());
    }

    @Test
    void shouldKeepExactExecutableBodyColdWhileLoadingItsHeader() {
        // given
        TypeClassResolver resolver =
                new TypeClassResolver("blue.language.processor.model");
        ExecutableBodyLoader loader =
                new ExecutableBodyLoader(new NodeToObjectConverter(resolver));
        String bodyBlueId = blueId("exact body");
        Node bodyReference = new Node().blueId(bodyBlueId);
        FrozenNode effective = FrozenNode.fromResolvedNode(
                new Node()
                        .properties("order", new Node().value(7))
                        .properties("program", new Node().value("resolved-body")));

        // when
        Node executable = loader.exactExecutableContract(
                effective,
                Collections.singletonList("program"),
                Collections.singletonMap("program", bodyReference));
        Node header = loader.headerNode(
                executable,
                Collections.singletonList("program"));

        // then
        assertTrue(executable.getProperties().get("program").isReferenceOnly());
        assertEquals(
                bodyBlueId,
                executable.getProperties().get("program").getBlueId());
        assertFalse(header.getProperties().containsKey("program"));
        assertTrue(header.getProperties().containsKey("order"));
    }

    @Test
    void shouldInvalidateStructuralCacheKeysWhenTypeOrEffectiveContractsChange() {
        // given
        ContractSnapshotCache cache = new ContractSnapshotCache(
                BlueCachePolicy.boundedDefaults());
        String selectedAType = blueId("selected type a");
        String selectedBType = blueId("selected type b");
        String effectiveAType = blueId("effective type a");
        String effectiveBType = blueId("effective type b");
        Node selectedA = new Node().type(new Node().blueId(selectedAType));
        Node selectedB = new Node().type(new Node().blueId(selectedBType));
        FrozenNode effectiveA = FrozenNode.fromResolvedNode(
                new Node().type(new Node().blueId(effectiveAType)));
        FrozenNode effectiveB = FrozenNode.fromResolvedNode(
                new Node().type(new Node().blueId(effectiveBType)));
        FrozenNode contractsChanged = FrozenNode.fromResolvedNode(
                new Node()
                        .type(new Node().blueId(effectiveAType))
                        .contracts(new Node().properties(
                                "handler",
                                new Node().properties(
                                        "order", new Node().value(1)))));

        // when
        ContractSnapshotCache.Key original =
                cache.key(selectedA, effectiveA, "/", 1L);
        ContractSnapshotCache.Key selectedTypeChanged =
                cache.key(selectedB, effectiveA, "/", 1L);
        ContractSnapshotCache.Key effectiveTypeChanged =
                cache.key(selectedA, effectiveB, "/", 1L);
        ContractSnapshotCache.Key effectiveContractsChanged =
                cache.key(selectedA, contractsChanged, "/", 1L);

        // then
        assertNotEquals(original, selectedTypeChanged);
        assertNotEquals(original, effectiveTypeChanged);
        assertNotEquals(original, effectiveContractsChanged);
    }

    @Test
    void shouldReuseFrozenDeliverySnapshotButRebuildEveryMeteredRecognition() {
        // given
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create().registerDefaults().build();
        TypeClassResolver resolver =
                new TypeClassResolver("blue.language.processor.model");
        ContractContributionCollector contributions =
                new ContractContributionCollector(null);
        EffectiveContractResolver effectiveContracts =
                new EffectiveContractResolver(
                        registry,
                        new NodeToObjectConverter(resolver),
                        resolver,
                        contributions);
        ContractRefreshService refresh = new ContractRefreshService(
                registry,
                effectiveContracts,
                new ContractSnapshotCache(BlueCachePolicy.boundedDefaults()));
        EffectiveContractSnapshot frozenDelivery =
                EffectiveContractSnapshot.builder("/", "delivery")
                        .effectiveTypeBlueId(blueId("delivery type"))
                        .role(EffectiveContractSnapshotConstants.Role.EXECUTABLE_EXTENSION)
                        .sourceContribution(blueId("source contribution"))
                        .build();
        AtomicInteger builds = new AtomicInteger();
        ContractRefreshService.StructuralBundleLoader structural =
                (selected, effective, scope, meter, reason) -> {
                    builds.incrementAndGet();
                    return ContractBundle.builder()
                            .addEffectiveContractSnapshot(frozenDelivery)
                            .build();
                };
        Node selectedScope = new Node().contracts(
                new Node().properties(
                        "initialized",
                        new Node().type(
                                new Node().blueId(
                                        RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))));
        FrozenNode effectiveScope = FrozenNode.fromResolvedNode(selectedScope);

        // when
        ContractBundle first = refresh.load(
                selectedScope,
                effectiveScope,
                "/",
                NoOpProcessingObserver.INSTANCE,
                null,
                null,
                structural);
        ContractBundle second = refresh.load(
                selectedScope,
                effectiveScope,
                "/",
                NoOpProcessingObserver.INSTANCE,
                null,
                null,
                structural);
        ContractRecognitionMeter meter =
                new ContractRecognitionMeter(new GasMeter(GasSchedule.contracts10()));
        refresh.load(
                selectedScope,
                effectiveScope,
                "/",
                NoOpProcessingObserver.INSTANCE,
                meter,
                "test",
                structural);
        refresh.load(
                selectedScope,
                effectiveScope,
                "/",
                NoOpProcessingObserver.INSTANCE,
                meter,
                "test",
                structural);

        // then
        assertEquals(3, builds.get());
        assertNotSame(first, second);
        assertNotSame(
                first.marker("initialized"),
                second.marker("initialized"));
        assertSame(
                first.effectiveContractSnapshot("delivery"),
                second.effectiveContractSnapshot("delivery"));
    }

    private String blueId(String value) {
        return BlueIdCalculator.calculateBlueId(new Node().value(value));
    }
}
