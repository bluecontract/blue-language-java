package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.EmbeddedCollectionBenchmarkSupport.CountingMaterializer;
import blue.language.processor.EmbeddedCollectionBenchmarkSupport.GasObservation;
import blue.language.processor.EmbeddedCollectionBenchmarkSupport.MemberFixture;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.CHANNEL_KEY;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.PARAM_SIZE_ONE_HUNDRED;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.PARAM_SIZE_ONE_THOUSAND;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.PARAM_SIZE_PORTABLE_EDGE;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.PARAM_SIZE_TEN;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.SELECTED_MEMBER_DIVISOR;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.collectionPath;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.memberFixture;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.memberKey;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.plainScope;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.requireEqualLogicalGas;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.requireExpectedGasObservation;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.scope;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.validationContext;

/** Trial-scoped immutable projection, gas, catalog, and delta fixtures. */
@State(Scope.Thread)
public class EmbeddedCollectionProjectionState {

    /** Direct collection size measured by the invocation. */
    @Param({
            PARAM_SIZE_TEN,
            PARAM_SIZE_ONE_HUNDRED,
            PARAM_SIZE_ONE_THOUSAND,
            PARAM_SIZE_PORTABLE_EDGE})
    public int size;

    FrozenNode inlineScope;
    FrozenNode pureTargetScope;
    FrozenNode pureMemberScope;
    CountingMaterializer pureTargetProvider;
    CountingMaterializer pureMemberProvider;
    GasObservation inlineGas;
    GasObservation pureTargetGas;
    GasObservation pureMemberGas;
    SubscriptionSurfaceValidationContext additionContext;
    SubscriptionSurfaceValidationContext removalContext;
    SubscriptionSurfaceValidationContext finalDeltaContext;
    String rootBlueId;
    Map<String, List<String>> catalogPaths;
    Map<String, List<EffectiveContractSnapshot>> catalogContracts;

    /** Builds exact immutable fixtures outside every timed operation. */
    @Setup(Level.Trial)
    public void prepare() {
        MemberFixture members = memberFixture(size);
        Node inline = scope(members.inlineMembers);
        inlineScope = FrozenNode.fromResolvedNode(inline);

        Node pureTargetCollection =
                new Node().properties(members.inlineMembers);
        String pureTargetBlueId = DirectBlueIdCalculator
                .calculateBlueId(pureTargetCollection);
        Map<String, FrozenNode> targetContent = new LinkedHashMap<>();
        targetContent.put(
                pureTargetBlueId,
                FrozenNode.fromResolvedNode(pureTargetCollection));
        pureTargetProvider = new CountingMaterializer(targetContent);
        pureTargetScope = FrozenNode.fromResolvedNode(
                scope(new Node().blueId(pureTargetBlueId)));

        pureMemberProvider =
                new CountingMaterializer(members.exactMemberHeaders);
        pureMemberScope = FrozenNode.fromResolvedNode(
                scope(members.referenceMembers));

        inlineGas = observeGas(
                inlineScope,
                new CountingMaterializer(
                        Collections.<String, FrozenNode>emptyMap()));
        pureTargetGas = observeGas(
                pureTargetScope,
                pureTargetProvider);
        pureMemberGas = observeGas(
                pureMemberScope,
                pureMemberProvider);
        requireExpectedGasObservation(size, inlineGas);
        requireExpectedGasObservation(size, pureTargetGas);
        requireExpectedGasObservation(size, pureMemberGas);
        requireEqualLogicalGas(
                inlineGas,
                pureTargetGas,
                "pure-reference collection target");
        requireEqualLogicalGas(
                inlineGas,
                pureMemberGas,
                "pure-reference member headers");

        int smallerSize = size - 1;
        Node smaller = plainScope(smallerSize, null);
        Node full = plainScope(size, null);
        String changedMember = memberKey(smallerSize);
        Set<String> changedPath = Collections.singleton(
                PointerUtils.appendPointer(
                        collectionPath(), changedMember));
        additionContext = validationContext(
                smaller,
                full,
                changedPath);
        removalContext = validationContext(
                full,
                smaller,
                changedPath);
        String selected = memberKey(size / SELECTED_MEMBER_DIVISOR);
        Node beforeChannel = plainScope(size, null);
        Node afterChannel = plainScope(size, selected);
        finalDeltaContext = validationContext(
                beforeChannel,
                afterChannel,
                Collections.singleton(
                        PointerUtils.appendPointer(
                                PointerUtils.appendPointer(
                                        PointerUtils.appendPointer(
                                                collectionPath(), selected),
                                        ProcessorContractConstants
                                                .KEY_CONTRACTS),
                                CHANNEL_KEY)));

        rootBlueId = DirectBlueIdCalculator.calculateBlueId(inline);
        EmbeddedScopePlan catalogPlan = inlinePlanner().plan(
                inlineScope,
                JsonPointer.ROOT,
                Collections.<String>emptyList(),
                Collections.singletonList(collectionPath()),
                GasSchedule.contracts10());
        prepareCatalog(catalogPlan);
    }

    EmbeddedScopePlanner inlinePlanner() {
        return new EmbeddedScopePlanner();
    }

    EmbeddedScopePlanner pureTargetPlanner() {
        return new EmbeddedScopePlanner(pureTargetProvider);
    }

    EmbeddedScopePlanner pureMemberPlanner() {
        return new EmbeddedScopePlanner(pureMemberProvider);
    }

    private GasObservation observeGas(
            FrozenNode scope,
            CountingMaterializer materializer) {
        materializer.reset();
        GasMeter meter = new GasMeter(GasSchedule.contracts10());
        GasLimitExceededException rejection = null;
        try {
            new EmbeddedScopePlanner(materializer).plan(
                    scope,
                    JsonPointer.ROOT,
                    Collections.<String>emptyList(),
                    Collections.singletonList(collectionPath()),
                    meter);
        } catch (GasLimitExceededException expected) {
            rejection = expected;
        }
        return new GasObservation(
                meter.totalGas(),
                meter.trace(),
                rejection);
    }

    private void prepareCatalog(EmbeddedScopePlan plan) {
        Map<String, List<String>> paths = new LinkedHashMap<>();
        Map<String, List<EffectiveContractSnapshot>> contracts =
                new LinkedHashMap<>();
        paths.put(JsonPointer.ROOT, plan.concreteChildPaths());
        contracts.put(
                JsonPointer.ROOT,
                Collections.<EffectiveContractSnapshot>emptyList());
        for (String childPath : plan.concreteChildPaths()) {
            paths.put(childPath, Collections.<String>emptyList());
            contracts.put(
                    childPath,
                    Collections.<EffectiveContractSnapshot>emptyList());
        }
        catalogPaths = Collections.unmodifiableMap(paths);
        catalogContracts = Collections.unmodifiableMap(contracts);
    }
}
