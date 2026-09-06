package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;
import java.util.Objects;

/** Exact, gas-free root and retained-dispatch classification for managed routing. */
final class ManagedRootRouteClassifier {
    private ManagedRootRouteClassifier() { }

    static ContractBundle classifyRetainedDispatch(ProcessorInvocationServices owner, Node contracts) {
        // These are the already resolved contract nodes from the authenticated
        // pre-patch bundle, not a newly authored document to resolve or normalize.
        FrozenNode retained = FrozenNode.fromResolvedNode(new Node().contracts(contracts));
        return owner.contractLoader().load(retained, retained, JsonPointer.ROOT,
                owner.observer(), null, null);
    }

    static RootSurfaceView classifyRootSurface(ProcessorInvocationServices owner, ProcessingGasContext sharedGasContext, Node exactDocument) {
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
        ProcessorMarkerStore.collapseInitializationDocuments(document);
        long gasBefore = sharedGasContext.meter().totalGas();
        DocumentProcessingRuntime view = new DocumentProcessingRuntime(
                document,
                owner.conformanceEngine(),
                owner.conformancePlannerOverride(),
                owner.snapshotManager(),
                owner.observer(),
                sharedGasContext,
                owner.registry().executableBodyFieldsByType(),
                owner.strictPlatformInvocation());
        FrozenNode selected = view.selectedFrozenAt(JsonPointer.ROOT);
        owner.contractLoader().preflightSelectedContractHeaders(selected);
        FrozenNode resolved = view.resolvedFrozenAt(JsonPointer.ROOT);
        FrozenNode recognition = view.contractRecognitionScope(
                selected, resolved);
        ContractBundle bundle = owner.contractLoader().load(
                selected,
                recognition,
                JsonPointer.ROOT,
                owner.observer(),
                null,
                null);
        if (sharedGasContext.meter().totalGas() != gasBefore) {
            throw new IllegalStateException(
                    "Route classification must not charge shared gas");
        }
        return new RootSurfaceView(selected, resolved, bundle);
    }

    static EffectiveContractSnapshot effectiveProcessEmbeddedSnapshot(
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

    /** Immutable selected, resolved, and classified view of one exact Root. */
    static final class RootSurfaceView {
        private final FrozenNode selectedRoot;
        private final FrozenNode resolvedRoot;
        private final ContractBundle bundle;

        RootSurfaceView(
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
