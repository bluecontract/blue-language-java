package blue.language.processor.closure;

import blue.language.processor.DocumentProcessor;
import blue.language.model.Node;
import blue.language.processor.ManagedRootSubscriptionSurface;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Production composition root for affected-closure admission and processing.
 *
 * <p>Every invocation executes against one read-locked configuration revision
 * of the supplied ordinary {@link DocumentProcessor}. A no-argument instance
 * owns a default processor generation. An instance created with an existing
 * processor borrows it and never closes it.</p>
 *
 * <p>This facade intentionally lives in the closure package. It keeps closure
 * model types out of the ordinary processor package while ensuring that the
 * same configured runtime and lifecycle boundary are used for every isolated
 * managed-document step.</p>
 */
public final class BlueClosureContracts
        implements ClosureProcessor, AutoCloseable {

    private final DocumentProcessor owner;
    private final DefaultClosureProcessor processor;
    private final boolean ownsOwner;
    private boolean closed;

    /** Creates a composition root that owns a default processor generation. */
    public BlueClosureContracts() {
        this(new DocumentProcessor(), null, true);
    }

    /**
     * Creates an owning default composition root with an evidence observer.
     *
     * @param observer supplemental implementation-evidence observer
     */
    public BlueClosureContracts(ClosureExecutionObserver observer) {
        this(new DocumentProcessor(), observer, true);
    }

    /**
     * Creates a composition root borrowing an existing processor generation.
     *
     * @param owner configured ordinary document processor
     */
    public BlueClosureContracts(DocumentProcessor owner) {
        this(owner, null, false);
    }

    /**
     * Creates a borrowing composition root with an evidence observer.
     *
     * @param owner configured ordinary document processor
     * @param observer supplemental implementation-evidence observer
     */
    public BlueClosureContracts(
            DocumentProcessor owner,
            ClosureExecutionObserver observer) {
        this(owner, observer, false);
    }

    private BlueClosureContracts(
            DocumentProcessor owner,
            ClosureExecutionObserver observer,
            boolean ownsOwner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.processor = observer == null
                ? new DefaultClosureProcessor(owner)
                : new DefaultClosureProcessor(owner, observer);
        this.ownsOwner = ownsOwner;
    }

    /**
     * Processes one verified affected-closure invocation atomically against a
     * captured ordinary runtime revision.
     *
     * @param input exact invocation input and evidence
     * @return complete result or exact retry disposition
     */
    @Override
    public synchronized ClosureAttemptResult processClosure(
            final ClosureInvocationInput input) {
        ensureOpen();
        return owner.withCapturedConfiguration(
                new Supplier<ClosureAttemptResult>() {
                    @Override
                    public ClosureAttemptResult get() {
                        return processor.processClosure(input);
                    }
                });
    }

    /**
     * Executes one exact external origin with independent initial component budgets and live
     * feedback admission. Returned operations are separate commits except where an operation
     * explicitly owns several joined lineages. No host state is read or mutated by this call.
     */
    public synchronized SameOriginProcessAttempt processSameOrigin(final ClosureInvocationInput input) {
        return processSameOrigin(input, SameOriginAttachmentPolicy.empty());
    }

    /** Executes with explicit occurrence attachment selections bound into each creating seed. */
    public synchronized SameOriginProcessAttempt processSameOrigin(final ClosureInvocationInput input,
            final SameOriginAttachmentPolicy attachmentPolicy) {
        ensureOpen();
        return owner.withCapturedConfiguration(() -> processor.processSameOrigin(input, attachmentPolicy));
    }

    /** Reuses already committed source programs while producing only newly owned atomic operations. */
    public synchronized SameOriginProcessAttempt processSameOrigin(final ClosureInvocationInput input,
            final SameOriginAttachmentPolicy attachmentPolicy, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps, final List<SourceOperationFailure> sourceFailures) {
        return processSameOrigin(input, attachmentPolicy, sourcePrograms, gaps, sourceFailures, Collections.emptyList());
    }

    /** Canonical initializers are immutable evidence; they are observed only at an actual creation site. */
    public synchronized SameOriginProcessAttempt processSameOrigin(final ClosureInvocationInput input,
            final SameOriginAttachmentPolicy attachmentPolicy, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps, final List<SourceOperationFailure> sourceFailures,
            final List<SourceInitialization> sourceInitializations) {
        return processSameOrigin(input, attachmentPolicy, sourcePrograms, gaps, sourceFailures, sourceInitializations, Collections.emptyList());
    }

    /**
     * Exact historical frontier views are offered without activating their prospective occurrences.
     * This compatibility overload fixes every external producer to the invocation's execution policy;
     * independently selected producer policies require the overload with expected source bases.
     */
    public synchronized SameOriginProcessAttempt processSameOrigin(final ClosureInvocationInput input,
            final SameOriginAttachmentPolicy attachmentPolicy, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps, final List<SourceOperationFailure> sourceFailures,
            final List<SourceInitialization> sourceInitializations, final List<SourceFrontierView> frontierViews) {
        return processSameOrigin(input, attachmentPolicy, sourcePrograms, gaps, sourceFailures, sourceInitializations,
                frontierViews, Collections.emptyMap());
    }

    /** Expected producer bases are authenticated source authority, independent of the consumer's execution policy. */
    public synchronized SameOriginProcessAttempt processSameOrigin(final ClosureInvocationInput input,
            final SameOriginAttachmentPolicy attachmentPolicy, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps, final List<SourceOperationFailure> sourceFailures,
            final List<SourceInitialization> sourceInitializations, final List<SourceFrontierView> frontierViews,
            final java.util.Map<DocumentId, String> expectedSourceBases) {
        return processSameOrigin(input, attachmentPolicy, sourcePrograms, gaps, sourceFailures, sourceInitializations,
                frontierViews, expectedSourceBases, null);
    }

    /**
     * Strict fresh-input admission. Only the named original owners may begin fresh execution or
     * join an owned operation. Missing owners are requested at their actual admission site, without
     * a partial result. Retained source capabilities keep their separately authenticated policies.
     * A null set is the compatibility form whose caller has admitted the complete invocation.
     */
    public synchronized SameOriginProcessAttempt processSameOrigin(final ClosureInvocationInput input,
            final SameOriginAttachmentPolicy attachmentPolicy, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps, final List<SourceOperationFailure> sourceFailures,
            final List<SourceInitialization> sourceInitializations, final List<SourceFrontierView> frontierViews,
            final java.util.Map<DocumentId, String> expectedSourceBases, final Set<DocumentId> admittedFreshSources) {
        ensureOpen();
        return owner.withCapturedConfiguration(() -> processor.processSameOrigin(input, attachmentPolicy, sourcePrograms, gaps, sourceFailures,
                sourceInitializations, frontierViews, expectedSourceBases, admittedFreshSources));
    }

    /**
     * Interprets retained independent source actions through the ordinary
     * continuation and FIFO, executing only newly participating consumers.
     * The input must name the source's original external cause and exact
     * source predecessor. This method does not publish or settle its source.
     * This compatibility lane fixes the producer policy to the invocation policy;
     * use the explicit-basis {@code processExternalScope} overload for cross-policy reuse.
     */
    public synchronized ClosureAttemptResult processWithSourceObservation(
            final ClosureInvocationInput input,
            final SourceObservationProgram sourceProgram) {
        ensureOpen();
        Objects.requireNonNull(sourceProgram, "sourceProgram");
        return owner.withCapturedConfiguration(new Supplier<ClosureAttemptResult>() {
            @Override public ClosureAttemptResult get() {
                return processor.processClosure(input, sourceProgram);
            }
        });
    }

    /**
     * Executes one externally owned atomic scope. Dependencies retain their own
     * completed source programs and cannot become newly metered source work.
     */
    public synchronized ClosureAttemptResult processExternalScope(
            final ClosureInvocationInput input,
            final java.util.Set<DocumentId> ownedDocuments,
            final List<SourceObservationProgram> sourcePrograms) {
        return processExternalScope(input, ownedDocuments, sourcePrograms,
                Collections.<DocumentId, List<SourceObservationGap>>emptyMap());
    }

    /** Executes a scope with exact consumed-failure continuity for stale successful pins. */
    public synchronized ClosureAttemptResult processExternalScope(
            final ClosureInvocationInput input,
            final java.util.Set<DocumentId> ownedDocuments,
            final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps) {
        return processExternalScope(input, ownedDocuments, sourcePrograms, gaps, Collections.<SourceOperationFailure>emptyList());
    }

    /**
     * Imports terminal producer outcomes without executing or publishing their failed business prefix.
     * This compatibility overload fixes external producer policies to the invocation policy.
     */
    public synchronized ClosureAttemptResult processExternalScope(final ClosureInvocationInput input,
            final java.util.Set<DocumentId> ownedDocuments, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps,
            final List<SourceOperationFailure> sourceFailures) {
        return processExternalScope(input, ownedDocuments, sourcePrograms, gaps, sourceFailures, Collections.emptyMap());
    }

    /** Cross-policy source interpretation requires independently selected producer bases. */
    public synchronized ClosureAttemptResult processExternalScope(final ClosureInvocationInput input,
            final java.util.Set<DocumentId> ownedDocuments, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps, final List<SourceOperationFailure> sourceFailures,
            final java.util.Map<DocumentId, String> expectedSourceBases) {
        ensureOpen();
        Objects.requireNonNull(ownedDocuments, "ownedDocuments");
        Objects.requireNonNull(sourcePrograms, "sourcePrograms");
        return owner.withCapturedConfiguration(new Supplier<ClosureAttemptResult>() {
            @Override public ClosureAttemptResult get() {
                return processor.processExternalScope(input, ownedDocuments, sourcePrograms, gaps, sourceFailures, expectedSourceBases);
            }
        });
    }

    /**
     * Executes one explicitly selected historical reaction without redelivering the consumer's external input.
     * This compatibility overload fixes external producer policies to the invocation policy.
     */
    public synchronized ClosureAttemptResult processManagedReaction(final ClosureInvocationInput sourceCauseInput,
            final java.util.Set<DocumentId> consumerOwned, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps, final List<SourceOperationFailure> sourceFailures,
            final ManagedReactionContext reaction) {
        return processManagedReaction(sourceCauseInput, consumerOwned, sourcePrograms, gaps, sourceFailures, reaction, Collections.emptyMap());
    }

    /** Interprets a managed lane under its original producer basis, not the importing consumer's budget. */
    public synchronized ClosureAttemptResult processManagedReaction(final ClosureInvocationInput sourceCauseInput,
            final java.util.Set<DocumentId> consumerOwned, final List<SourceObservationProgram> sourcePrograms,
            final java.util.Map<DocumentId, List<SourceObservationGap>> gaps, final List<SourceOperationFailure> sourceFailures,
            final ManagedReactionContext reaction, final java.util.Map<DocumentId, String> expectedSourceBases) {
        Objects.requireNonNull(reaction, "reaction");
        return processExternalScope(sourceCauseInput.withManagedReaction(reaction), consumerOwned, sourcePrograms, gaps, sourceFailures, expectedSourceBases);
    }

    /**
     * Reconstructs and retries one processing invocation with exact
     * demand-bound managed-occurrence resolutions.
     *
     * <p>The base invocation is reverified and reexecuted from its immutable
     * input. Resolutions are consumed only at their exact deterministic
     * post-patch demand boundary. The retry owns a distinct invocation
     * identity and does not retain an in-memory execution continuation.</p>
     *
     * @param input exact resolution-bound retry input
     * @return complete result or a further exact-resource suspension
     */
    public synchronized ClosureAttemptResult processClosureRetry(
            final ClosureProcessRetryInput input) {
        ensureOpen();
        return owner.withCapturedConfiguration(
                new Supplier<ClosureAttemptResult>() {
                    @Override
                    public ClosureAttemptResult get() {
                        return processor.processClosureRetry(input);
                    }
                });
    }

    /**
     * Admits one verified closure candidate through the legacy bounded
     * compatibility lane against a captured ordinary runtime revision.
     *
     * <p>This method does not execute initialization-caused Document Updates,
     * application events, lifecycle termination, or further queued work. It
     * is nonconforming for normative {@code ADMIT_CLOSURE} whenever admission
     * can enqueue lifecycle work. Use
     * {@link #admitClosureWithLifecycleQueue(ClosureInvocationInput)} for
     * normative conformance and production admission.</p>
     *
     * @param input exact admission invocation input and evidence
     * @return complete admission result or exact retry disposition
     */
    @Override
    public synchronized ClosureAttemptResult admitClosure(
            final ClosureInvocationInput input) {
        ensureOpen();
        return owner.withCapturedConfiguration(
                new Supplier<ClosureAttemptResult>() {
                    @Override
                    public ClosureAttemptResult get() {
                        return processor.admitClosure(input);
                    }
                });
    }

    /**
     * Admits one verified closure candidate through the normative complete
     * lifecycle work and event queues against a captured ordinary runtime
     * revision.
     *
     * <p>This is the conforming public Java entry point for
     * {@code ADMIT_CLOSURE}, including initialization-caused Document Updates,
     * application events, graceful termination, and further queued work.</p>
     *
     * @param input exact admission invocation input and evidence
     * @return complete admission result or exact retry disposition
     */
    @Override
    public synchronized ClosureAttemptResult admitClosureWithLifecycleQueue(
            final ClosureInvocationInput input) {
        ensureOpen();
        return owner.withCapturedConfiguration(
                new Supplier<ClosureAttemptResult>() {
                    @Override
                    public ClosureAttemptResult get() {
                        return processor.admitClosureWithLifecycleQueue(input);
                    }
                });
    }

    /** Canonical initialization owns only the selected atomic group; initialized dependencies remain read-only. */
    public synchronized ClosureAttemptResult admitExternalScope(final ClosureInvocationInput input,
            final java.util.Set<DocumentId> ownedDocuments) {
        return admitExternalScope(input, ownedDocuments, Collections.<SourceInitialization>emptyList());
    }

    /** Fixed-policy compatibility: every imported initialization must use this invocation's policy. */
    public synchronized ClosureAttemptResult admitExternalScope(final ClosureInvocationInput input,
            final java.util.Set<DocumentId> ownedDocuments, final List<SourceInitialization> sourceInitializations) {
        java.util.Set<DocumentId> sources = new java.util.HashSet<DocumentId>();
        java.util.ArrayDeque<SourceObservationProgram> pending = new java.util.ArrayDeque<SourceObservationProgram>();
        for (SourceInitialization initialization : sourceInitializations) pending.add(initialization.program());
        java.util.Set<String> seen = new java.util.HashSet<String>();
        while (!pending.isEmpty()) {
            SourceObservationProgram program = pending.removeFirst();
            if (!seen.add(program.invocationIdentity())) continue;
            sources.addAll(program.ownedDocumentIds()); pending.addAll(program.borrowedPrograms());
        }
        return admitExternalScope(input, ownedDocuments, sourceInitializations,
                SourceExecutionBasis.fixedPolicyBases(sources, input.environment(), input.executionPolicy()), false);
    }

    /**
     * Installs independent canonical initialization using trusted producer bases for every borrowed owner.
     * Each imported owner's canonical initialization operation must also be bound by the input's
     * semantic predecessors; producer authority alone cannot identify the consumer's chosen preparation.
     */
    public synchronized ClosureAttemptResult admitExternalScope(final ClosureInvocationInput input,
            final java.util.Set<DocumentId> ownedDocuments, final List<SourceInitialization> sourceInitializations,
            final java.util.Map<DocumentId, String> expectedSourceBases) {
        return admitExternalScope(input, ownedDocuments, sourceInitializations, expectedSourceBases, true);
    }

    private ClosureAttemptResult admitExternalScope(final ClosureInvocationInput input,
            final java.util.Set<DocumentId> ownedDocuments, final List<SourceInitialization> sourceInitializations,
            final java.util.Map<DocumentId, String> expectedSourceBases, boolean requireBoundPredecessors) {
        ensureOpen();
        Objects.requireNonNull(ownedDocuments, "ownedDocuments");
        final List<SourceInitialization> initializations = Collections.unmodifiableList(
                new java.util.ArrayList<SourceInitialization>(Objects.requireNonNull(sourceInitializations, "sourceInitializations")));
        if (requireBoundPredecessors) {
            java.util.Set<String> checked = new java.util.HashSet<String>();
            java.util.ArrayDeque<SourceObservationProgram> pending = new java.util.ArrayDeque<SourceObservationProgram>();
            for (SourceInitialization initialization : initializations) pending.add(initialization.program());
            while (!pending.isEmpty()) {
                SourceObservationProgram program = pending.removeFirst();
                if (!checked.add(program.invocationIdentity())) continue;
                for (DocumentId source : program.ownedDocumentIds())
                    if (!program.invocationIdentity().equals(input.semanticPredecessors().get(source)))
                        throw new IllegalArgumentException("Independent initialization must be bound by its exact semantic predecessor: " + source.value());
                pending.addAll(program.borrowedPrograms());
            }
        }
        java.util.Set<DocumentId> initializedSources = new java.util.HashSet<DocumentId>();
        for (SourceInitialization initialization : initializations) {
            initialization.verifyInstallationBasis(input.snapshot(), ownedDocuments, input.environment(), expectedSourceBases);
            for (DocumentId source : initialization.ownedDocumentIds()) {
                if (!initializedSources.add(source)) throw new IllegalArgumentException("Two initialization programs own the same source");
            }
        }
        for (ManagedDocumentSnapshot document : input.snapshot().managedDocuments()) {
            if (!ownedDocuments.contains(document.documentId()) && !document.initialized()
                    && !initializedSources.contains(document.documentId())) {
                throw new IllegalArgumentException("An independent source must have its own canonical initialization first");
            }
        }
        return owner.withCapturedConfiguration(new Supplier<ClosureAttemptResult>() {
            @Override public ClosureAttemptResult get() {
                return processor.admitExternalScope(input, ownedDocuments, initializations, expectedSourceBases);
            }
        });
    }

    /**
     * Projects the exact externally routable surface of one independently
     * managed Root without traversing a Process Embedded declaration.
     *
     * <p>The projection uses the same captured processor configuration and
     * deterministic header-function boundary as closure execution. It is
     * intended for host publication after a verified closure result.</p>
     *
     * @param exactRoot exact independently managed Root
     * @return immutable Root-only Channel and external-subscription surface
     */
    public synchronized ManagedRootSubscriptionSurface
            projectRootSubscriptionSurface(final Node exactRoot) {
        ensureOpen();
        Objects.requireNonNull(exactRoot, "exactRoot");
        return owner.withCapturedConfiguration(
                new Supplier<ManagedRootSubscriptionSurface>() {
                    @Override
                    public ManagedRootSubscriptionSurface get() {
                        try (ManagedDocumentStepProcessor steps =
                                     new ManagedDocumentStepProcessor(owner)) {
                            return steps.projectRootSubscriptionSurface(
                                    exactRoot);
                        }
                    }
                });
    }

    /** Captures complete routing metadata only after verifying the owning state and topology. */
    public synchronized List<RootChannelMetadata> captureRootMetadata(final AffectedClosureSnapshot snapshot) {
        return captureRootMetadata(snapshot, documentIds(snapshot));
    }

    /** Captures selected Root surfaces while retaining the complete verified topology read cut. */
    public synchronized List<RootChannelMetadata> captureRootMetadata(final AffectedClosureSnapshot snapshot,
            final Set<DocumentId> selectedRoots) {
        ensureOpen();
        ClosureEvidenceVerifier.verifySnapshot(Objects.requireNonNull(snapshot, "snapshot"));
        final Set<DocumentId> selected = checkedDocumentIds(snapshot, selectedRoots);
        return owner.withCapturedConfiguration(new Supplier<List<RootChannelMetadata>>() {
            @Override public List<RootChannelMetadata> get() {
                List<RootChannelMetadata> result = new ArrayList<RootChannelMetadata>();
                for (ManagedDocumentSnapshot state : snapshot.managedDocuments()) {
                    if (!selected.contains(state.documentId())) continue;
                    RootChannelMetadata retained = state.rootMetadata().orElse(null);
                    if (retained != null) {
                        retained.verifyState(state); retained.verifyRegistry(owner.runtimeRegistryIdentity()); result.add(retained);
                    } else result.add(RootChannelMetadata.fromVerifiedRoot(state, owner));
                }
                return Collections.unmodifiableList(result);
            }
        });
    }

    /** Derives the complete frozen direct-delivery set from exact Root headers. */
    public synchronized List<DirectLogicalDelivery> selectDirectDeliveries(
            final AffectedClosureSnapshot snapshot, final Node exactEvent) {
        return selectDirectDeliveries(snapshot, exactEvent, Collections.<SourceObservationProgram>emptyList());
    }

    /** Source headers are read for canonical admission without aligning visible dependency pins. */
    public synchronized List<DirectLogicalDelivery> selectDirectDeliveries(
            final AffectedClosureSnapshot snapshot, final Node exactEvent,
            final List<SourceObservationProgram> sourcePrograms) {
        return selectDirectDeliveries(snapshot, exactEvent, sourcePrograms, Collections.<SourceOperationFailure>emptyList());
    }

    public synchronized List<DirectLogicalDelivery> selectDirectDeliveries(final AffectedClosureSnapshot snapshot,
            final Node exactEvent, final List<SourceObservationProgram> sourcePrograms,
            final List<SourceOperationFailure> sourceFailures) {
        return selectDirectDeliveries(snapshot, exactEvent, sourcePrograms, sourceFailures, documentIds(snapshot));
    }

    /** Selects only live sources; inactive edges remain in the separately verified complete read cut. */
    public synchronized List<DirectLogicalDelivery> selectDirectDeliveries(final AffectedClosureSnapshot snapshot,
            final Node exactEvent, final List<SourceObservationProgram> sourcePrograms,
            final List<SourceOperationFailure> sourceFailures, final Set<DocumentId> eligibleSources) {
        ensureOpen();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(exactEvent, "exactEvent");
        final Set<DocumentId> eligible = checkedDocumentIds(snapshot, eligibleSources);
        return owner.withCapturedConfiguration(new Supplier<List<DirectLogicalDelivery>>() {
            @Override public List<DirectLogicalDelivery> get() {
                java.util.Map<DocumentId, Node> sourceHeaders = new java.util.HashMap<DocumentId, Node>();
                for (SourceObservationProgram program : sourcePrograms) {
                    for (SourceObservationProgram.SourceState state : program.sourcePredecessors()) {
                        if (eligible.contains(state.documentId()) && program.ownedDocumentIds().contains(state.documentId())
                                && sourceHeaders.put(state.documentId(), state.document()) != null) {
                            throw new IllegalArgumentException("Conflicting source header authority");
                        }
                    }
                }
                for (SourceOperationFailure failure : sourceFailures) {
                    for (SourceObservationProgram.SourceState state : failure.sourcePredecessors()) {
                        if (eligible.contains(state.documentId()) && failure.ownedDocumentIds().contains(state.documentId())
                                && sourceHeaders.put(state.documentId(), state.document()) != null) {
                            throw new IllegalArgumentException("Conflicting failed source header authority");
                        }
                    }
                }
                List<DirectLogicalDelivery> selected = new ArrayList<DirectLogicalDelivery>();
                long ordinal = 0L;
                try (ManagedDocumentStepProcessor steps = new ManagedDocumentStepProcessor(owner)) {
                    for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
                        if (!eligible.contains(document.documentId()) || !document.initialized() || document.terminated()) continue;
                        Node header; RootChannelMetadata retainedMetadata = null;
                        if (sourceHeaders.containsKey(document.documentId())) header = sourceHeaders.get(document.documentId());
                        else if (document.rootMetadata().isPresent()) {
                            RootChannelMetadata metadata = document.rootMetadata().get();
                            metadata.verifyState(document); metadata.verifyRegistry(owner.runtimeRegistryIdentity());
                            retainedMetadata = metadata; header = null;
                        } else header = document.document();
                        for (blue.language.processor.ManagedRootChannelOccurrence channel
                                : retainedMetadata == null ? steps.projectRootChannelSurface(header) : retainedMetadata.surface().channelOccurrences()) {
                            if (!channel.externalSource()) continue;
                            blue.language.processor.ManagedExternalDeliveryClassification classification =
                                    retainedMetadata == null ? steps.classifyExternalDelivery(header, channel.rawChannelKey(),
                                            exactEvent, blue.language.processor.GasChargeContext.empty())
                                            : steps.classifyExternalDelivery(retainedMetadata, channel.rawChannelKey(), exactEvent,
                                                    blue.language.processor.GasChargeContext.empty());
                            if (classification.state() == blue.language.processor.ManagedExternalDeliveryClassification.State.ACCEPTED_NEW) {
                                selected.add(new DirectLogicalDelivery(ManagedScopeKey.root(document.documentId()),
                                        channel.rawChannelKey(), classification.candidate().logicalDeliveryKey(), ordinal));
                            }
                            ordinal++;
                        }
                    }
                }
                return Collections.unmodifiableList(selected);
            }
        });
    }

    private static Set<DocumentId> documentIds(AffectedClosureSnapshot snapshot) {
        Set<DocumentId> ids = new java.util.HashSet<DocumentId>();
        for (ManagedDocumentSnapshot state : Objects.requireNonNull(snapshot, "snapshot").managedDocuments()) ids.add(state.documentId());
        return ids;
    }

    private static Set<DocumentId> checkedDocumentIds(AffectedClosureSnapshot snapshot, Set<DocumentId> requested) {
        Set<DocumentId> ids = new java.util.HashSet<DocumentId>(Objects.requireNonNull(requested, "selectedRoots"));
        if (ids.contains(null) || !documentIds(snapshot).containsAll(ids))
            throw new IllegalArgumentException("Selected Root is outside the complete verified read cut");
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Closes this composition root and its ordinary processor when owned.
     * Borrowed processor generations remain live.
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (ownsOwner) {
                owner.close();
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Blue closure contracts composition root is closed");
        }
    }
}
