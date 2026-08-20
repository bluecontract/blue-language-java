package blue.language.processor;

import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable named-counter schedule loaded from the bound Contracts gas
 * manifest.
 *
 * <p>Counter weights, formula parameters, portable limits, and the maximum
 * PROCESS budget are one identity-bound unit. Unknown counters and limits fail
 * closed instead of receiving implicit defaults.</p>
 */
public final class GasSchedule {

    /**
     * Classpath location of the bound Contracts 1.0 gas manifest.
     */
    public static final String CONTRACTS_1_0_RESOURCE =
            "blue/language/processor/contracts-gas-1.0.yaml";
    /**
     * Stable schedule name declared by the bound Contracts 1.0 manifest.
     */
    public static final String CONTRACTS_1_0_SCHEDULE = "blue-contracts/gas/1.0";
    /**
     * Canonical package identity declared by the bound manifest.
     */
    public static final String CONTRACTS_1_0_PACKAGE_IDENTITY =
            "sha256:03219c42eb3696ef8727fe8ae226c8a5eb4a6126859ba744f571d892c409626a";
    /**
     * SHA-256 digest of the exact shipped manifest bytes.
     */
    public static final String CONTRACTS_1_0_RESOURCE_SHA256 =
            "54310113bbfc0c6529802fa134a40d7131a4c72e52ccd11d16b20733db60bad8";

    private static final Pattern RADIX =
            Pattern.compile("2\\^(\\d+)");
    private static final Pattern DIRECT_HASH_BLOCKS =
            Pattern.compile(".*\\+\\s*(\\d+)\\)\\s*/\\s*(\\d+)\\).*");

    private static volatile GasSchedule contracts10;

    private final String schedule;
    private final String packageIdentity;
    private final long maxProcessGas;
    private final Map<String, Map<String, Long>> weights;
    private final Map<String, Long> portableLimits;
    private final Map<String, Long> formulaParameters;

    private GasSchedule(String schedule,
                        String packageIdentity,
                        long maxProcessGas,
                        Map<String, Map<String, Long>> weights,
                        Map<String, Long> portableLimits,
                        Map<String, Long> formulaParameters) {
        this.schedule = schedule;
        this.packageIdentity = packageIdentity;
        this.maxProcessGas = maxProcessGas;
        this.weights = deepImmutable(weights);
        this.portableLimits = Collections.unmodifiableMap(new LinkedHashMap<>(portableLimits));
        this.formulaParameters =
                Collections.unmodifiableMap(new LinkedHashMap<>(formulaParameters));
    }

    /**
     * Loads and caches the exact Contracts 1.0 manifest shipped with this
     * library.
     *
     * @return shared immutable Contracts 1.0 schedule
     * @throws IllegalStateException when the resource or bound identity is invalid
     */
    public static GasSchedule contracts10() {
        GasSchedule current = contracts10;
        if (current != null) {
            return current;
        }
        synchronized (GasSchedule.class) {
            current = contracts10;
            if (current == null) {
                InputStream input = GasSchedule.class.getClassLoader()
                        .getResourceAsStream(CONTRACTS_1_0_RESOURCE);
                if (input == null) {
                    throw new IllegalStateException(
                            "Missing Contracts 1.0 gas manifest resource: "
                                    + CONTRACTS_1_0_RESOURCE);
                }
                byte[] bytes = readAll(input);
                String resourceSha = toHex(sha256().digest(bytes));
                if (!CONTRACTS_1_0_RESOURCE_SHA256.equals(resourceSha)) {
                    throw new IllegalStateException(
                            "Contracts 1.0 gas manifest resource digest mismatch: "
                                    + resourceSha);
                }
                current = load(new ByteArrayInputStream(bytes));
                if (!CONTRACTS_1_0_SCHEDULE.equals(current.schedule())) {
                    throw new IllegalStateException(
                            "Expected gas schedule " + CONTRACTS_1_0_SCHEDULE
                                    + " but loaded " + current.schedule());
                }
                if (!CONTRACTS_1_0_PACKAGE_IDENTITY.equals(
                        current.packageIdentity())) {
                    throw new IllegalStateException(
                            "Contracts 1.0 gas package identity mismatch: "
                                    + current.packageIdentity());
                }
                contracts10 = current;
            }
        }
        return current;
    }

    /**
     * Loads a schedule from a caller-supplied manifest stream.
     *
     * <p>The stream is consumed but not closed by this method.</p>
     *
     * @param input manifest stream owned by the caller
     * @return validated immutable schedule
     * @throws NullPointerException when {@code input} is null
     * @throws IllegalArgumentException when manifest structure or identity is invalid
     */
    @SuppressWarnings("unchecked")
    public static GasSchedule load(InputStream input) {
        Objects.requireNonNull(input, "input");
        Map<String, Object> manifest = UncheckedObjectMapper.YAML_MAPPER.readValue(
                input, new TypeReference<Map<String, Object>>() { });
        String schedule = requiredText(
                manifest,
                GasScheduleConstants.ManifestField.SCHEDULE);
        String packageIdentity = requiredText(
                manifest,
                GasScheduleConstants.ManifestField.PACKAGE_IDENTITY);
        verifyPackageIdentity(manifest, packageIdentity);
        long maxProcessGas = requiredPositiveLong(
                manifest,
                GasScheduleConstants.ManifestField.MAX_PROCESS_GAS);

        Object namespacesValue = manifest.get(
                GasScheduleConstants.ManifestField.NAMESPACES);
        if (!(namespacesValue instanceof Map)) {
            throw new IllegalArgumentException("Gas manifest namespaces must be an object");
        }
        Map<String, Map<String, Long>> namespaces = new LinkedHashMap<>();
        for (Map.Entry<?, ?> namespaceEntry : ((Map<?, ?>) namespacesValue).entrySet()) {
            String namespace = requiredKey(namespaceEntry.getKey(), "namespace");
            if (!(namespaceEntry.getValue() instanceof Map)) {
                throw new IllegalArgumentException(
                        "Gas namespace '" + namespace + "' must be an object");
            }
            Map<?, ?> namespaceObject = (Map<?, ?>) namespaceEntry.getValue();
            Object countersValue = namespaceObject.get(
                    GasScheduleConstants.ManifestField.COUNTERS);
            if (!(countersValue instanceof Map)) {
                throw new IllegalArgumentException(
                        "Gas namespace '" + namespace + "' counters must be an object");
            }
            Map<String, Long> counters = new LinkedHashMap<>();
            for (Map.Entry<?, ?> counterEntry : ((Map<?, ?>) countersValue).entrySet()) {
                String counter = requiredKey(counterEntry.getKey(), "counter");
                long weight = positiveLong(counterEntry.getValue(),
                        "weight for " + namespace + "." + counter);
                if (counters.put(counter, weight) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate gas counter " + namespace + "." + counter);
                }
            }
            long declaredCount = nonNegativeLong(
                    namespaceObject.get(
                            GasScheduleConstants.ManifestField.COUNTER_COUNT),
                    GasScheduleConstants.ManifestField.COUNTER_COUNT
                            + " for " + namespace);
            if (declaredCount != counters.size()) {
                throw new IllegalArgumentException(
                        "Gas counterCount mismatch for " + namespace + ": declared "
                                + declaredCount + " but loaded " + counters.size());
            }
            namespaces.put(namespace, counters);
        }

        Map<String, Long> portableLimits = new LinkedHashMap<>();
        Object limitsValue = manifest.get(
                GasScheduleConstants.ManifestField.PORTABLE_LIMITS);
        if (!(limitsValue instanceof Map)) {
            throw new IllegalArgumentException("Gas manifest portableLimits must be an object");
        }
        for (Map.Entry<?, ?> limitEntry : ((Map<?, ?>) limitsValue).entrySet()) {
            String key = requiredKey(limitEntry.getKey(), "portable limit");
            portableLimits.put(key, nonNegativeLong(limitEntry.getValue(), "portable limit " + key));
        }
        Map<String, Long> formulaParameters = parseFormulaParameters(manifest);
        return new GasSchedule(schedule, packageIdentity, maxProcessGas,
                namespaces, portableLimits, formulaParameters);
    }

    /**
     * Returns the stable name declared by the bound manifest.
     *
     * @return stable schedule name
     */
    public String schedule() {
        return schedule;
    }

    /**
     * Returns the canonical identity of the complete manifest package.
     *
     * @return canonical package identity
     */
    public String packageIdentity() {
        return packageIdentity;
    }

    /**
     * Returns the largest PROCESS budget permitted by this schedule.
     *
     * @return maximum portable PROCESS budget
     */
    public long maxProcessGas() {
        return maxProcessGas;
    }

    /**
     * Returns every named counter and its strictly positive unit weight.
     *
     * @return deeply unmodifiable namespace and counter catalog
     */
    public Map<String, Map<String, Long>> namespaces() {
        return weights;
    }

    /**
     * Looks up the unit weight of one exactly qualified counter.
     *
     * @param namespace exact schedule namespace
     * @param counter exact counter name
     * @return strictly positive unit weight
     * @throws IllegalArgumentException when the counter is unknown
     */
    public long weight(String namespace, String counter) {
        Map<String, Long> counters = weights.get(namespace);
        Long weight = counters != null ? counters.get(counter) : null;
        if (weight == null) {
            throw new IllegalArgumentException(
                    "Unknown gas counter " + namespace + "." + counter);
        }
        return weight;
    }

    /**
     * Looks up one implementation-independent safety limit.
     *
     * @param name exact portable-limit name
     * @return non-negative configured limit
     * @throws IllegalArgumentException when the limit is unknown
     */
    public long portableLimit(String name) {
        Long value = portableLimits.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown portable limit: " + name);
        }
        return value;
    }

    /**
     * Returns every implementation-independent safety limit.
     *
     * @return immutable portable-limit catalog
     */
    public Map<String, Long> portableLimits() {
        return portableLimits;
    }

    /**
     * Looks up one parameter used by the semantic gas formulas.
     *
     * @param name exact formula-parameter name
     * @return non-negative configured parameter
     * @throws IllegalArgumentException when the parameter is unknown
     */
    public long formulaParameter(String name) {
        Long value = formulaParameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Unknown gas formula parameter: " + name);
        }
        return value;
    }

    /**
     * Returns every parameter used by the semantic gas formulas.
     *
     * @return immutable formula-parameter catalog
     */
    public Map<String, Long> formulaParameters() {
        return formulaParameters;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> parseFormulaParameters(
            Map<String, Object> manifest) {
        Object formulasValue = manifest.get(
                GasScheduleConstants.ManifestField.FORMULAS);
        if (!(formulasValue instanceof Map)) {
            throw new IllegalArgumentException(
                    "Gas manifest formulas must be an object");
        }
        Map<?, ?> formulas = (Map<?, ?>) formulasValue;
        Map<?, ?> text = requiredObject(
                formulas,
                GasScheduleConstants.ManifestField.TEXT_BLOCKS,
                "formula");
        Map<?, ?> integers = requiredObject(
                formulas,
                GasScheduleConstants.ManifestField.INTEGER_LIMBS,
                "formula");
        Map<?, ?> sorting = requiredObject(
                formulas,
                GasScheduleConstants.ManifestField.SORTING,
                "formula");
        Map<?, ?> identity = requiredObject(
                formulas,
                GasScheduleConstants.ManifestField.IDENTITY,
                "formula");

        Map<String, Long> result = new LinkedHashMap<>();
        result.put(GasScheduleConstants.FormulaParameter.TEXT_BLOCK_CODE_POINTS,
                positiveLong(text.get(
                                GasScheduleConstants
                                        .ManifestField.BLOCK_CODE_POINTS),
                        "textBlocks.blockCodePoints"));
        result.put(GasScheduleConstants.FormulaParameter.INTEGER_MINIMUM_LIMBS,
                positiveLong(integers.get(
                                GasScheduleConstants
                                        .ManifestField.MINIMUM_LIMBS),
                        "integerLimbs.minimumLimbs"));
        String radix = requiredTextValue(
                integers.get(
                        GasScheduleConstants.ManifestField.RADIX),
                "integerLimbs.radix");
        Matcher radixMatcher = RADIX.matcher(radix);
        if (!radixMatcher.matches()) {
            throw new IllegalArgumentException(
                    "integerLimbs.radix must have 2^N form");
        }
        result.put(GasScheduleConstants.FormulaParameter.INTEGER_RADIX_BITS,
                positiveLong(new BigInteger(radixMatcher.group(1)),
                        "integerLimbs.radix exponent"));
        result.put(GasScheduleConstants.FormulaParameter.SORTING_INITIAL_RUN_WIDTH,
                positiveLong(sorting.get(
                                GasScheduleConstants
                                        .ManifestField.INITIAL_RUN_WIDTH),
                        "sorting.initialRunWidth"));

        String directHash = requiredTextValue(
                identity.get(
                        GasScheduleConstants
                                .ManifestField.DIRECT_HASH_BLOCKS),
                "identity.directHashBlocks");
        Matcher hashMatcher = DIRECT_HASH_BLOCKS.matcher(directHash);
        if (!hashMatcher.matches()) {
            throw new IllegalArgumentException(
                    "identity.directHashBlocks must expose domain and block bytes");
        }
        result.put(GasScheduleConstants.FormulaParameter.IDENTITY_HASH_DOMAIN_BYTES,
                positiveLong(new BigInteger(hashMatcher.group(1)),
                        "identity hash domain bytes"));
        result.put(GasScheduleConstants.FormulaParameter.IDENTITY_HASH_BLOCK_BYTES,
                positiveLong(new BigInteger(hashMatcher.group(2)),
                        "identity hash block bytes"));
        return result;
    }

    private static Map<?, ?> requiredObject(Map<?, ?> map,
                                             String key,
                                             String label) {
        Object value = map.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                    label + " '" + key + "' must be an object");
        }
        return (Map<?, ?>) value;
    }

    private static String requiredTextValue(Object value, String label) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty Text");
        }
        return (String) value;
    }

    private static long positiveLong(Object value, String label) {
        long result = nonNegativeLong(value, label);
        if (result == 0L) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return result;
    }

    private static void verifyPackageIdentity(Map<String, Object> manifest,
                                              String packageIdentity) {
        Map<String, Object> payload = UncheckedObjectMapper.JSON_MAPPER
                .convertValue(manifest,
                        new TypeReference<Map<String, Object>>() { });
        payload.put(
                GasScheduleConstants.ManifestField.PACKAGE_IDENTITY,
                null);
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
            byte[] canonical = new JsonCanonicalizer(
                    mapper.writeValueAsString(payload)).getEncodedUTF8();
            String calculated = "sha256:" + toHex(
                    sha256().digest(canonical));
            if (!packageIdentity.equals(calculated)) {
                throw new IllegalArgumentException(
                        "Gas manifest package identity mismatch: calculated="
                                + calculated + ", manifest=" + packageIdentity);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "Unable to canonicalize gas manifest", ex);
        }
    }

    private static byte[] readAll(InputStream input) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to read gas manifest", ex);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError("SHA-256 is unavailable", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(
                    Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static Map<String, Map<String, Long>> deepImmutable(
            Map<String, Map<String, Long>> input) {
        Map<String, Map<String, Long>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> entry : input.entrySet()) {
            copy.put(entry.getKey(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String requiredText(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException(
                    "Gas manifest field '" + key + "' must be non-empty Text");
        }
        return (String) value;
    }

    private static long requiredPositiveLong(Map<String, Object> map, String key) {
        long value = nonNegativeLong(map.get(key), key);
        if (value == 0L) {
            throw new IllegalArgumentException(
                    "Gas manifest field '" + key + "' must be positive");
        }
        return value;
    }

    private static long nonNegativeLong(Object value, String label) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(label + " must be an Integer");
        }
        BigInteger integer;
        if (value instanceof BigInteger) {
            integer = (BigInteger) value;
        } else {
            integer = BigInteger.valueOf(((Number) value).longValue());
        }
        if (integer.signum() < 0 || integer.bitLength() > 63) {
            throw new IllegalArgumentException(label + " is outside non-negative long range");
        }
        return integer.longValue();
    }

    private static String requiredKey(Object value, String label) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException(label + " name must be non-empty Text");
        }
        return (String) value;
    }
}
