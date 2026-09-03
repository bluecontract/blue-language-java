package blue.language.conformance.contracts;

import blue.language.identity.NodeToBlueIdInput;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.api.BlueOperationResult;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStateReferencePaths;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Establishes fixture Source identity without opening opaque exact values. */
final class FixtureSourceIdentityResolver {

    private FixtureSourceIdentityResolver() {
    }

    static Identity resolve(
            BlueLanguage language,
            Node source,
            Map<String, List<String>> exactSourceFieldsByType,
            boolean includeTypeContracts) {
        return resolve(
                language,
                source,
                exactSourceFieldsByType,
                java.util.Collections
                        .<String, List<String>>emptyMap(),
                includeTypeContracts);
    }

    static Identity resolve(
            BlueLanguage language,
            Node source,
            Map<String, List<String>> exactSourceFieldsByType,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean includeTypeContracts) {
        BlueLanguage checkedLanguage = Objects.requireNonNull(
                language, "language");
        ExactSourceFieldCatalog fields = new ExactSourceFieldCatalog(
                exactSourceFieldsByType,
                executableBodyFieldsByType);
        try (LanguageProcessing.Scope scope =
                     checkedLanguage.processing().openScope()) {
            return resolve(
                    source,
                    fields,
                    includeTypeContracts,
                    blueId -> materializeVerifiedExact(scope, blueId),
                    new SnapshotResolver() {
                        @Override
                        public ResolvedSnapshot resolve(
                                Node sourceProjection,
                                Set<String> preservedPaths) {
                            return scope.resolveTransientPreservingPaths(
                                    sourceProjection, preservedPaths);
                        }

                        @Override
                        public CanonicalTypeIdentityEvidence
                        resolveTypeDeclarationIdentity(
                                Node declaration) {
                            return scope.resolveTypeDeclarationIdentity(
                                    declaration);
                        }
                    });
        }
    }

    static Identity resolve(
            BlueLanguageRuntime language,
            Node source,
            Map<String, List<String>> exactSourceFieldsByType,
            boolean includeTypeContracts) {
        return resolve(
                language,
                source,
                exactSourceFieldsByType,
                java.util.Collections
                        .<String, List<String>>emptyMap(),
                includeTypeContracts);
    }

    static Identity resolve(
            BlueLanguageRuntime language,
            Node source,
            Map<String, List<String>> exactSourceFieldsByType,
            Map<String, List<String>> executableBodyFieldsByType,
            boolean includeTypeContracts) {
        BlueLanguageRuntime checkedLanguage = Objects.requireNonNull(
                language, "language");
        try (BlueLanguage scopedLanguage = BlueLanguage.builder()
                .nodeProvider(checkedLanguage.nodeProvider())
                .cachePolicy(checkedLanguage.cachePolicy())
                .preprocessingAliases(
                        checkedLanguage.preprocessingAliases())
                .environmentImports(checkedLanguage.environmentImports())
                .build()) {
            return resolve(
                    scopedLanguage,
                    source,
                    exactSourceFieldsByType,
                    executableBodyFieldsByType,
                    includeTypeContracts);
        }
    }

    /**
     * Resolves one exact inline contract while retaining its runtime-owned
     * Node fields as independently canonicalized Source values.
     */
    static ResolvedSnapshot resolveContractSnapshot(
            BlueLanguage language,
            Node contract,
            Map<String, List<String>> exactSourceFieldsByType) {
        return resolveContractSnapshot(
                language,
                contract,
                exactSourceFieldsByType,
                java.util.Collections
                        .<String, List<String>>emptyMap());
    }

    static ResolvedSnapshot resolveContractSnapshot(
            BlueLanguage language,
            Node contract,
            Map<String, List<String>> exactSourceFieldsByType,
            Map<String, List<String>> executableBodyFieldsByType) {
        BlueLanguage checkedLanguage = Objects.requireNonNull(
                language, "language");
        Node checkedContract = Objects.requireNonNull(contract, "contract");
        ExactSourceFieldCatalog fields = new ExactSourceFieldCatalog(
                exactSourceFieldsByType,
                executableBodyFieldsByType);
        if (checkedContract.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Exact fixture contract content must be materialized");
        }
        try (LanguageProcessing.Scope scope =
                     checkedLanguage.processing().openScope()) {
            return resolveSnapshot(
                    checkedContract,
                    fields,
                    false,
                    true,
                    false,
                    blueId -> materializeVerifiedExact(scope, blueId),
                    new SnapshotResolver() {
                        @Override
                        public ResolvedSnapshot resolve(
                                Node sourceProjection,
                                Set<String> preservedPaths) {
                            return scope.resolveTransientPreservingPaths(
                                    sourceProjection, preservedPaths);
                        }

                        @Override
                        public CanonicalTypeIdentityEvidence
                        resolveTypeDeclarationIdentity(
                                Node declaration) {
                            return scope.resolveTypeDeclarationIdentity(
                                    declaration);
                        }
                    });
        }
    }

    private static Identity resolve(
            Node source,
            ExactSourceFieldCatalog fields,
            boolean includeTypeContracts,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver) {
        Node checkedSource = Objects.requireNonNull(source, "source");
        ExactSourceFieldCatalog checkedFields = Objects.requireNonNull(
                fields, "fields");
        if (checkedSource.isReferenceOnly()) {
            return new Identity(
                    checkedSource.clone(), checkedSource.getBlueId());
        }
        ResolvedSnapshot snapshot = resolveSnapshot(
                checkedSource,
                checkedFields,
                includeTypeContracts,
                false,
                false,
                exactNodeLoader,
                resolver);
        return new Identity(
                snapshot.canonicalRoot(), snapshot.blueId());
    }

    private static ResolvedSnapshot resolveSnapshot(
            Node source,
            ExactSourceFieldCatalog fields,
            boolean includeTypeContracts,
            boolean sourceIsContract,
            boolean preserveProcessorStatePatchEffects,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver) {
        Node checkedSource = Objects.requireNonNull(source, "source");
        ExactSourceFieldCatalog checkedFields = Objects.requireNonNull(
                fields, "fields");
        Node sourceProjection = NodeToBlueIdInput
                .stripResolvedBlueIdMetadata(checkedSource.clone());
        Set<String> preservedPaths = ordinaryReferencePaths(sourceProjection);
        if (preserveProcessorStatePatchEffects) {
            preservedPaths.addAll(
                    ProcessorStateReferencePaths.inPatchEffects(
                            sourceProjection));
        }
        if (sourceIsContract) {
            collectTypedContractExactSourceFieldPaths(
                    sourceProjection,
                    JsonPointer.ROOT,
                    checkedFields,
                    exactNodeLoader,
                    resolver,
                    preservedPaths);
        }
        collectExactSourceFieldPaths(
                sourceProjection,
                JsonPointer.ROOT,
                checkedFields,
                includeTypeContracts,
                exactNodeLoader,
                resolver,
                preservedPaths);
        ResolvedSnapshot snapshot = Objects.requireNonNull(
                resolver.resolve(sourceProjection, preservedPaths),
                "fixtureSourceIdentitySnapshot");
        if (!snapshot.hasCanonicalIdentity()) {
            throw new IllegalStateException(
                    "Fixture Source identity lacks complete canonical "
                            + "type evidence");
        }
        return snapshot;
    }

    private static void collectExactSourceFieldPaths(
            Node node,
            String path,
            ExactSourceFieldCatalog fields,
            boolean includeTypeContracts,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver,
            Set<String> result) {
        IdentityHashMap<Node, Boolean> active =
                new IdentityHashMap<>();
        Deque<SourceTraversalFrame> pending = new ArrayDeque<>();
        pending.push(SourceTraversalFrame.enter(node, path));
        while (!pending.isEmpty()) {
            SourceTraversalFrame frame = pending.pop();
            if (frame.exit) {
                active.remove(frame.node);
                continue;
            }
            Node current = frame.node;
            if (current == null
                    || current.isReferenceOnly()
                    || active.put(current, Boolean.TRUE) != null) {
                continue;
            }
            pending.push(SourceTraversalFrame.exit(current));
            collectProcessorStatePath(current, frame.path, result);
            if (includeTypeContracts) {
                collectTypeExactSourceFieldPaths(
                        current.getType(),
                        current,
                        frame.path,
                        fields,
                        exactNodeLoader,
                        resolver,
                        result,
                        new LinkedHashSet<String>(),
                        new IdentityHashMap<Node, Boolean>(),
                        0);
            }
            collectContractExactSourceFieldPaths(
                    current.getContracts(),
                    frame.path,
                    fields,
                    exactNodeLoader,
                    resolver,
                    result);
            if (current.getItems() != null) {
                for (int index = current.getItems().size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(SourceTraversalFrame.enter(
                            current.getItems().get(index),
                            JsonPointer.append(
                                    frame.path, Integer.toString(index))));
                }
            }
            if (current.getProperties() != null) {
                List<Map.Entry<String, Node>> entries =
                        new ArrayList<>(current.getProperties().entrySet());
                for (int index = entries.size() - 1;
                        index >= 0;
                        index--) {
                    Map.Entry<String, Node> entry = entries.get(index);
                    pending.push(SourceTraversalFrame.enter(
                            entry.getValue(),
                            JsonPointer.append(
                                    frame.path, entry.getKey())));
                }
            }
        }
    }

    private static void collectProcessorStatePath(
            Node scope,
            String scopePath,
            Set<String> result) {
        Node contracts = scope.getContracts();
        Node initialized = contracts != null
                && contracts.getProperties() != null
                ? contracts.getProperties().get(
                        ProcessorContractConstants.KEY_INITIALIZED)
                : null;
        Node exactDocument = initialized != null
                && initialized.getProperties() != null
                ? initialized.getProperties().get(
                        ProcessorContractConstants.KEY_DOCUMENT)
                : null;
        if (exactDocument == null) {
            return;
        }
        result.add(JsonPointer.append(
                JsonPointer.append(
                        JsonPointer.append(
                                scopePath,
                                BlueLanguageConstants.OBJECT_CONTRACTS),
                        ProcessorContractConstants.KEY_INITIALIZED),
                ProcessorContractConstants.KEY_DOCUMENT));
    }

    private static void collectTypeExactSourceFieldPaths(
            Node declaredType,
            Node authoredScope,
            String scopePath,
            ExactSourceFieldCatalog fields,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver,
            Set<String> result,
            Set<String> activeReferenceTypes,
            IdentityHashMap<Node, Boolean> activeInlineTypes,
            int depth) {
        if (declaredType == null) {
            return;
        }
        long maxTypeEdges = GasSchedule.contracts10().portableLimit(
                GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES);
        if (depth >= maxTypeEdges) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.DirectNodeLimitExceeded,
                    GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES,
                    depth + 1L,
                    maxTypeEdges);
        }
        String referenceBlueId = declaredType.isReferenceOnly()
                ? declaredType.getBlueId()
                : null;
        boolean entered = referenceBlueId != null
                ? activeReferenceTypes.add(referenceBlueId)
                : activeInlineTypes.put(
                        declaredType, Boolean.TRUE) == null;
        if (!entered) {
            throw new IllegalStateException(
                    "Cyclic type contribution while cataloging fixture "
                            + "Source identity fields");
        }
        try {
            Node exactType = referenceBlueId != null
                    ? exactNodeLoader.load(referenceBlueId)
                    : declaredType;
            collectTypeExactSourceFieldPaths(
                    exactType.getType(),
                    authoredScope,
                    scopePath,
                    fields,
                    exactNodeLoader,
                    resolver,
                    result,
                    activeReferenceTypes,
                    activeInlineTypes,
                    depth + 1);
            collectDirectContributionExactSourceFieldPaths(
                    exactType,
                    authoredScope,
                    scopePath,
                    fields,
                    exactNodeLoader,
                    resolver,
                    result);
        } finally {
            if (referenceBlueId != null) {
                activeReferenceTypes.remove(referenceBlueId);
            } else {
                activeInlineTypes.remove(declaredType);
            }
        }
    }

    private static void collectDirectContributionExactSourceFieldPaths(
            Node contribution,
            Node authoredScope,
            String scopePath,
            ExactSourceFieldCatalog fields,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver,
            Set<String> result) {
        if (contribution == null) {
            return;
        }
        collectContractExactSourceFieldPaths(
                contribution.getContracts(),
                scopePath,
                fields,
                exactNodeLoader,
                resolver,
                result);
        if (contribution.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : contribution.getProperties().entrySet()) {
                collectTypeProvidedScope(
                        entry.getValue(),
                        authoredScope != null
                                && authoredScope.getProperties() != null
                                ? authoredScope.getProperties().get(
                                        entry.getKey())
                                : null,
                        JsonPointer.append(scopePath, entry.getKey()),
                        fields,
                        exactNodeLoader,
                        resolver,
                        result);
            }
        }
        if (contribution.getItems() != null) {
            for (int index = 0;
                    index < contribution.getItems().size();
                    index++) {
                Node authoredChild = authoredScope != null
                        && authoredScope.getItems() != null
                        && index < authoredScope.getItems().size()
                        ? authoredScope.getItems().get(index)
                        : null;
                collectTypeProvidedScope(
                        contribution.getItems().get(index),
                        authoredChild,
                        JsonPointer.append(
                                scopePath, Integer.toString(index)),
                        fields,
                        exactNodeLoader,
                        resolver,
                        result);
            }
        }
    }

    private static void collectTypeProvidedScope(
            Node contribution,
            Node authoredScope,
            String scopePath,
            ExactSourceFieldCatalog fields,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver,
            Set<String> result) {
        if (authoredScope == null) {
            result.add(scopePath);
            return;
        }
        Node exactContribution = contribution;
        if (exactContribution != null && exactContribution.isReferenceOnly()) {
            exactContribution = exactNodeLoader.load(
                    exactContribution.getBlueId());
        }
        if (exactContribution == null) {
            return;
        }
        collectTypeExactSourceFieldPaths(
                exactContribution.getType(),
                authoredScope,
                scopePath,
                fields,
                exactNodeLoader,
                resolver,
                result,
                new LinkedHashSet<String>(),
                new IdentityHashMap<Node, Boolean>(),
                0);
        collectDirectContributionExactSourceFieldPaths(
                exactContribution,
                authoredScope,
                scopePath,
                fields,
                exactNodeLoader,
                resolver,
                result);
    }

    private static Node materializeVerifiedExact(
            LanguageProcessing.Scope scope,
            String blueId) {
        BlueOperationResult<FrozenNode> result =
                scope.materializeVerifiedExactReference(
                        FrozenNode.fromNode(new Node().blueId(blueId)));
        if (!result.isEstablished()) {
            throw new IllegalStateException(
                    "Exact fixture type evidence for " + blueId + " is "
                            + result.outcome()
                            + result.reason()
                                    .map(reason -> ": " + reason)
                                    .orElse(""));
        }
        Node exact = result.requireEstablished().toNode();
        if (exact.isReferenceOnly()) {
            throw new IllegalStateException(
                    "Verified fixture provider returned a reference instead "
                            + "of exact content for " + blueId);
        }
        return exact;
    }

    private static void collectContractExactSourceFieldPaths(
            Node contracts,
            String scopePath,
            ExactSourceFieldCatalog fields,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver,
            Set<String> result) {
        Node recognizedContracts = contracts;
        if (recognizedContracts != null
                && recognizedContracts.isReferenceOnly()) {
            recognizedContracts = exactNodeLoader.load(
                    recognizedContracts.getBlueId());
        }
        if (recognizedContracts == null
                || recognizedContracts.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Node> entry
                : recognizedContracts.getProperties().entrySet()) {
            Node contract = entry.getValue();
            String contractPath = JsonPointer.append(
                    JsonPointer.append(
                            scopePath,
                            BlueLanguageConstants.OBJECT_CONTRACTS),
                    entry.getKey());
            if (contract != null && contract.isReferenceOnly()) {
                result.add(contractPath);
                continue;
            }
            collectTypedContractExactSourceFieldPaths(
                    contract,
                    contractPath,
                    fields,
                    exactNodeLoader,
                    resolver,
                    result);
        }
    }

    private static void collectTypedContractExactSourceFieldPaths(
            Node contract,
            String contractPath,
            ExactSourceFieldCatalog fields,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver,
            Set<String> result) {
        String typeBlueId = sourceTypeBlueId(
                contract,
                resolver);
        List<String> exactFields = fields.exactFields(typeBlueId);
        if (exactFields == null) {
            /*
             * An unrecognized contract type is deliberately opaque to the
             * fixture identity preparer.  Its capability classification
             * belongs to the Contracts runtime, so preserve the authored
             * declaration and do not demand its body in Language first.
             * Preserving the declaration also works when the contract itself
             * is the transient-resolution root, where preserving "/" would
             * not establish a nested opacity boundary.
             */
            result.add(contractPath);
            result.add(JsonPointer.append(
                    contractPath, BlueLanguageConstants.OBJECT_TYPE));
            return;
        }
        Map<String, Node> properties = contract != null
                ? contract.getProperties()
                : null;
        for (String field : exactFields) {
            if (properties != null) {
                Node exactValue = properties.get(field);
                if (exactValue != null) {
                    properties.put(
                            field,
                            canonicalizeExactSourceValue(
                                    exactValue,
                                    fields,
                                    fields.isExecutableBodyField(
                                            typeBlueId, field),
                                    exactNodeLoader,
                                    resolver));
                }
            }
            result.add(JsonPointer.append(contractPath, field));
        }
    }

    private static Node canonicalizeExactSourceValue(
            Node exactValue,
            ExactSourceFieldCatalog fields,
            boolean executableBody,
            ExactNodeLoader exactNodeLoader,
            SnapshotResolver resolver) {
        if (!executableBody) {
            Node checked = NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                    Objects.requireNonNull(exactValue, "exactValue").clone());
            if (checked.isReferenceOnly()) {
                return checked;
            }
            CanonicalTypeIdentityEvidence evidence = Objects.requireNonNull(
                    resolver.resolveTypeDeclarationIdentity(checked),
                    "fixtureExactHeaderIdentityEvidence");
            Node canonical = evidence.canonicalTypeIdentityInput();
            if (canonical == null) {
                throw new IllegalStateException(
                        "Fixture exact header field did not retain canonical "
                                + "inline Source proof");
            }
            return canonical;
        }
        ResolvedSnapshot snapshot = resolveSnapshot(
                exactValue,
                fields,
                false,
                false,
                executableBody,
                exactNodeLoader,
                resolver);
        return snapshot.canonicalRoot();
    }

    private static String sourceTypeBlueId(
            Node contract,
            SnapshotResolver resolver) {
        if (contract == null || contract.getType() == null) {
            return null;
        }
        Node type = contract.getType();
        if (type.isReferenceOnly()) {
            return type.getBlueId();
        }
        Node typeSource = NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                type.clone());
        return resolver.resolveTypeDeclarationIdentity(typeSource).blueId();
    }

    /**
     * Retains ordinary pure-reference values while allowing every structural
     * type, schema, contracts-map, and list-replacement edge to resolve.
     */
    private static Set<String> ordinaryReferencePaths(Node source) {
        Set<String> result = new LinkedHashSet<>();
        IdentityHashMap<Node, Boolean> active =
                new IdentityHashMap<>();
        Deque<ReferenceTraversalFrame> pending = new ArrayDeque<>();
        pending.push(ReferenceTraversalFrame.enter(
                source, JsonPointer.ROOT, false));
        while (!pending.isEmpty()) {
            ReferenceTraversalFrame frame = pending.pop();
            if (frame.exit) {
                active.remove(frame.node);
                continue;
            }
            Node node = frame.node;
            if (node == null) {
                continue;
            }
            if (node.isReferenceOnly()) {
                if (BlueIds.hasCyclicMemberSeparator(node.getBlueId())
                        || (!frame.structuralReference
                                && !JsonPointer.ROOT.equals(frame.path))) {
                    result.add(frame.path);
                }
                continue;
            }
            if (active.put(node, Boolean.TRUE) != null) {
                continue;
            }
            pending.push(ReferenceTraversalFrame.exit(node));
            List<ReferenceTraversalFrame> children =
                    referenceTraversalChildren(node, frame.path);
            for (int index = children.size() - 1;
                    index >= 0;
                    index--) {
                pending.push(children.get(index));
            }
        }
        return result;
    }

    private static List<ReferenceTraversalFrame> referenceTraversalChildren(
            Node node,
            String path) {
        List<ReferenceTraversalFrame> children = new ArrayList<>();
        addReferenceTraversalChild(children, node.getType(),
                JsonPointer.append(path, BlueLanguageConstants.OBJECT_TYPE),
                true);
        addReferenceTraversalChild(children, node.getItemType(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_ITEM_TYPE), true);
        addReferenceTraversalChild(children, node.getKeyType(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_KEY_TYPE), true);
        addReferenceTraversalChild(children, node.getValueType(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_VALUE_TYPE), true);
        addReferenceTraversalChild(children, node.getBlue(),
                JsonPointer.append(path, BlueLanguageConstants.OBJECT_BLUE),
                true);
        addReferenceTraversalChild(children, node.getContracts(),
                JsonPointer.append(
                        path, BlueLanguageConstants.OBJECT_CONTRACTS), true);
        addSchemaTraversalChildren(children, node.getSchema(), path);
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                addReferenceTraversalChild(
                        children,
                        entry.getValue(),
                        JsonPointer.append(path, entry.getKey()),
                        BlueLanguageConstants.LIST_CONTROL_REPLACE.equals(
                                entry.getKey()));
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                addReferenceTraversalChild(
                        children,
                        node.getItems().get(index),
                        JsonPointer.append(path, Integer.toString(index)),
                        false);
            }
        }
        return children;
    }

    private static void addSchemaTraversalChildren(
            List<ReferenceTraversalFrame> children,
            Schema schema,
            String parentPath) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        String path = JsonPointer.append(
                parentPath, BlueLanguageConstants.OBJECT_SCHEMA);
        addReferenceTraversalChild(
                children, schema.getRequired(), path + "/required", true);
        addReferenceTraversalChild(
                children, schema.getMinLength(), path + "/minLength", true);
        addReferenceTraversalChild(
                children, schema.getMaxLength(), path + "/maxLength", true);
        addReferenceTraversalChild(
                children, schema.getMinimum(), path + "/minimum", true);
        addReferenceTraversalChild(
                children, schema.getMaximum(), path + "/maximum", true);
        addReferenceTraversalChild(
                children,
                schema.getExclusiveMinimum(),
                path + "/exclusiveMinimum",
                true);
        addReferenceTraversalChild(
                children,
                schema.getExclusiveMaximum(),
                path + "/exclusiveMaximum",
                true);
        addReferenceTraversalChild(
                children, schema.getMultipleOf(), path + "/multipleOf", true);
        addReferenceTraversalChild(
                children, schema.getMinItems(), path + "/minItems", true);
        addReferenceTraversalChild(
                children, schema.getMaxItems(), path + "/maxItems", true);
        addReferenceTraversalChild(
                children, schema.getUniqueItems(), path + "/uniqueItems", true);
        addReferenceTraversalChild(
                children, schema.getMinFields(), path + "/minFields", true);
        addReferenceTraversalChild(
                children, schema.getMaxFields(), path + "/maxFields", true);
        if (schema.getEnum() != null) {
            for (int index = 0; index < schema.getEnum().size(); index++) {
                addReferenceTraversalChild(
                        children,
                        schema.getEnum().get(index),
                        path + "/enum/" + index,
                        true);
            }
        }
    }

    private static void addReferenceTraversalChild(
            List<ReferenceTraversalFrame> children,
            Node node,
            String path,
            boolean structuralReference) {
        if (node != null) {
            children.add(ReferenceTraversalFrame.enter(
                    node, path, structuralReference));
        }
    }

    private static final class ExactSourceFieldCatalog {
        private final Map<String, List<String>> exactFieldsByType;
        private final Map<String, List<String>> executableBodyFieldsByType;

        private ExactSourceFieldCatalog(
                Map<String, List<String>> exactFieldsByType,
                Map<String, List<String>> executableBodyFieldsByType) {
            this.exactFieldsByType = Objects.requireNonNull(
                    exactFieldsByType, "exactSourceFieldsByType");
            this.executableBodyFieldsByType = Objects.requireNonNull(
                    executableBodyFieldsByType,
                    "executableBodyFieldsByType");
        }

        private List<String> exactFields(String typeBlueId) {
            return exactFieldsByType.get(typeBlueId);
        }

        private boolean isExecutableBodyField(
                String typeBlueId,
                String field) {
            List<String> executableFields =
                    executableBodyFieldsByType.get(typeBlueId);
            return executableFields != null
                    && executableFields.contains(field);
        }
    }

    private static final class SourceTraversalFrame {
        private final Node node;
        private final String path;
        private final boolean exit;

        private SourceTraversalFrame(Node node, String path, boolean exit) {
            this.node = node;
            this.path = path;
            this.exit = exit;
        }

        private static SourceTraversalFrame enter(Node node, String path) {
            return new SourceTraversalFrame(node, path, false);
        }

        private static SourceTraversalFrame exit(Node node) {
            return new SourceTraversalFrame(node, null, true);
        }
    }

    private static final class ReferenceTraversalFrame {
        private final Node node;
        private final String path;
        private final boolean structuralReference;
        private final boolean exit;

        private ReferenceTraversalFrame(
                Node node,
                String path,
                boolean structuralReference,
                boolean exit) {
            this.node = node;
            this.path = path;
            this.structuralReference = structuralReference;
            this.exit = exit;
        }

        private static ReferenceTraversalFrame enter(
                Node node,
                String path,
                boolean structuralReference) {
            return new ReferenceTraversalFrame(
                    node, path, structuralReference, false);
        }

        private static ReferenceTraversalFrame exit(Node node) {
            return new ReferenceTraversalFrame(node, null, false, true);
        }
    }

    static final class Identity {
        private final FrozenNode canonicalInput;
        private final String blueId;

        private Identity(FrozenNode canonicalInput, String blueId) {
            this.canonicalInput = Objects.requireNonNull(
                    canonicalInput, "canonicalInput");
            this.blueId = Objects.requireNonNull(
                    blueId, BlueLanguageConstants.OBJECT_BLUE_ID);
        }

        private Identity(Node canonicalInput, String blueId) {
            this(FrozenNode.fromNode(canonicalInput), blueId);
        }

        Node canonicalInput() {
            return canonicalInput.toNode();
        }

        String blueId() {
            return blueId;
        }
    }

    private interface SnapshotResolver {
        ResolvedSnapshot resolve(
                Node sourceProjection,
                Set<String> preservedPaths);

        CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
                Node declaration);
    }

    @FunctionalInterface
    private interface ExactNodeLoader {
        Node load(String blueId);
    }
}
