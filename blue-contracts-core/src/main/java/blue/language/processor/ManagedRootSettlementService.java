package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.CheckpointEntry;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Processor-internal implementation of managed Root read/settlement seams. */
final class ManagedRootSettlementService {

    private final ProcessorInvocationServices owner;
    private final ProcessingGasContext sharedGasContext;

    ManagedRootSettlementService(
            ProcessorInvocationServices owner,
            ProcessingGasContext sharedGasContext) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.sharedGasContext = Objects.requireNonNull(
                sharedGasContext, "sharedGasContext");
    }

    List<ManagedRootChannelOccurrence> projectRootChannelSurface(
            Node exactRoot) {
        RootView view = openRoot(exactRoot);
        List<ManagedRootChannelOccurrence> result =
                new ArrayList<ManagedRootChannelOccurrence>();
        for (EffectiveContractSnapshot snapshot
                : channelSnapshots(view.bundle)) {
            result.add(occurrence(view.bundle, snapshot));
        }
        return Collections.unmodifiableList(result);
    }

    ManagedRootSubscriptionSurface projectRootSubscriptionSurface(
            Node exactRoot) {
        RootView view = openRoot(exactRoot);
        List<ManagedRootChannelOccurrence> occurrences =
                new ArrayList<ManagedRootChannelOccurrence>();
        List<SubscriptionDelta.Entry> subscriptions =
                new ArrayList<SubscriptionDelta.Entry>();
        for (EffectiveContractSnapshot snapshot
                : channelSnapshots(view.bundle)) {
            ManagedRootChannelOccurrence occurrence = occurrence(
                    view.bundle, snapshot);
            occurrences.add(occurrence);
            if (!occurrence.externalSource()) {
                continue;
            }
            ExternalChannelFunctionResolver.Header header =
                    resolveHeader(view, snapshot);
            subscriptions.add(new SubscriptionDelta.Entry(
                    JsonPointer.ROOT,
                    occurrence.rawChannelKey(),
                    occurrence.effectiveTypeBlueId(),
                    occurrence.sourceContributionNodeBlueIds(),
                    occurrence.order(),
                    header.channelKeys(),
                    header.checkpointDomainBlueId(),
                    header.dependencies(),
                    null,
                    null,
                    null));
        }
        return new ManagedRootSubscriptionSurface(
                occurrences,
                subscriptions,
                view.bundle.effectiveContractSnapshots());
    }

    ManagedExternalDeliveryClassification classifyExternalDelivery(
            Node exactRoot,
            String rawChannelKey,
            ExactEventIdentityEvidence exactEvent,
            GasChargeContext comparisonContext) {
        RootView view = openRoot(exactRoot);
        requireActiveRoot(view.document);
        String channelKey = requireText(rawChannelKey, "rawChannelKey");
        ExactEventIdentityEvidence eventEvidence = Objects.requireNonNull(
                exactEvent, "exactEvent");
        Node event = eventEvidence.event();
        EffectiveContractSnapshot snapshot =
                view.bundle.effectiveContractSnapshot(channelKey);
        if (snapshot == null
                || !EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                .equals(snapshot.role())) {
            throw new InvalidExecutionEvidenceException(
                    "Managed external source is not an active Root External "
                            + "Channel: " + channelKey,
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        ContractBundle.ChannelBinding binding =
                view.bundle.channelBinding(channelKey);
        if (binding == null) {
            throw new InvalidExecutionEvidenceException(
                    "Managed external source binding is absent: " + channelKey,
                    ProcessorErrorCategory.InvalidContractBinding);
        }

        view.runtime.chargeChannelMatchAttempt(JsonPointer.ROOT, channelKey);
        ExternalChannelFunctionEvaluation evaluation =
                ExternalChannelFunctionEvaluation.evaluateWithExactInput(
                        owner, view.runtime, view.bundle, snapshot,
                        eventEvidence, null);
        if (!evaluation.preselects()) {
            return new ManagedExternalDeliveryClassification(
                    ManagedExternalDeliveryClassification.State.NO_MATCH,
                    false,
                    false,
                    false,
                    null);
        }
        if (!evaluation.accepts()) {
            return new ManagedExternalDeliveryClassification(
                    ManagedExternalDeliveryClassification.State.REJECTED,
                    true,
                    false,
                    false,
                    null);
        }
        view.runtime.chargeChannelAccepted(JsonPointer.ROOT, channelKey);

        ManagedRootChannelOccurrence channel = occurrence(
                view.bundle, snapshot);
        ManagedCheckpointDomain domain = domain(
                snapshot,
                evaluation.dependencies(),
                evaluation.runtimeDiscriminator(),
                evaluation.checkpointDomainBlueId());
        Map<String, ManagedCheckpointDomain> knownDomains =
                new LinkedHashMap<String, ManagedCheckpointDomain>();
        knownDomains.put(domain.blueId(), domain);
        ProcessingCheckpointTransaction transaction =
                new ProcessingCheckpointTransaction(
                        view.runtime,
                        owner.languageRuntimeAccess(),
                        owner.observer());
        String subjectBlueId = requireText(
                evaluation.checkpointSubjectBlueId(),
                "checkpointSubjectBlueId");
        FrozenNode exactSubject = Objects.requireNonNull(
                evaluation.checkpointSubject(),
                "checkpointSubject");
        CheckpointManager.CheckpointRecord record =
                transaction.findForComparison(
                        JsonPointer.ROOT,
                        view.bundle,
                        channelKey,
                        domain.blueId(),
                        subjectBlueId,
                        Objects.requireNonNull(
                                comparisonContext, "comparisonContext"));
        ManagedCheckpointState before = checkpointState(
                view.bundle, channelKey, knownDomains);
        boolean newer = isNewer(
                binding,
                view,
                event,
                exactSubject.toNode(),
                subjectBlueId,
                record);
        boolean eligibleNew = newer
                && !transaction.isDuplicate(record, subjectBlueId);
        ManagedCheckpointCandidate candidate =
                new ManagedCheckpointCandidate(
                        channel,
                        domain,
                        exactSubject,
                        subjectBlueId,
                        before,
                        eligibleNew,
                        requireText(
                                evaluation.handlerChannelKey(),
                                "handlerChannelKey"),
                        requireText(
                                evaluation.logicalDeliveryKey(),
                                "logicalDeliveryKey"),
                        Objects.requireNonNull(
                                evaluation.payload(), "payload"),
                        requireText(
                                evaluation.payloadBlueId(),
                                "payloadBlueId"));
        return new ManagedExternalDeliveryClassification(
                eligibleNew
                        ? ManagedExternalDeliveryClassification.State
                        .ACCEPTED_NEW
                        : ManagedExternalDeliveryClassification.State.STALE,
                true,
                true,
                evaluation.handlerChannel() != null,
                candidate);
    }

    ManagedCheckpointSettlement settleCheckpoints(
            Node exactRoot,
            List<ManagedCheckpointSettlementEntry> completedEntries,
            ManagedCheckpointCleanupContextFactory cleanupContextFactory) {
        long gasBefore = sharedGasContext.meter().totalGas();
        RootPlan plan = planRoot(
                "singleton-managed-root",
                exactRoot,
                completedEntries);
        final ManagedCheckpointCleanupContextFactory legacyFactory =
                Objects.requireNonNull(
                        cleanupContextFactory, "cleanupContextFactory");
        ManagedCheckpointSettlementBatch batch = applyBatch(
                Collections.singletonList(plan),
                new ManagedCheckpointBatchCleanupContextFactory() {
                    @Override
                    public GasChargeContext contextFor(
                            String targetManagedScopeIdentity,
                            String rawChannelKey,
                            long checkpointWriteOrdinal) {
                        return legacyFactory.contextFor(
                                rawChannelKey,
                                plan.cleanupOrdinal(rawChannelKey));
                    }
                },
                gasBefore);
        ManagedCheckpointSettlementBatch.TargetResult target =
                batch.targets().get(0);
        List<ManagedCheckpointMutation> mutations =
                new ArrayList<ManagedCheckpointMutation>();
        for (ManagedCheckpointSettlementBatch.Mutation mutation
                : batch.mutations()) {
            mutations.add(mutation.mutation());
        }
        return new ManagedCheckpointSettlement(
                target.resultingBody(),
                mutations,
                batch.gasBefore(),
                batch.gasAfter());
    }

    ManagedCheckpointSettlementBatch settleCheckpointBatch(
            List<ManagedCheckpointSettlementRequest> requests,
            ManagedCheckpointBatchCleanupContextFactory
                    cleanupContextFactory) {
        long gasBefore = sharedGasContext.meter().totalGas();
        List<RootPlan> plans = new ArrayList<RootPlan>();
        Set<String> targetIdentities = new LinkedHashSet<String>();
        for (ManagedCheckpointSettlementRequest request
                : Objects.requireNonNull(requests, "requests")) {
            ManagedCheckpointSettlementRequest target =
                    Objects.requireNonNull(request, "request");
            if (!targetIdentities.add(
                    target.targetManagedScopeIdentity())) {
                throw new InvalidExecutionEvidenceException(
                        "Duplicate checkpoint settlement target: "
                                + target.targetManagedScopeIdentity(),
                        ProcessorErrorCategory.CheckpointPolicyError);
            }
            try (GasMeter.AttributionScope ignored =
                         sharedGasContext.withAttribution(
                                 target.revalidationAttribution())) {
                plans.add(planRoot(
                        target.targetManagedScopeIdentity(),
                        target.exactRoot(),
                        target.completedEntries()));
            }
        }
        return applyBatch(
                plans,
                Objects.requireNonNull(
                        cleanupContextFactory, "cleanupContextFactory"),
                gasBefore);
    }

    private RootPlan planRoot(
            String targetManagedScopeIdentity,
            Node exactRoot,
            List<ManagedCheckpointSettlementEntry> completedEntries) {
        RootView view = openRoot(exactRoot);
        requireActiveRoot(view.document);

        Map<String, ManagedRootChannelOccurrence> surfaceByKey =
                new LinkedHashMap<String, ManagedRootChannelOccurrence>();
        Map<String, ManagedCheckpointDomain> activeDomains =
                new LinkedHashMap<String, ManagedCheckpointDomain>();
        Map<String, ManagedCheckpointDomain> knownDomains =
                new LinkedHashMap<String, ManagedCheckpointDomain>();
        for (EffectiveContractSnapshot snapshot
                : channelSnapshots(view.bundle)) {
            ManagedRootChannelOccurrence channel = occurrence(
                    view.bundle, snapshot);
            surfaceByKey.put(channel.rawChannelKey(), channel);
            if (!channel.externalSource()) {
                continue;
            }
            ExternalChannelFunctionResolver.Header header =
                    resolveHeader(view, snapshot);
            ManagedCheckpointDomain domain = domain(
                    snapshot,
                    header.dependencies(),
                    header.runtimeDiscriminator(),
                    header.checkpointDomainBlueId());
            activeDomains.put(channel.rawChannelKey(), domain);
            knownDomains.put(domain.blueId(), domain);
        }

        List<ManagedCheckpointSettlementEntry> entries =
                canonicalEntries(completedEntries);
        List<PlannedMutation> writes =
                new ArrayList<PlannedMutation>();
        Set<String> candidateKeys = new LinkedHashSet<String>();
        for (ManagedCheckpointSettlementEntry completed : entries) {
            ManagedCheckpointCandidate candidate = completed.candidate();
            if (!candidate.eligibleNew()) {
                throw new InvalidExecutionEvidenceException(
                        "Settlement received a non-new checkpoint candidate",
                        ProcessorErrorCategory.CheckpointPolicyError);
            }
            String key = candidate.rawChannelKey();
            if (!candidateKeys.add(key)) {
                throw new InvalidExecutionEvidenceException(
                        "Settlement received duplicate raw source: " + key,
                        ProcessorErrorCategory.CheckpointPolicyError);
            }
            ManagedRootChannelOccurrence active = surfaceByKey.get(key);
            ManagedCheckpointDomain activeDomain = activeDomains.get(key);
            requireSameActiveChannel(candidate, active, activeDomain);
            knownDomains.put(
                    candidate.domain().blueId(), candidate.domain());
            ManagedCheckpointState before = checkpointState(
                    view.bundle, key, knownDomains);
            if (!before.sameValue(candidate.beforeState())) {
                throw new InvalidExecutionEvidenceException(
                        "Checkpoint state changed after Phase-B comparison: "
                                + key,
                        ProcessorErrorCategory.CheckpointPolicyError);
            }
            ManagedCheckpointState after = new ManagedCheckpointState(
                    candidate.domain(), candidate.subjectBlueId());
            if (before.sameValue(after)) {
                continue;
            }
            ManagedCheckpointMutation.Operation operation = before.present()
                    ? ManagedCheckpointMutation.Operation.REPLACE
                    : ManagedCheckpointMutation.Operation.ADD;
            writes.add(PlannedMutation.write(
                    operation,
                    key,
                    before,
                    after,
                    candidate,
                    completed.rawOccurrenceOrder(),
                    completed.writeContext()));
        }

        ChannelEventCheckpoint marker = checkpointMarker(view.bundle);
        List<PlannedMutation> cleanups =
                new ArrayList<PlannedMutation>();
        if (marker != null) {
            List<String> rawKeys = new ArrayList<String>(
                    marker.getEntries().keySet());
            Collections.sort(rawKeys, ExternalOrderKey::compareTextCodePoints);
            for (String rawKey : rawKeys) {
                CheckpointEntry entry = marker.entry(rawKey);
                ManagedCheckpointDomain active = activeDomains.get(rawKey);
                if (entry != null
                        && active != null
                        && active.blueId().equals(entry.domainBlueId())) {
                    continue;
                }
                if (candidateKeys.contains(rawKey)
                        && active != null) {
                    continue;
                }
                ManagedCheckpointState before = checkpointState(
                        view.bundle, rawKey, knownDomains);
                cleanups.add(PlannedMutation.remove(rawKey, before));
            }
        }
        return new RootPlan(
                targetManagedScopeIdentity,
                view,
                writes,
                cleanups);
    }

    private ManagedCheckpointSettlementBatch applyBatch(
            List<RootPlan> plans,
            ManagedCheckpointBatchCleanupContextFactory cleanupContextFactory,
            long gasBefore) {
        List<BatchMutation> ordered = orderedMutations(
                plans, cleanupContextFactory);
        preflight(ordered);
        for (BatchMutation item : ordered) {
            RootPlan plan = item.plan;
            PlannedMutation mutation = item.mutation;
            ProcessingCheckpointTransaction transaction = plan.transaction();
            if (mutation.candidate != null) {
                CheckpointManager.CheckpointRecord record = transaction.find(
                        plan.view.bundle,
                        mutation.rawChannelKey,
                        mutation.after.domainBlueId());
                transaction.persistSettlement(
                        JsonPointer.ROOT,
                        plan.view.bundle,
                        record,
                        mutation.after.subjectBlueId(),
                        mutation.candidate.settlementSubject(),
                        mutation.writeContext);
            } else {
                transaction.removeSettlementEntry(
                        JsonPointer.ROOT,
                        plan.view.bundle,
                        mutation.rawChannelKey,
                        mutation.before.domainBlueId(),
                        mutation.writeContext);
            }
        }
        long gasAfter = sharedGasContext.meter().totalGas();
        List<ManagedCheckpointSettlementBatch.TargetResult> targets =
                new ArrayList<ManagedCheckpointSettlementBatch.TargetResult>();
        for (RootPlan plan : plans) {
            targets.add(new ManagedCheckpointSettlementBatch.TargetResult(
                    plan.targetManagedScopeIdentity,
                    plan.view.runtime.document()));
        }
        List<ManagedCheckpointSettlementBatch.Mutation> mutations =
                new ArrayList<ManagedCheckpointSettlementBatch.Mutation>();
        for (BatchMutation item : ordered) {
            mutations.add(new ManagedCheckpointSettlementBatch.Mutation(
                    item.plan.targetManagedScopeIdentity,
                    item.mutation.evidence));
        }
        return new ManagedCheckpointSettlementBatch(
                targets,
                mutations,
                gasBefore,
                gasAfter);
    }

    private boolean isNewer(
            ContractBundle.ChannelBinding binding,
            RootView view,
            Node event,
            Node exactSubject,
            String subjectBlueId,
            CheckpointManager.CheckpointRecord record) {
        @SuppressWarnings("unchecked")
        Optional<ChannelProcessor<? extends ChannelContract>> registered =
                owner.registry().lookupChannel(binding.contract());
        if (!registered.isPresent()) {
            throw new InvalidExecutionEvidenceException(
                    "External Channel processor disappeared before comparison",
                    ProcessorErrorCategory.UnsupportedRuntimeType);
        }
        RuntimeWorkSession work = view.runtime.newRuntimeWorkSession(
                owner.languageRuntimeAccess());
        Node previousSubject = record != null
                ? record.lastEventNode : null;
        String previousBlueId = record != null
                ? record.lastEventSignature : null;
        if (previousBlueId == null && previousSubject != null) {
            previousBlueId = previousSubject.getBlueId();
        }
        ChannelCheckpointContext context =
                ChannelCheckpointContext.withRuntimeWorkSession(
                        JsonPointer.ROOT,
                        binding.key(),
                        event,
                        subjectBlueId,
                        exactSubject,
                        previousSubject != null
                                && !previousSubject.isReferenceOnly()
                                ? previousSubject
                                : null,
                        previousBlueId,
                        view.bundle.markers(),
                        previousSubject != null
                                && previousSubject.isReferenceOnly()
                                ? view.runtime.checkpointSubjectMaterializer(
                                previousSubject)
                                : null,
                        work);
        try {
            @SuppressWarnings("rawtypes")
            ChannelProcessor processor = registered.get();
            boolean newer = processor.isNewerEvent(
                    binding.contract(), context);
            work.complete();
            return newer;
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            work.suspend();
            throw unavailable;
        } catch (RuntimeException failure) {
            work.failDeterministically();
            throw failure;
        } finally {
            work.close();
        }
    }

    private ExternalChannelFunctionResolver.Header resolveHeader(
            RootView view,
            EffectiveContractSnapshot snapshot) {
        RuntimeWorkSession authoritative = null;
        RuntimeWorkSession comparison = null;
        ExternalChannelFunctionEvaluation.MatcherSession firstMatcher = null;
        ExternalChannelFunctionEvaluation.MatcherSession secondMatcher = null;
        ExternalChannelFunctionResolver.Header first = null;
        Throwable failure = null;
        boolean evidenceUnavailable = false;
        try {
            authoritative = view.runtime.newRuntimeWorkSession(
                    owner.languageRuntimeAccess());
            comparison = authoritative.diagnosticTwin();
            firstMatcher = view.runtime.externalChannelMatcherSessions().open();
            secondMatcher = view.runtime.externalChannelMatcherSessions().open();
            first = new ExternalChannelFunctionResolver(
                    owner.registry(),
                    owner.contractConverter(),
                    firstMatcher,
                    view.bundle,
                    null,
                    authoritative).header(snapshot);
            ExternalChannelFunctionResolver.Header second =
                    new ExternalChannelFunctionResolver(
                            owner.registry(),
                            owner.contractConverter(),
                            secondMatcher,
                            view.bundle,
                            null,
                            comparison).header(snapshot);
            if (!first.sameResult(second)
                    || !sameRuntimeTrace(
                    authoritative.stagedTrace(),
                    comparison.stagedTrace())) {
                throw new InvalidExecutionEvidenceException(
                        "External Channel header functions are not deterministic",
                        ProcessorErrorCategory.RuntimeExecutionFailure);
            }
            authoritative.complete();
            comparison.suspend();
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            failure = unavailable;
            evidenceUnavailable = true;
        } catch (RuntimeException | Error caught) {
            failure = caught;
        } finally {
            if (failure != null) {
                failure = evidenceUnavailable
                        ? RuntimeWorkSession.suspendIfOpenPreserving(
                                authoritative, failure)
                        : RuntimeWorkSession.failIfOpenPreserving(
                                authoritative, failure);
                failure = RuntimeWorkSession.suspendIfOpenPreserving(
                        comparison, failure);
            }
            failure = RuntimeWorkSession.closePreserving(
                    secondMatcher == null ? null : secondMatcher::close,
                    failure);
            failure = RuntimeWorkSession.closePreserving(
                    firstMatcher == null ? null : firstMatcher::close,
                    failure);
            failure = RuntimeWorkSession.closePreserving(comparison, failure);
            failure = RuntimeWorkSession.closePreserving(authoritative, failure);
        }
        RuntimeWorkSession.rethrow(failure);
        return Objects.requireNonNull(first, "resolvedHeader");
    }

    private ManagedCheckpointState checkpointState(
            ContractBundle bundle,
            String rawChannelKey,
            Map<String, ManagedCheckpointDomain> knownDomains) {
        ChannelEventCheckpoint checkpoint = checkpointMarker(bundle);
        CheckpointEntry entry = checkpoint != null
                ? checkpoint.entry(rawChannelKey) : null;
        if (entry == null) {
            return ManagedCheckpointState.absent();
        }
        Node domainNode = entry.getDomain();
        Node subjectNode = entry.getSubject();
        if (domainNode == null || subjectNode == null) {
            throw new InvalidExecutionEvidenceException(
                    "Checkpoint entry is incomplete: " + rawChannelKey,
                    ProcessorErrorCategory.InvalidReservedRuntimeState);
        }
        String domainBlueId = CheckpointDomainIdentity.exact(domainNode);
        ManagedCheckpointDomain domain = knownDomains.get(domainBlueId);
        if (domain == null) {
            Node exactDomain = materializeExactDomain(domainNode);
            domain = parsedDomain(exactDomain, domainBlueId);
            knownDomains.put(domainBlueId, domain);
        }
        String subjectBlueId = CheckpointIdentityCalculator.identity(
                subjectNode, owner.languageRuntimeAccess());
        return new ManagedCheckpointState(domain, subjectBlueId);
    }

    private Node materializeExactDomain(Node domainNode) {
        if (!domainNode.isReferenceOnly()) {
            return domainNode.clone();
        }
        ProcessingSnapshotManager manager = owner.snapshotManager();
        if (manager == null) {
            throw new ExecutionEvidenceUnavailableException(
                    "Complete checkpoint-domain value is unavailable for "
                            + domainNode.getBlueId(),
                    Collections.singleton(domainNode.getBlueId()));
        }
        FrozenNode materialized = manager.materializeVerifiedExactReference(
                FrozenNode.fromNode(domainNode));
        if (materialized == null || materialized.isReferenceOnly()) {
            throw new ExecutionEvidenceUnavailableException(
                    "Complete checkpoint-domain value is unavailable for "
                            + domainNode.getBlueId(),
                    Collections.singleton(domainNode.getBlueId()));
        }
        return materialized.toNode();
    }

    private ManagedCheckpointDomain parsedDomain(
            Node exactDomain,
            String assertedBlueId) {
        String type = textProperty(
                exactDomain,
                ProcessorIdentityConstants.Field.EFFECTIVE_TYPE_BLUE_ID,
                true);
        List<String> sources = textListProperty(
                exactDomain,
                ProcessorIdentityConstants.Field
                        .SOURCE_CONTRIBUTION_NODE_BLUE_IDS,
                true);
        List<String> dependencies = textListProperty(
                exactDomain,
                ProcessorIdentityConstants.Field
                        .DETERMINISTIC_DEPENDENCY_NODE_BLUE_IDS,
                false);
        String discriminator = textProperty(
                exactDomain,
                ProcessorIdentityConstants.Field.RUNTIME_DISCRIMINATOR,
                false);
        return new ManagedCheckpointDomain(
                type,
                sources,
                dependencies,
                discriminator,
                exactDomain,
                assertedBlueId);
    }

    private ManagedCheckpointDomain domain(
            EffectiveContractSnapshot snapshot,
            ExternalChannelDependencySnapshot dependencies,
            String runtimeDiscriminator,
            String assertedBlueId) {
        Node exact = CheckpointDomain.value(
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                dependencies,
                runtimeDiscriminator);
        return new ManagedCheckpointDomain(
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                dependencies.deterministicDependencyNodeBlueIds(),
                runtimeDiscriminator,
                exact,
                assertedBlueId);
    }

    private ManagedRootChannelOccurrence occurrence(
            ContractBundle bundle,
            EffectiveContractSnapshot snapshot) {
        FrozenNode exactContribution = bundle.contractNode(snapshot.key());
        if (exactContribution == null) {
            throw new ExecutionEvidenceUnavailableException(
                    "Effective Root Channel contribution is unavailable: "
                            + snapshot.key(),
                    Collections.<String>emptySet());
        }
        return new ManagedRootChannelOccurrence(
                snapshot.key(),
                snapshot.order(),
                EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                        .equals(snapshot.role()),
                snapshot.effectiveTypeBlueId(),
                exactContribution.blueId(),
                ChannelMemberSnapshot.from(snapshot, bundle.canonicalTypeIdentities())
                        .headerIdentityBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                snapshot.deterministicDependencyNodeBlueIds());
    }

    private List<EffectiveContractSnapshot> channelSnapshots(
            ContractBundle bundle) {
        List<EffectiveContractSnapshot> result =
                new ArrayList<EffectiveContractSnapshot>();
        for (EffectiveContractSnapshot snapshot
                : bundle.effectiveContractSnapshots()) {
            if (EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                    .equals(snapshot.role())
                    || EffectiveContractSnapshotConstants.Role.PROCESSOR_CHANNEL
                    .equals(snapshot.role())) {
                result.add(snapshot);
            }
        }
        Collections.sort(result, new Comparator<EffectiveContractSnapshot>() {
            @Override
            public int compare(
                    EffectiveContractSnapshot left,
                    EffectiveContractSnapshot right) {
                int order = Integer.compare(left.order(), right.order());
                if (order != 0) {
                    return order;
                }
                int key = ExternalOrderKey.compareTextCodePoints(
                        left.key(), right.key());
                return key != 0 ? key : ExternalOrderKey.compareTextCodePoints(
                        left.effectiveTypeBlueId(),
                        right.effectiveTypeBlueId());
            }
        });
        return result;
    }

    private RootView openRoot(Node exactRoot) {
        Node document = Objects.requireNonNull(
                exactRoot, "exactRoot").clone();
        DocumentProcessingResult invalid =
                ProcessingInputAdmission.validateDocument(document);
        if (invalid != null) {
            ProcessorDiagnostic diagnostic = invalid.diagnostic();
            throw new ProcessorFailureException(
                    diagnostic != null
                            ? diagnostic.category()
                            : ProcessorErrorCategory.InvalidProcessingDocument,
                    diagnostic != null && diagnostic.message() != null
                            ? diagnostic.message()
                            : "Invalid independently managed Root");
        }
        ProcessorMarkerStore.collapseInitializationDocuments(
                document, owner.snapshotManager());
        long gasBefore = sharedGasContext.meter().totalGas();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                document,
                owner.conformanceEngine(),
                owner.conformancePlannerOverride(),
                owner.snapshotManager(),
                owner.observer(),
                sharedGasContext,
                owner.registry().executableBodyFieldsByType(),
                owner.strictPlatformInvocation());
        ResolvedScopeView scope = runtime.scopeViewAt(JsonPointer.ROOT);
        owner.contractLoader().preflightSelectedContractHeaders(scope.selected());
        ResolvedScopeView recognition = runtime.contractRecognitionScope(scope);
        ContractBundle bundle = owner.contractLoader().load(
                scope.selected(),
                recognition.resolved(),
                JsonPointer.ROOT,
                owner.observer(),
                null,
                null,
                recognition.canonicalTypeIdentities());
        if (sharedGasContext.meter().totalGas() != gasBefore) {
            throw new IllegalStateException(
                    "Managed Root contract projection must not charge gas");
        }
        return new RootView(document, runtime, bundle);
    }

    private static void requireActiveRoot(Node document) {
        if (ProcessorMarkerStore.terminationMarker(
                document, JsonPointer.ROOT) != null) {
            throw new InvalidExecutionEvidenceException(
                    "Terminated managed Root cannot accept or settle work",
                    ProcessorErrorCategory.ActiveScopeCutOff);
        }
    }

    private static ChannelEventCheckpoint checkpointMarker(
            ContractBundle bundle) {
        MarkerContract marker = bundle.marker(
                ProcessorContractConstants.KEY_CHECKPOINT);
        if (marker == null) {
            return null;
        }
        if (!(marker instanceof ChannelEventCheckpoint)) {
            throw new InvalidExecutionEvidenceException(
                    "Reserved Root checkpoint marker has the wrong type",
                    ProcessorErrorCategory.InvalidReservedRuntimeState);
        }
        return (ChannelEventCheckpoint) marker;
    }

    private static void requireSameActiveChannel(
            ManagedCheckpointCandidate candidate,
            ManagedRootChannelOccurrence active,
            ManagedCheckpointDomain activeDomain) {
        ManagedRootChannelOccurrence frozen = candidate.channelOccurrence();
        if (active == null
                || activeDomain == null
                || !active.externalSource()
                || !frozen.rawChannelKey().equals(active.rawChannelKey())
                || !frozen.effectiveTypeBlueId().equals(
                active.effectiveTypeBlueId())
                || !frozen.effectiveRuntimeContributionBlueId().equals(
                active.effectiveRuntimeContributionBlueId())) {
            throw new InvalidExecutionEvidenceException(
                    "Accepted source no longer denotes its exact active Root "
                            + "Channel: " + candidate.rawChannelKey(),
                    ProcessorErrorCategory.CheckpointPolicyError);
        }
        /*
         * Header and checkpoint-domain identities may legitimately refresh
         * after the accepted Handler changes another Root Channel. Phase B
         * already froze the exact source occurrence and comparison domain;
         * settling that completed occurrence remains owned by the frozen
         * candidate while the resulting subscription projection adopts the
         * refreshed header/domain for later events.
         */
    }

    private static List<ManagedCheckpointSettlementEntry> canonicalEntries(
            List<ManagedCheckpointSettlementEntry> entries) {
        List<ManagedCheckpointSettlementEntry> result =
                new ArrayList<ManagedCheckpointSettlementEntry>();
        for (ManagedCheckpointSettlementEntry entry : Objects.requireNonNull(
                entries, "completedEntries")) {
            result.add(Objects.requireNonNull(entry, "entry"));
        }
        Collections.sort(
                result,
                new Comparator<ManagedCheckpointSettlementEntry>() {
                    @Override
                    public int compare(
                            ManagedCheckpointSettlementEntry left,
                            ManagedCheckpointSettlementEntry right) {
                        return Long.compare(
                                left.rawOccurrenceOrder(),
                                right.rawOccurrenceOrder());
                    }
                });
        long previous = -1L;
        for (ManagedCheckpointSettlementEntry entry : result) {
            if (entry.rawOccurrenceOrder() <= previous) {
                throw new InvalidExecutionEvidenceException(
                        "Completed checkpoint source ordinals are not "
                                + "strictly increasing",
                        ProcessorErrorCategory.CheckpointPolicyError);
            }
            previous = entry.rawOccurrenceOrder();
        }
        return Collections.unmodifiableList(result);
    }

    private static List<BatchMutation> orderedMutations(
            List<RootPlan> plans,
            ManagedCheckpointBatchCleanupContextFactory cleanupContextFactory) {
        List<BatchMutation> writes = new ArrayList<BatchMutation>();
        List<BatchMutation> cleanups = new ArrayList<BatchMutation>();
        for (RootPlan plan : plans) {
            for (PlannedMutation mutation : plan.writes) {
                writes.add(new BatchMutation(plan, mutation));
            }
            for (PlannedMutation mutation : plan.cleanups) {
                cleanups.add(new BatchMutation(plan, mutation));
            }
        }
        Collections.sort(writes, new Comparator<BatchMutation>() {
            @Override
            public int compare(BatchMutation left, BatchMutation right) {
                return Long.compare(
                        left.mutation.rawOccurrenceOrder,
                        right.mutation.rawOccurrenceOrder);
            }
        });
        Collections.sort(cleanups, new Comparator<BatchMutation>() {
            @Override
            public int compare(BatchMutation left, BatchMutation right) {
                int target = ExternalOrderKey.compareTextCodePoints(
                        left.plan.targetManagedScopeIdentity,
                        right.plan.targetManagedScopeIdentity);
                return target != 0 ? target
                        : ExternalOrderKey.compareTextCodePoints(
                                left.mutation.rawChannelKey,
                                right.mutation.rawChannelKey);
            }
        });
        List<BatchMutation> result = new ArrayList<BatchMutation>(
                writes.size() + cleanups.size());
        result.addAll(writes);
        for (BatchMutation cleanup : cleanups) {
            long ordinal = result.size();
            GasChargeContext context = Objects.requireNonNull(
                    cleanupContextFactory.contextFor(
                            cleanup.plan.targetManagedScopeIdentity,
                            cleanup.mutation.rawChannelKey,
                            ordinal),
                    "cleanup context");
            result.add(new BatchMutation(
                    cleanup.plan,
                    cleanup.mutation.withWriteContext(context)));
        }
        return Collections.unmodifiableList(result);
    }

    private static void preflight(List<BatchMutation> planned) {
        Map<String, Set<String>> keysByTarget =
                new LinkedHashMap<String, Set<String>>();
        for (BatchMutation item : planned) {
            PlannedMutation mutation = item.mutation;
            Set<String> keys = keysByTarget.get(
                    item.plan.targetManagedScopeIdentity);
            if (keys == null) {
                keys = new LinkedHashSet<String>();
                keysByTarget.put(
                        item.plan.targetManagedScopeIdentity, keys);
            }
            if (!keys.add(mutation.rawChannelKey)) {
                throw new InvalidExecutionEvidenceException(
                        "Conflicting checkpoint settlement operations: "
                                + item.plan.targetManagedScopeIdentity
                                + "/" + mutation.rawChannelKey,
                        ProcessorErrorCategory.CheckpointPolicyError);
            }
            if (mutation.candidate != null) {
                if (!mutation.candidate.subjectBlueId().equals(
                        mutation.after.subjectBlueId())) {
                    throw new InvalidExecutionEvidenceException(
                            "Checkpoint settlement subject identity mismatch",
                            ProcessorErrorCategory.CheckpointPolicyError);
                }
            }
        }
    }

    private static String textProperty(
            Node node,
            String key,
            boolean required) {
        Node value = node != null && node.getProperties() != null
                ? node.getProperties().get(key) : null;
        Object raw = value != null ? value.getValue() : null;
        if (raw instanceof String && !((String) raw).isEmpty()) {
            return (String) raw;
        }
        if (!required && value == null) {
            return null;
        }
        throw new InvalidExecutionEvidenceException(
                "Checkpoint-domain field is invalid: " + key,
                ProcessorErrorCategory.CheckpointPolicyError);
    }

    private static List<String> textListProperty(
            Node node,
            String key,
            boolean required) {
        Node value = node != null && node.getProperties() != null
                ? node.getProperties().get(key) : null;
        if (value == null && !required) {
            return Collections.emptyList();
        }
        if (value == null || value.getItems() == null) {
            throw new InvalidExecutionEvidenceException(
                    "Checkpoint-domain list field is invalid: " + key,
                    ProcessorErrorCategory.CheckpointPolicyError);
        }
        List<String> result = new ArrayList<String>();
        for (Node item : value.getItems()) {
            Object raw = item != null ? item.getValue() : null;
            if (!(raw instanceof String) || ((String) raw).isEmpty()) {
                throw new InvalidExecutionEvidenceException(
                        "Checkpoint-domain list item is invalid: " + key,
                        ProcessorErrorCategory.CheckpointPolicyError);
            }
            result.add((String) raw);
        }
        return result;
    }

    private static boolean sameRuntimeTrace(
            List<GasTraceEntry> left,
            List<GasTraceEntry> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            GasTraceEntry a = left.get(index);
            GasTraceEntry b = right.get(index);
            if (!a.namespace().equals(b.namespace())
                    || !a.counter().equals(b.counter())
                    || a.quantity() != b.quantity()
                    || a.weight() != b.weight()
                    || a.subtotal() != b.subtotal()
                    || !Objects.equals(a.scopePath(), b.scopePath())
                    || !Objects.equals(a.contractKey(), b.contractKey())
                    || !Objects.equals(a.logicalPath(), b.logicalPath())
                    || !Objects.equals(a.reason(), b.reason())) {
                return false;
            }
        }
        return true;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }

    private static final class RootView {
        private final Node document;
        private final DocumentProcessingRuntime runtime;
        private final ContractBundle bundle;

        private RootView(
                Node document,
                DocumentProcessingRuntime runtime,
                ContractBundle bundle) {
            this.document = document;
            this.runtime = runtime;
            this.bundle = bundle;
        }
    }

    private static final class PlannedMutation {
        private final String rawChannelKey;
        private final ManagedCheckpointState before;
        private final ManagedCheckpointState after;
        private final ManagedCheckpointCandidate candidate;
        private final long rawOccurrenceOrder;
        private final GasChargeContext writeContext;
        private final ManagedCheckpointMutation evidence;

        private PlannedMutation(
                String rawChannelKey,
                ManagedCheckpointState before,
                ManagedCheckpointState after,
                ManagedCheckpointCandidate candidate,
                long rawOccurrenceOrder,
                GasChargeContext writeContext,
                ManagedCheckpointMutation evidence) {
            this.rawChannelKey = rawChannelKey;
            this.before = before;
            this.after = after;
            this.candidate = candidate;
            this.rawOccurrenceOrder = rawOccurrenceOrder;
            this.writeContext = writeContext;
            this.evidence = evidence;
        }

        private static PlannedMutation write(
                ManagedCheckpointMutation.Operation operation,
                String rawChannelKey,
                ManagedCheckpointState before,
                ManagedCheckpointState after,
                ManagedCheckpointCandidate candidate,
                long rawOccurrenceOrder,
                GasChargeContext writeContext) {
            Node entry = new Node()
                    .properties(
                            ProcessorContractConstants.KEY_DOMAIN,
                            new Node().blueId(after.domainBlueId()))
                    .properties(
                            ProcessorContractConstants.KEY_SUBJECT,
                            candidate.settlementSubject());
            String path = PointerUtils.resolvePointer(
                    JsonPointer.ROOT,
                    ProcessorPointerConstants.relativeCheckpointEntry(
                            ProcessorContractConstants.KEY_CHECKPOINT,
                            rawChannelKey));
            FrozenJsonPatch patch = operation
                    == ManagedCheckpointMutation.Operation.ADD
                    ? FrozenJsonPatch.add(
                    path, FrozenNode.fromNode(entry))
                    : FrozenJsonPatch.replace(
                    path, FrozenNode.fromNode(entry));
            ManagedCheckpointMutation evidence =
                    new ManagedCheckpointMutation(
                            operation,
                            rawChannelKey,
                            before,
                            after,
                            patch);
            return new PlannedMutation(
                    rawChannelKey,
                    before,
                    after,
                    candidate,
                    rawOccurrenceOrder,
                    Objects.requireNonNull(writeContext, "writeContext"),
                    evidence);
        }

        private static PlannedMutation remove(
                String rawChannelKey,
                ManagedCheckpointState before) {
            String path = PointerUtils.resolvePointer(
                    JsonPointer.ROOT,
                    ProcessorPointerConstants.relativeCheckpointEntry(
                            ProcessorContractConstants.KEY_CHECKPOINT,
                            rawChannelKey));
            ManagedCheckpointMutation evidence =
                    new ManagedCheckpointMutation(
                            ManagedCheckpointMutation.Operation.REMOVE,
                            rawChannelKey,
                            before,
                            ManagedCheckpointState.absent(),
                            FrozenJsonPatch.remove(path));
            return new PlannedMutation(
                    rawChannelKey,
                    before,
                    ManagedCheckpointState.absent(),
                    null,
                    -1L,
                    null,
                    evidence);
        }

        private PlannedMutation withWriteContext(
                GasChargeContext context) {
            if (candidate != null || writeContext != null) {
                throw new IllegalStateException(
                        "Only an unbound cleanup accepts batch attribution");
            }
            return new PlannedMutation(
                    rawChannelKey,
                    before,
                    after,
                    null,
                    rawOccurrenceOrder,
                    Objects.requireNonNull(context, "context"),
                    evidence);
        }
    }

    private final class RootPlan {
        private final String targetManagedScopeIdentity;
        private final RootView view;
        private final List<PlannedMutation> writes;
        private final List<PlannedMutation> cleanups;
        private ProcessingCheckpointTransaction transaction;

        private RootPlan(
                String targetManagedScopeIdentity,
                RootView view,
                List<PlannedMutation> writes,
                List<PlannedMutation> cleanups) {
            this.targetManagedScopeIdentity = requireText(
                    targetManagedScopeIdentity,
                    "targetManagedScopeIdentity");
            this.view = Objects.requireNonNull(view, "view");
            this.writes = Collections.unmodifiableList(
                    new ArrayList<PlannedMutation>(writes));
            this.cleanups = Collections.unmodifiableList(
                    new ArrayList<PlannedMutation>(cleanups));
        }

        private int cleanupOrdinal(String rawChannelKey) {
            for (int index = 0; index < cleanups.size(); index++) {
                if (cleanups.get(index).rawChannelKey.equals(rawChannelKey)) {
                    return index;
                }
            }
            throw new IllegalStateException(
                    "Unknown planned checkpoint cleanup: " + rawChannelKey);
        }

        private ProcessingCheckpointTransaction transaction() {
            if (transaction == null) {
                transaction = new ProcessingCheckpointTransaction(
                        view.runtime,
                        owner.languageRuntimeAccess(),
                        owner.observer());
            }
            return transaction;
        }
    }

    private static final class BatchMutation {
        private final RootPlan plan;
        private final PlannedMutation mutation;

        private BatchMutation(
                RootPlan plan,
                PlannedMutation mutation) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.mutation = Objects.requireNonNull(mutation, "mutation");
        }
    }
}
