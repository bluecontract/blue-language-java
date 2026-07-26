package blue.language.processor.conformance;

import blue.language.BlueContractsConformanceReport;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
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
 */
public final class ContractsGasSchedule {

    private final String schedule;
    private final long maxProcessGas;
    private final Map<String, Map<String, Long>> weights;
    private final JsonNode manifest;
    private final GasSchedule productionSchedule;

    public ContractsGasSchedule() {
        this.manifest = loadYaml(BlueContractsConformanceReport.GAS_MANIFEST_RESOURCE);
        validateEnvelope(manifest);
        this.schedule = manifest.path("schedule").asText();
        this.maxProcessGas = manifest.path("maxProcessGas").asLong();
        this.weights = Collections.unmodifiableMap(loadWeights(manifest.path("namespaces")));
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

    public String schedule() {
        return schedule;
    }

    public long maxProcessGas() {
        return maxProcessGas;
    }

    public Map<String, Map<String, Long>> weights() {
        return weights;
    }

    public long weight(String namespace, String counter) {
        Map<String, Long> counters = weights.get(namespace);
        if (counters == null || !counters.containsKey(counter)) {
            throw new IllegalArgumentException(
                    "Unknown Contracts gas counter: " + namespace + "." + counter);
        }
        return counters.get(counter);
    }

    public Set<String> qualifiedCounters() {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Long>> namespace : weights.entrySet()) {
            for (String counter : namespace.getValue().keySet()) {
                result.add(namespace.getKey() + "." + counter);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public boolean hasCompleteMicrofixtureCoverage(Iterable<JsonNode> fixtures) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (JsonNode fixture : fixtures) {
            JsonNode input = fixture.path("input");
            if (!"gas-micro".equals(fixture.path("operation").asText())
                    || !input.has("namespace")
                    || !input.has("counter")) {
                continue;
            }
            String key = input.path("namespace").asText() + "." + input.path("counter").asText();
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

    public GasMicroResult evaluate(JsonNode fixture, boolean completeCounterCoverage) {
        if (!"gas-micro".equals(fixture.path("operation").asText())) {
            throw new IllegalArgumentException("Not a Contracts gas-micro fixture");
        }
        JsonNode input = fixture.path("input");
        GasMicroResult result = new GasMicroResult();
        result.projection.put("manifest.counterCoverage.complete", completeCounterCoverage);

        if (input.has("namespace") || input.has("counter") || input.has("quantity")) {
            requireFields(input, "namespace", "counter", "quantity", "weightManifest");
            String namespace = input.path("namespace").asText();
            String counter = input.path("counter").asText();
            String requestedSchedule = input.path("weightManifest").asText();
            if (!schedule.equals(requestedSchedule)) {
                throw new IllegalArgumentException(
                        "Gas fixture requested unbound schedule: " + requestedSchedule);
            }
            long quantity = nonNegative(input.get("quantity"), "input.quantity");
            GasMeter meter = new GasMeter(productionSchedule);
            meter.charge(namespace, counter, quantity);
            copyProductionLedger(meter, result);
        }

        if (input.has("limit") || input.has("charges")) {
            requireFields(input, "limit", "charges");
            long limit = nonNegative(input.get("limit"), "input.limit");
            if (!input.get("charges").isArray()) {
                throw new IllegalArgumentException("input.charges must be a list");
            }
            GasMeter meter = new GasMeter(productionSchedule, limit);
            Map<String, Long> unitWeight = new LinkedHashMap<>();
            unitWeight.put("fixtureUnit", 1L);
            GasMeter.ChildGasLedger child = meter.childLedger("fixture-runtime", unitWeight);
            for (JsonNode rawCharge : input.get("charges")) {
                long charge;
                if (rawCharge.isIntegralNumber()) {
                    charge = nonNegative(rawCharge, "input.charges[]");
                } else if (rawCharge.isObject()) {
                    requireFields(rawCharge, "counter", "quantity");
                    String counter = rawCharge.path("counter").asText();
                    String namespace = resolveUniqueNamespace(counter);
                    charge = multiplyExact(
                            nonNegative(rawCharge.get("quantity"), "input.charges[].quantity"),
                            weight(namespace, counter));
                } else {
                    throw new IllegalArgumentException(
                            "input.charges entries must be integers or named charges");
                }
                try {
                    child.charge("fixtureUnit", charge);
                    result.admitted.add(charge);
                } catch (GasLimitExceededException exhausted) {
                    result.failedChargeAbsent = true;
                    break;
                }
            }
            meter.merge(child);
            copyProductionLedger(meter, result);
        }

        if (input.has("directCanonicalBytes")) {
            long bytes = nonNegative(input.get("directCanonicalBytes"),
                    "input.directCanonicalBytes");
            GasMeter meter = new GasMeter(productionSchedule);
            meter.semantic().directIdentityInput(bytes, GasChargeContext.empty());
            copyProductionLedger(meter, result);
            result.directIdentityHashBlock =
                    counterQuantity(meter, "semantic", "directIdentityHashBlock");
        }
        if (input.has("textCodePointsExamined")) {
            long codePoints = nonNegative(
                    input.get("textCodePointsExamined"), "input.textCodePointsExamined");
            GasMeter meter = new GasMeter(productionSchedule);
            meter.semantic().textCodePointsExamined(codePoints, GasChargeContext.empty());
            copyProductionLedger(meter, result);
            result.textBlockExamined =
                    counterQuantity(meter, "semantic", "textBlockExamined");
        }
        if (input.has("proofKey") || input.has("uses")) {
            requireFields(input, "proofKey", "uses");
            String proofKey = input.path("proofKey").asText();
            if (proofKey.isEmpty()) {
                throw new IllegalArgumentException("input.proofKey must be non-empty");
            }
            long uses = nonNegative(input.get("uses"), "input.uses");
            GasMeter meter = new GasMeter(productionSchedule);
            for (long use = 0L; use < uses; use++) {
                meter.semantic().useValidationProof(proofKey, GasChargeContext.empty());
            }
            copyProductionLedger(meter, result);
            result.validationProofReused =
                    counterQuantity(meter, "semantic", "validationProofReused");
        }
        if (input.has("leftLimbs") || input.has("rightLimbs") || input.has("operation")) {
            requireFields(input, "leftLimbs", "rightLimbs", "operation");
            long left = nonNegative(input.get("leftLimbs"), "input.leftLimbs");
            long right = nonNegative(input.get("rightLimbs"), "input.rightLimbs");
            String operation = input.path("operation").asText();
            if (left == 0L || right == 0L) {
                throw new IllegalArgumentException(
                        "Contracts integer limb operands must be positive");
            }
            final SemanticGasMeter.IntegerOperation formula;
            if ("multiply".equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.MULTIPLICATION;
            } else if ("division".equals(operation) || "remainder".equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.DIVISION_OR_REMAINDER;
            } else if ("gcd".equals(operation) || "multipleOf".equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.GCD_OR_MULTIPLE_OF;
            } else if ("add".equals(operation) || "subtract".equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.ADDITION_OR_SUBTRACTION;
            } else if ("equals".equals(operation) || "order".equals(operation)) {
                formula = SemanticGasMeter.IntegerOperation.EQUALITY_OR_ORDERING;
            } else if ("lcm".equals(operation)) {
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
                    counterQuantity(meter, "semantic", "integerLimbOperation");
        }
        if (input.has("replaceIndex")) {
            requireFields(input, "oldLength", "replaceIndex");
            long length = nonNegative(input.get("oldLength"), "input.oldLength");
            long index = nonNegative(input.get("replaceIndex"), "input.replaceIndex");
            GasMeter meter = new GasMeter(productionSchedule);
            meter.semantic().listReplaceAt(length, index, GasChargeContext.empty());
            copyProductionLedger(meter, result);
            result.listFoldStepRecomputed =
                    counterQuantity(meter, "semantic", "listFoldStepRecomputed");
        } else if (input.has("append")) {
            requireFields(input, "oldLength", "append", "priorExactIdentity");
            long length = nonNegative(input.get("oldLength"), "input.oldLength");
            long appended = nonNegative(input.get("append"), "input.append");
            GasMeter meter = new GasMeter(productionSchedule);
            if (input.path("priorExactIdentity").asBoolean(false)) {
                meter.semantic().verifiedListAppend(
                        length, appended, GasChargeContext.empty());
            } else {
                meter.semantic().fullListIdentity(
                        addExact(length, appended), GasChargeContext.empty());
            }
            copyProductionLedger(meter, result);
            result.listFoldStepRecomputed =
                    counterQuantity(meter, "semantic", "listFoldStepRecomputed");
        }

        result.projection.put("trace.namedEntries", result.trace);
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
            value.put("sequence", entry.sequence());
            value.put("namespace", entry.namespace());
            value.put("counter", entry.counter());
            value.put("quantity", entry.quantity());
            value.put("weight", entry.weight());
            value.put("subtotal", entry.subtotal());
            if (entry.scopePath() != null) {
                value.put("scopePath", entry.scopePath());
            }
            if (entry.contractKey() != null) {
                value.put("contractKey", entry.contractKey());
            }
            if (entry.logicalPath() != null) {
                value.put("logicalPath", entry.logicalPath());
            }
            if (entry.reason() != null
                    && !entry.reason().isEmpty()
                    && !"unspecified".equals(entry.reason())) {
                value.put("reason", entry.reason());
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
            JsonNode counters = namespace.getValue().path("counters");
            if (!counters.isObject()) {
                throw new IllegalStateException(
                        "Contracts gas namespace has no counter map: " + namespace.getKey());
            }
            Map<String, Long> counterWeights = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> countersIt = counters.fields();
                 countersIt.hasNext(); ) {
                Map.Entry<String, JsonNode> counter = countersIt.next();
                long weight = nonNegative(counter.getValue(),
                        namespace.getKey() + "." + counter.getKey());
                counterWeights.put(counter.getKey(), weight);
            }
            int declaredCount = namespace.getValue().path("counterCount").asInt(-1);
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
        if (!"blue-contracts-gas-manifest".equals(manifest.path("manifestType").asText())
                || !"blue-contracts/gas/1.0".equals(manifest.path("schedule").asText())
                || !"1.0".equals(manifest.path("specificationVersion").asText())
                || !BlueContractsConformanceReport.CONTRACTS_GAS_PACKAGE_IDENTITY.equals(
                manifest.path("packageIdentity").asText())) {
            throw new IllegalStateException("Contracts gas manifest binding mismatch");
        }
        if (!manifest.path("admissionRule").asText()
                .startsWith("Admit quantity * weight before the corresponding logical work.")) {
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

        public List<Map<String, Object>> trace() {
            return Collections.unmodifiableList(trace);
        }

        public List<Long> admitted() {
            return Collections.unmodifiableList(admitted);
        }

        public ContractsConformanceProjection projection() {
            return projection;
        }

        public long totalGas() {
            return totalGas;
        }

        public boolean failedChargeAbsent() {
            return failedChargeAbsent;
        }

        public Long listFoldStepRecomputed() {
            return listFoldStepRecomputed;
        }

        public Long textBlockExamined() {
            return textBlockExamined;
        }

        public Long validationProofReused() {
            return validationProofReused;
        }

        public Long directIdentityHashBlock() {
            return directIdentityHashBlock;
        }

        public Long integerLimbOperation() {
            return integerLimbOperation;
        }
    }
}
