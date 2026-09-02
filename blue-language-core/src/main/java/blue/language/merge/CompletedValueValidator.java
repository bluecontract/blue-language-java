package blue.language.merge;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
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

import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPES;

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
    private Map<String, PresenceGate> presenceGates;
    private Set<String> incompletePaths;

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
        if (state == null || state.contribution == ResolutionEngine.Contribution.TYPE_METADATA) {
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

        if (isRootInlineSchemaDeclaration(state, source)) {
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
                || (node.getProperties() != null && !node.getProperties().isEmpty());
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
                    || node.getItems() != null) {
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

    private boolean hasConcretePayload(Node node) {
        if (node == null) {
            return false;
        }
        if (node.getValue() != null || node.getItems() != null) {
            return true;
        }
        return node.getProperties() != null && !node.getProperties().isEmpty();
    }

    boolean isInlineTypeDeclaration(Node node) {
        return node != null
                && node.getType() != null
                && !node.getType().isReferenceOnly()
                && !isBareCoreTypeAlias(node.getType());
    }

    private boolean isBareCoreTypeAlias(Node type) {
        if (type.isInlineValue()
                && type.getValue() instanceof String
                && CORE_TYPES.contains(type.getValue())) {
            return true;
        }
        return type.getName() != null
                && CORE_TYPES.contains(type.getName())
                && type.getDescription() == null
                && type.getType() == null
                && type.getItemType() == null
                && type.getKeyType() == null
                && type.getValueType() == null
                && type.getValue() == null
                && type.getItems() == null
                && (type.getProperties() == null || type.getProperties().isEmpty())
                && type.getContracts() == null
                && type.getSchema() == null
                && type.getMergePolicy() == null
                && type.getPreviousBlueId() == null
                && type.getPosition() == null
                && type.getBlue() == null;
    }

    private boolean isRootInlineSchemaDeclaration(ResolutionEngine.ResolutionState state, Node source) {
        return state.path.isEmpty()
                && state.rootInlineTypeDeclaration
                && !hasConcretePayload(source);
    }

    private ValidationCandidate candidate(ResolutionEngine.ResolutionState state, String path) {
        if (candidates == null) {
            candidates = new LinkedHashMap<>();
        }
        ValidationCandidate candidate = candidates.get(path);
        if (candidate == null) {
            candidate = new ValidationCandidate();
            candidates.put(path, candidate);
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
