package blue.language.conformance;

import blue.language.NodeProvider;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ConformanceEngine {

    private final NodeProvider nodeProvider;
    private final MergingProcessor mergingProcessor;
    private final ResolvedReferenceCache resolvedReferenceCache;

    public ConformanceEngine(NodeProvider nodeProvider, MergingProcessor mergingProcessor) {
        this(nodeProvider, mergingProcessor, null);
    }

    public ConformanceEngine(NodeProvider nodeProvider,
                             MergingProcessor mergingProcessor,
                             ResolvedReferenceCache resolvedReferenceCache) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.mergingProcessor = Objects.requireNonNull(mergingProcessor, "mergingProcessor");
        this.resolvedReferenceCache = resolvedReferenceCache;
    }

    public ConformanceResult check(Node node) {
        if (node == null) {
            return ConformanceResult.conformant();
        }
        try {
            new Merger(mergingProcessor, nodeProvider, resolvedReferenceCache).resolve(node.clone(), Limits.NO_LIMITS);
            return ConformanceResult.conformant();
        } catch (RuntimeException ex) {
            return ConformanceResult.nonConformant(ex.getMessage());
        }
    }

    public boolean conforms(Node node) {
        return check(node).isConformant();
    }

    public void requireConformant(Node node) {
        ConformanceResult result = check(node);
        if (!result.isConformant()) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    public ConformancePlan planGeneralization(FrozenNode resolvedRoot, String changedPath) {
        return planGeneralization(null, resolvedRoot, changedPath);
    }

    public ConformancePlan planGeneralization(FrozenNode canonicalRoot, FrozenNode resolvedRoot, String changedPath) {
        return new FrozenConformancePlanner(nodeProvider, mergingProcessor, resolvedReferenceCache)
                .plan(canonicalRoot, resolvedRoot, changedPath);
    }

    public ConformancePlan planGeneralization(FrozenNode canonicalRoot,
                                              FrozenNode resolvedRoot,
                                              List<String> changedPaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            return ConformancePlan.unchanged(canonicalRoot, resolvedRoot);
        }
        FrozenNode nextCanonical = canonicalRoot;
        FrozenNode nextResolved = resolvedRoot;
        boolean generalized = false;
        List<CanonicalGeneralizationPatch> canonicalPatches = new ArrayList<>();
        List<String> allChangedPaths = new ArrayList<>();
        FrozenConformancePlanner planner = new FrozenConformancePlanner(nodeProvider,
                mergingProcessor,
                resolvedReferenceCache);
        for (String changedPath : changedPaths) {
            ConformancePlan plan = planner.plan(nextCanonical, nextResolved, changedPath);
            nextCanonical = plan.canonicalRoot() != null ? plan.canonicalRoot() : nextCanonical;
            nextResolved = plan.root();
            if (plan.generalized()) {
                generalized = true;
                canonicalPatches.addAll(plan.canonicalPatches());
                allChangedPaths.addAll(plan.changedPaths());
            }
        }
        if (!generalized) {
            return ConformancePlan.unchanged(nextCanonical, nextResolved);
        }
        return ConformancePlan.generalized(nextCanonical,
                nextResolved,
                canonicalPatches,
                allChangedPaths,
                nextCanonical != null);
    }
}
