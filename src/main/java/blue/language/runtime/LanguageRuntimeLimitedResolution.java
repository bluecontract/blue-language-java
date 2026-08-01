package blue.language.runtime;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.provider.NodeProvider;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Demand-closure and evidence accounting for limited resolution. */
final class LanguageRuntimeLimitedResolution {

    private static final ReferenceCacheAdmissionPolicy
            REFERENCE_CACHE_ADMISSION = blueId -> true;

    private LanguageRuntimeLimitedResolution() {
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

        for (String path : limits.demandedPaths()) {
            try {
                if (BlueViewPath.select(resolved, path) != null) {
                    return BlueOperationResult.established(resolved);
                }
            } catch (IllegalArgumentException absent) {
                // Continue until every demanded path has been checked.
            }
        }
        return BlueOperationResult.absent(
                "Demanded paths are absent from the completed resolved value.");
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
                    throw new IllegalStateException(
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
                NodeProviderResult result = nodeProvider
                        .fetchResultByBlueId(blueId);
                budget.providerOutcome = result.outcome();
                if (result.outcome() != NodeProviderOutcome.FOUND) {
                    budget.outstandingBlueIds.add(blueId);
                }
                return result;
            }
        };
    }

    private static BlueOperationResult<Node> classifyFailure(
            RuntimeException failure,
            ReferenceBudget budget) {
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

    private static final class ReferenceBudget {
        private final int maximum;
        private final Set<String> requestedBlueIds =
                new LinkedHashSet<>();
        private final Set<String> outstandingBlueIds =
                new LinkedHashSet<>();
        private NodeProviderOutcome providerOutcome;

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

    private static final class SemanticDemandLimits implements Limits {
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
        public boolean shouldExtendPathSegment(
                String segment, Node current) {
            return shouldExpandPathSegment(segment, current);
        }

        @Override
        public boolean shouldMergePathSegment(
                String segment, Node current) {
            return isDemandedClosure(potentialPath(segment));
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
