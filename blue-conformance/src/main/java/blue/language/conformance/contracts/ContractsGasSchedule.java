package blue.language.conformance.contracts;

import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.SemanticGasMeter;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manifest-driven evaluator for the Contracts 1.0 gas microfixtures.
 *
 * <p>Construction binds the conformance manifest to the production
 * {@link GasSchedule}. Evaluation uses production meters and returns actual
 * admitted traces; fixture expectations are never read while computing those
 * results.</p>
 */
final class ContractsGasSchedule {

    /** Counter used by the fixture-only child ledger for raw admitted units. */
    private static final String FIXTURE_UNIT_COUNTER = "fixtureUnit";
    private static final String MANIFEST_TYPE =
            "blue-contracts-gas-manifest";
    private static final String MANIFEST_SCHEDULE =
            "blue-contracts/gas/1.0";
    private static final String MANIFEST_SPECIFICATION_VERSION = "1.0";
    private static final String ADMISSION_RULE_PREFIX =
            "Admit quantity * weight before the corresponding logical work.";

    private final String schedule;
    private final long maxProcessGas;
    private final Map<String, Map<String, Long>> weights;
    private final JsonNode manifest;
    private final GasSchedule productionSchedule;

    /**
     * Loads and validates the bound gas manifest against production metadata.
     *
     * @throws IllegalStateException when the manifest is missing, malformed,
     *         or inconsistent with the production schedule
     */
    public ContractsGasSchedule() {
        this.manifest = loadYaml(BlueContractsConformanceReport.GAS_MANIFEST_RESOURCE);
        validateEnvelope(manifest);
        this.schedule = manifest.path(
                GasScheduleConstants.ManifestField.SCHEDULE).asText();
        this.maxProcessGas = manifest.path(
                GasScheduleConstants.ManifestField.MAX_PROCESS_GAS)
                .asLong();
        this.weights = Collections.unmodifiableMap(loadWeights(
                manifest.path(
                        GasScheduleConstants.ManifestField.NAMESPACES)));
        this.productionSchedule = GasSchedule.contracts10();
        if (!schedule.equals(productionSchedule.schedule())
                || !BlueContractsConformanceReport.CONTRACTS_GAS_PACKAGE_IDENTITY.equals(
                productionSchedule.packageIdentity())
                || maxProcessGas != productionSchedule.maxProcessGas()
                || !weights.equals(productionSchedule.namespaces())) {
            throw new IllegalStateException(
                    "Production GasSchedule does not match the bound Contracts manifest");
        }
    }

    /**
     * Returns the stable schedule name.
     *
     * @return stable bound schedule name
     */
    public String schedule() {
        return schedule;
    }

    /**
     * Returns the manifest's maximum PROCESS gas budget.
     *
     * @return maximum PROCESS gas declared by the bound manifest
     */
    public long maxProcessGas() {
        return maxProcessGas;
    }

    /**
     * Returns the complete manifest counter-weight catalog.
     *
     * @return deeply unmodifiable namespace and counter weight catalog
     */
    public Map<String, Map<String, Long>> weights() {
        return weights;
    }

    /**
     * Looks up an exact manifest counter weight.
     *
     * @param namespace exact gas namespace
     * @param counter exact counter name
     * @return non-negative unit weight
     * @throws IllegalArgumentException when the qualified counter is unknown
     */
    public long weight(String namespace, String counter) {
        Map<String, Long> counters = weights.get(namespace);
        if (counters == null || !counters.containsKey(counter)) {
            throw new IllegalArgumentException(
                    "Unknown Contracts gas counter: " + namespace + "." + counter);
        }
        return counters.get(counter);
    }

    /**
     * Returns every manifest counter as {@code namespace.counter}.
     *
     * @return immutable qualified counter set in manifest order
     */
    public Set<String> qualifiedCounters() {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Long>> namespace : weights.entrySet()) {
            for (String counter : namespace.getValue().keySet()) {
                result.add(namespace.getKey() + "." + counter);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Checks that every qualified counter has exactly one named microfixture.
     *
     * @param fixtures fixture collection to inspect without modification
     * @return {@code true} only for exact one-to-one counter coverage
     */
    public boolean hasCompleteMicrofixtureCoverage(Iterable<JsonNode> fixtures) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (JsonNode fixture : fixtures) {
            JsonNode input = fixture.path(
                    ContractsFixtureConstants.Field.INPUT);
            if (!ContractsFixtureConstants.Operation.GAS_MICRO.equals(
                    fixture.path(
                            ContractsFixtureConstants.Field.OPERATION)
                            .asText())
                    || !input.has(
                            ContractsFixtureConstants.Field.NAMESPACE)
                    || !input.has(
                            ContractsFixtureConstants.Field.COUNTER)) {
                continue;
            }
            String key = input.path(
                    ContractsFixtureConstants.Field.NAMESPACE).asText()
                    + "."
                    + input.path(
                            ContractsFixtureConstants.Field.COUNTER)
                    .asText();
            occurrences.put(key, occurrences.containsKey(key) ? occurrences.get(key) + 1 : 1);
        }
        if (!occurrences.keySet().equals(qualifiedCounters())) {
            return false;
        }
        for (Integer count : occurrences.values()) {
            if (count == null || count != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates one standalone gas microfixture through production gas APIs.
     *
     * @param fixture validated {@code gas-micro} fixture
     * @param completeCounterCoverage suite-level coverage fact projected into
     *         the result
     * @return actual gas trace, admitted-prefix data, and derived projection
     * @throws IllegalArgumentException when the fixture is not a gas
     *         microfixture or contains invalid inputs, counters, or arithmetic
     */
    public GasMicroResult evaluate(JsonNode fixture, boolean completeCounterCoverage) {
        if (!ContractsFixtureConstants.Operation.GAS_MICRO.equals(
                fixture.path(
                        ContractsFixtureConstants.Field.OPERATION).asText())) {
            throw new IllegalArgumentException("Not a Contracts gas-micro fixture");
        }
        JsonNode input = fixture.path(
                ContractsFixtureConstants.Field.INPUT);
        GasMicroResult result = new GasMicroResult();
        result.projection.put(
                ContractsFixtureConstants.Projection
                        .MANIFEST_COUNTER_COVERAGE_COMPLETE,
                completeCounterCoverage);

        if (input.has(ContractsFixtureConstants.Field.NAMESPACE)
                || input.has(ContractsFixtureConstants.Field.COUNTER)
                || input.has(ContractsFixtureConstants.Field.QUANTITY)) {
            requireFields(
                    input,
                    ContractsFixtureConstants.Field.NAMESPACE,
                    ContractsFixtureConstants.Field.COUNTER,
                    ContractsFixtureConstants.Field.QUANTITY,
                    ContractsFixtureConstants.Field.WEIGHT_MANIFEST);
            String namespace = input.path(
                    ContractsFixtureConstants.Field.NAMESPACE).asText();
            String counter = input.path(
                    ContractsFixtureConstants.Field.COUNTER).asText();
            String requestedSchedule = input.path(
                    ContractsFixtureConstants.Field.WEIGHT_MANIFEST)
                    .asText();
            if (!schedule.equals(requestedSchedule)) {
                throw new IllegalArgumentException(
                        "Gas fixture requested unbound schedule: " + requestedSchedule);
            }
            long quantity = nonNegative(
                    input.get(ContractsFixtureConstants.Field.QUANTITY),
                    "input.quantity");
            GasMeter meter = new GasMeter(productionSchedule);
            meter.charge(namespace, counter, quantity);
            copyProductionLedger(meter, result);
        }

        if (input.has(ContractsFixtureConstants.Field.LIMIT)
                || input.has(ContractsFixtureConstants.Field.CHARGES)) {
            requireFields(
                    input,
                    ContractsFixtureConstants.Field.LIMIT,
                    ContractsFixtureConstants.Field.CHARGES);
            long limit = nonNegative(
                    input.get(ContractsFixtureConstants.Field.LIMIT),
                    "input.limit");
            if (!input.get(
                    ContractsFixtureConstants.Field.CHARGES).isArray()) {
                throw new IllegalArgumentException("input.charges must be a list");
            }
            GasMeter meter = new GasMeter(productionSchedule, limit);
            Map<String, Long> unitWeight = new LinkedHashMap<>();
            unitWeight.put(FIXTURE_UNIT_COUNTER, 1L);
            GasMeter.ChildGasLedger child = meter.childLedger("fixture-runtime", unitWeight);
            for (JsonNode rawCharge
                    : input.get(ContractsFixtureConstants.Field.CHARGES)) {
                long charge;
                if (rawCharge.isIntegralNumber()) {
                    charge = nonNegative(rawCharge, "input.charges[]");
                } else if (rawCharge.isObject()) {
                    requireFields(
                            rawCharge,
                            ContractsFixtureConstants.Field.COUNTER,
                            ContractsFixtureConstants.Field.QUANTITY);
                    String counter = rawCharge.path(
                            ContractsFixtureConstants.Field.COUNTER)
                            .asText();
                    String namespace = resolveUniqueNamespace(counter);
                    charge = multiplyExact(
                            nonNegative(
                                    rawCharge.get(
                                            ContractsFixtureConstants.Field
                                                    .QUANTITY),
                                    "input.charges[].quantity"),
                            weight(namespace, counter));
                } else {
                    throw new IllegalArgumentException(
                            "input.charges entries must be integers or named charges");
                }
                try {
                    child.charge(FIXTURE_UNIT_COUNTER, charge);
                    result.admitted.add(charge);
                } catch (GasLimitExceededException exhausted) {
                    result.failedChargeAbsent = true;
                    break;
                }
            }
            meter.merge(child);
            copyProductionLedger(meter, result);
        }

        if (input.has(
                ContractsFixtureConstants.Field.DIRECT_CANONICAL_BYTES)) {
            long bytes = nonNegative(input.get(
                            ContractsFixtureConstants.Field
                                    .DIRECT_CANONICAL_BYTES),
                    "input.directCanonicalBytes");
            GasMeter meter = new GasMeter(productionSchedule);
            meter.semantic().directIdentityInput(bytes, GasChargeContext.empty());
            copyProductionLedger(meter, result);
            result.directIdentityHashBlock =
                    counterQuantity(
                            meter,
                            GasScheduleConstants.Namespace.SEMANTIC,
                            GasScheduleConstants.SemanticCounter
                                    .DIRECT_IDENTITY_HASH_BLOCK);
        }
        if (input.has(
                ContractsFixtureConstants.Field.TEXT_CODE_POINTS_EXAMINED)) {
            long codePoints = nonNegative(
                    input.get(
                            ContractsFixtureConstants.Field
                                    .TEXT_CODE_POINTS_EXAMINED),
                    "input.textCodePointsExamined");
            GasMeter meter = new GasMeter(productionSchedule);
            meter.semantic().textCodePointsExamined(codePoints, GasChargeContext.empty());
            copyProductionLedger(meter, result);
            result.textBlockExamined =
                    counterQuantity(
                            meter,
                            GasScheduleConstants.Namespace.SEMANTIC,
                            GasScheduleConstants.SemanticCounter
                                    .TEXT_BLOCK_EXAMINED);
        }
        if (input.has(ContractsFixtureConstants.Field.PROOF_KEY)
                || input.has(ContractsFixtureConstants.Field.USES)) {
            requireFields(
                    input,
                    ContractsFixtureConstants.Field.PROOF_KEY,
                    ContractsFixtureConstants.Field.USES);
            String proofKey = input.path(
                    ContractsFixtureConstants.Field.PROOF_KEY).asText();
            if (proofKey.isEmpty()) {
                throw new IllegalArgumentException("input.proofKey must be non-empty");
            }
            long uses = nonNegative(
                    input.get(ContractsFixtureConstants.Field.USES),
                    "input.uses");
            GasMeter meter = new GasMeter(productionSchedule);
            for (long use = 0L; use < uses; use++) {
                meter.semantic().useValidationProof(proofKey, GasChargeContext.empty());
            }
            copyProductionLedger(meter, result);
            result.validationProofReused =
                    counterQuantity(
                            meter,
                            GasScheduleConstants.Namespace.SEMANTIC,
                            GasScheduleConstants.SemanticCounter
                                    .VALIDATION_PROOF_REUSED);
        }
        if (input.has(ContractsFixtureConstants.Field.LEFT_LIMBS)
                || input.has(ContractsFixtureConstants.Field.RIGHT_LIMBS)
                || input.has(ContractsFixtureConstants.Field.OPERATION)) {
            requireFields(
                    input,
                    ContractsFixtureConstants.Field.LEFT_LIMBS,
                    ContractsFixtureConstants.Field.RIGHT_LIMBS,
                    ContractsFixtureConstants.Field.OPERATION);
            long left = nonNegative(
                    input.get(ContractsFixtureConstants.Field.LEFT_LIMBS),
                    "input.leftLimbs");
            long right = nonNegative(
                    input.get(ContractsFixtureConstants.Field.RIGHT_LIMBS),
                    "input.rightLimbs");
            String operation = input.path(
                    ContractsFixtureConstants.Field.OPERATION).asText();
            if (left == 0L || right == 0L) {
                throw new IllegalArgumentException(
                        "Contracts integer limb operands must be positive");
            }
            final SemanticGasMeter.IntegerOperation formula;
            if (ContractsFixtureConstants.IntegerOperation.MULTIPLY
                    .equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.MULTIPLICATION;
            } else if (ContractsFixtureConstants.IntegerOperation.DIVISION
                    .equals(operation)
                    || ContractsFixtureConstants.IntegerOperation.REMAINDER
                    .equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.DIVISION_OR_REMAINDER;
            } else if (ContractsFixtureConstants.IntegerOperation.GCD
                    .equals(operation)
                    || ContractsFixtureConstants.IntegerOperation.MULTIPLE_OF
                    .equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.GCD_OR_MULTIPLE_OF;
            } else if (ContractsFixtureConstants.IntegerOperation.ADD
                    .equals(operation)
                    || ContractsFixtureConstants.IntegerOperation.SUBTRACT
                    .equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.ADDITION_OR_SUBTRACTION;
            } else if (ContractsFixtureConstants.IntegerOperation.EQUALS
                    .equals(operation)
                    || ContractsFixtureConstants.IntegerOperation.ORDER
                    .equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.EQUALITY_OR_ORDERING;
            } else if (ContractsFixtureConstants.IntegerOperation.LCM
                    .equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.LCM;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported Contracts integer-limb gas operation: " + operation);
            }
            GasMeter meter = new GasMeter(productionSchedule);
            meter.semantic().integerOperation(
                    formula, left, right, GasChargeContext.empty());
            copyProductionLedger(meter, result);
            result.integerLimbOperation =
                    counterQuantity(
                            meter,
                            GasScheduleConstants.Namespace.SEMANTIC,
                            GasScheduleConstants.SemanticCounter
                                    .INTEGER_LIMB_OPERATION);
        }
        if (input.has(ContractsFixtureConstants.Field.REPLACE_INDEX)) {
            requireFields(input, ContractsFixtureConstants.Field.OLD_LENGTH, ContractsFixtureConstants.Field.REPLACE_INDEX);
            long length = nonNegative(input.get(ContractsFixtureConstants.Field.OLD_LENGTH), "input.oldLength");
            long index = nonNegative(input.get(ContractsFixtureConstants.Field.REPLACE_INDEX), "input.replaceIndex");
            GasMeter meter = new GasMeter(productionSchedule);
            meter.semantic().listReplaceAt(length, index, GasChargeContext.empty());
            copyProductionLedger(meter, result);
            result.listFoldStepRecomputed =
                    counterQuantity(
                            meter,
                            GasScheduleConstants.Namespace.SEMANTIC,
                            GasScheduleConstants.SemanticCounter
                                    .LIST_FOLD_STEP_RECOMPUTED);
        } else if (input.has(ContractsFixtureConstants.Field.APPEND)) {
            requireFields(
                    input,
                    ContractsFixtureConstants.Field.OLD_LENGTH,
                    ContractsFixtureConstants.Field.APPEND,
                    ContractsFixtureConstants.Field.PRIOR_EXACT_IDENTITY);
            long length = nonNegative(input.get(ContractsFixtureConstants.Field.OLD_LENGTH), "input.oldLength");
            long appended = nonNegative(
                    input.get(ContractsFixtureConstants.Field.APPEND),
                    "input.append");
            GasMeter meter = new GasMeter(productionSchedule);
            if (input.path(ContractsFixtureConstants.Field.PRIOR_EXACT_IDENTITY).asBoolean(false)) {
                meter.semantic().verifiedListAppend(
                        length, appended, GasChargeContext.empty());
            } else {
                meter.semantic().fullListIdentity(
                        addExact(length, appended), GasChargeContext.empty());
            }
            copyProductionLedger(meter, result);
            result.listFoldStepRecomputed =
                    counterQuantity(
                            meter,
                            GasScheduleConstants.Namespace.SEMANTIC,
                            GasScheduleConstants.SemanticCounter
                                    .LIST_FOLD_STEP_RECOMPUTED);
        }

        result.projection.put(
                ContractsFixtureConstants.Projection.TRACE_NAMED_ENTRIES,
                result.trace);
        result.projection.put("trace.total", "sum(entries)");
        result.projection.put("trace.failedChargePresent", false);
        return result;
    }

    private static long counterQuantity(GasMeter meter,
                                        String namespace,
                                        String counter) {
        long quantity = 0L;
        for (GasTraceEntry entry : meter.trace()) {
            if (namespace.equals(entry.namespace()) && counter.equals(entry.counter())) {
                quantity = addExact(quantity, entry.quantity());
            }
        }
        return quantity;
    }

    private String resolveUniqueNamespace(String counter) {
        String match = null;
        for (Map.Entry<String, Map<String, Long>> namespace : weights.entrySet()) {
            if (namespace.getValue().containsKey(counter)) {
                if (match != null) {
                    throw new IllegalArgumentException(
                            "Ambiguous unqualified Contracts gas counter: " + counter);
                }
                match = namespace.getKey();
            }
        }
        if (match == null) {
            throw new IllegalArgumentException("Unknown Contracts gas counter: " + counter);
        }
        return match;
    }

    private static void copyProductionLedger(GasMeter meter, GasMicroResult result) {
        result.trace.clear();
        for (GasTraceEntry entry : meter.trace()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put(
                    ContractsFixtureConstants.Field.SEQUENCE,
                    entry.sequence());
            value.put(
                    ContractsFixtureConstants.Field.NAMESPACE,
                    entry.namespace());
            value.put(
                    ContractsFixtureConstants.Field.COUNTER,
                    entry.counter());
            value.put(
                    ContractsFixtureConstants.Field.QUANTITY,
                    entry.quantity());
            value.put(
                    ContractsFixtureConstants.Field.WEIGHT,
                    entry.weight());
            value.put(
                    ContractsFixtureConstants.Field.SUBTOTAL,
                    entry.subtotal());
            if (entry.scopePath() != null) {
                value.put(
                        ContractsFixtureConstants.Field.SCOPE_PATH,
                        entry.scopePath());
            }
            if (entry.contractKey() != null) {
                value.put(
                        ContractsFixtureConstants.Field.CONTRACT_KEY,
                        entry.contractKey());
            }
            if (entry.logicalPath() != null) {
                value.put(
                        ContractsFixtureConstants.Field.LOGICAL_PATH,
                        entry.logicalPath());
            }
            if (entry.reason() != null
                    && !entry.reason().isEmpty()
                    && !"unspecified".equals(entry.reason())) {
                value.put(
                        ContractsFixtureConstants.Field.REASON,
                        entry.reason());
            }
            result.trace.add(value);
        }
        result.totalGas = meter.totalGas();
    }

    private static Map<String, Map<String, Long>> loadWeights(JsonNode namespaces) {
        if (!namespaces.isObject()) {
            throw new IllegalStateException("Contracts gas namespaces must be an object");
        }
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = namespaces.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> namespace = it.next();
            JsonNode counters = namespace.getValue().path(
                    GasScheduleConstants.ManifestField.COUNTERS);
            if (!counters.isObject()) {
                throw new IllegalStateException(
                        "Contracts gas namespace has no counter map: " + namespace.getKey());
            }
            Map<String, Long> counterWeights = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> countersIt = counters.fields();
                 countersIt.hasNext(); ) {
                Map.Entry<String, JsonNode> counter = countersIt.next();
                long weight = positive(counter.getValue(),
                        namespace.getKey() + "." + counter.getKey());
                counterWeights.put(counter.getKey(), weight);
            }
            int declaredCount = namespace.getValue().path(
                    GasScheduleConstants.ManifestField.COUNTER_COUNT)
                    .asInt(-1);
            if (declaredCount != counterWeights.size()) {
                throw new IllegalStateException(
                        "Contracts gas counterCount mismatch for " + namespace.getKey());
            }
            result.put(namespace.getKey(), Collections.unmodifiableMap(counterWeights));
        }
        return result;
    }

    private static void validateEnvelope(JsonNode manifest) {
        if (!manifest.isObject()) {
            throw new IllegalStateException("Contracts gas manifest must be an object");
        }
        if (!MANIFEST_TYPE.equals(manifest.path(
                GasScheduleConstants.ManifestField.MANIFEST_TYPE).asText())
                || !MANIFEST_SCHEDULE.equals(manifest.path(
                        GasScheduleConstants.ManifestField.SCHEDULE).asText())
                || !MANIFEST_SPECIFICATION_VERSION.equals(manifest.path(
                        GasScheduleConstants.ManifestField
                                .SPECIFICATION_VERSION).asText())
                || !BlueContractsConformanceReport.CONTRACTS_GAS_PACKAGE_IDENTITY.equals(
                manifest.path(
                        GasScheduleConstants.ManifestField.PACKAGE_IDENTITY)
                        .asText())) {
            throw new IllegalStateException("Contracts gas manifest binding mismatch");
        }
        if (!manifest.path(
                GasScheduleConstants.ManifestField.ADMISSION_RULE).asText()
                .startsWith(ADMISSION_RULE_PREFIX)) {
            throw new IllegalStateException("Contracts gas admission rule mismatch");
        }
    }

    private static JsonNode loadYaml(String resource) {
        ObjectMapper mapper = new ObjectMapper(
                YAMLFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build());
        try (InputStream input = ContractsGasSchedule.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing Contracts gas manifest: " + resource);
            }
            return mapper.readTree(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Contracts gas manifest", ex);
        }
    }

    private static void requireFields(JsonNode object, String... fields) {
        for (String field : fields) {
            if (!object.has(field) || object.get(field).isNull()) {
                throw new IllegalArgumentException("Missing gas-micro input field: " + field);
            }
        }
    }

    private static long nonNegative(JsonNode value, String path) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(path + " must be a non-negative long integer");
        }
        long result = value.asLong();
        if (result < 0L) {
            throw new IllegalArgumentException(path + " must be non-negative");
        }
        return result;
    }

    private static long positive(JsonNode value, String path) {
        long result = nonNegative(value, path);
        if (result == 0L) {
            throw new IllegalArgumentException(
                    path + " must be positive");
        }
        return result;
    }

    private static long addExact(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            throw new IllegalArgumentException("Contracts gas arithmetic overflow");
        }
        return left + right;
    }

    private static long multiplyExact(long left, long right) {
        if (left != 0L && right > Long.MAX_VALUE / left) {
            throw new IllegalArgumentException("Contracts gas arithmetic overflow");
        }
        return left * right;
    }

    /**
     * Mutable result accumulator populated by one gas microfixture evaluation.
     *
     * <p>Callers normally receive a completed instance from
     * {@link #evaluate(JsonNode, boolean)}. A directly constructed instance is
     * an empty result with zero gas and an empty projection.</p>
     */
    public static final class GasMicroResult {
        private final List<Map<String, Object>> trace = new ArrayList<>();
        private final List<Long> admitted = new ArrayList<>();
        private final ContractsConformanceProjection projection =
                new ContractsConformanceProjection();
        private long totalGas;
        private boolean failedChargeAbsent;
        private Long listFoldStepRecomputed;
        private Long textBlockExamined;
        private Long validationProofReused;
        private Long directIdentityHashBlock;
        private Long integerLimbOperation;

        /**
         * Creates an empty gas microfixture result.
         */
        public GasMicroResult() {
        }

        /**
         * Returns admitted trace entries in canonical sequence order.
         *
         * @return unmodifiable trace list
         */
        public List<Map<String, Object>> trace() {
            return Collections.unmodifiableList(trace);
        }

        /**
         * Returns the raw charges admitted before exhaustion.
         *
         * @return unmodifiable sequence of successfully admitted raw charges
         */
        public List<Long> admitted() {
            return Collections.unmodifiableList(admitted);
        }

        /**
         * Returns the projection derived from this result.
         *
         * @return execution-owned mutable projection of derived observables
         */
        public ContractsConformanceProjection projection() {
            return projection;
        }

        /**
         * Returns the final admitted gas total.
         *
         * @return exact gas admitted by the final production ledger
         */
        public long totalGas() {
            return totalGas;
        }

        /**
         * Reports whether the rejected charge was excluded from the trace.
         *
         * @return whether an exhausted charge was absent from the admitted trace
         */
        public boolean failedChargeAbsent() {
            return failedChargeAbsent;
        }

        /**
         * Returns the list-fold recomputation quantity when evaluated.
         *
         * @return list-fold recomputation quantity, or {@code null} when not evaluated
         */
        public Long listFoldStepRecomputed() {
            return listFoldStepRecomputed;
        }

        /**
         * Returns the text-block examination quantity when evaluated.
         *
         * @return text-block examination quantity, or {@code null} when not evaluated
         */
        public Long textBlockExamined() {
            return textBlockExamined;
        }

        /**
         * Returns the validation-proof reuse quantity when evaluated.
         *
         * @return validation-proof reuse quantity, or {@code null} when not evaluated
         */
        public Long validationProofReused() {
            return validationProofReused;
        }

        /**
         * Returns the direct identity hash-block quantity when evaluated.
         *
         * @return direct identity hash-block quantity, or {@code null} when not evaluated
         */
        public Long directIdentityHashBlock() {
            return directIdentityHashBlock;
        }

        /**
         * Returns the integer-limb operation quantity when evaluated.
         *
         * @return integer-limb operation quantity, or {@code null} when not evaluated
         */
        public Long integerLimbOperation() {
            return integerLimbOperation;
        }
    }
}
