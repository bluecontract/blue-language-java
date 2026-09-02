package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static blue.language.model.wire.BlueLanguageConstants.LIST_CONTROL_REPLACE;
import static blue.language.model.wire.BlueLanguageConstants.LIST_MERGE_POLICY_APPEND_ONLY;

/**
 * Immutable proof that a selected scope was projected to a standalone
 * Source-equivalent document without changing its completed resolved view.
 */
final class ScopeSourceProjection {

    private static final String STRUCTURE_PROPERTIES_SEGMENT = "properties";
    private static final String STRUCTURE_KEYS_SEGMENT = "keys";
    private static final String STRUCTURE_SIZE_SEGMENT = "size";

    private final String scopePath;
    private final FrozenNode standaloneSource;
    private final ResolvedSnapshot standaloneSnapshot;

    private ScopeSourceProjection(String scopePath,
                                  FrozenNode standaloneSource,
                                  ResolvedSnapshot standaloneSnapshot) {
        this.scopePath = scopePath;
        this.standaloneSource = standaloneSource;
        this.standaloneSnapshot = standaloneSnapshot;
    }

    static ScopeSourceProjection project(String scopePath,
                                         FrozenNode selectedScopeContribution,
                                         ResolvedSnapshot capturedDocumentSnapshot,
                                         ProcessingSnapshotManager snapshotManager) {
        String normalizedScope = JsonPointer.canonicalize(scopePath);
        ResolvedSnapshot captured = Objects.requireNonNull(
                capturedDocumentSnapshot, "capturedDocumentSnapshot");
        ProcessingSnapshotManager manager = Objects.requireNonNull(
                snapshotManager, "snapshotManager");
        FrozenNode capturedResolvedScope = captured.resolvedAt(normalizedScope);
        if (capturedResolvedScope == null) {
            throw new IllegalStateException(
                    "Cannot project missing selected scope " + normalizedScope);
        }

        Node selectedContribution = selectedScopeContribution != null
                ? selectedScopeContribution.toNode()
                : null;
        FrozenNode sourceFragment = captured.sourceAt(normalizedScope);

        // Prefer the actually selected Phase 1 contribution. For Node-backed
        // processing this is already a real Source-equivalent subtree and is
        // the only representation that can retain authoring controls and
        // preprocessing provenance exactly. Add only context that a parent
        // field supplied and that a standalone root therefore needs explicitly.
        Node standaloneSource = selectedContribution != null
                ? selectedContribution.clone()
                : null;
        if (standaloneSource != null) {
            makeStandaloneRoot(standaloneSource, selectedContribution,
                    sourceFragment, capturedResolvedScope,
                    captured.canonicalTypeIdentities());
        }

        ResolvedSnapshot projected = null;
        RuntimeException selectedProjectionFailure = null;
        if (standaloneSource != null) {
            try {
                projected = Objects.requireNonNull(
                        manager.fromDocumentTransient(standaloneSource.clone()),
                        "projectedStandaloneSnapshot");
                if (!sameCompletedResolvedStructure(
                        projected.frozenResolvedRoot(), capturedResolvedScope)) {
                    projected = null;
                }
            } catch (RuntimeException failure) {
                if (ScopeIdentityErrorMapper.isProviderIdentityFailure(
                        failure)) {
                    throw failure;
                }
                selectedProjectionFailure = failure;
                projected = null;
            }
        }

        if (projected == null) {
            // Start from the captured exact input lane, augment the
            // parent-supplied root context, and derive the desired standalone
            // canonical overlay. Convert only final inherited lists back to
            // valid Source controls; never use the general author-facing
            // resolved-view minimizer as an identity reconstruction oracle.
            Node sourceSeed = sourceFragment != null
                    ? sourceFragment.toNode()
                    : new Node();
            makeStandaloneRoot(sourceSeed, selectedContribution,
                    sourceFragment, capturedResolvedScope,
                    captured.canonicalTypeIdentities());
            Node desiredStandaloneCanonical = CanonicalIdentityEvidence
                    .projectResolvedGraph(
                            capturedResolvedScope.toNode(),
                            sourceSeed,
                            captured.canonicalTypeIdentities())
                    .toNode();
            standaloneSource = sourceifyCanonicalFinalLists(
                    desiredStandaloneCanonical,
                    capturedResolvedScope.toNode(),
                    capturedResolvedScope.getType() != null
                            ? capturedResolvedScope.getType().toNode()
                            : null);
            makeStandaloneRoot(standaloneSource, selectedContribution,
                    sourceFragment, capturedResolvedScope,
                    captured.canonicalTypeIdentities());
            try {
                projected = Objects.requireNonNull(
                        manager.fromDocumentTransient(standaloneSource.clone()),
                        "projectedStandaloneSnapshot");
            } catch (RuntimeException failure) {
                if (selectedProjectionFailure != null) {
                    failure.addSuppressed(selectedProjectionFailure);
                }
                throw failure;
            }
        }

        if (!sameCompletedResolvedStructure(
                projected.frozenResolvedRoot(), capturedResolvedScope)) {
            IllegalStateException failure = new IllegalStateException(
                    "Standalone selected-scope projection changed the resolved view at "
                    + normalizedScope
                    + "; first difference: "
                    + firstResolvedDifference(
                    capturedResolvedScope,
                    projected.frozenResolvedRoot(),
                    normalizedScope));
            if (selectedProjectionFailure != null) {
                failure.addSuppressed(selectedProjectionFailure);
            }
            throw failure;
        }
        if (!projected.frozenCanonicalRoot().isStrictBlueIdValidation()) {
            projected = projected.toStrictBlueIdValidatedCanonical();
        }
        return new ScopeSourceProjection(
                normalizedScope,
                FrozenNode.fromSourceNode(standaloneSource),
                projected);
    }

    private static void makeStandaloneRoot(Node standaloneSource,
                                           Node selectedContribution,
                                           FrozenNode sourceFragment,
                                           FrozenNode capturedResolvedScope,
                                           CanonicalTypeIdentityLookup
                                                   typeIdentities) {
        if (standaloneSource.isReferenceOnly()) {
            return;
        }

        Node source = sourceFragment != null ? sourceFragment.toNode() : null;
        if (source != null && source.isReferenceOnly()) {
            standaloneSource.replaceWith(source);
            return;
        }

        Node effectiveType = capturedResolvedScope.getType() != null
                ? capturedResolvedScope.getType().toNode()
                : null;
        Node selectedType = standaloneSource.getType();
        boolean mixedBlueIdMetadata = selectedType != null
                && selectedType.getBlueId() != null
                && !selectedType.isReferenceOnly();
        if ((selectedType == null
                || Nodes.isEmptyNode(selectedType)
                || mixedBlueIdMetadata)
                && effectiveType != null) {
            standaloneSource.type(sourceType(
                    effectiveType,
                    Objects.requireNonNull(
                            typeIdentities, "typeIdentities")));
        }

        String selectedName = selectedContribution != null
                ? selectedContribution.getName()
                : null;
        String sourceName = source != null ? source.getName() : null;
        if (standaloneSource.getName() == null) {
            standaloneSource.name(selectedName != null
                    ? selectedName
                    : sourceName != null ? sourceName : capturedResolvedScope.getName());
        }
        String selectedDescription = selectedContribution != null
                ? selectedContribution.getDescription()
                : null;
        String sourceDescription = source != null
                ? source.getDescription()
                : null;
        if (standaloneSource.getDescription() == null) {
            standaloneSource.description(selectedDescription != null
                    ? selectedDescription
                    : sourceDescription != null
                    ? sourceDescription
                    : capturedResolvedScope.getDescription());
        }

        Node selectedContracts = selectedContribution != null
                ? selectedContribution.getContracts()
                : null;
        if (selectedContracts != null
                && Nodes.isEmptyNode(selectedContracts)
                && standaloneSource.getContracts() == null) {
            standaloneSource.contracts(new Node());
        }
        if (capturedResolvedScope.getContracts() != null
                && Nodes.isEmptyNode(capturedResolvedScope.getContracts().toNode())
                && standaloneSource.getContracts() == null) {
            // Empty contracts are hash-neutral but observable in the selected
            // resolved scope. Retain them so the projection proof compares the
            // complete structural view rather than a cleaned approximation.
            standaloneSource.contracts(new Node());
        }
    }

    private static Node sourceType(
            Node effectiveType,
            CanonicalTypeIdentityLookup typeIdentities) {
        CanonicalTypeIdentityEvidence evidence = typeIdentities
                .findCanonicalTypeIdentityEvidence(
                        Objects.requireNonNull(
                                effectiveType, "effectiveType"))
                .orElseThrow(() -> new IllegalStateException(
                        "Standalone scope projection requires canonical "
                                + "effective-type identity evidence"));
        if (effectiveType.isReferenceOnly()
                || evidence.hasReferenceSource()) {
            return new Node().blueId(evidence.blueId());
        }
        Node authoredInline = evidence.authoredTypeSource();
        if (authoredInline == null) {
            throw new IllegalStateException(
                    "Standalone scope projection cannot reconstruct an "
                            + "inline effective type without authored Source "
                            + "evidence");
        }
        return authoredInline;
    }

    private static boolean sameCompletedResolvedStructure(FrozenNode left,
                                                          FrozenNode right) {
        return left.sameResolvedStructure(right);
    }

    private static String firstResolvedDifference(FrozenNode captured,
                                                  FrozenNode projected,
                                                  String path) {
        if (captured == projected) {
            return path + " (unknown representation difference)";
        }
        if (captured == null || projected == null) {
            return path + " (captured=" + (captured != null)
                    + ", projected=" + (projected != null) + ")";
        }
        if (!Objects.equals(captured.getName(), projected.getName())) {
            return JsonPointer.append(path, BlueLanguageConstants.OBJECT_NAME)
                    + " (captured=" + captured.getName()
                    + ", projected=" + projected.getName()
                    + ", capturedBlueId=" + captured.getReferenceBlueId()
                    + ", projectedBlueId=" + projected.getReferenceBlueId() + ")";
        }
        if (!Objects.equals(captured.getDescription(), projected.getDescription())) {
            return JsonPointer.append(
                    path,
                    BlueLanguageConstants.OBJECT_DESCRIPTION);
        }
        if (!Objects.deepEquals(captured.getValue(), projected.getValue())) {
            return JsonPointer.append(path, BlueLanguageConstants.OBJECT_VALUE);
        }
        if (!Objects.equals(captured.getReferenceBlueId(), projected.getReferenceBlueId())) {
            return JsonPointer.append(path, BlueLanguageConstants.OBJECT_BLUE_ID);
        }
        if (!Objects.equals(captured.getMergePolicy(), projected.getMergePolicy())) {
            return JsonPointer.append(
                    path,
                    BlueLanguageConstants.OBJECT_MERGE_POLICY);
        }
        if (!Objects.equals(captured.getPreviousBlueId(), projected.getPreviousBlueId())) {
            return JsonPointer.append(
                    path,
                    BlueLanguageConstants.LIST_CONTROL_PREVIOUS);
        }
        if (!Objects.equals(captured.getPosition(), projected.getPosition())) {
            return JsonPointer.append(
                    path,
                    BlueLanguageConstants.LIST_CONTROL_POS);
        }
        String nested = firstNestedDifference(
                captured.getType(),
                projected.getType(),
                JsonPointer.append(path, BlueLanguageConstants.OBJECT_TYPE));
        if (nested != null) {
            return nested;
        }
        nested = firstNestedDifference(captured.getItemType(), projected.getItemType(),
                JsonPointer.append(path, BlueLanguageConstants.OBJECT_ITEM_TYPE));
        if (nested != null) {
            return nested;
        }
        nested = firstNestedDifference(captured.getKeyType(), projected.getKeyType(),
                JsonPointer.append(path, BlueLanguageConstants.OBJECT_KEY_TYPE));
        if (nested != null) {
            return nested;
        }
        nested = firstNestedDifference(captured.getValueType(), projected.getValueType(),
                JsonPointer.append(path, BlueLanguageConstants.OBJECT_VALUE_TYPE));
        if (nested != null) {
            return nested;
        }
        nested = firstNestedDifference(captured.getContracts(), projected.getContracts(),
                JsonPointer.append(path, BlueLanguageConstants.OBJECT_CONTRACTS));
        if (nested != null) {
            return nested;
        }
        nested = firstNestedDifference(
                captured.getBlue(),
                projected.getBlue(),
                JsonPointer.append(path, BlueLanguageConstants.OBJECT_BLUE));
        if (nested != null) {
            return nested;
        }
        List<FrozenNode> capturedItems = captured.getItems();
        List<FrozenNode> projectedItems = projected.getItems();
        if (capturedItems == null || projectedItems == null) {
            if (capturedItems != projectedItems) {
                return JsonPointer.append(
                        path,
                        BlueLanguageConstants.OBJECT_ITEMS);
            }
        } else {
            if (capturedItems.size() != projectedItems.size()) {
                return JsonPointer.append(
                        JsonPointer.append(
                                path,
                                BlueLanguageConstants.OBJECT_ITEMS),
                        STRUCTURE_SIZE_SEGMENT);
            }
            for (int index = 0; index < capturedItems.size(); index++) {
                nested = firstNestedDifference(capturedItems.get(index), projectedItems.get(index),
                        JsonPointer.append(
                                JsonPointer.append(
                                        path,
                                        BlueLanguageConstants.OBJECT_ITEMS),
                                String.valueOf(index)));
                if (nested != null) {
                    return nested;
                }
            }
        }
        Map<String, FrozenNode> capturedProperties = captured.getProperties();
        Map<String, FrozenNode> projectedProperties = projected.getProperties();
        if (capturedProperties == null || projectedProperties == null) {
            if (capturedProperties != projectedProperties) {
                return JsonPointer.append(
                        path,
                        STRUCTURE_PROPERTIES_SEGMENT);
            }
        } else {
            if (!capturedProperties.keySet().equals(projectedProperties.keySet())) {
                return JsonPointer.append(
                        JsonPointer.append(
                                path,
                                STRUCTURE_PROPERTIES_SEGMENT),
                        STRUCTURE_KEYS_SEGMENT);
            }
            for (String key : capturedProperties.keySet()) {
                nested = firstNestedDifference(capturedProperties.get(key),
                        projectedProperties.get(key),
                        JsonPointer.append(path, key));
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (!Objects.equals(String.valueOf(captured.getSchema()),
                String.valueOf(projected.getSchema()))) {
            return JsonPointer.append(path, BlueLanguageConstants.OBJECT_SCHEMA);
        }
        return path + " (unknown representation difference)";
    }

    private static String firstNestedDifference(FrozenNode captured,
                                                FrozenNode projected,
                                                String path) {
        if (captured == projected) {
            return null;
        }
        if (captured == null || projected == null) {
            return path;
        }
        if (captured.sameResolvedStructure(projected)) {
            return null;
        }
        return firstResolvedDifference(captured, projected, path);
    }

    /**
     * Converts Canonical Identity Input back into a valid Source-equivalent
     * overlay without discarding its captured labels or pure references.
     * Canonical and Source forms differ materially only for final inherited
     * list payloads here; those payloads are expressed as deterministic
     * positional replacements or append-only suffixes.
     */
    private static Node sourceifyCanonicalFinalLists(Node canonical,
                                                     Node resolved,
                                                     Node inherited) {
        if (canonical == null) {
            return null;
        }
        if (canonical.isReferenceOnly()) {
            return canonical.clone();
        }

        Node source = canonical.clone();
        Node context = inherited != null
                ? inherited
                : resolved != null ? resolved.getType() : null;

        source.type(sourceifyMetadata(
                canonical.getType(),
                resolved != null ? resolved.getType() : null,
                context != null ? context.getType() : null));
        source.itemType(sourceifyMetadata(
                canonical.getItemType(),
                resolved != null ? resolved.getItemType() : null,
                context != null ? context.getItemType() : null));
        source.keyType(sourceifyMetadata(
                canonical.getKeyType(),
                resolved != null ? resolved.getKeyType() : null,
                context != null ? context.getKeyType() : null));
        source.valueType(sourceifyMetadata(
                canonical.getValueType(),
                resolved != null ? resolved.getValueType() : null,
                context != null ? context.getValueType() : null));

        if (canonical.getContracts() != null) {
            source.contracts(sourceifyCanonicalFinalLists(
                    canonical.getContracts(),
                    resolved != null ? resolved.getContracts() : null,
                    context != null ? context.getContracts() : null));
        }
        if (canonical.getBlue() != null) {
            source.blue(sourceifyCanonicalFinalLists(
                    canonical.getBlue(),
                    resolved != null ? resolved.getBlue() : null,
                    context != null ? context.getBlue() : null));
        }

        if (canonical.getProperties() != null) {
            for (Map.Entry<String, Node> entry : canonical.getProperties().entrySet()) {
                String key = entry.getKey();
                Node resolvedChild = resolved != null && resolved.getProperties() != null
                        ? resolved.getProperties().get(key)
                        : null;
                Node inheritedChild = context != null && context.getProperties() != null
                        ? context.getProperties().get(key)
                        : null;
                source.getProperties().put(key, sourceifyCanonicalFinalLists(
                        entry.getValue(), resolvedChild, inheritedChild));
            }
        }

        if (canonical.getItems() != null) {
            source.items(sourceItemsForFinalCanonicalList(
                    canonical, resolved, context));
        }
        return source;
    }

    private static Node sourceifyMetadata(Node canonical,
                                          Node resolved,
                                          Node inherited) {
        if (canonical == null) {
            return null;
        }
        return sourceifyCanonicalFinalLists(canonical, resolved, inherited);
    }

    private static List<Node> sourceItemsForFinalCanonicalList(Node canonical,
                                                               Node resolved,
                                                               Node inherited) {
        List<Node> canonicalItems = canonical.getItems();
        List<Node> resolvedItems = resolved != null ? resolved.getItems() : null;
        if (resolvedItems == null || canonicalItems.size() != resolvedItems.size()) {
            throw new IllegalStateException(
                    "Canonical final list does not match the captured resolved list");
        }

        List<Node> inheritedItems = inherited != null ? inherited.getItems() : null;
        Node itemContext = resolved.getItemType() != null
                ? resolved.getItemType()
                : inherited != null ? inherited.getItemType() : null;
        if (inheritedItems == null) {
            List<Node> sourceItems = new ArrayList<>(canonicalItems.size());
            for (int index = 0; index < canonicalItems.size(); index++) {
                sourceItems.add(sourceifyCanonicalFinalLists(
                        canonicalItems.get(index), resolvedItems.get(index), itemContext));
            }
            return sourceItems;
        }
        if (resolvedItems.size() < inheritedItems.size()) {
            throw new IllegalStateException(
                    "Captured final list is shorter than its inherited list");
        }

        boolean appendOnly = LIST_MERGE_POLICY_APPEND_ONLY.equals(
                resolved.getMergePolicy() != null
                        ? resolved.getMergePolicy()
                        : inherited.getMergePolicy());
        List<Node> sourceItems = new ArrayList<>();
        for (int index = 0; index < inheritedItems.size(); index++) {
            Node resolvedItem = resolvedItems.get(index);
            if (sameResolvedNode(resolvedItem, inheritedItems.get(index))) {
                continue;
            }
            if (appendOnly) {
                throw new IllegalStateException(
                        "Captured append-only list changed its inherited prefix at index "
                                + index);
            }
            Node replacement = sourceifyCanonicalFinalLists(
                    canonicalItems.get(index), resolvedItem, itemContext);
            sourceItems.add(new Node()
                    .position(index)
                    .properties(LIST_CONTROL_REPLACE, replacement));
        }
        for (int index = inheritedItems.size(); index < resolvedItems.size(); index++) {
            sourceItems.add(sourceifyCanonicalFinalLists(
                    canonicalItems.get(index), resolvedItems.get(index), itemContext));
        }
        return sourceItems.isEmpty() ? null : sourceItems;
    }

    private static boolean sameResolvedNode(Node left, Node right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return FrozenNode.fromResolvedNode(left)
                .sameResolvedStructure(FrozenNode.fromResolvedNode(right));
    }

    String scopePath() {
        return scopePath;
    }

    FrozenNode standaloneSource() {
        return standaloneSource;
    }

    ResolvedSnapshot standaloneSnapshot() {
        return standaloneSnapshot;
    }

    String contentBlueId() {
        return standaloneSnapshot.blueId();
    }
}
