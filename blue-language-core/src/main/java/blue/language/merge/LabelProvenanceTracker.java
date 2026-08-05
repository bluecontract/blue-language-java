package blue.language.merge;

import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.resolve.ResolutionLimits;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_IDS;
import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPES;
import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_REPLACE;

/**
 * Tracks whether authored labels refine declarations or conflict with fixed
 * values across type, object, contract, and list overlays.
 */
final class LabelProvenanceTracker {

    private final ResolutionEngine engine;
    private final NodeProvider nodeProvider;
    private final ListOverlayMerger listOverlayMerger;
    private final List<LabelProvenanceScope> scopes = new ArrayList<>();

    LabelProvenanceTracker(
            ResolutionEngine engine,
            NodeProvider nodeProvider,
            ListOverlayMerger listOverlayMerger) {
        this.engine = engine;
        this.nodeProvider = nodeProvider;
        this.listOverlayMerger = listOverlayMerger;
    }

    private ResolutionEngine.ResolutionState activeResolutionState() {
        return engine.activeResolutionState();
    }

    private String currentPath(ResolutionEngine.ResolutionState state) {
        return engine.currentPath(state);
    }

    private LabelPath currentLabelPath(ResolutionEngine.ResolutionState state) {
        return new LabelPath(state.path);
    }

    LabelPath currentLabelPath() {
        return currentLabelPath(activeResolutionState());
    }

    MergeMode mergeMode(ResolutionEngine.Contribution contribution) {
        if (contribution == ResolutionEngine.Contribution.MATERIALIZED_REFERENCE) {
            return MergeMode.REFERENCE_EXPANSION;
        }
        if (contribution == ResolutionEngine.Contribution.TYPE_ROOT) {
            return MergeMode.NONE;
        }
        if (contribution == ResolutionEngine.Contribution.TYPE_METADATA) {
            /*
             * TYPE_METADATA must remain the semantic contribution throughout
             * metadata children: processor presence and completed-schema
             * validation depend on that boundary. Labels authored below the
             * metadata root are nevertheless declaration overlays and may
             * refine labels inherited from the metadata type hierarchy.
             */
            LabelProvenanceScope scope = currentLabelProvenanceScope();
            return scope != null
                    && !currentLabelPath(activeResolutionState()).equals(scope.rootPath)
                    ? MergeMode.AUTHORED_OVERLAY
                    : MergeMode.NONE;
        }
        return MergeMode.AUTHORED_OVERLAY;
    }

    /**
     * A declaration-only child inherits labels until an instance explicitly
     * overrides them. Fixed payload labels remain governed by fixed-value rules.
     */
    boolean isDeclarationOnlyForLabels(Node node) {
        ResolutionEngine.ResolutionState state = activeResolutionState();
        if (state != null) {
            LabelPath path = currentLabelPath(state);
            for (int index = scopes.size() - 1; index >= 0; index--) {
                LabelProvenanceScope scope = scopes.get(index);
                if (scope.fixedPaths.contains(path)) {
                    return false;
                }
                if (scope.declarationOnlyPaths.contains(path)) {
                    return true;
                }
            }
        }
        return !sourceContainsFixedContent(node);
    }

    void recordTypeDeclarationLabelPaths(Node typeNode,
                                                 LabelPath basePath,
                                                 Set<LabelPath> relevantLabelPaths) {
        LabelProvenanceScope scope = currentLabelProvenanceScope();
        if (scope == null || !hasLabelPathAtOrBelow(relevantLabelPaths, basePath)) {
            return;
        }
        LabelScanState scan = new LabelScanState(scope, relevantLabelPaths);
        Deque<LabelScanTask> pending = new ArrayDeque<>();
        pending.push(LabelScanTask.type(typeNode, basePath));
        while (!pending.isEmpty()) {
            LabelScanTask task = pending.pop();
            switch (task.kind) {
                case TYPE:
                    scanTypeLabelTask(task, scan, pending);
                    break;
                case SOURCE:
                    scanSourceLabelTask(task.node, task.path, scan, pending);
                    break;
                case CHILDREN:
                    scanDirectChildLabelTasks(task.node, task.path, scan, pending);
                    break;
                case EXIT_TYPE:
                    scan.exitType(task.typeBlueId, task.node);
                    break;
                default:
                    throw new IllegalStateException("Unknown label scan task: " + task.kind);
            }
        }
    }

    private void scanTypeLabelTask(LabelScanTask task,
                                   LabelScanState scan,
                                   Deque<LabelScanTask> pending) {
        Node typeNode = task.node;
        if (typeNode == null || isBareCoreTypeAlias(typeNode)
                || !hasLabelPathAtOrBelow(scan.relevantLabelPaths, task.path)) {
            return;
        }
        String typeBlueId = typeNode.getBlueId();
        if (typeBlueId != null && CORE_TYPE_BLUE_IDS.contains(typeBlueId)) {
            return;
        }
        if (!scan.enterType(typeBlueId, typeNode)) {
            return;
        }
        Node canonicalType;
        try {
            canonicalType = canonicalTypeForLabelProvenance(typeNode);
        } catch (RuntimeException failure) {
            scan.exitType(typeBlueId, typeNode);
            throw failure;
        }
        if (canonicalType == null) {
            scan.exitType(typeBlueId, typeNode);
            return;
        }
        pending.push(LabelScanTask.exitType(typeBlueId, typeNode));
        pending.push(LabelScanTask.children(canonicalType, task.path));
        pending.push(LabelScanTask.type(canonicalType.getType(), task.path));
    }

    private Node canonicalTypeForLabelProvenance(Node typeNode) {
        return engine.canonicalTypeForLabelProvenance(typeNode);
    }

    private void scanSourceLabelTask(Node source,
                                     LabelPath path,
                                     LabelScanState scan,
                                     Deque<LabelScanTask> pending) {
        if (source == null || !hasLabelPathAtOrBelow(scan.relevantLabelPaths, path)) {
            return;
        }
        if (scan.relevantLabelPaths.contains(path)) {
            setDeclarationOnlyLabelPath(
                    scan.scope, path,
                    !sourceContainsFixedContent(source));
        }
        pending.push(LabelScanTask.children(source, path));
        pending.push(LabelScanTask.type(source.getType(), path));
    }

    private void scanDirectChildLabelTasks(Node source,
                                           LabelPath basePath,
                                           LabelScanState scan,
                                           Deque<LabelScanTask> pending) {
        if (source == null || !hasLabelPathAtOrBelow(scan.relevantLabelPaths, basePath)) {
            return;
        }
        List<Map.Entry<String, Node>> properties = source.getProperties() == null
                ? Collections.<Map.Entry<String, Node>>emptyList()
                : new ArrayList<>(source.getProperties().entrySet());
        for (int index = properties.size() - 1; index >= 0; index--) {
            Map.Entry<String, Node> property = properties.get(index);
            LabelPath childPath = basePath.child(property.getKey());
            if (hasLabelPathAtOrBelow(scan.relevantLabelPaths, childPath)) {
                pending.push(LabelScanTask.source(property.getValue(), childPath));
            }
        }
        scanDirectListChildLabelTasks(source, basePath, scan, pending);
        LabelPath contractsPath = basePath.child(BlueLanguageConstants.OBJECT_CONTRACTS);
        if (source.getContracts() != null
                && hasLabelPathAtOrBelow(scan.relevantLabelPaths, contractsPath)) {
            pending.push(LabelScanTask.source(source.getContracts(), contractsPath));
        }
    }

    private void scanDirectListChildLabelTasks(Node source,
                                               LabelPath basePath,
                                               LabelScanState scan,
                                               Deque<LabelScanTask> pending) {
        List<Node> children = source.getItems();
        Node effectiveItemType = source.getItemType() != null
                ? source.getItemType()
                : scan.effectiveItemTypes.get(basePath);
        if (source.getItemType() != null) {
            scan.effectiveItemTypes.put(basePath, source.getItemType());
        }
        if (children == null || !hasLabelPathAtOrBelow(scan.relevantLabelPaths, basePath)) {
            return;
        }

        int size = scan.listSizes.getOrDefault(basePath, 0);
        Map<Integer, Node> effectiveItems = scan.effectiveListItems.computeIfAbsent(
                basePath, ignored -> new HashMap<>());
        int start = startsWithPrevious(children) ? 1 : 0;
        List<PositionedLabelSource> effectiveChildren = new ArrayList<>();
        if (start > 0 && size == 0) {
            List<Node> previousChildren = previousLabelChildren(children.get(0));
            for (int index = 0; index < previousChildren.size(); index++) {
                Node effectiveChild = applyItemType(previousChildren.get(index), effectiveItemType);
                effectiveChildren.add(new PositionedLabelSource(index, effectiveChild));
                effectiveItems.put(index, effectiveChild);
            }
            size = previousChildren.size();
        }

        boolean hasPositionControls = children.stream()
                .anyMatch(child -> child.getPosition() != null);
        for (int index = start; index < children.size(); index++) {
            Node child = children.get(index);
            int position;
            Node effectiveChild;
            boolean replacement = false;
            if (child.getPosition() != null) {
                position = child.getPosition();
                Node overlay = withoutPosition(child);
                Node previousItem = effectiveItems.get(position);
                Node positionItemType = previousItem != null && previousItem.getType() != null
                        ? previousItem.getType()
                        : effectiveItemType;
                if (hasReplacement(overlay)) {
                    replacement = true;
                    overlay = overlay.getProperties().get(LIST_CONTROL_REPLACE);
                }
                replacement = replacement
                        || (previousItem != null && isEmptyPlaceholder(previousItem))
                        || overlay.getValue() != null
                        || overlay.getItems() != null;
                effectiveChild = applyItemType(overlay, positionItemType);
                if (position == size) {
                    size++;
                }
            } else if (hasPositionControls || start > 0) {
                position = size++;
                effectiveChild = applyItemType(child, effectiveItemType);
            } else {
                position = index - start;
                Node previousItem = effectiveItems.get(position);
                Node positionItemType = previousItem != null && previousItem.getType() != null
                        ? previousItem.getType()
                        : effectiveItemType;
                effectiveChild = applyItemType(child, positionItemType);
                size = Math.max(size, position + 1);
            }
            Node previousItem = effectiveItems.get(position);
            effectiveItems.put(position, replacement || previousItem == null
                    ? effectiveChild
                    : effectiveListItemAfterOverlay(previousItem, effectiveChild));
            effectiveChildren.add(new PositionedLabelSource(
                    position, effectiveChild, replacement));
        }
        scan.listSizes.put(basePath, size);

        for (int index = effectiveChildren.size() - 1; index >= 0; index--) {
            PositionedLabelSource child = effectiveChildren.get(index);
            LabelPath childPath = basePath.child(String.valueOf(child.position));
            if (hasLabelPathAtOrBelow(scan.relevantLabelPaths, childPath)) {
                if (child.replacement) {
                    clearLabelClassificationAtOrBelow(scan.scope, childPath);
                }
                pending.push(LabelScanTask.source(child.node, childPath));
            }
        }
    }

    private List<Node> previousLabelChildren(Node previousAnchor) {
        List<Node> fetched = nodeProvider.fetchByBlueId(previousAnchor.getPreviousBlueId());
        if (fetched == null || fetched.isEmpty()) {
            throw new IllegalArgumentException(
                    "No content found for $previous blueId: " + previousAnchor.getPreviousBlueId());
        }
        return fetched.size() == 1 && fetched.get(0).getItems() != null
                ? fetched.get(0).getItems()
                : fetched;
    }

    private Node effectiveListItemAfterOverlay(Node inherited, Node overlay) {
        if (overlay.getType() != null || overlay.getBlueId() != null) {
            return overlay;
        }
        if (inherited.getType() != null) {
            return overlay.clone().type(itemTypeReference(inherited.getType()));
        }
        return overlay;
    }

    private boolean sourceContainsFixedContent(Node source) {
        return sourceContainsFixedContent(source, false);
    }

    private boolean sourceContainsFixedContent(Node source, boolean typeRoot) {
        Deque<FixedContentTask> pending = new ArrayDeque<>();
        Set<Node> visitedNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Node> visitedTypeRoots = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> visitedTypeBlueIds = new HashSet<>();
        Set<Node> visitedInlineTypes = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        pending.push(new FixedContentTask(source, typeRoot));
        while (!pending.isEmpty()) {
            FixedContentTask task = pending.pop();
            Node current = task.node;
            Set<Node> visited = task.typeRoot ? visitedTypeRoots : visitedNodes;
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (current.getRawValue() != null
                    || current.isInlineValue()
                    || current.getItems() != null
                    || (!task.typeRoot && current.getBlueId() != null)
                    || current.getPreviousBlueId() != null
                    || current.getPosition() != null) {
                return true;
            }
            enqueueTypeForFixedContent(
                    current.getType(), pending, visitedTypeBlueIds, visitedInlineTypes);
            if (current.getContracts() != null) {
                pending.push(new FixedContentTask(current.getContracts(), false));
            }
            if (current.getProperties() != null) {
                for (Node child : current.getProperties().values()) {
                    if (child != null) {
                        pending.push(new FixedContentTask(child, false));
                    }
                }
            }
        }
        return false;
    }

    private void enqueueTypeForFixedContent(Node typeNode,
                                            Deque<FixedContentTask> pending,
                                            Set<String> visitedTypeBlueIds,
                                            Set<Node> visitedInlineTypes) {
        if (typeNode == null || isBareCoreTypeAlias(typeNode)) {
            return;
        }
        String typeBlueId = typeNode.getBlueId();
        if (typeBlueId != null) {
            if (CORE_TYPE_BLUE_IDS.contains(typeBlueId)
                    || !visitedTypeBlueIds.add(typeBlueId)) {
                return;
            }
        } else if (!visitedInlineTypes.add(typeNode)) {
            return;
        }
        Node canonicalType = canonicalTypeForLabelProvenance(typeNode);
        if (canonicalType != null) {
            pending.push(new FixedContentTask(canonicalType, true));
        }
    }

    private void setDeclarationOnlyLabelPath(LabelProvenanceScope scope,
                                             LabelPath path,
                                             boolean declarationOnly) {
        if (scope == null || !scope.labelPaths.contains(path)) {
            return;
        }
        if (declarationOnly) {
            if (!scope.fixedPaths.contains(path)) {
                scope.declarationOnlyPaths.add(path);
            }
        } else {
            scope.declarationOnlyPaths.remove(path);
            scope.fixedPaths.add(path);
        }
    }

    private void clearLabelClassificationAtOrBelow(LabelProvenanceScope scope,
                                                    LabelPath path) {
        scope.declarationOnlyPaths.removeIf(candidate -> candidate.isAtOrBelow(path));
        scope.fixedPaths.removeIf(candidate -> candidate.isAtOrBelow(path));
    }

    LabelProvenanceScope pushLabelProvenanceScope(Node source,
                                                          ResolutionLimits limits,
                                                          boolean includeRootLabel) {
        ResolutionEngine.ResolutionState state = activeResolutionState();
        if (state == null) {
            return null;
        }
        Set<LabelPath> labelPaths = new HashSet<>();
        collectAuthoredLabelPaths(
                source, currentLabelPath(state), limits, includeRootLabel, labelPaths,
                Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
        LabelProvenanceScope scope = new LabelProvenanceScope(
                currentLabelPath(state), labelPaths);
        scopes.add(scope);
        return scope;
    }

    void popLabelProvenanceScope(LabelProvenanceScope expected) {
        if (expected == null || activeResolutionState() == null) {
            return;
        }
        if (scopes.isEmpty() || scopes.remove(scopes.size() - 1) != expected) {
            throw new IllegalStateException("Label provenance scope stack is unbalanced.");
        }
    }

    LabelProvenanceScope currentLabelProvenanceScope() {
        if (activeResolutionState() == null || scopes.isEmpty()) {
            return null;
        }
        return scopes.get(scopes.size() - 1);
    }

    private void collectAuthoredLabelPaths(Node source,
                                           LabelPath path,
                                           ResolutionLimits limits,
                                           boolean includeRootLabel,
                                           Set<LabelPath> labelPaths,
                                           Set<Node> activeNodes) {
        if (source == null || !activeNodes.add(source)) {
            return;
        }
        try {
            if ((includeRootLabel || !path.isRoot())
                    && (source.getName() != null || source.getDescription() != null)) {
                labelPaths.add(path);
            }
            collectAuthoredLabelPath(
                    source.getContracts(), BlueLanguageConstants.OBJECT_CONTRACTS, path,
                    limits, labelPaths, activeNodes);
            if (source.getItems() != null) {
                collectAuthoredListLabelPaths(
                        source.getItems(), path, limits, labelPaths, activeNodes);
            }
            if (source.getProperties() != null) {
                source.getProperties().forEach((key, child) -> collectAuthoredLabelPath(
                        child, key, path, limits, labelPaths, activeNodes));
            }
        } finally {
            activeNodes.remove(source);
        }
    }

    private void collectAuthoredListLabelPaths(List<Node> children,
                                               LabelPath parentPath,
                                               ResolutionLimits limits,
                                               Set<LabelPath> labelPaths,
                                               Set<Node> activeNodes) {
        boolean hasPositionControls = children.stream()
                .anyMatch(child -> child.getPosition() != null);
        int start = startsWithPrevious(children) ? 1 : 0;
        if (hasPositionControls) {
            for (int index = start; index < children.size(); index++) {
                Node child = children.get(index);
                if (child.getPosition() == null) {
                    // Unpositioned children in a controlled list are appended, so they
                    // do not overlay an inherited label at a pre-existing path.
                    continue;
                }
                collectAuthoredLabelPath(
                        effectivePositionOverlay(child), String.valueOf(child.getPosition()), parentPath,
                        limits, labelPaths, activeNodes);
            }
            return;
        }
        if (start > 0) {
            // Children after a $previous anchor are appended. Their own nested
            // resolution creates a scope at the effective appended position.
            return;
        }
        for (int index = 0; index < children.size(); index++) {
            collectAuthoredLabelPath(
                    children.get(index), String.valueOf(index), parentPath,
                    limits, labelPaths, activeNodes);
        }
    }

    private void collectAuthoredLabelPath(Node child,
                                          String segment,
                                          LabelPath parentPath,
                                          ResolutionLimits limits,
                                          Set<LabelPath> labelPaths,
                                          Set<Node> activeNodes) {
        if (child == null || !limits.shouldMergePathSegment(segment, child)) {
            return;
        }
        limits.enterPathSegment(segment, child);
        try {
            collectAuthoredLabelPaths(
                    child, parentPath.child(segment), limits, true,
                    labelPaths, activeNodes);
        } finally {
            limits.exitPathSegment();
        }
    }

    private Node effectivePositionOverlay(Node child) {
        Node overlay = withoutPosition(child);
        return hasReplacement(overlay)
                ? overlay.getProperties().get(LIST_CONTROL_REPLACE)
                : overlay;
    }

    boolean hasLabelPathAtOrBelow(Set<LabelPath> labelPaths, LabelPath path) {
        if (labelPaths.contains(path)) {
            return true;
        }
        for (LabelPath labelPath : labelPaths) {
            if (labelPath.isAtOrBelow(path)) {
                return true;
            }
        }
        return false;
    }

    void seedMaterializedTargetLabelProvenance(Node target,
                                                       LabelProvenanceScope scope) {
        if (target == null || scope == null
                || !hasLabelPathAtOrBelow(scope.labelPaths, LabelPath.root())) {
            return;
        }
        if (target.getType() != null) {
            recordTypeDeclarationLabelPaths(
                    target.getType(), LabelPath.root(), scope.labelPaths);
        }
        for (LabelPath labelPath : scope.labelPaths) {
            Node materialized = nodeAtPath(target, labelPath);
            if (materialized != null && sourceContainsFixedContent(materialized)) {
                setDeclarationOnlyLabelPath(scope, labelPath, false);
            }
        }
    }

    private Node nodeAtPath(Node root, LabelPath path) {
        Node current = root;
        for (String segment : path.segments()) {
            if (current == null) {
                return null;
            }
            if (BlueLanguageConstants.OBJECT_CONTRACTS.equals(segment) && current.getContracts() != null) {
                current = current.getContracts();
                continue;
            }
            if (current.getItems() != null && JsonPointer.isArrayIndexSegment(segment)) {
                if ("-".equals(segment)) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (index < 0 || index >= current.getItems().size()) {
                    return null;
                }
                current = current.getItems().get(index);
                continue;
            }
            current = current.getProperties() == null
                    ? null
                    : current.getProperties().get(segment);
        }
        return current;
    }

    void validateExplicitInstanceLabels(Node inherited,
                                                Node source,
                                                boolean inheritedDeclarationOnly) {
        if (source.getName() == null && source.getDescription() == null) {
            return;
        }
        if (inherited.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "An inherited pure reference cannot carry name or description overlays. Path: "
                            + currentPath(activeResolutionState()));
        }
        if (inheritedDeclarationOnly) {
            return;
        }
        validateFixedValueLabel(BlueLanguageConstants.OBJECT_NAME, inherited.getName(), source.getName());
        validateFixedValueLabel(BlueLanguageConstants.OBJECT_DESCRIPTION, inherited.getDescription(), source.getDescription());
    }

    private void validateFixedValueLabel(String label, String inherited, String source) {
        if (source != null && inherited != null && !inherited.equals(source)) {
            throw new IllegalArgumentException(
                    "Inherited fixed value " + label + " conflicts at path "
                            + currentPath(activeResolutionState()) + ". Source label: " + source
                            + ", inherited label: " + inherited);
        }
    }

    void applyExplicitInstanceLabels(Node target,
                                             Node source,
                                             boolean inheritedDeclarationOnly) {
        if (source.getName() != null
                && (inheritedDeclarationOnly || target.getName() == null)) {
            target.name(source.getName());
        }
        if (source.getDescription() != null
                && (inheritedDeclarationOnly || target.getDescription() == null)) {
            target.description(source.getDescription());
        }
    }

    void copyMaterializedReferenceLabels(Node target, Node materialized) {
        if (target.getName() == null && materialized.getName() != null) {
            target.name(materialized.getName());
        }
        if (target.getDescription() == null && materialized.getDescription() != null) {
            target.description(materialized.getDescription());
        }
    }

    private Node applyItemType(Node child, Node itemType) {
        return listOverlayMerger.applyItemType(child, itemType);
    }

    private Node itemTypeReference(Node itemType) {
        return listOverlayMerger.itemTypeReference(itemType);
    }

    private Node withoutPosition(Node node) {
        return listOverlayMerger.withoutPosition(node);
    }

    private boolean startsWithPrevious(List<Node> children) {
        return listOverlayMerger.startsWithPrevious(children);
    }

    private boolean hasReplacement(Node node) {
        return listOverlayMerger.hasReplacement(node);
    }

    private boolean isEmptyPlaceholder(Node node) {
        return listOverlayMerger.isEmptyPlaceholder(node);
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

    enum MergeMode {
        AUTHORED_OVERLAY,
        REFERENCE_EXPANSION,
        NONE
    }

    static final class LabelProvenanceScope {
        final LabelPath rootPath;
        final Set<LabelPath> labelPaths;
        private final Set<LabelPath> declarationOnlyPaths = new HashSet<>();
        private final Set<LabelPath> fixedPaths = new HashSet<>();

        private LabelProvenanceScope(LabelPath rootPath,
                                     Set<LabelPath> labelPaths) {
            this.rootPath = rootPath;
            this.labelPaths = labelPaths;
        }
    }

    private enum LabelScanTaskKind {
        TYPE,
        SOURCE,
        CHILDREN,
        EXIT_TYPE
    }

    private static final class LabelScanTask {
        private final LabelScanTaskKind kind;
        private final Node node;
        private final LabelPath path;
        private final String typeBlueId;

        private LabelScanTask(LabelScanTaskKind kind,
                              Node node,
                              LabelPath path,
                              String typeBlueId) {
            this.kind = kind;
            this.node = node;
            this.path = path;
            this.typeBlueId = typeBlueId;
        }

        private static LabelScanTask type(Node node, LabelPath path) {
            return new LabelScanTask(LabelScanTaskKind.TYPE, node, path, null);
        }

        private static LabelScanTask source(Node node, LabelPath path) {
            return new LabelScanTask(LabelScanTaskKind.SOURCE, node, path, null);
        }

        private static LabelScanTask children(Node node, LabelPath path) {
            return new LabelScanTask(LabelScanTaskKind.CHILDREN, node, path, null);
        }

        private static LabelScanTask exitType(String typeBlueId, Node node) {
            return new LabelScanTask(LabelScanTaskKind.EXIT_TYPE, node, null, typeBlueId);
        }
    }

    private static final class LabelScanState {
        private final LabelProvenanceScope scope;
        private final Set<LabelPath> relevantLabelPaths;
        private final Set<String> activeTypeBlueIds = new HashSet<>();
        private final Set<Node> activeInlineTypes = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<LabelPath, Integer> listSizes = new HashMap<>();
        private final Map<LabelPath, Node> effectiveItemTypes = new HashMap<>();
        private final Map<LabelPath, Map<Integer, Node>> effectiveListItems = new HashMap<>();

        private LabelScanState(LabelProvenanceScope scope,
                               Set<LabelPath> relevantLabelPaths) {
            this.scope = scope;
            this.relevantLabelPaths = relevantLabelPaths;
        }

        private boolean enterType(String typeBlueId, Node typeNode) {
            return typeBlueId != null
                    ? activeTypeBlueIds.add(typeBlueId)
                    : activeInlineTypes.add(typeNode);
        }

        private void exitType(String typeBlueId, Node typeNode) {
            if (typeBlueId != null) {
                activeTypeBlueIds.remove(typeBlueId);
            } else {
                activeInlineTypes.remove(typeNode);
            }
        }
    }

    private static final class PositionedLabelSource {
        private final int position;
        private final Node node;
        private final boolean replacement;

        private PositionedLabelSource(int position, Node node) {
            this(position, node, false);
        }

        private PositionedLabelSource(int position, Node node, boolean replacement) {
            this.position = position;
            this.node = node;
            this.replacement = replacement;
        }
    }

}
