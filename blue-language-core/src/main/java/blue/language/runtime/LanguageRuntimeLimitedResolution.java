package blue.language.runtime;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.provider.NodeProvider;
import blue.language.provider.ProviderUnavailableException;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.graph.NodeExpander;
import blue.language.matching.MatchingRuntime;
import blue.language.matching.NodeTypeMatcher;
import blue.language.model.Node;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.resolve.ResolutionLimits;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Demand-closure and evidence accounting for limited resolution. */
final class LanguageRuntimeLimitedResolution {

    private static final ReferenceCacheAdmissionPolicy
            REFERENCE_CACHE_ADMISSION = blueId -> true;

    private LanguageRuntimeLimitedResolution() {
    }

    static BlueOperationResult<Boolean> matches(
            NodeProvider nodeProvider,
            MergingProcessor mergingProcessor,
            Node candidate,
            Node type,
            BlueOperationLimits limits,
            Function<Node, Node> preprocessor,
            BlueCachePolicy cachePolicy) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(limits, "limits");
        ReferenceBudget budget = new ReferenceBudget(limits.maxReferenceExpansions());
        LimitedMatchingRuntime runtime = new LimitedMatchingRuntime(
                budgetedProvider(nodeProvider, budget), mergingProcessor,
                limits, preprocessor, cachePolicy);
        try {
            boolean matched;
            if (type == null) {
                runtime.resolveTypeEvidenceForMatching(
                        preprocessor.apply(candidate.clone()), ResolutionLimits.NO_LIMITS);
                matched = true;
            } else {
                // The throwing path retains evidence failures until this operation
                // forms its typed result. Candidate preparation happens only once.
                matched = new NodeTypeMatcher(runtime).matchesTypeOrThrow(
                        candidate, type, ResolutionLimits.NO_LIMITS);
            }
            return BlueOperationResult.established(matched);
        } catch (DemandedPathsAbsentException absent) {
            return BlueOperationResult.absent(absent.getMessage());
        } catch (RuntimeException failure) {
            return classifyFailure(failure, budget);
        }
    }

    static BlueOperationResult<Node> resolve(
            NodeProvider nodeProvider,
            MergingProcessor mergingProcessor,
            Node source,
            BlueOperationLimits limits,
            Function<Node, Node> preprocessor) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        ReferenceBudget budget = new ReferenceBudget(
                limits.maxReferenceExpansions());
        NodeProvider budgetedProvider = budgetedProvider(
                nodeProvider, budget);

        Node resolved;
        try {
            resolved = new Merger(
                    mergingProcessor,
                    budgetedProvider,
                    null,
                    REFERENCE_CACHE_ADMISSION)
                    .resolve(
                            preprocessor.apply(source.clone()),
                            new SemanticDemandLimits(
                                    limits.demandedSegments()));
        } catch (ReferenceExpansionLimitException limitReached) {
            return BlueOperationResult.incomplete(
                    null,
                    budget.outstandingBlueIds,
                    null,
                    limitReached.getMessage());
        } catch (RuntimeException failure) {
            return classifyFailure(failure, budget);
        }

        if (hasDemandedPath(resolved, limits)) {
            return BlueOperationResult.established(resolved);
        }
        return BlueOperationResult.absent(
                "Demanded paths are absent from the completed resolved value.");
    }

    private static boolean hasDemandedPath(Node resolved, BlueOperationLimits limits) {
        for (String path : limits.demandedPaths()) {
            try {
                if (BlueViewPath.select(resolved, path) != null) {
                    return true;
                }
            } catch (IllegalArgumentException absent) {
                // Continue until every demanded path has been checked.
            }
        }
        return false;
    }

    private static NodeProvider budgetedProvider(
            NodeProvider nodeProvider,
            ReferenceBudget budget) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                if (result.outcome() == NodeProviderOutcome.FOUND) {
                    return result.nodes();
                }
                if (result.outcome()
                        == NodeProviderOutcome.INVALID_EVIDENCE) {
                    throw new IllegalArgumentException(
                            result.diagnostic().orElse(
                                    "Provider returned invalid evidence for "
                                            + blueId));
                }
                if (result.outcome()
                        == NodeProviderOutcome.UNAVAILABLE) {
                    throw new ProviderUnavailableException(blueId,
                            result.diagnostic().orElse(
                                    "Provider unavailable for " + blueId));
                }
                return null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String blueId) {
                if (!budget.tryAcquire(blueId)) {
                    throw new ReferenceExpansionLimitException(blueId);
                }
                NodeProviderResult result = budget.evidence.get(blueId);
                if (result == null) {
                    result = nodeProvider.fetchResultByBlueId(blueId);
                    // Detached ancestry/enum probes share one invocation's
                    // evidence and budget, including defensive result copies.
                    budget.evidence.put(blueId, result);
                }
                budget.providerOutcome = result.outcome();
                if (result.outcome() != NodeProviderOutcome.FOUND) {
                    budget.outstandingBlueIds.add(blueId);
                }
                return result;
            }
        };
    }

    private static <T> BlueOperationResult<T> classifyFailure(
            RuntimeException failure,
            ReferenceBudget budget) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ReferenceExpansionLimitException) {
                return BlueOperationResult.incomplete(null, budget.outstandingBlueIds,
                        null, cause.getMessage());
            }
            if (cause instanceof ProviderUnavailableException) {
                ProviderUnavailableException unavailable =
                        (ProviderUnavailableException) cause;
                unavailable.requiredExactBlueId().ifPresent(
                        budget.outstandingBlueIds::add);
                return BlueOperationResult.incomplete(
                        null,
                        budget.outstandingBlueIds,
                        NodeProviderOutcome.UNAVAILABLE,
                        failure.getMessage());
            }
        }
        if (budget.providerOutcome == NodeProviderOutcome.INVALID_EVIDENCE) {
            return BlueOperationResult.invalid(failure.getMessage(), NodeProviderOutcome.INVALID_EVIDENCE);
        }
        BlueLanguageErrorCategory category =
                BlueLanguageErrorClassifier.classify(failure);
        if (category == BlueLanguageErrorCategory.ProviderUnavailable) {
            return BlueOperationResult.incomplete(
                    null,
                    budget.outstandingBlueIds,
                    budget.providerOutcome,
                    failure.getMessage());
        }
        if (category
                == BlueLanguageErrorCategory.ProviderBlueIdMismatch) {
            return BlueOperationResult.invalid(
                    failure.getMessage(),
                    NodeProviderOutcome.INVALID_EVIDENCE);
        }
        return BlueOperationResult.invalid(
                failure.getMessage(), null);
    }

    /** All candidate preparation and demanded target lookups share this provider. */
    private static final class LimitedMatchingRuntime implements MatchingRuntime {
        private final NodeProvider provider;
        private final Merger merger;
        private final BlueOperationLimits limits;
        private final Function<Node, Node> preprocessor;
        private final BlueCachePolicy cachePolicy;

        private LimitedMatchingRuntime(
                NodeProvider provider,
                MergingProcessor mergingProcessor,
                BlueOperationLimits limits,
                Function<Node, Node> preprocessor,
                BlueCachePolicy cachePolicy) {
            this.provider = provider;
            // Resolved caches must not skip this invocation's reference budget.
            this.merger = new Merger(mergingProcessor, provider, null, REFERENCE_CACHE_ADMISSION);
            this.limits = limits;
            this.preprocessor = preprocessor;
            this.cachePolicy = cachePolicy;
        }

        @Override
        public BlueCachePolicy matchingCachePolicy() {
            return cachePolicy;
        }

        @Override
        public Node preprocessForMatching(Node source) {
            return preprocessor.apply(source);
        }

        @Override
        public void expandForMatching(Node source, ResolutionLimits targetLimits) {
            new NodeExpander(provider).expand(source, candidateLimits(targetLimits));
        }

        @Override
        public TypeEvidenceResolution resolveTypeEvidenceForMatching(
                Node source, ResolutionLimits targetLimits) {
            TypeEvidenceResolution resolution = merger.resolveTypeEvidence(
                    source, candidateLimits(targetLimits));
            if (!hasDemandedPath(resolution.resolvedRoot().toNode(), limits)) {
                throw new DemandedPathsAbsentException();
            }
            return resolution;
        }

        @Override
        public TypeEvidenceResolution materializeTypeReferenceForMatching(FrozenNode reference) {
            TypeEvidenceResolution wrapper = merger.materializeTypeReferenceEvidence(
                    reference, ResolutionLimits.NO_LIMITS);
            FrozenNode materialized = wrapper.resolvedRoot().getType();
            if (materialized == null || materialized.isReferenceOnly()) {
                throw new IllegalStateException("Required matching reference was not materialized: "
                        + reference.getReferenceBlueId());
            }
            return new TypeEvidenceResolution(materialized, wrapper.canonicalTypeIdentities());
        }

        private ResolutionLimits candidateLimits(ResolutionLimits targetLimits) {
            return ResolutionLimits.allOf(targetLimits, new SemanticDemandLimits(limits.demandedSegments()));
        }
    }

    private static final class DemandedPathsAbsentException extends RuntimeException {
        private DemandedPathsAbsentException() {
            super("Demanded paths are absent from the completed resolved value.");
        }
    }

    private static final class ReferenceBudget {
        private final int maximum;
        private final Set<String> requestedBlueIds =
                new LinkedHashSet<>();
        private final Set<String> outstandingBlueIds =
                new LinkedHashSet<>();
        private NodeProviderOutcome providerOutcome;
        private final Map<String, NodeProviderResult> evidence = new LinkedHashMap<>();

        private ReferenceBudget(int maximum) {
            this.maximum = maximum;
        }

        private boolean tryAcquire(String blueId) {
            if (requestedBlueIds.contains(blueId)) {
                return true;
            }
            if (requestedBlueIds.size() >= maximum) {
                outstandingBlueIds.add(blueId);
                return false;
            }
            requestedBlueIds.add(blueId);
            return true;
        }
    }

    private static final class SemanticDemandLimits implements ResolutionLimits {
        private final List<List<String>> demands;
        private final List<String> currentPath = new ArrayList<>();
        private final List<Boolean> enteredSegments = new ArrayList<>();

        private SemanticDemandLimits(List<List<String>> demands) {
            this.demands = demands;
        }

        @Override
        public boolean shouldExpandPathSegment(
                String segment, Node current) {
            return isDemandedClosure(potentialPath(segment));
        }

        @Override
        public boolean shouldMergePathSegment(
                String segment, Node current) {
            return isDemandedClosure(potentialPath(segment));
        }

        @Override
        public boolean retainsEveryAuthoredPath() {
            return false;
        }

        @Override
        public void enterPathSegment(String segment, Node current) {
            boolean entered = segment != null && !segment.isEmpty();
            enteredSegments.add(entered);
            if (entered) {
                currentPath.add(segment);
            }
        }

        @Override
        public void exitPathSegment() {
            if (enteredSegments.isEmpty()) {
                return;
            }
            boolean entered = enteredSegments.remove(
                    enteredSegments.size() - 1);
            if (entered && !currentPath.isEmpty()) {
                currentPath.remove(currentPath.size() - 1);
            }
        }

        private List<String> potentialPath(String segment) {
            List<String> path = new ArrayList<>(currentPath);
            if (segment != null && !segment.isEmpty()) {
                path.add(segment);
            }
            return path;
        }

        private boolean isDemandedClosure(List<String> path) {
            for (List<String> demand : demands) {
                if (isPrefix(path, demand)
                        || isPrefix(demand, path)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isPrefix(
                List<String> prefix,
                List<String> value) {
            if (prefix.size() > value.size()) {
                return false;
            }
            for (int index = 0; index < prefix.size(); index++) {
                if (!Objects.equals(
                        prefix.get(index), value.get(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ReferenceExpansionLimitException
            extends RuntimeException {
        private ReferenceExpansionLimitException(String blueId) {
            super("Reference expansion limit reached for "
                    + blueId + ".");
        }
    }
}
