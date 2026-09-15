package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.Nodes;
import blue.language.model.wire.JsonPointer;
import blue.language.resolve.ResolutionLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks semantic presence and validates only values completed by the current
 * resolution invocation.
 */
final class CompletedValueValidator {

    private final ResolutionEngine engine;
    private final MergingProcessor mergingProcessor;
    private final ReferenceResolver referenceResolver;
    private final List<Boolean> referenceExpansionStack = new ArrayList<>();
    private final List<ContributionFrame> contributionFrames = new ArrayList<>();
    /* Keeps declaration-vs-instance provenance when an intermediate resolved
     * node is subsequently merged into an existing target in this invocation. */
    private final Map<Node, Boolean> semanticPresenceByResolvedNode =
            new IdentityHashMap<>();
    private Map<String, ValidationCandidate> candidates;
    private List<ValidationCandidate> candidateOrder;
    private Map<String, PresenceGate> presenceGates;
    private Set<String> incompletePaths;
    private final List<ValidationCandidate> definitionCandidates = new ArrayList<>();
    private final Map<Node, ValidationCandidate> definitionsByNode = new IdentityHashMap<>();

    CompletedValueValidator(
            ResolutionEngine engine,
            MergingProcessor mergingProcessor,
            ReferenceResolver referenceResolver) {
        this.engine = engine;
        this.mergingProcessor = mergingProcessor;
        this.referenceResolver = referenceResolver;
    }

    ContributionFrame beginContribution(
            ResolutionEngine.ResolutionState state,
            Node target,
            Node source,
            String path) {
        if (!tracksSemanticPresence(state, target, source, path)) {
            return null;
        }
        ContributionFrame frame = new ContributionFrame(
                target,
                path,
                state.path.size(),
                isDirectSemanticContribution(source, state.contribution),
                isInheritedReferenceContribution(
                        target, source, state.contribution),
                state.contribution != ResolutionEngine.Contribution.CONTRACT_ROOT);
        contributionFrames.add(frame);
        return frame;
    }

    void completeContribution(
            ResolutionEngine.ResolutionState state,
            ContributionFrame frame) {
        if (frame == null) {
            return;
        }
        contributionFrames.remove(contributionFrames.size() - 1);
        boolean semanticContribution = frame.semanticContribution
                || frame.inheritedSemanticContribution;
        Boolean previousPresence = semanticPresenceByResolvedNode.get(
                frame.target);
        if (previousPresence == null || semanticContribution) {
            semanticPresenceByResolvedNode.put(
                    frame.target,
                    semanticContribution);
        }
        if (semanticContribution) {
            presenceGate(state, frame.path).present = true;
        }
        if (semanticContribution && frame.propagatesToParent
                && !contributionFrames.isEmpty()) {
            contributionFrames.get(
                    contributionFrames.size() - 1).semanticContribution = true;
        }
    }

    private boolean tracksSemanticPresence(
            ResolutionEngine.ResolutionState state,
            Node target,
            Node source,
            String path) {
        return state.contribution == ResolutionEngine.Contribution.TYPE_ROOT
                || state.contribution == ResolutionEngine.Contribution.TYPE_DECLARATION
                || target.getSchema() != null
                || source.getSchema() != null
                || !contributionFrames.isEmpty()
                || (presenceGates != null && presenceGates.containsKey(path));
    }

    void observeCompletedPath(Node target, Node source, ResolutionLimits limits) {
        ResolutionEngine.ResolutionState state = engine.activeResolutionState();
        if (state == null) {
            return;
        }

        if (state.definitionGoal || state.contribution == ResolutionEngine.Contribution.TYPE_METADATA) {
            boolean needsContent = source.isReferenceOnly()
                    && referenceResolver.requiresReferenceContent(target);
            if (mergingProcessor.hasCompletedValidation(target) || needsContent) {
                ValidationCandidate candidate = definitionsByNode.get(target);
                if (candidate == null) {
                    candidate = new ValidationCandidate();
                    candidate.node = target;
                    candidate.definitionPath = currentPath(state);
                    definitionsByNode.put(target, candidate);
                    definitionCandidates.add(candidate);
                }
                if (needsContent) {
                    candidate.pendingReferenceBlueId = source.getBlueId();
                    candidate.pendingReferenceLimits = limits;
                    candidate.complete = state.referenceExpansionAllowed;
                }
            }
            return;
        }

        boolean hasValidation = target.getSchema() != null
                && mergingProcessor.hasCompletedValidation(target);
        if (!hasValidation && source.getBlueId() == null) {
            return;
        }
        boolean pureReference = source.isReferenceOnly();
        boolean needsReferenceContent = pureReference
                && referenceResolver.requiresReferenceContent(target);
        boolean referenceExpansionAllowed = state.referenceExpansionAllowed;
        if (!hasValidation) {
            if (needsReferenceContent && referenceExpansionAllowed
                    && state.contribution != ResolutionEngine.Contribution.TYPE_DECLARATION) {
                referenceResolver.materializeReferenceAtCurrentPath(
                        target, source.getBlueId(), limits, state);
            }
            return;
        }

        String path = currentPath(state);
        ValidationCandidate candidate = candidate(state, path);
        candidate.node = target;
        candidate.presence = presenceGate(state, path);
        bindAncestorPresenceGates(state, candidate);
        candidate.observed = true;
        if (needsReferenceContent) {
            if (!referenceExpansionAllowed) {
                candidate.complete = false;
            } else if (state.contribution == ResolutionEngine.Contribution.TYPE_DECLARATION) {
                candidate.pendingReferenceBlueId = source.getBlueId();
                candidate.pendingReferenceLimits = limits;
            } else {
                referenceResolver.materializeReferenceAtCurrentPath(
                        target, source.getBlueId(), limits, state);
                candidate.pendingReferenceBlueId = null;
                candidate.pendingReferenceLimits = null;
            }
        }
        if (state.path.isEmpty()) {
            candidate.presence.present = true;
        }
        ContributionFrame frame = contributionFrames.get(contributionFrames.size() - 1);
        if (frame.semanticContribution || frame.inheritedSemanticContribution) {
            candidate.presence.present = true;
        }
        if (isIncomplete(state, path)) {
            candidate.complete = false;
        }
    }


    private boolean isDirectSemanticContribution(Node node, ResolutionEngine.Contribution contribution) {
        if (node == null
                || contribution == ResolutionEngine.Contribution.TYPE_METADATA) {
            return false;
        }
        Boolean resolvedPresence = semanticPresenceByResolvedNode.get(node);
        if (resolvedPresence != null) {
            return resolvedPresence;
        }
        if (contribution == ResolutionEngine.Contribution.TYPE_ROOT) {
            return node.getValue() != null || node.getItems() != null;
        }
        if (contribution == ResolutionEngine.Contribution.TYPE_DECLARATION) {
            return hasDeclaredInstancePayload(
                    node,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        return node.isReferenceOnly()
                || node.getValue() != null
                || node.getItems() != null
                || Nodes.hasObjectPayload(node);
    }

    /**
     * Distinguishes a fixed value inherited from a type from the completed
     * declaration graph used to describe that value.
     *
     * <p>Completed types may be traversed more than once (for example after
     * reference-cache admission). Their expanded {@code type}, {@code schema}
     * and {@code contracts} subtrees are declarations and cannot by
     * themselves make an optional instance path present. Fixed scalars,
     * lists, exact references, and ordinary object subtrees containing such
     * payload are instance content and do make it present. Declaration-only
     * children containing reserved metadata do not.</p>
     */
    private boolean hasDeclaredInstancePayload(
            Node node,
            Set<Node> visiting) {
        if (node == null || !visiting.add(node)) {
            return false;
        }
        try {
            if (node.isReferenceOnly()
                    || node.getValue() != null
                    || node.getItems() != null
                    || (node.getProperties() != null && node.getProperties().isEmpty())) {
                return true;
            }
            if (node.getProperties() != null) {
                for (Node child : node.getProperties().values()) {
                    if (hasDeclaredInstancePayload(
                            child, visiting)) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            visiting.remove(node);
        }
    }

    private boolean isInheritedReferenceContribution(
            Node target,
            Node source,
            ResolutionEngine.Contribution contribution) {
        /*
         * A reference used to obtain a type is declaration evidence, not an
         * authored reference value at the instance path. The declaration's
         * own fixed payload, if any, is accounted for separately above.
         */
        if (contribution == ResolutionEngine.Contribution.TYPE_ROOT
                || contribution == ResolutionEngine.Contribution.TYPE_DECLARATION
                || contribution == ResolutionEngine.Contribution.TYPE_METADATA) {
            return false;
        }
        if (!target.isReferenceOnly()) {
            return false;
        }
        Node sourceType = source.getType();
        if (sourceType == null) {
            return true;
        }
        return !engine.canonicalTypeIdentities()
                .findCanonicalTypeBlueId(sourceType)
                .map(target.getBlueId()::equals)
                .orElse(false);
    }

    private ValidationCandidate candidate(ResolutionEngine.ResolutionState state, String path) {
        if (candidates == null) {
            candidates = new LinkedHashMap<>();
        }
        ValidationCandidate candidate = candidates.get(path);
        if (candidate == null) {
            candidate = new ValidationCandidate();
            candidate.path = path;
            candidates.put(path, candidate);
            if (candidateOrder == null) {
                candidateOrder = new ArrayList<>();
            }
            candidateOrder.add(candidate);
        }
        return candidate;
    }

    private PresenceGate presenceGate(ResolutionEngine.ResolutionState state, String path) {
        if (presenceGates == null) {
            presenceGates = new LinkedHashMap<>();
        }
        PresenceGate gate = presenceGates.get(path);
        if (gate == null) {
            gate = new PresenceGate();
            presenceGates.put(path, gate);
        }
        return gate;
    }

    private void bindAncestorPresenceGates(ResolutionEngine.ResolutionState state, ValidationCandidate candidate) {
        int candidateDepth = state.path.size();
        for (ContributionFrame frame : contributionFrames) {
            if (frame.pathDepth == 0 || frame.pathDepth >= candidateDepth) {
                continue;
            }
            PresenceGate gate = presenceGate(state, frame.path);
            if (frame.semanticContribution || frame.inheritedSemanticContribution) {
                gate.present = true;
            }
            if (!candidate.ancestorPresence.contains(gate)) {
                candidate.ancestorPresence.add(gate);
            }
        }
    }

    private boolean ancestorsPresent(ValidationCandidate candidate) {
        for (PresenceGate gate : candidate.ancestorPresence) {
            if (!gate.present) {
                return false;
            }
        }
        return true;
    }

    void validateCompletedCandidates(ResolutionEngine.ResolutionState state) {
        for (int index = 0; index < definitionCandidates.size(); index++) {
            ValidationCandidate definition = definitionCandidates.get(index);
            if (definition.complete && !isIncomplete(state, definition.definitionPath)) {
                if (definition.pendingReferenceBlueId != null) {
                    enterPath(state, definition.definitionPath);
                    int segments = enterLimitPath(definition.pendingReferenceLimits,
                            definition.definitionPath, definition.node);
                    boolean previousGoal = state.definitionGoal;
                    state.definitionGoal = true;
                    try {
                        referenceResolver.materializeReferenceAtCurrentPath(definition.node,
                                definition.pendingReferenceBlueId, definition.pendingReferenceLimits, state);
                    } finally {
                        state.definitionGoal = previousGoal;
                        exitLimitPath(definition.pendingReferenceLimits, segments);
                        state.path.clear();
                    }
                }
                mergingProcessor.validateDefinition(definition.node,
                        hasDeclaredInstancePayload(definition.node,
                                Collections.newSetFromMap(new IdentityHashMap<>())),
                        definition.definitionPath, engine.canonicalTypeIdentities());
            }
        }
        if (candidates == null) {
            return;
        }
        List<Map.Entry<String, ValidationCandidate>> pendingCandidates =
                new ArrayList<>(candidates.entrySet());
        for (int index = 0; index < pendingCandidates.size(); index++) {
            Map.Entry<String, ValidationCandidate> entry =
                    pendingCandidates.get(index);
            ValidationCandidate candidate = entry.getValue();
            if (!candidate.complete) {
                // Limited resolution deliberately returns a partial view. Skipped candidates
                // are never certified as completed values and must not be semantically hashed.
                continue;
            }
            if (!ancestorsPresent(candidate)) {
                continue;
            }
            if (candidate.pendingReferenceBlueId != null) {
                enterPath(state, entry.getKey());
                int enteredLimitSegments = enterLimitPath(candidate.pendingReferenceLimits,
                        entry.getKey(), candidate.node);
                try {
                    referenceResolver.materializeReferenceAtCurrentPath(candidate.node,
                            candidate.pendingReferenceBlueId,
                            candidate.pendingReferenceLimits,
                            state);
                } finally {
                    exitLimitPath(candidate.pendingReferenceLimits, enteredLimitSegments);
                    state.path.clear();
                }
                candidate.pendingReferenceBlueId = null;
                candidate.pendingReferenceLimits = null;
                if (candidates.size() > pendingCandidates.size()) {
                    pendingCandidates = new ArrayList<>(candidates.entrySet());
                }
            }
            mergingProcessor.validateCompleted(candidate.node,
                    candidate.presence.present,
                    entry.getKey(),
                    engine.canonicalTypeIdentities());
        }
    }

    /**
     * Marks the current end of the candidate lists so a later flush inspects
     * only candidates observed after this point.
     */
    int[] candidateMark() {
        return new int[] {
                definitionCandidates.size(),
                candidateOrder == null ? 0 : candidateOrder.size()};
    }

    /**
     * Materializes pending candidate references observed since {@code mark}
     * that live inside {@code subtreeRoot}, before that subtree's canonical
     * identity is recorded or its content is copied elsewhere.
     *
     * <p>Resolution defers value-reference materialization inside type
     * declarations to the end of the invocation. An authored inline type,
     * including the root document of a canonicalization run, records its
     * canonical identity and detaches a copy of its resolved body earlier
     * than that, and materialized reference content is cloned into its
     * enclosing value; references whose context requires content must be
     * complete first. Only complete candidates are materialized; validation
     * still happens in {@link #validateCompletedCandidates}. The work is
     * linear in the candidates observed since the mark and in the nodes
     * they add.</p>
     */
    void materializePendingDefinitionReferences(
            ResolutionEngine.ResolutionState state,
            Node subtreeRoot,
            int[] mark) {
        int definitionStart = mark[0];
        int candidateStart = mark[1];
        if (definitionCandidates.size() <= definitionStart
                && (candidateOrder == null
                || candidateOrder.size() <= candidateStart)) {
            return;
        }
        List<String> savedPath = new ArrayList<>(state.path);
        Set<Node> subtree = subtreeNodes(subtreeRoot);
        try {
            boolean progressed = true;
            while (progressed) {
                progressed = false;
                for (int index = definitionStart; index < definitionCandidates.size(); index++) {
                    ValidationCandidate definition = definitionCandidates.get(index);
                    if (definition.pendingReferenceBlueId != null
                            && definition.complete
                            && !isIncomplete(state, definition.definitionPath)
                            && materializeWithin(state, savedPath, subtree, subtreeRoot,
                                    definition.definitionPath, definition, true)) {
                        progressed = true;
                    }
                }
                for (int index = candidateStart;
                     candidateOrder != null && index < candidateOrder.size(); index++) {
                    ValidationCandidate candidate = candidateOrder.get(index);
                    if (candidate.pendingReferenceBlueId != null
                            && candidate.complete
                            && ancestorsPresent(candidate)
                            && materializeWithin(state, savedPath, subtree, subtreeRoot,
                                    candidate.path, candidate, false)) {
                        progressed = true;
                    }
                }
            }
        } finally {
            state.path.clear();
            state.path.addAll(savedPath);
        }
    }

    /**
     * Materializes one pending candidate when its target lives inside the
     * subtree. A candidate observed before its enclosing value was merged
     * into a fresh target may point at an orphaned node; the live node at
     * the same path is then the target, provided it still awaits that
     * reference.
     */
    private boolean materializeWithin(
            ResolutionEngine.ResolutionState state,
            List<String> basePath,
            Set<Node> subtree,
            Node subtreeRoot,
            String pointer,
            ValidationCandidate candidate,
            boolean definitionGoal) {
        List<String> relative = relativeSegments(basePath, pointer);
        if (relative == null) {
            return false;
        }
        if (!subtree.contains(candidate.node)) {
            Node live = NodePathEditor.getOrNull(
                    subtreeRoot, JsonPointer.toPointer(relative));
            if (live == null
                    || !subtree.contains(live)
                    || !candidate.pendingReferenceBlueId.equals(live.getBlueId())
                    || state.materializedReferenceTargets.contains(live)) {
                return false;
            }
            candidate.node = live;
        }
        materializePendingReference(state, relative, pointer, candidate, definitionGoal);
        addSubtreeNodes(subtree, candidate.node);
        return true;
    }

    private void materializePendingReference(
            ResolutionEngine.ResolutionState state,
            List<String> relative,
            String pointer,
            ValidationCandidate candidate,
            boolean definitionGoal) {
        enterPath(state, pointer);
        int segments = enterLimitPath(candidate.pendingReferenceLimits,
                JsonPointer.toPointer(relative), candidate.node);
        boolean previousGoal = state.definitionGoal;
        state.definitionGoal = definitionGoal || previousGoal;
        try {
            referenceResolver.materializeReferenceAtCurrentPath(candidate.node,
                    candidate.pendingReferenceBlueId,
                    candidate.pendingReferenceLimits, state);
        } finally {
            state.definitionGoal = previousGoal;
            exitLimitPath(candidate.pendingReferenceLimits, segments);
        }
        candidate.pendingReferenceBlueId = null;
        candidate.pendingReferenceLimits = null;
    }

    private static List<String> relativeSegments(List<String> base, String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        if (segments.size() < base.size()
                || !segments.subList(0, base.size()).equals(base)) {
            return null;
        }
        return segments.subList(base.size(), segments.size());
    }

    private static Set<Node> subtreeNodes(Node root) {
        Set<Node> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        addSubtreeNodes(nodes, root);
        return nodes;
    }

    /**
     * Adds every node reachable from {@code root}. The root may already be a
     * member whose children were attached afterwards by a materialization,
     * so membership of the root never stops the walk.
     */
    private static void addSubtreeNodes(Set<Node> nodes, Node root) {
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Node> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node current = pending.remove(pending.size() - 1);
            if (current == null || !visited.add(current)) {
                continue;
            }
            nodes.add(current);
            if (current.getProperties() != null) {
                pending.addAll(current.getProperties().values());
            }
            if (current.getItems() != null) {
                pending.addAll(current.getItems());
            }
            if (current.getContracts() != null) {
                pending.add(current.getContracts());
            }
        }
    }

    private void enterPath(ResolutionEngine.ResolutionState state, String pointer) {
        state.path.clear();
        state.path.addAll(JsonPointer.split(pointer));
    }

    private int enterLimitPath(ResolutionLimits limits, String pointer, Node node) {
        List<String> segments = JsonPointer.split(pointer);
        for (int index = 0; index < segments.size(); index++) {
            Node current = index == segments.size() - 1 ? node : null;
            limits.enterPathSegment(segments.get(index), current);
        }
        return segments.size();
    }

    private void exitLimitPath(ResolutionLimits limits, int enteredSegments) {
        for (int index = 0; index < enteredSegments; index++) {
            limits.exitPathSegment();
        }
    }

    void enterValidationPath(String segment) {
        enterValidationPath(segment, true);
    }

    void enterValidationPath(String segment, boolean referenceExpansionAllowed) {
        ResolutionEngine.ResolutionState state = engine.activeResolutionState();
        if (state != null) {
            state.path.add(segment);
            referenceExpansionStack.add(state.referenceExpansionAllowed);
            state.referenceExpansionAllowed = state.referenceExpansionAllowed && referenceExpansionAllowed;
        }
    }

    void exitValidationPath() {
        ResolutionEngine.ResolutionState state = engine.activeResolutionState();
        if (state != null && !state.path.isEmpty()) {
            state.path.remove(state.path.size() - 1);
            state.referenceExpansionAllowed = referenceExpansionStack
                    .remove(referenceExpansionStack.size() - 1);
        }
    }

    void markIncomplete(String segment) {
        ResolutionEngine.ResolutionState state = engine.activeResolutionState();
        if (state == null) {
            return;
        }
        List<String> path = new ArrayList<>(state.path);
        path.add(segment);
        String prefix = JsonPointer.toPointer(path);
        if (incompletePaths == null) {
            incompletePaths = new HashSet<>();
        }
        incompletePaths.add(prefix);
        if (candidates != null) {
            candidates.forEach((candidatePath, candidate) -> {
                if (candidatePath.equals(prefix)
                        || candidatePath.startsWith(prefix + "/")
                        || prefix.startsWith(candidatePath + "/")) {
                    candidate.complete = false;
                }
            });
        }
    }

    private boolean isIncomplete(ResolutionEngine.ResolutionState state, String path) {
        if (incompletePaths == null) {
            return false;
        }
        for (String incomplete : incompletePaths) {
            if (path.equals(incomplete)
                    || path.startsWith(incomplete + "/")
                    || incomplete.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }

    String currentPath(ResolutionEngine.ResolutionState state) {
        return JsonPointer.toPointer(state.path);
    }


    static final class ContributionFrame {
        private final Node target;
        private final String path;
        private final int pathDepth;
        private boolean semanticContribution;
        private final boolean inheritedSemanticContribution;
        private final boolean propagatesToParent;

        private ContributionFrame(
                Node target,
                String path,
                int pathDepth,
                boolean semanticContribution,
                boolean inheritedSemanticContribution,
                boolean propagatesToParent) {
            this.target = target;
            this.path = path;
            this.pathDepth = pathDepth;
            this.semanticContribution = semanticContribution;
            this.inheritedSemanticContribution = inheritedSemanticContribution;
            this.propagatesToParent = propagatesToParent;
        }
    }

    private static final class ValidationCandidate {
        private String path;
        private String definitionPath;
        private Node node;
        private boolean observed;
        private PresenceGate presence;
        private final List<PresenceGate> ancestorPresence = new ArrayList<>();
        private boolean complete = true;
        private String pendingReferenceBlueId;
        private ResolutionLimits pendingReferenceLimits;
    }

    private static final class PresenceGate {
        private boolean present;
    }
}
