package blue.language.processor;

import blue.language.utils.UncheckedObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes the release runtime-trace scenarios and writes their observations.
 *
 * <p>The report is built from the traces and failures produced by the live
 * runtime classes. No expected trace size is copied into the report as an
 * observed result.</p>
 */
public final class RuntimeTraceEvidenceCli {

    private static final String SCHEMA_VERSION =
            "blue-language-java-runtime-trace-evidence/1.0";
    private static final String MEMBER_VISIT_NAMESPACE =
            "member-visits";
    private static final String COUNTER_MEMBER_VISITED =
            "compositeMemberVisited";
    private static final String COUNTER_HEADER_READ =
            "timelineHeaderRead";
    private static final String COUNTER_TIMELINE_COMPARED =
            "timelineBindingCompared";
    private static final String COUNTER_ACTOR_COMPARED =
            "actorBindingCompared";
    private static final String[] MEMBER_VISIT_COUNTERS = {
            COUNTER_MEMBER_VISITED,
            COUNTER_HEADER_READ,
            COUNTER_TIMELINE_COMPARED,
            COUNTER_ACTOR_COMPARED
    };
    private static final long UNIT_WEIGHT = 1L;
    private static final int MINIMUM_LONG_TRACE_ENTRIES = 516;
    private static final int BOUNDED_MEMBER_VISITS = 1024;
    private static final int BOUNDED_MEMBER_VISIT_ENTRIES =
            BOUNDED_MEMBER_VISITS
                    * MEMBER_VISIT_COUNTERS.length;
    private static final int ENTRIES_PER_SHARED_NAMESPACE = 160;
    private static final Map<String, Long> MEMBER_VISIT_CATALOG =
            memberVisitCatalog();

    private RuntimeTraceEvidenceCli() {
    }

    /**
     * Runs every required scenario and writes a complete report before
     * returning a failing process status.
     *
     * @param args one output JSON path
     * @throws Exception when the report cannot be written or any scenario
     *                   fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Expected one runtime-trace evidence output path.");
        }

        List<Map<String, Object>> scenarios =
                new ArrayList<>();
        runScenario(
                scenarios,
                "long-trace-success",
                "shouldAdmit516OrderedEntriesForSmallCounterCatalogWhenGasPermits",
                RuntimeTraceEvidenceCli::observeLongTraceSuccess);
        runScenario(
                scenarios,
                "known-entry-gas-exhaustion",
                "shouldRetainExactPrefixAndOmitRejectedChargeAtKnownEntry",
                RuntimeTraceEvidenceCli::observeKnownEntryGasExhaustion);
        runScenario(
                scenarios,
                "bounded-member-visits",
                "shouldAdmitAllChargesFor1024BoundedMemberVisitsWhenGasPermits",
                RuntimeTraceEvidenceCli::observeBoundedMemberVisits);
        runScenario(
                scenarios,
                "counter-catalog-overflow",
                "shouldRejectCounterCatalogLimitBeforeAdmission",
                RuntimeTraceEvidenceCli::observeCounterCatalogOverflow);
        runScenario(
                scenarios,
                "combined-multiple-namespaces",
                "shouldAdmitMoreThan256CombinedEntriesAcrossValidNamespaces",
                RuntimeTraceEvidenceCli::observeMultipleNamespaces);
        runScenario(
                scenarios,
                "deterministic-namespace-order",
                "shouldVerifySeveralNamespacesReserveLiveBudgetAndMergeCanonically",
                RuntimeTraceEvidenceCli::observeDeterministicNamespaceOrder);
        runScenario(
                scenarios,
                "deterministic-failure-retention",
                "shouldRetainLongExactPrefixAfterDeterministicRuntimeFailure",
                RuntimeTraceEvidenceCli::observeDeterministicFailureRetention);
        runScenario(
                scenarios,
                "transient-suspension-discard",
                "shouldDiscardLongStagedPortablePrefixAfterTransientSuspension",
                RuntimeTraceEvidenceCli::observeTransientSuspensionDiscard);

        int passed = 0;
        int maximumObservedOrderedEntries = 0;
        List<Map<String, Object>> failures =
                new ArrayList<>();
        for (Map<String, Object> scenario : scenarios) {
            if ("PASS".equals(scenario.get("status"))) {
                passed++;
            } else {
                Map<String, Object> failure =
                        new LinkedHashMap<>();
                failure.put("id", scenario.get("id"));
                failure.put(
                        "diagnostic",
                        scenario.get("diagnostic"));
                failures.add(failure);
            }
            Object observed =
                    scenario.get("observedOrderedEntries");
            if (observed instanceof Number) {
                maximumObservedOrderedEntries =
                        Math.max(
                                maximumObservedOrderedEntries,
                                ((Number) observed).intValue());
            }
        }

        int failed = scenarios.size() - passed;
        Map<String, Object> summary =
                new LinkedHashMap<>();
        summary.put("executed", scenarios.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("skipped", 0);
        summary.put(
                "minimumRequiredOrderedEntries",
                MINIMUM_LONG_TRACE_ENTRIES);
        summary.put(
                "maximumObservedOrderedEntries",
                maximumObservedOrderedEntries);
        summary.put("conformant", failed == 0);

        Map<String, Object> report =
                new LinkedHashMap<>();
        report.put("schemaVersion", SCHEMA_VERSION);
        report.put("sourceTask", ":runtimeTraceEvidence");
        report.put(
                "runtimeClass",
                RuntimeWorkSession.class.getName());
        report.put(
                "orderedEntryBound",
                "strictly-positive-counter-weights-and-live-parent-gas");
        report.put("scenarios", scenarios);
        report.put("failures", failures);
        report.put("summary", summary);

        writeReport(Paths.get(args[0]), report);
        if (failed != 0) {
            throw new AssertionError(
                    "Runtime trace evidence has "
                            + failed + " failing scenario(s); see "
                            + args[0]);
        }
    }

    private static Map<String, Object> observeLongTraceSuccess() {
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);

        chargeMemberVisitEntries(
                ledger,
                MINIMUM_LONG_TRACE_ENTRIES);
        session.submit(ledger);
        session.complete();
        List<GasTraceEntry> trace = parent.trace();

        requireExactMemberVisitPrefix(
                trace,
                MEMBER_VISIT_NAMESPACE,
                MINIMUM_LONG_TRACE_ENTRIES);
        requireEquals(
                MINIMUM_LONG_TRACE_ENTRIES,
                parent.totalGas(),
                "long trace admitted gas");
        require(!session.isOpen(), "completed session remained open");

        Map<String, Object> result =
                observation(trace.size());
        result.put("distinctCounterKinds", MEMBER_VISIT_CATALOG.size());
        result.put("admittedGas", parent.totalGas());
        result.put("exactOrderVerified", true);
        return result;
    }

    private static Map<String, Object> observeKnownEntryGasExhaustion() {
        int admittedEntries =
                MINIMUM_LONG_TRACE_ENTRIES - 1;
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                admittedEntries);
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);
        chargeMemberVisitEntries(
                ledger,
                admittedEntries);
        String rejectedReason =
                memberVisitReason(
                        admittedEntries,
                        memberVisitCounter(
                                admittedEntries));

        GasLimitExceededException rejected =
                capture(
                        GasLimitExceededException.class,
                        () -> chargeMemberVisitEntry(
                                ledger,
                                admittedEntries));
        List<GasTraceEntry> staged =
                session.stagedTrace();
        capture(
                IllegalStateException.class,
                () -> chargeMemberVisitEntry(
                        ledger,
                        admittedEntries + 1));
        GasLimitExceededException propagated =
                capture(
                        GasLimitExceededException.class,
                        () -> session.propagateGasExhaustion(
                                RuntimeGasExhaustion.from(
                                        rejected)));
        List<GasTraceEntry> committed =
                parent.trace();

        require(
                rejected == propagated,
                "gas rejection was not propagated canonically");
        requireEquals(
                admittedEntries,
                rejected.admittedGas(),
                "rejection admitted gas");
        requireEquals(
                admittedEntries,
                rejected.effectiveBudget(),
                "rejection effective budget");
        requireExactMemberVisitPrefix(
                staged,
                MEMBER_VISIT_NAMESPACE,
                admittedEntries);
        requireExactMemberVisitPrefix(
                committed,
                MEMBER_VISIT_NAMESPACE,
                admittedEntries);
        require(
                !containsReason(
                        committed,
                        rejectedReason),
                "rejected charge entered the committed trace");
        require(!session.isOpen(), "exhausted session remained open");

        Map<String, Object> result =
                observation(committed.size());
        result.put("gasBudget", parent.gasLimit());
        result.put("admittedPrefixEntries", committed.size());
        result.put("rejectedEntryIndex", admittedEntries);
        result.put("rejectedChargeAbsent", true);
        result.put("laterWorkPrevented", true);
        result.put("exactPrefixVerified", true);
        return result;
    }

    private static Map<String, Object> observeBoundedMemberVisits() {
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);

        chargeMemberVisitEntries(
                ledger,
                BOUNDED_MEMBER_VISIT_ENTRIES);
        session.submit(ledger);
        session.complete();
        List<GasTraceEntry> trace = parent.trace();

        requireExactMemberVisitPrefix(
                trace,
                MEMBER_VISIT_NAMESPACE,
                BOUNDED_MEMBER_VISIT_ENTRIES);
        Map<String, Integer> observedCounters =
                new LinkedHashMap<>();
        for (String counter : MEMBER_VISIT_COUNTERS) {
            int count = countCounter(trace, counter);
            requireEquals(
                    BOUNDED_MEMBER_VISITS,
                    count,
                    "bounded visit counter " + counter);
            observedCounters.put(counter, count);
        }

        Map<String, Object> result =
                observation(trace.size());
        result.put("boundedMemberVisits", BOUNDED_MEMBER_VISITS);
        result.put("counterOccurrences", observedCounters);
        result.put("admittedGas", parent.totalGas());
        result.put("exactOrderVerified", true);
        return result;
    }

    private static Map<String, Object> observeCounterCatalogOverflow() {
        int limit = (int) GasSchedule.contracts10()
                .portableLimit(
                        GasScheduleConstants.PortableLimit
                                .RUNTIME_CHILD_LEDGER_COUNTER_KINDS);
        Map<String, Long> oversizedCatalog =
                new LinkedHashMap<>();
        for (int index = 0; index <= limit; index++) {
            oversizedCatalog.put(
                    "counter-" + index,
                    UNIT_WEIGHT);
        }
        RuntimeWorkSession session =
                processing(new GasMeter());

        PortableLimitExceededException rejection =
                capture(
                        PortableLimitExceededException.class,
                        () -> session.openLedger(
                                "catalog-overflow",
                                oversizedCatalog));
        session.suspend();

        requireEquals(
                ProcessorErrorCategory.RuntimeLedgerLimitExceeded,
                rejection.diagnostic().category(),
                "catalog rejection category");
        requireEquals(
                limit + 1L,
                rejection.observed(),
                "catalog observed counter kinds");
        requireEquals(
                limit,
                rejection.limit(),
                "catalog counter-kind limit");

        Map<String, Object> result =
                observation(0);
        result.put("portableLimitName", rejection.limitName());
        result.put("counterKindsObserved", rejection.observed());
        result.put("counterKindLimit", rejection.limit());
        result.put(
                "failureCategory",
                rejection.diagnostic().category().name());
        result.put("rejectedBeforeAdmission", true);
        return result;
    }

    private static Map<String, Object> observeMultipleNamespaces() {
        String alphaNamespace = "alpha-runtime";
        String zetaNamespace = "zeta-runtime";
        String counter = "step";
        Map<String, Long> catalog =
                Collections.singletonMap(
                        counter,
                        UNIT_WEIGHT);
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger zeta =
                session.openLedger(
                        zetaNamespace,
                        catalog);
        GasMeter.ChildGasLedger alpha =
                session.openLedger(
                        alphaNamespace,
                        catalog);

        chargeRepeatedEntries(
                zeta,
                counter,
                zetaNamespace,
                ENTRIES_PER_SHARED_NAMESPACE);
        chargeRepeatedEntries(
                alpha,
                counter,
                alphaNamespace,
                ENTRIES_PER_SHARED_NAMESPACE);
        session.submit(zeta);
        session.submit(alpha);
        session.complete();
        List<GasTraceEntry> trace = parent.trace();

        requireEquals(
                ENTRIES_PER_SHARED_NAMESPACE * 2,
                trace.size(),
                "combined namespace entries");
        requireNamespaceBlock(
                trace,
                0,
                ENTRIES_PER_SHARED_NAMESPACE,
                alphaNamespace);
        requireNamespaceBlock(
                trace,
                ENTRIES_PER_SHARED_NAMESPACE,
                ENTRIES_PER_SHARED_NAMESPACE * 2,
                zetaNamespace);

        Map<String, Object> result =
                observation(trace.size());
        result.put("namespaceCount", 2);
        result.put(
                "namespaceOrder",
                java.util.Arrays.asList(
                        alphaNamespace,
                        zetaNamespace));
        result.put("admittedGas", parent.totalGas());
        result.put("combinedEntriesExceed256", trace.size() > 256);
        return result;
    }

    private static Map<String, Object> observeDeterministicNamespaceOrder() {
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        Map<String, Long> catalog =
                Collections.singletonMap(
                        "step",
                        UNIT_WEIGHT);
        GasMeter.ChildGasLedger zeta =
                session.openLedger("zeta", catalog);
        GasMeter.ChildGasLedger alpha =
                session.openLedger("alpha", catalog);
        zeta.charge(
                "step",
                1L,
                GasChargeContext.reason("zeta-first"));
        alpha.charge(
                "step",
                1L,
                GasChargeContext.reason("alpha-second"));

        session.submit(zeta);
        session.submit(alpha);
        session.complete();
        List<GasTraceEntry> trace = parent.trace();

        requireEquals(2, trace.size(), "namespace ordering trace size");
        requireEquals(
                "alpha",
                trace.get(0).namespace(),
                "first canonical namespace");
        requireEquals(
                "zeta",
                trace.get(1).namespace(),
                "second canonical namespace");
        requireEquals(
                "alpha-second",
                trace.get(0).reason(),
                "alpha local trace entry");
        requireEquals(
                "zeta-first",
                trace.get(1).reason(),
                "zeta local trace entry");

        Map<String, Object> result =
                observation(trace.size());
        result.put(
                "openedOrder",
                java.util.Arrays.asList("zeta", "alpha"));
        result.put(
                "submittedOrder",
                java.util.Arrays.asList("zeta", "alpha"));
        result.put(
                "observedNamespaceOrder",
                java.util.Arrays.asList("alpha", "zeta"));
        result.put("canonicalOrderVerified", true);
        return result;
    }

    private static Map<String, Object>
    observeDeterministicFailureRetention() {
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);

        chargeMemberVisitEntries(
                ledger,
                MINIMUM_LONG_TRACE_ENTRIES);
        List<GasTraceEntry> staged =
                session.stagedTrace();
        session.failDeterministically();
        List<GasTraceEntry> retained =
                parent.trace();

        requireExactMemberVisitPrefix(
                staged,
                MEMBER_VISIT_NAMESPACE,
                MINIMUM_LONG_TRACE_ENTRIES);
        requireExactMemberVisitPrefix(
                retained,
                MEMBER_VISIT_NAMESPACE,
                MINIMUM_LONG_TRACE_ENTRIES);
        requireEquals(
                staged.size(),
                retained.size(),
                "deterministic failure retained prefix");
        require(!session.isOpen(), "failed session remained open");

        Map<String, Object> result =
                observation(retained.size());
        result.put("stagedPrefixEntries", staged.size());
        result.put("retainedPrefixEntries", retained.size());
        result.put("exactPrefixRetained", true);
        result.put("admittedGas", parent.totalGas());
        return result;
    }

    private static Map<String, Object>
    observeTransientSuspensionDiscard() {
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);

        chargeMemberVisitEntries(
                ledger,
                MINIMUM_LONG_TRACE_ENTRIES);
        session.submit(ledger);
        List<GasTraceEntry> staged =
                session.stagedTrace();
        session.suspend();
        List<GasTraceEntry> committed =
                parent.trace();

        requireExactMemberVisitPrefix(
                staged,
                MEMBER_VISIT_NAMESPACE,
                MINIMUM_LONG_TRACE_ENTRIES);
        require(
                committed.isEmpty(),
                "transient suspension committed portable trace");
        requireEquals(
                0L,
                parent.totalGas(),
                "transient suspension committed gas");
        requireEquals(
                parent.gasLimit(),
                parent.remainingGas(),
                "transient suspension restored gas budget");
        require(!session.isOpen(), "suspended session remained open");

        Map<String, Object> result =
                observation(staged.size());
        result.put("stagedPrefixEntries", staged.size());
        result.put("committedEntries", committed.size());
        result.put("committedGas", parent.totalGas());
        result.put("remainingGas", parent.remainingGas());
        result.put("portableTraceDiscarded", true);
        return result;
    }

    private static void runScenario(
            List<Map<String, Object>> scenarios,
            String id,
            String sourceTest,
            Scenario scenario) {
        Map<String, Object> result =
                new LinkedHashMap<>();
        result.put("id", id);
        result.put(
                "sourceTest",
                RuntimeWorkSessionTest.class.getName()
                        + "#" + sourceTest);
        try {
            result.putAll(scenario.observe());
            result.put("status", "PASS");
            result.put("diagnostic", null);
        } catch (Throwable failure) {
            result.putIfAbsent("observedOrderedEntries", 0);
            result.put("status", "FAIL");
            result.put(
                    "diagnostic",
                    failure.getClass().getName()
                            + ": "
                            + String.valueOf(
                            failure.getMessage()));
        }
        scenarios.add(result);
    }

    private static Map<String, Object> observation(
            int observedOrderedEntries) {
        Map<String, Object> observation =
                new LinkedHashMap<>();
        observation.put(
                "observedOrderedEntries",
                observedOrderedEntries);
        return observation;
    }

    private static RuntimeWorkSession processing(
            GasMeter parent) {
        return new RuntimeWorkSession(
                parent,
                RuntimeWorkSession.Mode.PROCESSING);
    }

    private static Map<String, Long> memberVisitCatalog() {
        Map<String, Long> catalog =
                new LinkedHashMap<>();
        for (String counter : MEMBER_VISIT_COUNTERS) {
            catalog.put(counter, UNIT_WEIGHT);
        }
        return Collections.unmodifiableMap(catalog);
    }

    private static void chargeMemberVisitEntries(
            GasMeter.ChildGasLedger ledger,
            int entryCount) {
        for (int entryIndex = 0;
             entryIndex < entryCount;
             entryIndex++) {
            chargeMemberVisitEntry(
                    ledger,
                    entryIndex);
        }
    }

    private static void chargeMemberVisitEntry(
            GasMeter.ChildGasLedger ledger,
            int entryIndex) {
        String counter =
                memberVisitCounter(entryIndex);
        ledger.charge(
                counter,
                1L,
                GasChargeContext.reason(
                        memberVisitReason(
                                entryIndex,
                                counter)));
    }

    private static String memberVisitCounter(
            int entryIndex) {
        return MEMBER_VISIT_COUNTERS[
                entryIndex
                        % MEMBER_VISIT_COUNTERS.length];
    }

    private static String memberVisitReason(
            int entryIndex,
            String counter) {
        int visitIndex =
                entryIndex
                        / MEMBER_VISIT_COUNTERS.length;
        return "visit-" + visitIndex
                + ":" + counter;
    }

    private static void chargeRepeatedEntries(
            GasMeter.ChildGasLedger ledger,
            String counter,
            String reasonPrefix,
            int entryCount) {
        for (int index = 0;
             index < entryCount;
             index++) {
            ledger.charge(
                    counter,
                    1L,
                    GasChargeContext.reason(
                            reasonPrefix + "-" + index));
        }
    }

    private static void requireExactMemberVisitPrefix(
            List<GasTraceEntry> trace,
            String namespace,
            int entryCount) {
        requireEquals(
                entryCount,
                trace.size(),
                "member-visit trace size");
        for (int entryIndex = 0;
             entryIndex < entryCount;
             entryIndex++) {
            String counter =
                    memberVisitCounter(entryIndex);
            GasTraceEntry entry =
                    trace.get(entryIndex);
            requireEquals(
                    entryIndex,
                    entry.sequence(),
                    "trace sequence " + entryIndex);
            requireEquals(
                    namespace,
                    entry.namespace(),
                    "trace namespace " + entryIndex);
            requireEquals(
                    counter,
                    entry.counter(),
                    "trace counter " + entryIndex);
            requireEquals(
                    1L,
                    entry.quantity(),
                    "trace quantity " + entryIndex);
            requireEquals(
                    UNIT_WEIGHT,
                    entry.weight(),
                    "trace weight " + entryIndex);
            requireEquals(
                    UNIT_WEIGHT,
                    entry.subtotal(),
                    "trace subtotal " + entryIndex);
            requireEquals(
                    memberVisitReason(
                            entryIndex,
                            counter),
                    entry.reason(),
                    "trace reason " + entryIndex);
        }
    }

    private static void requireNamespaceBlock(
            List<GasTraceEntry> trace,
            int start,
            int end,
            String namespace) {
        for (int index = start;
             index < end;
             index++) {
            requireEquals(
                    namespace,
                    trace.get(index).namespace(),
                    "namespace block " + index);
        }
    }

    private static boolean containsReason(
            List<GasTraceEntry> trace,
            String reason) {
        for (GasTraceEntry entry : trace) {
            if (reason.equals(entry.reason())) {
                return true;
            }
        }
        return false;
    }

    private static int countCounter(
            List<GasTraceEntry> trace,
            String counter) {
        int count = 0;
        for (GasTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                count++;
            }
        }
        return count;
    }

    private static <T extends Throwable> T capture(
            Class<T> expected,
            ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return expected.cast(failure);
            }
            throw new EvidenceFailure(
                    "Expected "
                            + expected.getName()
                            + " but caught "
                            + failure.getClass().getName(),
                    failure);
        }
        throw new EvidenceFailure(
                "Expected " + expected.getName()
                        + " but no failure was thrown.");
    }

    private static void require(
            boolean condition,
            String message) {
        if (!condition) {
            throw new EvidenceFailure(message);
        }
    }

    private static void requireEquals(
            Object expected,
            Object actual,
            String description) {
        boolean equal;
        if (expected instanceof Number
                && actual instanceof Number) {
            equal = new BigDecimal(expected.toString())
                    .compareTo(
                            new BigDecimal(
                                    actual.toString()))
                    == 0;
        } else {
            equal = expected == null
                    ? actual == null
                    : expected.equals(actual);
        }
        if (!equal) {
            throw new EvidenceFailure(
                    description
                            + ": expected "
                            + expected
                            + " but observed "
                            + actual);
        }
    }

    private static void writeReport(
            Path output,
            Map<String, Object> report)
            throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = UncheckedObjectMapper.JSON_MAPPER
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(report)
                + "\n";
        Files.write(
                output,
                json.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface Scenario {
        Map<String, Object> observe()
                throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run()
                throws Exception;
    }

    private static final class EvidenceFailure
            extends RuntimeException {

        private EvidenceFailure(String message) {
            super(message);
        }

        private EvidenceFailure(
                String message,
                Throwable cause) {
            super(message, cause);
        }
    }
}
