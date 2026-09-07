package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Invocation-owned writer for deterministic conformance evidence.
 *
 * <p>The recorder is deliberately independent from operational observations:
 * only semantic demands, contract snapshots, semantic trace records, and the
 * already-admitted gas prefix can appear in the published trace.</p>
 */
final class ProcessingConformanceRecorder {

    private final ProcessingConformanceTrace.Builder trace =
            new ProcessingConformanceTrace.Builder();
    private final GasMeter gasMeter;
    private final ProcessingSnapshotManager snapshotManager;

    ProcessingConformanceRecorder(
            GasMeter gasMeter,
            ProcessingSnapshotManager snapshotManager) {
        this.gasMeter = Objects.requireNonNull(gasMeter, "gasMeter");
        this.snapshotManager = snapshotManager;
    }

    ProcessingConformanceTrace snapshot() {
        return trace.build(gasMeter.trace());
    }

    void semanticDemand(String demand) {
        trace.semanticDemand(demand);
    }

    void selectedExecutableBodyDemand(
            FrozenNode body,
            String scopePath,
            String contractKey,
            String logicalPath) {
        if (body == null) {
            return;
        }
        String bodyBlueId = CanonicalIdentityEvidence.executableBodyBlueId(
                body.toNode(),
                snapshotManager,
                "Selected executable body at " + logicalPath
                        + " for contract '" + contractKey + "'");
        semanticDemand(bodyBlueId);
    }

    void patchSemanticDemands(String patchPath) {
        List<String> segments = JsonPointer.split(
                PointerUtils.normalizePointer(patchPath));
        for (int count = 1; count < segments.size(); count++) {
            semanticDemand(JsonPointer.toPointer(
                    segments.subList(0, count)));
        }
    }

    void contractSnapshot(EffectiveContractSnapshot snapshot) {
        trace.contractSnapshot(snapshot);
    }

    void record(
            ProcessingTraceRecord.Kind kind,
            String scopePath,
            String contractKey,
            String logicalPath,
            Map<String, ?> details,
            Node node) {
        trace.record(kind, scopePath, contractKey, logicalPath, details, node);
    }

    void record(
            ProcessingTraceRecord.Kind kind,
            String scopePath,
            String contractKey,
            String logicalPath) {
        trace.record(kind, scopePath, contractKey, logicalPath);
    }
}
