package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.processor.EmbeddedCollectionBenchmarkSupport.BenchmarkChannelProcessor;
import blue.language.processor.EmbeddedCollectionBenchmarkSupport.BenchmarkHandlerProcessor;
import blue.language.processor.EmbeddedCollectionBenchmarkSupport.CountingNodeProvider;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.runtime.BlueLanguageRuntime;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.BODY_MEMBER_FIELD;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.CHANNEL_KEY;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.CHANNEL_TYPE;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.CHANNEL_TYPE_BLUE_ID;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.CHECKPOINT_DOMAIN;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.HANDLER_TYPE;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.HANDLER_TYPE_BLUE_ID;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.FIRST_DELIVERY_ORDER;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.INITIAL_PROCESSING_REVISION;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.PARAM_SIZE_ONE_HUNDRED;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.PARAM_SIZE_ONE_THOUSAND;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.PARAM_SIZE_PORTABLE_EDGE;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.PARAM_SIZE_TEN;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.SELECTED_MEMBER_DIVISOR;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.SUBSCRIPTION_KEY;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.collectionPath;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.memberKey;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.nodeAt;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.processingMember;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.scope;

/** Invocation-cold state for one end-to-end selected collection delivery. */
@State(Scope.Thread)
public class EmbeddedCollectionSelectedProcessingState {

    /** Direct collection size measured by the invocation. */
    @Param({
            PARAM_SIZE_TEN,
            PARAM_SIZE_ONE_HUNDRED,
            PARAM_SIZE_ONE_THOUSAND,
            PARAM_SIZE_PORTABLE_EDGE})
    public int size;

    Node root;
    Node event;
    String selectedScopePath;
    String selectedBodyBlueId;
    Map<String, Node> bodiesByBlueId;
    CountingNodeProvider provider;
    BlueLanguageRuntime languageRuntime;
    DocumentProcessor processor;
    long executions;

    /** Builds one immutable authored fixture for the trial. */
    @Setup(Level.Trial)
    public void prepareFixture() {
        Map<String, Node> members = new LinkedHashMap<>();
        Map<String, Node> bodies = new LinkedHashMap<>();
        String selected = memberKey(size / SELECTED_MEMBER_DIVISOR);
        for (int index = size - 1; index >= 0; index--) {
            String key = memberKey(index);
            Node body = new Node().properties(
                    BODY_MEMBER_FIELD, new Node().value(key));
            String bodyBlueId = DirectBlueIdCalculator
                    .calculateBlueId(body);
            bodies.put(bodyBlueId, body);
            if (key.equals(selected)) {
                selectedBodyBlueId = bodyBlueId;
            }
            members.put(
                    key,
                    processingMember(bodyBlueId));
        }
        root = scope(members);
        event = new Node().properties(
                ProcessorContractConstants.KEY_SUBSCRIPTION_KEY,
                new Node().value(SUBSCRIPTION_KEY));
        selectedScopePath = PointerUtils.appendPointer(
                collectionPath(), selected);
        bodiesByBlueId = Collections.unmodifiableMap(bodies);
    }

    /** Opens a cold processor and zeroes physical observations. */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        executions = 0L;
        provider = new CountingNodeProvider(bodiesByBlueId);
        NodeProvider effectiveProvider = new SequentialNodeProvider(
                BlueRuntimeTypeRegistry.getDefault().asProvider(),
                provider);
        BenchmarkChannelProcessor channelProcessor =
                new BenchmarkChannelProcessor();
        BenchmarkHandlerProcessor handlerProcessor =
                new BenchmarkHandlerProcessor(this);
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .register(
                                CHANNEL_TYPE_BLUE_ID,
                                CHANNEL_TYPE,
                                channelProcessor)
                        .register(
                                HANDLER_TYPE_BLUE_ID,
                                HANDLER_TYPE,
                                handlerProcessor)
                        .build();
        languageRuntime = BlueLanguageRuntime.create(
                effectiveProvider,
                BlueCachePolicy.disabled(),
                Collections.<String, String>emptyMap());
        ProcessingSnapshotManager snapshotManager =
                new RegisteredContractScopeIdentitySnapshotManager(
                        registry,
                        languageRuntime);
        processor = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .snapshotStore(snapshotManager)
                .nodeProvider(effectiveProvider)
                .cachePolicy(BlueCachePolicy.disabled())
                .evidenceVerifier(
                        (document, suppliedEvent, evidence) -> {
                            // The benchmark supplies exact local evidence.
                        })
                .deliveryPlanDeriver(this::deliveryPlan)
                .build();
        provider.reset();
    }

    /** Releases invocation-owned processor state outside timed work. */
    @TearDown(Level.Invocation)
    public void closeInvocation() {
        if (processor != null) {
            processor.close();
            processor = null;
        }
        if (languageRuntime != null) {
            languageRuntime.close();
            languageRuntime = null;
        }
    }

    private ExternalDeliveryPlan deliveryPlan(
            Node suppliedRoot,
            Node suppliedEvent) {
        Node selected = nodeAt(
                suppliedRoot,
                selectedScopePath);
        Node channel = selected.getContracts()
                .getProperties().get(CHANNEL_KEY);
        String contribution = DirectBlueIdCalculator
                .calculateBlueId(channel);
        String eventBlueId = DirectBlueIdCalculator
                .calculateBlueId(suppliedEvent);
        String domain = CheckpointDomain.derive(
                CHANNEL_TYPE_BLUE_ID,
                Collections.singletonList(contribution),
                CHECKPOINT_DOMAIN);
        SubscriptionDelta.Entry interval =
                new SubscriptionDelta.Entry(
                        selectedScopePath,
                        CHANNEL_KEY,
                        CHANNEL_TYPE_BLUE_ID,
                        Collections.singletonList(contribution),
                        FIRST_DELIVERY_ORDER,
                        Collections.singletonList(SUBSCRIPTION_KEY),
                        domain,
                        INITIAL_PROCESSING_REVISION,
                        null,
                        null);
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder(
                                selectedScopePath,
                                CHANNEL_KEY)
                        .order(FIRST_DELIVERY_ORDER)
                        .sourceContribution(contribution)
                        .effectiveTypeBlueId(CHANNEL_TYPE_BLUE_ID)
                        .subscriptionKey(SUBSCRIPTION_KEY)
                        .checkpointDomainBlueId(domain)
                        .checkpointSubjectBlueId(eventBlueId)
                        .build();
        return ExternalDeliveryPlan.builder()
                .revisions(
                        INITIAL_PROCESSING_REVISION,
                        INITIAL_PROCESSING_REVISION)
                .eventOrderKey(ExternalOrderKey.of(
                        Collections.singletonList(eventBlueId)))
                .delivery(delivery)
                .activeSubscriptionInterval(interval)
                .exactRuntimeState()
                .build();
    }
}
