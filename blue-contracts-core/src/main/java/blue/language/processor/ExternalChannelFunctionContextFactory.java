package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds run-local function contexts while preserving lazy member access. */
final class ExternalChannelFunctionContextFactory {

    interface ResolverAccess {
        ExternalChannelFunctionResolver.Header header(String key);

        ExternalChannelFunctionResolver.Evaluation evaluate(
                String key,
                Node exactEvent);

        IllegalStateException cycle(
                String from,
                String to,
                String phase);
    }

    private final ExternalChannelFunctionEvaluation.MatcherSession
            eventMatcher;
    private final RuntimeWorkSession runtimeWorkSession;
    private final ExternalChannelResolverCatalog catalog;
    private final ResolverAccess resolver;
    private final List<String> channelLookupResults = new ArrayList<>();

    ExternalChannelFunctionContextFactory(
            ExternalChannelFunctionEvaluation.MatcherSession eventMatcher,
            RuntimeWorkSession runtimeWorkSession,
            ExternalChannelResolverCatalog catalog,
            ResolverAccess resolver) {
        this.eventMatcher = eventMatcher;
        this.runtimeWorkSession = runtimeWorkSession;
        this.catalog = catalog;
        this.resolver = resolver;
    }

    List<String> channelLookupResults() {
        return channelLookupResults;
    }

    ExternalChannelFunctionContext create(
            EffectiveContractSnapshot owner,
            ExternalChannelDependencyCapture capture,
            boolean eventEvaluation,
            ExternalChannelDependencySnapshot declaredDependencies) {
        return new ExternalChannelFunctionContext(
                owner.scopePath(),
                owner.key(),
                new ExternalChannelFunctionContext.Access() {
                    @Override
                    public ExternalChannelMemberSnapshot member(String key) {
                        if (owner.key().equals(key)) {
                            throw resolver.cycle(
                                    owner.key(),
                                    owner.key(),
                                    "self dependency");
                        }
                        ExternalChannelFunctionResolver.Header member =
                                resolver.header(key);
                        capture.record(member);
                        return memberSnapshot(
                                member, owner, eventEvaluation);
                    }

                    @Override
                    public List<ExternalChannelMemberSnapshot> members() {
                        capture.wholeSurface();
                        List<EffectiveContractSnapshot> snapshots =
                                catalog.externalSnapshots(owner.key());
                        List<ExternalChannelMemberSnapshot> members =
                                new ArrayList<>(snapshots.size());
                        for (EffectiveContractSnapshot snapshot : snapshots) {
                            ExternalChannelFunctionResolver.Header member =
                                    resolver.header(snapshot.key());
                            capture.record(member);
                            members.add(memberSnapshot(
                                    member, owner, eventEvaluation));
                        }
                        return Collections.unmodifiableList(members);
                    }

                    @Override
                    public List<ExternalChannelMemberSnapshot>
                    membersByEffectiveType(String effectiveTypeBlueId) {
                        List<EffectiveContractSnapshot> matching =
                                new ArrayList<>();
                        for (EffectiveContractSnapshot snapshot
                                : catalog.externalSnapshots(owner.key())) {
                            if (effectiveTypeBlueId.equals(
                                    snapshot.effectiveTypeBlueId())) {
                                matching.add(snapshot);
                            }
                        }
                        capture.typeFamily(
                                owner.key(),
                                effectiveTypeBlueId,
                                ExternalChannelDependencySnapshot
                                        .TypeMatchMode.EXACT,
                                matching);
                        List<ExternalChannelMemberSnapshot> members =
                                new ArrayList<>(matching.size());
                        for (EffectiveContractSnapshot snapshot : matching) {
                            members.add(shallowMemberSnapshot(
                                    snapshot,
                                    capture,
                                    owner,
                                    eventEvaluation));
                        }
                        return Collections.unmodifiableList(members);
                    }

                    @Override
                    public List<ExternalChannelMemberSnapshot>
                    membersAssignableToType(String baseTypeBlueId) {
                        if (eventMatcher == null) {
                            throw new IllegalStateException(
                                    "Verified subtype-family matcher is "
                                            + "unavailable at "
                                            + owner.scopePath() + "/"
                                            + owner.key());
                        }
                        List<EffectiveContractSnapshot> matching =
                                new ArrayList<>();
                        for (EffectiveContractSnapshot snapshot
                                : catalog.externalSnapshots(owner.key())) {
                            if (eventMatcher.isAssignableToType(
                                    snapshot.effectiveTypeBlueId(),
                                    baseTypeBlueId)) {
                                matching.add(snapshot);
                            }
                        }
                        capture.typeFamily(
                                owner.key(),
                                baseTypeBlueId,
                                ExternalChannelDependencySnapshot
                                        .TypeMatchMode.ASSIGNABLE,
                                matching);
                        List<ExternalChannelMemberSnapshot> members =
                                new ArrayList<>(matching.size());
                        for (EffectiveContractSnapshot snapshot : matching) {
                            members.add(shallowMemberSnapshot(
                                    snapshot,
                                    capture,
                                    owner,
                                    eventEvaluation));
                        }
                        return Collections.unmodifiableList(members);
                    }

                    @Override
                    public ChannelMemberSnapshot dependOnSameScopeChannel(
                            String key) {
                        if (eventEvaluation) {
                            throw new IllegalStateException(
                                    "Exact same-scope Channel dependencies "
                                            + "must be declared during "
                                            + "subscription-header evaluation "
                                            + "at " + owner.scopePath() + "/"
                                            + owner.key());
                        }
                        ChannelMemberSnapshot selected =
                                catalog.channelSnapshot(key);
                        if (selected == null) {
                            throw new IllegalStateException(
                                    "Missing required same-scope Channel "
                                            + "dependency: " + key);
                        }
                        capture.record(
                                catalog.channelDependencyEntry(selected));
                        return selected;
                    }

                    @Override
                    public void dependOnSameScopeChannelCatalog() {
                        if (eventEvaluation) {
                            throw new IllegalStateException(
                                    "Same-scope Channel catalog dependencies "
                                            + "must be declared during "
                                            + "subscription-header evaluation "
                                            + "at " + owner.scopePath() + "/"
                                            + owner.key());
                        }
                        capture.channelCatalog(
                                catalog.channelDependencyEntries(),
                                catalog.effectiveContractKeys());
                    }

                    @Override
                    public ChannelLookupResult lookupChannel(String key) {
                        requireEventEvaluation(
                                owner,
                                eventEvaluation,
                                "same-scope Channel catalog lookup");
                        ExternalChannelDependencySnapshot.ChannelEntry
                                declaredEntry =
                                catalog.declaredChannelEntry(
                                        declaredDependencies, key);
                        if (!declaredDependencies
                                .wholeSameScopeChannelCatalog()
                                && declaredEntry == null) {
                            throw new IllegalStateException(
                                    "External Channel event evaluation "
                                            + "consulted an undeclared "
                                            + "same-scope Channel header at "
                                            + owner.scopePath() + "/"
                                            + owner.key() + ": " + key);
                        }
                        /*
                         * Record the complete selector before key lookup, so
                         * an empty result proves exact absence rather than a
                         * pruned classification surface.
                         */
                        if (declaredDependencies
                                .wholeSameScopeChannelCatalog()) {
                            capture.channelCatalog(
                                    catalog.channelDependencyEntries(),
                                    declaredDependencies
                                            .channelCatalogContractKeys());
                        }
                        EffectiveContractSnapshot selectedSnapshot =
                                catalog.effectiveContractSnapshot(key);
                        boolean effectiveContractPresent =
                                catalog.effectiveContractPresent(key);
                        if (selectedSnapshot == null
                                || !catalog.isChannelRole(
                                selectedSnapshot.role())) {
                            if (declaredEntry != null) {
                                throw new IllegalStateException(
                                        "Required same-scope Channel "
                                                + "dependency is unavailable: "
                                                + key);
                            }
                            ChannelLookupResult result =
                                    effectiveContractPresent
                                            ? ChannelLookupResult.nonChannel()
                                            : ChannelLookupResult.absent();
                            recordChannelLookup(key, result);
                            return result;
                        }
                        ChannelMemberSnapshot selected =
                                catalog.channelSnapshot(selectedSnapshot);
                        ExternalChannelDependencySnapshot.ChannelEntry actual =
                                catalog.channelDependencyEntry(selected);
                        if (declaredEntry != null
                                && !declaredEntry.equals(actual)) {
                            throw new IllegalStateException(
                                    "Same-scope Channel dependency changed "
                                            + "during event evaluation: "
                                            + key);
                        }
                        capture.record(actual);
                        ChannelLookupResult result =
                                ChannelLookupResult.channel(selected);
                        recordChannelLookup(key, result);
                        return result;
                    }

                    @Override
                    public boolean matchesPattern(
                            FrozenNode candidate,
                            FrozenNode pattern) {
                        if (!eventEvaluation) {
                            throw new IllegalStateException(
                                    "External Channel pattern matching is "
                                            + "available only during event "
                                            + "evaluation at "
                                            + owner.scopePath() + "/"
                                            + owner.key());
                        }
                        return eventMatcher.matches(candidate, pattern);
                    }

                    @Override
                    public FrozenNode materializeExactReference(
                            FrozenNode reference) {
                        requireEventEvaluation(
                                owner,
                                eventEvaluation,
                                "exact event fragment materialization");
                        FrozenNode exact = eventMatcher
                                .materializeExactReference(reference);
                        if (runtimeWorkSession != null
                                && runtimeWorkSession
                                .hasSemanticOutputBoundary()) {
                            String blueId = reference.isReferenceOnly()
                                    ? reference.getReferenceBlueId()
                                    : reference.blueId();
                            runtimeWorkSession.carryExactInput(
                                    exact, blueId);
                        }
                        return exact;
                    }
                },
                runtimeWorkSession);
    }

    private void recordChannelLookup(
            String key,
            ChannelLookupResult result) {
        channelLookupResults.add(key + ":" + result.kind().name());
    }

    private ExternalChannelMemberSnapshot memberSnapshot(
            ExternalChannelFunctionResolver.Header header,
            EffectiveContractSnapshot owner,
            boolean eventEvaluation) {
        EffectiveContractSnapshot snapshot = header.snapshotInternal();
        return new ExternalChannelMemberSnapshot(
                snapshot.key(),
                snapshot.order(),
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                header.dependencies(),
                header.channelKeys(),
                header.checkpointDomainBlueId(),
                header.contractNodeInternal().toNode(),
                exactEvent -> {
                    requireEventEvaluation(
                            owner,
                            eventEvaluation,
                            "member evaluation");
                    ExternalChannelFunctionResolver.Evaluation evaluation =
                            resolver.evaluate(snapshot.key(), exactEvent);
                    return memberEvaluation(evaluation);
                });
    }

    /**
     * Builds a header-only member whose derived fields remain unresolved until
     * the caller selects that member.
     */
    private ExternalChannelMemberSnapshot shallowMemberSnapshot(
            EffectiveContractSnapshot snapshot,
            ExternalChannelDependencyCapture capture,
            EffectiveContractSnapshot owner,
            boolean eventEvaluation) {
        FrozenNode contractNode = catalog.requireContractNode(snapshot);
        ExternalChannelMemberSnapshot.Header lazyHeader =
                new ExternalChannelMemberSnapshot.Header() {
                    private ExternalChannelFunctionResolver.Header resolve() {
                        ExternalChannelFunctionResolver.Header resolved =
                                resolver.header(snapshot.key());
                        capture.record(resolved);
                        return resolved;
                    }

                    @Override
                    public ExternalChannelDependencySnapshot dependencies() {
                        return resolve().dependencies();
                    }

                    @Override
                    public List<String> channelKeys() {
                        return resolve().channelKeys();
                    }

                    @Override
                    public String checkpointDomainBlueId() {
                        return resolve().checkpointDomainBlueId();
                    }
                };
        return new ExternalChannelMemberSnapshot(
                snapshot.key(),
                snapshot.order(),
                snapshot.effectiveTypeBlueId(),
                snapshot.sourceContributionNodeBlueIds(),
                contractNode.toNode(),
                lazyHeader,
                exactEvent -> {
                    requireEventEvaluation(
                            owner,
                            eventEvaluation,
                            "member evaluation");
                    ExternalChannelFunctionResolver.Header selected =
                            resolver.header(snapshot.key());
                    capture.record(selected);
                    ExternalChannelFunctionResolver.Evaluation evaluation =
                            resolver.evaluate(snapshot.key(), exactEvent);
                    return memberEvaluation(evaluation);
                });
    }

    private ExternalChannelMemberEvaluation memberEvaluation(
            ExternalChannelFunctionResolver.Evaluation evaluation) {
        return new ExternalChannelMemberEvaluation(
                evaluation.channelKeys(),
                evaluation.eventKeys(),
                evaluation.preselects(),
                evaluation.accepts(),
                evaluation.checkpointDomainBlueId(),
                evaluation.payload() != null
                        ? evaluation.payload().toNode()
                        : null,
                evaluation.checkpointSubject() != null
                        ? evaluation.checkpointSubject().toNode()
                        : null,
                evaluation.handlerChannelKey(),
                evaluation.logicalDeliveryKey());
    }

    private void requireEventEvaluation(
            EffectiveContractSnapshot owner,
            boolean eventEvaluation,
            String operation) {
        if (!eventEvaluation) {
            throw new IllegalStateException(
                    "External Channel " + operation
                            + " is available only during event "
                            + "evaluation at "
                            + owner.scopePath() + "/"
                            + owner.key());
        }
        eventMatcher.requireActive();
    }
}
