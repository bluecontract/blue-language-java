package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedCollectionEventChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Classifies already-admitted Root work against one captured processor
 * generation without executing application code.
 */
final class ManagedDocumentStepRouteClassifier {

    private final ProcessorInvocationServices owner;
    private final ProcessingGasContext sharedGasContext;
    private final ManagedRootSurfaceResolver rootSurfaceResolver;

    ManagedDocumentStepRouteClassifier(
            ProcessorInvocationServices owner,
            ProcessingGasContext sharedGasContext,
            ManagedRootSurfaceResolver rootSurfaceResolver) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.sharedGasContext = Objects.requireNonNull(
                sharedGasContext, "sharedGasContext");
        this.rootSurfaceResolver = Objects.requireNonNull(
                rootSurfaceResolver, "rootSurfaceResolver");
    }

    List<ManagedDocumentStepRoute> classifyTriggeredEventRoutes(
            Node exactDocument,
            ExactEventIdentityEvidence exactEvent) {
        ExactEventIdentityEvidence admitted = requireExactEventIdentity(
                exactEvent);
        Node event = admitted.event();
        String eventBlueId = admitted.eventBlueId();
        ContractBundle bundle = rootSurfaceResolver.classify(
                exactDocument).bundle();
        List<ManagedDocumentStepRoute> routes =
                new ArrayList<ManagedDocumentStepRoute>();
        for (ContractBundle.ChannelBinding binding
                : bundle.channelsOfType(TriggeredEventChannel.class)) {
            TriggeredEventChannel channel =
                    (TriggeredEventChannel) binding.contract();
            if (channel.getEvent() == null
                    || owner.matchingService().matchesExactValue(
                            FrozenNode.fromResolvedNode(event),
                            eventBlueId,
                            FrozenNode.fromResolvedNode(
                                    channel.getEvent()))) {
                routes.add(new ManagedDocumentStepRoute(
                        ManagedDocumentWorkKind.TRIGGERED_EVENT,
                        binding.key(),
                        event,
                        null,
                        eventBlueId,
                        bundle));
            }
        }
        return Collections.unmodifiableList(routes);
    }

    List<ManagedDocumentStepRoute> classifyLifecycleRoutes(
            Node exactDocument,
            Node exactEvent) {
        Node event = Objects.requireNonNull(exactEvent, "exactEvent").clone();
        ContractBundle bundle = rootSurfaceResolver.classify(
                exactDocument).bundle();
        List<ManagedDocumentStepRoute> routes =
                new ArrayList<ManagedDocumentStepRoute>();
        String eventBlueId = CheckpointIdentityCalculator.identity(
                event, owner.languageRuntimeAccess());
        for (ContractBundle.ChannelBinding binding
                : bundle.channelsOfType(LifecycleChannel.class)) {
            routes.add(new ManagedDocumentStepRoute(
                    ManagedDocumentWorkKind.LIFECYCLE,
                    binding.key(),
                    event,
                    null,
                    eventBlueId,
                    bundle));
        }
        return Collections.unmodifiableList(routes);
    }

    List<ManagedDocumentStepRoute> classifyEmbeddedEventRoutes(
            Node exactContainingDocument,
            String exactSourcePath,
            ExactEventIdentityEvidence exactEvent,
            GasChargeContext attribution) {
        String sourcePath = ProcessorEngine.normalizeScope(
                Objects.requireNonNull(exactSourcePath, "exactSourcePath"));
        if (JsonPointer.ROOT.equals(sourcePath)) {
            throw new IllegalArgumentException(
                    "An embedded-event source must be below Root");
        }
        ExactEventIdentityEvidence admitted = requireExactEventIdentity(
                exactEvent);
        Node event = admitted.event();
        String eventBlueId = admitted.eventBlueId();
        String adapterSourcePath = ProcessorEngine.relativizePointer(
                JsonPointer.ROOT, sourcePath);
        Node wrapper = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY))
                .properties(
                        ProcessorContractConstants.KEY_SOURCE_PATH,
                        new Node().value(adapterSourcePath))
                .properties(
                        ProcessorContractConstants.KEY_EVENT,
                        new Node().blueId(eventBlueId));
        try (GasMeter.AttributionScope ignored =
                     sharedGasContext.withAttribution(
                             Objects.requireNonNull(
                                     attribution, "attribution"))) {
            ContractBundle bundle = rootSurfaceResolver.classify(
                    exactContainingDocument).bundle();
            List<ManagedDocumentStepRoute> routes =
                    new ArrayList<ManagedDocumentStepRoute>();
            for (ContractBundle.ChannelBinding binding
                    : EmbeddedCollectionEventChannelSupport
                    .orderedChannels(bundle)) {
                sharedGasContext.processMeter().channelMatch(
                        JsonPointer.ROOT, binding.key());
                ChannelContract channel = binding.contract();
                boolean pathMatches = matchesEmbeddedSourcePath(
                        sourcePath, channel);
                Node pattern = eventPattern(channel);
                boolean eventMatches = pathMatches && (pattern == null
                        || owner.matchingService().matchesExactValue(
                                FrozenNode.fromResolvedNode(event),
                                eventBlueId,
                                FrozenNode.fromResolvedNode(pattern)));
                if (eventMatches) {
                    routes.add(new ManagedDocumentStepRoute(
                            ManagedDocumentWorkKind.EMBEDDED_EVENT,
                            binding.key(),
                            wrapper,
                            event,
                            eventBlueId,
                            bundle));
                }
            }
            return Collections.unmodifiableList(routes);
        }
    }

    List<ManagedDocumentStepRoute> classifyDocumentUpdateRoutes(
            Node exactDocument,
            DocumentUpdateOccurrence occurrence) {
        DocumentUpdateOccurrence update = Objects.requireNonNull(
                occurrence, "occurrence");
        ContractBundle bundle = update.frozenRootDispatchBundle() != null
                ? update.frozenRootDispatchBundle()
                : rootSurfaceResolver.classify(exactDocument).bundle();
        DocumentUpdateData data = new DocumentUpdateData(
                update.path(),
                update.before(),
                update.after(),
                update.op(),
                update.originScope(),
                update.recipientChain());
        Node payload = ProcessorEngine.createDocumentUpdateEvent(
                data, JsonPointer.ROOT);
        String payloadBlueId = CheckpointIdentityCalculator.identity(
                payload, owner.languageRuntimeAccess());
        List<ManagedDocumentStepRoute> routes =
                new ArrayList<ManagedDocumentStepRoute>();
        for (ContractBundle.ChannelBinding binding
                : bundle.channelsOfType(DocumentUpdateChannel.class)) {
            DocumentUpdateChannel channel =
                    (DocumentUpdateChannel) binding.contract();
            if (ProcessorEngine.matchesDocumentUpdate(
                    JsonPointer.ROOT,
                    channel.getPath(),
                    update.path())) {
                routes.add(new ManagedDocumentStepRoute(
                        ManagedDocumentWorkKind.DOCUMENT_UPDATE,
                        binding.key(),
                        payload,
                        null,
                        payloadBlueId,
                        bundle));
            }
        }
        return Collections.unmodifiableList(routes);
    }

    private boolean matchesEmbeddedSourcePath(
            String sourcePath,
            ChannelContract channel) {
        if (channel instanceof EmbeddedNodeChannel) {
            return ScopePropagationChain.matchesEmbeddedNodeSourcePath(
                    JsonPointer.ROOT,
                    sourcePath,
                    (EmbeddedNodeChannel) channel);
        }
        EmbeddedCollectionEventChannel collection =
                (EmbeddedCollectionEventChannel) channel;
        sharedGasContext.processMeter().embeddedPathEntry(
                JsonPointer.ROOT, collection.getCollectionPath());
        EmbeddedCollectionEventChannelSupport.Match match =
                EmbeddedCollectionEventChannelSupport.match(
                        collection.getCollectionPath(),
                        sourcePath,
                        collection.includesDescendants(),
                        owner.gasSchedule());
        if (match.comparedSegments() > 0) {
            sharedGasContext.processMeter().embeddedPathSegments(
                    JsonPointer.ROOT,
                    collection.getCollectionPath(),
                    match.comparedSegments());
        }
        return match.matches();
    }

    private static Node eventPattern(ChannelContract channel) {
        if (channel instanceof EmbeddedNodeChannel) {
            return ((EmbeddedNodeChannel) channel).getEvent();
        }
        return ((EmbeddedCollectionEventChannel) channel).getEvent();
    }

    private static ExactEventIdentityEvidence requireExactEventIdentity(
            ExactEventIdentityEvidence exactEvent) {
        /*
         * This route consumes already-admitted event evidence. Re-hashing its
         * cursor would be both redundant for ordinary Source and invalid for
         * a resolved member of a cyclic set.
         */
        return Objects.requireNonNull(exactEvent, "exactEvent");
    }
}
