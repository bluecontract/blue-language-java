package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Routes one committed semantic document update to its frozen recipients.
 *
 * <p>The receiving chain is captured before cut-off is applied. Therefore an
 * in-flight update can finish along its already-established ancestor chain,
 * while later work observes the monotonic cut-off immediately.</p>
 */
final class DocumentUpdateRouter {

    private final DocumentProcessor owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ScopeParticipationRegistry participation;
    private final ScopeFrameFactory frameFactory;
    private final ScopePropagationChain propagationChain;
    private final ScopeCutoffTracker cutoffTracker;
    private final ChannelRunner channelRunner;

    DocumentUpdateRouter(
            DocumentProcessor owner,
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            ScopeParticipationRegistry participation,
            ScopeFrameFactory frameFactory,
            ScopePropagationChain propagationChain,
            ChannelRunner channelRunner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.participation = Objects.requireNonNull(
                participation, "participation");
        this.frameFactory = Objects.requireNonNull(
                frameFactory, "frameFactory");
        this.propagationChain = Objects.requireNonNull(
                propagationChain, "propagationChain");
        this.channelRunner = Objects.requireNonNull(
                channelRunner, "channelRunner");
        this.cutoffTracker = new ScopeCutoffTracker(execution);
    }

    void route(String scopePath,
               ContractBundle bundle,
               DocumentUpdateData update) {
        if (update == null) {
            return;
        }
        /*
         * Freeze the participating scope chain before any cascade handler can
         * replace or cut off its source. Object-path ancestors that were never
         * activated through Process Embedded are not receiving scopes.
         */
        List<String> receivingChain =
                propagationChain.freezeReceivingChain(update);
        recordUpdateTrace(receivingChain, update);
        cutoffTracker.recordEmbeddedReplacement(
                scopePath, bundle, update);

        List<DocumentUpdateParticipant> participants = participants(
                receivingChain, update);
        runtime.chargeCascadeRouting(participants.size());
        for (DocumentUpdateParticipant participant : participants) {
            if (execution.shouldStopScopeWork(participant.scopePath)) {
                continue;
            }
            Node event = ProcessorEngine.createDocumentUpdateEvent(
                    update, participant.scopePath);
            ProcessingObservations.record(
                    owner.observer(),
                    ProcessingMetricId.DOCUMENT_UPDATE_EVENTS_BUILT,
                    1L);
            for (ContractBundle.ChannelBinding channel
                    : participant.channels) {
                channelRunner.runHandlers(
                        participant.scopePath,
                        participant.bundle,
                        channel.key(),
                        event,
                        true);
                if (execution.shouldStopScopeWork(
                        participant.scopePath)) {
                    continue;
                }
            }
        }
    }

    private void recordUpdateTrace(
            List<String> receivingChain,
            DocumentUpdateData update) {
        for (String cascadeScope : receivingChain) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put(
                    ProcessingTraceConstants.FIELD_OPERATION,
                    update.op().name().toLowerCase());
            details.put(
                    ProcessingTraceConstants.FIELD_BEFORE_PRESENT,
                    update.beforePresent());
            details.put(
                    ProcessingTraceConstants.FIELD_AFTER_PRESENT,
                    update.afterPresent());
            details.put(
                    ProcessingTraceConstants.FIELD_SOURCE_SCOPE_PATH,
                    update.originScope());
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.DOCUMENT_UPDATE,
                    cascadeScope,
                    null,
                    update.path(),
                    details,
                    null);
        }
    }

    private List<DocumentUpdateParticipant> participants(
            List<String> receivingChain,
            DocumentUpdateData update) {
        List<DocumentUpdateParticipant> participants = new ArrayList<>();
        for (String cascadeScope : receivingChain) {
            if (execution.shouldStopScopeWork(cascadeScope)) {
                continue;
            }
            ContractBundle targetBundle;
            try {
                targetBundle = frameFactory.refresh(cascadeScope);
            } catch (MustUnderstandFailureException exception) {
                if (affectsEmbeddedSubscriptionSurface(
                        cascadeScope, update.path())) {
                    throw new SubscriptionSurfaceInvalidException(
                            execution.fatalReason(
                                    exception,
                                    "Invalid changed Process Embedded surface"),
                            cascadeScope,
                            ProcessorContractConstants.KEY_EMBEDDED);
                }
                execution.abortRuntimeFailure(
                        cascadeScope,
                        participation.bundle(cascadeScope),
                        exception.errorCategory(),
                        execution.fatalReason(
                                exception,
                                "Unsupported runtime contract"));
                return participants;
            }
            if (targetBundle == null) {
                continue;
            }
            List<ContractBundle.ChannelBinding> matching =
                    matchingChannels(
                            cascadeScope, targetBundle, update.path());
            if (matching.isEmpty()) {
                ProcessingObservations.record(
                        owner.observer(),
                        ProcessingMetricId.DOCUMENT_UPDATE_EVENTS_SKIPPED_NO_CHANNEL,
                        1L);
                continue;
            }
            participants.add(new DocumentUpdateParticipant(
                    cascadeScope, targetBundle, matching));
        }
        return participants;
    }

    private List<ContractBundle.ChannelBinding> matchingChannels(
            String scopePath,
            ContractBundle bundle,
            String updatePath) {
        List<ContractBundle.ChannelBinding> matching = new ArrayList<>();
        for (ContractBundle.ChannelBinding channel
                : bundle.channelsOfType(DocumentUpdateChannel.class)) {
            DocumentUpdateChannel documentUpdate =
                    (DocumentUpdateChannel) channel.contract();
            if (ProcessorEngine.matchesDocumentUpdate(
                    scopePath,
                    documentUpdate.getPath(),
                    updatePath)) {
                matching.add(channel);
            }
        }
        return matching;
    }

    static boolean affectsEmbeddedSubscriptionSurface(
            String scopePath,
            String changedPath) {
        String normalizedChange = PointerUtils.normalizePointer(changedPath);
        return affectsEmbeddedDeclaration(
                scopePath,
                normalizedChange,
                ProcessorPointerConstants.RELATIVE_EMBEDDED_PATHS)
                || affectsEmbeddedDeclaration(
                        scopePath,
                        normalizedChange,
                        ProcessorPointerConstants
                                .RELATIVE_EMBEDDED_COLLECTION_PATHS);
    }

    private static boolean affectsEmbeddedDeclaration(
            String scopePath,
            String normalizedChange,
            String relativeDeclarationPath) {
        String declarationPath = ProcessorEngine.resolvePointer(
                scopePath,
                relativeDeclarationPath);
        return PointerUtils.descendantOrEqual(
                normalizedChange, declarationPath)
                || PointerUtils.descendantOrEqual(
                declarationPath, normalizedChange);
    }

    /** One participating scope and its already-selected matching channels. */
    private static final class DocumentUpdateParticipant {
        private final String scopePath;
        private final ContractBundle bundle;
        private final List<ContractBundle.ChannelBinding> channels;

        private DocumentUpdateParticipant(
                String scopePath,
                ContractBundle bundle,
                List<ContractBundle.ChannelBinding> channels) {
            this.scopePath = scopePath;
            this.bundle = bundle;
            this.channels = channels;
        }
    }
}
