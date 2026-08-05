package blue.language.examples;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Runs a generic Agreement whose stable-key Lesson collection is embedded.
 *
 * <p>The runtime types are intentionally application-neutral: one Channel
 * derives a concrete occurrence key, and one scripted Handler applies patches
 * declared in the Blue document. No Coordination type or policy is used.</p>
 */
public final class EmbeddedCollectionAgreementExample {

    private static final String ROOT_SCOPE = "/";
    private static final String LESSONS_PATH = "/lessons";
    private static final String LESSON_A_PATH = "/lessons/lesson-a";
    private static final String LESSON_B_PATH = "/lessons/lesson-b";
    private static final String LESSON_C_PATH = "/lessons/lesson-c";

    private static final String LESSONS_KEY = "lessons";
    private static final String LESSON_A_KEY = "lesson-a";
    private static final String LESSON_B_KEY = "lesson-b";
    private static final String LESSON_C_KEY = "lesson-c";
    private static final String PARTICIPANT_CHANNEL_KEY =
            "participantChannel";
    private static final String PARENT_PARTICIPANT_CHANNEL_KEY =
            "parentParticipantChannel";
    private static final String ADMIN_CHANNEL_KEY = "adminChannel";
    private static final String SCRIPTED_HANDLER_KEY = "scriptedHandler";

    private static final String BINDING_KEY = "binding";
    private static final String RESULT_KEY = "result";
    private static final String HANDLER_CHANNEL_KEY = "channel";
    private static final String PATCHES_KEY = "patches";
    private static final String PATCH_OPERATION_KEY =
            ProcessorContractConstants.KEY_OPERATION;
    private static final String PATCH_PATH_KEY =
            ProcessorContractConstants.KEY_PATH;
    private static final String PATCH_VALUE_KEY = "val";
    private static final String PROGRESS_KEY = "progress";
    private static final String PROGRESS_PATH = "/" + PROGRESS_KEY;
    private static final String CONTRACTS_PATH =
            "/" + ProcessorContractConstants.KEY_CONTRACTS;
    private static final String ROOT_REVISION_KEY = "rootRevision";
    private static final String EVENT_SEQUENCE_KEY = "eventSequence";

    private static final String PATCH_ADD = "add";
    private static final String PATCH_REPLACE = "replace";
    private static final String OLD_PARTICIPANT_BINDING = "participant-v1";
    private static final String NEW_PARTICIPANT_BINDING = "participant-v2";
    private static final String ADMIN_BINDING = "agreement-admin";
    private static final String EXAMPLE_REGISTRY_IDENTITY =
            "example:embedded-collection-agreement/1";
    private static final String CHANNEL_KEY_SEPARATOR = "@";

    private static final long INITIAL_ROOT_REVISION = 7L;
    private static final long TARGET_EVENT_SEQUENCE = 1L;
    private static final long CREATE_EVENT_SEQUENCE = 2L;
    private static final long ACTIVATE_EVENT_SEQUENCE = 3L;

    private static final Node CHANNEL_TYPE_NODE =
            new Node().name("Occurrence Channel");
    private static final Node SCRIPTED_HANDLER_TYPE_NODE =
            new Node().name("Declared Patch Handler");
    private static final String CHANNEL_TYPE_BLUE_ID =
            blueId(CHANNEL_TYPE_NODE);
    private static final String SCRIPTED_HANDLER_TYPE_BLUE_ID =
            blueId(SCRIPTED_HANDLER_TYPE_NODE);

    private EmbeddedCollectionAgreementExample() {
    }

    /**
     * Processes the target, creation, and post-commit activation events.
     *
     * @return immutable observations from the complete worked example
     */
    public static EmbeddedCollectionAgreementResult run() {
        // tag::embedded-collection-agreement[]
        Node agreement = agreementRoot();
        String lessonTemplateBlueId = blueId(lessonAt(
                agreement, LESSON_A_KEY));
        String reusedParticipantBlueId = participantBlueId(
                lessonAt(agreement, LESSON_A_KEY));

        ExternalDeliveryPlanDeriver deliveryPlans =
                EmbeddedCollectionAgreementExample::deliveryPlan;
        ContractProcessorRegistry registry = runtimeRegistry();
        try (DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .runtimeRegistryIdentity(EXAMPLE_REGISTRY_IDENTITY)
                .deliveryPlanDeriver(deliveryPlans)
                .build()) {
            PlatformProcessingResult targeted = processForCommit(
                    processor,
                    deliveryPlans,
                    agreement,
                    event(
                            LESSON_A_PATH,
                            OLD_PARTICIPANT_BINDING,
                            INITIAL_ROOT_REVISION,
                            TARGET_EVENT_SEQUENCE));
            requireSuccess(targeted.processResult(), "target lesson-a");
            Node afterTarget = targeted.processResult().document();

            ExampleSupport.require(
                    integerAt(afterTarget, LESSON_A_PATH + PROGRESS_PATH) == 1L,
                    "Only lesson-a must change for its concrete Channel key");
            ExampleSupport.require(
                    integerAt(afterTarget, LESSON_B_PATH + PROGRESS_PATH) == 0L,
                    "lesson-b must remain unchanged");
            ExampleSupport.require(
                    lessonTemplateBlueId.equals(blueId(
                            lessonAt(agreement, LESSON_B_KEY))),
                    "The same initial Lesson BlueId may occur at two keys");

            PlatformProcessingResult created = processForCommit(
                    processor,
                    deliveryPlans,
                    afterTarget,
                    event(
                            ROOT_SCOPE,
                            ADMIN_BINDING,
                            INITIAL_ROOT_REVISION + 1L,
                            CREATE_EVENT_SEQUENCE));
            requireSuccess(created.processResult(), "create lesson-c");
            Node afterCreate = created.processResult().document();
            SubscriptionDelta.Entry lessonCActivation = findAddedInterval(
                    created.commitCompanion().subscriptionDelta(),
                    LESSON_C_PATH,
                    PARTICIPANT_CHANNEL_KEY);

            ExampleSupport.require(
                    integerAt(afterCreate, LESSON_C_PATH + PROGRESS_PATH) == 0L,
                    "The creating event must not process lesson-c");
            ExampleSupport.require(
                    created.commitCompanion().eventOrderKey().equals(
                            lessonCActivation.startAfterExternalOrderKey()),
                    "lesson-c must activate strictly after the creating event");
            ExampleSupport.require(
                    reusedParticipantBlueId.equals(participantBlueId(
                            lessonAt(afterCreate, LESSON_A_KEY)))
                            && reusedParticipantBlueId.equals(
                            participantBlueId(lessonAt(
                                    afterCreate, LESSON_B_KEY))),
                    "Existing Lessons must retain their exact participant binding");
            ExampleSupport.require(
                    parentParticipantBlueId(afterCreate).equals(
                            participantBlueId(lessonAt(
                                    afterCreate, LESSON_C_KEY))),
                    "A new Lesson may use the replacement parent binding");

            PlatformProcessingResult activated = processForCommit(
                    processor,
                    deliveryPlans,
                    afterCreate,
                    event(
                            LESSON_C_PATH,
                            NEW_PARTICIPANT_BINDING,
                            INITIAL_ROOT_REVISION + 2L,
                            ACTIVATE_EVENT_SEQUENCE));
            requireSuccess(activated.processResult(), "target lesson-c");

            return new EmbeddedCollectionAgreementResult(
                    lessonTemplateBlueId,
                    reusedParticipantBlueId,
                    integerAt(afterTarget, LESSON_A_PATH + PROGRESS_PATH),
                    integerAt(afterTarget, LESSON_B_PATH + PROGRESS_PATH),
                    integerAt(afterCreate, LESSON_C_PATH + PROGRESS_PATH),
                    integerAt(
                            activated.processResult().document(),
                            LESSON_C_PATH + PROGRESS_PATH),
                    lessonCActivation.scopePath(),
                    lessonCActivation.startAfterExternalOrderKey(),
                    participantBlueId(lessonAt(afterCreate, LESSON_A_KEY)),
                    participantBlueId(lessonAt(afterCreate, LESSON_B_KEY)),
                    participantBlueId(lessonAt(afterCreate, LESSON_C_KEY)),
                    parentParticipantBlueId(afterCreate));
        }
        // end::embedded-collection-agreement[]
    }

    /**
     * Runs the complete example and prints the activated Lesson progress.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        System.out.println(run().getLessonCProgressAfterNextEvent());
    }

    private static ContractProcessorRegistry runtimeRegistry() {
        return ContractProcessorRegistryBuilder.create()
                .register(
                        CHANNEL_TYPE_BLUE_ID,
                        CHANNEL_TYPE_NODE.clone(),
                        new OccurrenceChannelProcessor())
                .register(
                        SCRIPTED_HANDLER_TYPE_BLUE_ID,
                        SCRIPTED_HANDLER_TYPE_NODE.clone(),
                        new DeclaredPatchHandlerProcessor())
                .build();
    }

    private static Node agreementRoot() {
        Node oldParticipant = occurrenceChannel(OLD_PARTICIPANT_BINDING);
        Node newParticipant = occurrenceChannel(NEW_PARTICIPANT_BINDING);
        Node lessonTemplate = lesson(oldParticipant, 0L);
        Node lessons = new Node()
                .properties(LESSON_A_KEY, lessonTemplate.clone())
                .properties(LESSON_B_KEY, lessonTemplate.clone());
        Node rootScript = scriptedHandler(
                ADMIN_CHANNEL_KEY,
                patch(
                        PATCH_REPLACE,
                        CONTRACTS_PATH + "/"
                                + PARENT_PARTICIPANT_CHANNEL_KEY,
                        newParticipant.clone()),
                patch(
                        PATCH_ADD,
                        LESSON_C_PATH,
                        lesson(newParticipant, 0L)));
        Node contracts = new Node()
                .properties(
                        PARENT_PARTICIPANT_CHANNEL_KEY,
                        oldParticipant.clone())
                .properties(
                        ADMIN_CHANNEL_KEY,
                        occurrenceChannel(ADMIN_BINDING))
                .properties(SCRIPTED_HANDLER_KEY, rootScript)
                .properties(
                        ProcessorContractConstants.KEY_EMBEDDED,
                        typed(RuntimeBlueIds.PROCESS_EMBEDDED)
                                .properties(
                                        ProcessorContractConstants
                                                .KEY_COLLECTION_PATHS,
                                        new Node().items(text(LESSONS_PATH))));
        return new Node()
                .name("Agreement Root")
                .properties(LESSONS_KEY, lessons)
                .contracts(contracts);
    }

    private static Node lesson(Node participant, long progress) {
        Node script = scriptedHandler(
                PARTICIPANT_CHANNEL_KEY,
                patch(
                        PATCH_REPLACE,
                        PROGRESS_PATH,
                        integer(progress + 1L)));
        return new Node()
                .name("Lesson")
                .properties(PROGRESS_KEY, integer(progress))
                .contracts(new Node()
                        .properties(
                                PARTICIPANT_CHANNEL_KEY,
                                participant.clone())
                        .properties(SCRIPTED_HANDLER_KEY, script));
    }

    private static Node occurrenceChannel(String binding) {
        return typed(CHANNEL_TYPE_BLUE_ID)
                .properties(BINDING_KEY, text(binding));
    }

    private static Node scriptedHandler(
            String channelKey,
            Node... patches) {
        return typed(SCRIPTED_HANDLER_TYPE_BLUE_ID)
                .properties(
                        HANDLER_CHANNEL_KEY,
                        text(channelKey))
                .properties(
                        RESULT_KEY,
                        new Node().properties(
                                PATCHES_KEY,
                                new Node().items(patches)));
    }

    private static Node patch(
            String operation,
            String path,
            Node value) {
        return new Node()
                .properties(PATCH_OPERATION_KEY, text(operation))
                .properties(PATCH_PATH_KEY, text(path))
                .properties(PATCH_VALUE_KEY, value);
    }

    private static Node event(
            String scopePath,
            String binding,
            long rootRevision,
            long sequence) {
        return new Node()
                .properties(
                        ProcessorContractConstants.KEY_SUBSCRIPTION_KEY,
                        text(occurrenceKey(scopePath, binding)))
                .properties(ROOT_REVISION_KEY, integer(rootRevision))
                .properties(EVENT_SEQUENCE_KEY, integer(sequence));
    }

    private static PlatformProcessingResult processForCommit(
            DocumentProcessor processor,
            ExternalDeliveryPlanDeriver deriver,
            Node root,
            Node event) {
        ExternalDeliveryPlan plan = deriver.derive(root, event);
        VerifiedExecutionEvidence.Builder evidence =
                VerifiedExecutionEvidence.builder(
                                blueId(root),
                                blueId(event))
                        .revisions(
                                plan.managedRootRevision(),
                                plan.indexedRootRevision())
                        .runtimeRegistryIdentity(
                                EXAMPLE_REGISTRY_IDENTITY)
                        .eventOrderKey(plan.eventOrderKey());
        for (ExternalDeliverySnapshot delivery : plan.deliveries()) {
            evidence.delivery(delivery);
        }
        if (plan.hasActiveSubscriptionIntervals()) {
            evidence.activeSubscriptionIntervals(
                    plan.activeSubscriptionIntervals());
        }
        for (String blueId : plan.availableExactNodeBlueIds()) {
            evidence.availableExactNode(blueId);
        }
        for (String blueId : plan.requiredExactNodeBlueIds()) {
            evidence.requiredExactNode(blueId);
        }
        return processor.processDocumentForPlatformCommit(
                root, event, evidence.build());
    }

    private static ExternalDeliveryPlan deliveryPlan(
            Node root,
            Node event) {
        long rootRevision = integerProperty(
                event, ROOT_REVISION_KEY).longValueExact();
        long eventSequence = integerProperty(
                event, EVENT_SEQUENCE_KEY).longValueExact();
        String selectedKey = textProperty(
                event,
                ProcessorContractConstants.KEY_SUBSCRIPTION_KEY);
        ExternalOrderKey orderKey = ExternalOrderKey.of(
                Arrays.<Object>asList(eventSequence, selectedKey));
        ExternalDeliveryPlan.Builder plan = ExternalDeliveryPlan.builder()
                .revisions(rootRevision, rootRevision)
                .eventOrderKey(orderKey)
                .activeSubscriptionIntervals(
                        Collections.<SubscriptionDelta.Entry>emptyList())
                .exactRuntimeState();
        for (ScopeChannel channel : scopeChannels(root)) {
            String subscriptionKey = occurrenceKey(
                    channel.scopePath,
                    textProperty(channel.channel, BINDING_KEY));
            String contributionBlueId = blueId(channel.channel);
            String checkpointDomainBlueId = CheckpointDomain.derive(
                    CHANNEL_TYPE_BLUE_ID,
                    Collections.singletonList(contributionBlueId),
                    ExternalChannelDependencySnapshot.none(),
                    textProperty(channel.channel, BINDING_KEY));
            boolean createdByAgreementOperation =
                    NEW_PARTICIPANT_BINDING.equals(
                            textProperty(channel.channel, BINDING_KEY));
            SubscriptionDelta.Entry interval =
                    new SubscriptionDelta.Entry(
                            channel.scopePath,
                            channel.channelKey,
                            CHANNEL_TYPE_BLUE_ID,
                            Collections.singletonList(
                                    contributionBlueId),
                            0,
                            Collections.singletonList(subscriptionKey),
                            checkpointDomainBlueId,
                            ExternalChannelDependencySnapshot.none(),
                            createdByAgreementOperation
                                    ? Long.valueOf(
                                    INITIAL_ROOT_REVISION + 2L)
                                    : Long.valueOf(0L),
                            createdByAgreementOperation
                                    ? creationOrderKey()
                                    : null,
                            null);
            plan.activeSubscriptionInterval(interval);
            if (subscriptionKey.equals(selectedKey)) {
                plan.delivery(ExternalDeliverySnapshot
                        .builder(
                                channel.scopePath,
                                channel.channelKey)
                        .order(0)
                        .sourceContribution(contributionBlueId)
                        .effectiveTypeBlueId(CHANNEL_TYPE_BLUE_ID)
                        .subscriptionKey(subscriptionKey)
                        .checkpointDomainBlueId(
                                checkpointDomainBlueId)
                        .checkpointSubjectBlueId(blueId(event))
                        .activationStartExclusive(
                                createdByAgreementOperation
                                        ? creationOrderKey()
                                        : null)
                        .build());
            }
        }
        return plan.build();
    }

    private static ExternalOrderKey creationOrderKey() {
        return ExternalOrderKey.of(Arrays.<Object>asList(
                CREATE_EVENT_SEQUENCE,
                occurrenceKey(ROOT_SCOPE, ADMIN_BINDING)));
    }

    private static List<ScopeChannel> scopeChannels(Node root) {
        List<ScopeChannel> channels = new ArrayList<>();
        collectChannels(root, ROOT_SCOPE, channels);
        Node lessons = property(root, LESSONS_KEY);
        if (lessons != null && lessons.getProperties() != null) {
            List<String> keys = new ArrayList<>(
                    lessons.getProperties().keySet());
            keys.sort(ExternalOrderKey::compareTextCodePoints);
            for (String key : keys) {
                collectChannels(
                        lessons.getProperties().get(key),
                        LESSONS_PATH + "/" + escapePointerSegment(key),
                        channels);
            }
        }
        return channels;
    }

    private static void collectChannels(
            Node scope,
            String scopePath,
            List<ScopeChannel> channels) {
        Node contracts = scope != null ? scope.getContracts() : null;
        if (contracts == null || contracts.getProperties() == null) {
            return;
        }
        List<String> keys = new ArrayList<>(
                contracts.getProperties().keySet());
        keys.sort(ExternalOrderKey::compareTextCodePoints);
        for (String key : keys) {
            Node contract = contracts.getProperties().get(key);
            if (isOccurrenceChannel(contract)) {
                channels.add(new ScopeChannel(
                        scopePath, key, contract));
            }
        }
    }

    private static boolean isOccurrenceChannel(Node contract) {
        return contract != null
                && contract.getType() != null
                && CHANNEL_TYPE_BLUE_ID.equals(
                        contract.getType().getBlueId());
    }

    private static SubscriptionDelta.Entry findAddedInterval(
            SubscriptionDelta delta,
            String scopePath,
            String channelKey) {
        for (SubscriptionDelta.Entry interval : delta.added()) {
            if (scopePath.equals(interval.scopePath())
                    && channelKey.equals(interval.channelKey())) {
                return interval;
            }
        }
        throw new IllegalStateException(
                "Missing added subscription interval for "
                        + scopePath + "/" + channelKey);
    }

    private static void requireSuccess(
            DocumentProcessingResult result,
            String operation) {
        ExampleSupport.require(
                result.status() == ProcessorStatus.SUCCESS,
                operation + " must commit: "
                        + ContractsExampleSupport.diagnostic(result));
    }

    private static String occurrenceKey(
            String scopePath,
            String binding) {
        return binding + CHANNEL_KEY_SEPARATOR + scopePath;
    }

    private static Node lessonAt(Node agreement, String lessonKey) {
        return property(property(agreement, LESSONS_KEY), lessonKey);
    }

    private static String participantBlueId(Node lesson) {
        return blueId(property(
                lesson.getContracts(), PARTICIPANT_CHANNEL_KEY));
    }

    private static String parentParticipantBlueId(Node agreement) {
        return blueId(property(
                agreement.getContracts(),
                PARENT_PARTICIPANT_CHANNEL_KEY));
    }

    private static long integerAt(Node root, String pointer) {
        Node current = nodeAt(root, pointer);
        if (current == null || !(current.getValue() instanceof BigInteger)) {
            throw new IllegalStateException(
                    "Expected Integer at " + pointer);
        }
        return ((BigInteger) current.getValue()).longValueExact();
    }

    private static Node nodeAt(Node root, String pointer) {
        if (ROOT_SCOPE.equals(pointer)) {
            return root;
        }
        Node current = root;
        for (String rawSegment : pointer.substring(1).split("/", -1)) {
            if (current == null || current.getProperties() == null) {
                return null;
            }
            current = current.getProperties().get(
                    rawSegment.replace("~1", "/")
                            .replace("~0", "~"));
        }
        return current;
    }

    private static Node property(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private static String textProperty(Node node, String key) {
        Node value = property(node, key);
        if (value == null || !(value.getValue() instanceof String)) {
            throw new IllegalStateException(
                    "Expected Text property " + key);
        }
        return (String) value.getValue();
    }

    private static BigInteger integerProperty(Node node, String key) {
        Node value = property(node, key);
        if (value == null || !(value.getValue() instanceof BigInteger)) {
            throw new IllegalStateException(
                    "Expected Integer property " + key);
        }
        return (BigInteger) value.getValue();
    }

    private static Node typed(String typeBlueId) {
        return new Node().type(new Node().blueId(typeBlueId));
    }

    private static Node text(String value) {
        return new Node().value(value);
    }

    private static Node integer(long value) {
        return new Node().value(BigInteger.valueOf(value));
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static String escapePointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /** Channel value whose runtime key identifies one concrete occurrence. */
    public static final class OccurrenceChannel extends ChannelContract {
        private String binding;

        /** Creates an unbound example Channel model. */
        public OccurrenceChannel() {
        }

        /**
         * Returns the exact reusable participant binding.
         *
         * @return participant binding, or {@code null} before mapping
         */
        public String getBinding() {
            return binding;
        }

        /**
         * Assigns the exact reusable participant binding.
         *
         * @param binding participant binding supplied by the mapped Channel
         */
        public void setBinding(String binding) {
            this.binding = binding;
        }
    }

    /** Generic Handler whose declared result is a list of Blue patches. */
    public static final class DeclaredPatchHandler extends HandlerContract {
        private Node result;

        /** Creates an empty declared-patch Handler model. */
        public DeclaredPatchHandler() {
        }

        /**
         * Returns the declared result retained by the mapper.
         *
         * @return declared result, or {@code null} when absent
         */
        public Node getResult() {
            return result;
        }

        /**
         * Assigns the declared result retained by the mapper.
         *
         * @param result declared result retained by reference
         */
        public void setResult(Node result) {
            this.result = result;
        }
    }

    private static final class OccurrenceChannelProcessor
            implements ChannelProcessor<OccurrenceChannel> {
        private static final ExternalChannelSubscriptionFunctions<
                OccurrenceChannel> FUNCTIONS =
                new ExternalChannelSubscriptionFunctions<
                        OccurrenceChannel>() {
                    @Override
                    public List<String> channelKeys(
                            OccurrenceChannel contract) {
                        return Collections.singletonList(
                                contract.getBinding());
                    }

                    @Override
                    public List<String> channelKeys(
                            OccurrenceChannel contract,
                            ExternalChannelFunctionContext context) {
                        return Collections.singletonList(
                                occurrenceKey(
                                        context.scopePath(),
                                        contract.getBinding()));
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            OccurrenceChannel contract) {
                        return contract.getBinding();
                    }

                    @Override
                    public String handlerChannelKey(
                            OccurrenceChannel contract,
                            Node event,
                            Node payload,
                            ExternalChannelFunctionContext context) {
                        return context.channelKey();
                    }
                };

        @Override
        public Class<OccurrenceChannel> contractType() {
            return OccurrenceChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<OccurrenceChannel>
        externalSubscriptionFunctions() {
            return FUNCTIONS;
        }

        @Override
        public boolean matches(
                OccurrenceChannel contract,
                ChannelEvaluationContext context) {
            return true;
        }
    }

    private static final class DeclaredPatchHandlerProcessor
            implements HandlerProcessor<DeclaredPatchHandler> {

        @Override
        public Class<DeclaredPatchHandler> contractType() {
            return DeclaredPatchHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList(RESULT_KEY);
        }

        @Override
        public void execute(
                DeclaredPatchHandler contract,
                ProcessorExecutionContext context) {
            Node patches = property(contract.getResult(), PATCHES_KEY);
            if (patches == null || patches.getItems() == null) {
                return;
            }
            for (Node patch : patches.getItems()) {
                String operation = textProperty(
                        patch, PATCH_OPERATION_KEY);
                String path = context.resolvePointer(
                        textProperty(patch, PATCH_PATH_KEY));
                Node value = property(patch, PATCH_VALUE_KEY);
                if (PATCH_ADD.equals(operation)) {
                    context.applyPatch(JsonPatch.add(path, value));
                } else if (PATCH_REPLACE.equals(operation)) {
                    context.applyPatch(JsonPatch.replace(path, value));
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported declared patch operation: "
                                    + operation);
                }
            }
        }
    }

    private static final class ScopeChannel {
        private final String scopePath;
        private final String channelKey;
        private final Node channel;

        private ScopeChannel(
                String scopePath,
                String channelKey,
                Node channel) {
            this.scopePath = scopePath;
            this.channelKey = channelKey;
            this.channel = channel;
        }
    }

}
