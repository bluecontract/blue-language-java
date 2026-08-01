package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable canonical run record used by conformance and deterministic debug
 * tooling. It is not a public effect log and is not part of PROCESS semantics.
 */
public final class ProcessingConformanceTrace {

    private static final ProcessingConformanceTrace EMPTY =
            new ProcessingConformanceTrace(Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap());

    private final List<GasTraceEntry> gas;
    private final List<String> semanticDemands;
    private final List<ProcessingTraceRecord> records;
    private final Map<String, EffectiveContractSnapshot> contractSnapshots;
    private final Map<ProcessingTraceRecord.Kind, List<ProcessingTraceRecord>> byKind;

    private ProcessingConformanceTrace(List<GasTraceEntry> gas,
                                       List<String> semanticDemands,
                                       List<ProcessingTraceRecord> records,
                                       Map<String, EffectiveContractSnapshot> contractSnapshots) {
        this.gas = Collections.unmodifiableList(new ArrayList<>(gas));
        this.semanticDemands = Collections.unmodifiableList(new ArrayList<>(semanticDemands));
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
        this.contractSnapshots =
                Collections.unmodifiableMap(new LinkedHashMap<>(contractSnapshots));
        Map<ProcessingTraceRecord.Kind, List<ProcessingTraceRecord>> index =
                new EnumMap<>(ProcessingTraceRecord.Kind.class);
        for (ProcessingTraceRecord record : records) {
            index.computeIfAbsent(record.kind(), ignored -> new ArrayList<>()).add(record);
        }
        Map<ProcessingTraceRecord.Kind, List<ProcessingTraceRecord>> frozen =
                new EnumMap<>(ProcessingTraceRecord.Kind.class);
        for (Map.Entry<ProcessingTraceRecord.Kind, List<ProcessingTraceRecord>> entry
                : index.entrySet()) {
            frozen.put(entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.byKind = Collections.unmodifiableMap(frozen);
    }

    /**
     * Returns the shared trace instance representing an execution with no entries.
     *
     * @return shared empty immutable trace
     */
    public static ProcessingConformanceTrace empty() {
        return EMPTY;
    }

    /**
     * Returns the gas entries recorded in admission order.
     *
     * @return immutable ordered gas entries
     */
    public List<GasTraceEntry> gas() {
        return gas;
    }

    /**
     * Returns semantic evidence demands in first-demand order.
     *
     * <p>Each demand is either an exact BlueId or a canonical logical demand
     * path.</p>
     *
     * @return immutable ordered demands
     */
    public List<String> semanticDemands() {
        return semanticDemands;
    }

    /**
     * Returns all semantic trace records in encounter order.
     *
     * @return immutable ordered semantic trace records
     */
    public List<ProcessingTraceRecord> records() {
        return records;
    }

    /**
     * Selects records of one kind without changing encounter order.
     *
     * @param kind record kind
     * @return immutable matching records
     */
    public List<ProcessingTraceRecord> records(ProcessingTraceRecord.Kind kind) {
        List<ProcessingTraceRecord> selected = byKind.get(kind);
        return selected != null ? selected : Collections.emptyList();
    }

    /**
     * Returns the effective contract snapshots indexed by deterministic location.
     *
     * @return immutable map of deterministic locations to contract snapshots
     */
    public Map<String, EffectiveContractSnapshot> contractSnapshots() {
        return contractSnapshots;
    }

    /**
     * Sums admitted quantity for one qualified gas counter, saturating on overflow.
     *
     * @param namespace counter namespace
     * @param counter counter name
     * @return saturated admitted quantity
     */
    public long counterQuantity(String namespace, String counter) {
        long quantity = 0L;
        for (GasTraceEntry entry : gas) {
            if (entry.namespace().equals(namespace) && entry.counter().equals(counter)) {
                if (Long.MAX_VALUE - quantity < entry.quantity()) {
                    return Long.MAX_VALUE;
                }
                quantity += entry.quantity();
            }
        }
        return quantity;
    }

    static final class Builder {
        private final Set<String> semanticDemands = new LinkedHashSet<>();
        private final List<ProcessingTraceRecord> records = new ArrayList<>();
        private final Map<String, EffectiveContractSnapshot> contractSnapshots =
                new LinkedHashMap<>();

        void semanticDemand(String demand) {
            if (demand != null && !demand.isEmpty()) {
                semanticDemands.add(demand);
            }
        }

        void contractSnapshot(EffectiveContractSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            contractSnapshots.put(snapshot.scopePath() + "/" + snapshot.key(), snapshot);
        }

        void record(ProcessingTraceRecord.Kind kind,
                    String scopePath,
                    String contractKey,
                    String logicalPath,
                    Map<String, ?> details,
                    Node node) {
            Map<String, String> normalized = new LinkedHashMap<>();
            if (details != null) {
                for (Map.Entry<String, ?> entry : details.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        normalized.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
            records.add(new ProcessingTraceRecord(records.size(),
                    kind,
                    scopePath,
                    contractKey,
                    logicalPath,
                    normalized,
                    node));
        }

        void record(ProcessingTraceRecord.Kind kind,
                    String scopePath,
                    String contractKey,
                    String logicalPath) {
            record(kind, scopePath, contractKey, logicalPath,
                    Collections.emptyMap(), null);
        }

        ProcessingConformanceTrace build(List<GasTraceEntry> gasTrace) {
            return new ProcessingConformanceTrace(
                    gasTrace,
                    new ArrayList<>(semanticDemands),
                    records,
                    contractSnapshots);
        }
    }
}
