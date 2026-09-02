package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/** Resolves one exact Root surface with canonical type evidence intact. */
final class ManagedRootSurfaceResolver {

    private final ProcessorInvocationServices owner;
    private final ProcessingGasContext gasContext;

    ManagedRootSurfaceResolver(
            ProcessorInvocationServices owner,
            ProcessingGasContext gasContext) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.gasContext = Objects.requireNonNull(gasContext, "gasContext");
    }

    Surface classify(Node exactDocument) {
        Node document = Objects.requireNonNull(
                exactDocument, "exactDocument").clone();
        DocumentProcessingResult invalid =
                ProcessingInputAdmission.validateDocument(document);
        if (invalid != null) {
            ProcessorDiagnostic diagnostic = invalid.diagnostic();
            throw new ProcessorFailureException(
                    diagnostic != null
                            ? diagnostic.category()
                            : ProcessorErrorCategory.InvalidProcessingDocument,
                    diagnostic != null && diagnostic.message() != null
                            ? diagnostic.message()
                            : "Invalid managed route-classification document");
        }
        ProcessorMarkerStore.collapseInitializationDocuments(
                document, owner.snapshotManager());
        long gasBefore = gasContext.meter().totalGas();
        DocumentProcessingRuntime view = new DocumentProcessingRuntime(
                document,
                owner.conformanceEngine(),
                owner.conformancePlannerOverride(),
                owner.snapshotManager(),
                owner.observer(),
                gasContext,
                owner.registry().executableBodyFieldsByType(),
                owner.strictPlatformInvocation());
        ResolvedScopeView scope = view.scopeViewAt(JsonPointer.ROOT);
        FrozenNode selected = scope.selected();
        owner.contractLoader().preflightSelectedContractHeaders(selected);
        FrozenNode resolved = scope.resolved();
        ResolvedScopeView recognition =
                view.contractRecognitionScope(scope);
        ContractBundle bundle = owner.contractLoader().load(
                selected,
                recognition.resolved(),
                JsonPointer.ROOT,
                owner.observer(),
                null,
                null,
                recognition.canonicalTypeIdentities());
        if (gasContext.meter().totalGas() != gasBefore) {
            throw new IllegalStateException(
                    "Route classification must not charge shared gas");
        }
        return new Surface(selected, resolved, bundle);
    }

    EffectiveContractSnapshot effectiveProcessEmbeddedSnapshot(
            ContractBundle bundle) {
        EffectiveContractSnapshot found = null;
        for (EffectiveContractSnapshot snapshot
                : bundle.effectiveContractSnapshots()) {
            if (!EffectiveContractSnapshotConstants.Role.PROCESS_EMBEDDED
                    .equals(snapshot.role())) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException(
                        "Effective Root contains multiple Process Embedded snapshots");
            }
            found = snapshot;
        }
        if (found == null) {
            throw new IllegalStateException(
                    "Effective Process Embedded declaration lacks its exact snapshot");
        }
        return found;
    }

    static final class Surface {
        private final FrozenNode selectedRoot;
        private final FrozenNode resolvedRoot;
        private final ContractBundle bundle;

        Surface(
                FrozenNode selectedRoot,
                FrozenNode resolvedRoot,
                ContractBundle bundle) {
            this.selectedRoot = Objects.requireNonNull(
                    selectedRoot, "selectedRoot");
            this.resolvedRoot = Objects.requireNonNull(
                    resolvedRoot, "resolvedRoot");
            this.bundle = Objects.requireNonNull(bundle, "bundle");
        }

        FrozenNode selectedRoot() {
            return selectedRoot;
        }

        FrozenNode resolvedRoot() {
            return resolvedRoot;
        }

        ContractBundle bundle() {
            return bundle;
        }
    }
}
