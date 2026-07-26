package blue.language.processor;

import blue.language.utils.UncheckedObjectMapper;
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
 */
public final class GasSchedule {

    public static final String CONTRACTS_1_0_RESOURCE =
            "blue/language/processor/contracts-gas-1.0.yaml";
    public static final String CONTRACTS_1_0_SCHEDULE = "blue-contracts/gas/1.0";
    public static final String CONTRACTS_1_0_PACKAGE_IDENTITY =
            "sha256:88c7bbe77d531c9e973cae13002c3464a2c14568833adf5d804d13b7b3d26af5";
    public static final String CONTRACTS_1_0_RESOURCE_SHA256 =
            "1f4054b77fc7ef01a3e62f5b29d209e84f26e85148c91b03fe48da2c3579408f";

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
     */
    @SuppressWarnings("unchecked")
    public static GasSchedule load(InputStream input) {
        Objects.requireNonNull(input, "input");
        Map<String, Object> manifest = UncheckedObjectMapper.YAML_MAPPER.readValue(
                input, new TypeReference<Map<String, Object>>() { });
        String schedule = requiredText(manifest, "schedule");
        String packageIdentity = requiredText(manifest, "packageIdentity");
        verifyPackageIdentity(manifest, packageIdentity);
        long maxProcessGas = requiredPositiveLong(manifest, "maxProcessGas");

        Object namespacesValue = manifest.get("namespaces");
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
            Object countersValue = namespaceObject.get("counters");
            if (!(countersValue instanceof Map)) {
                throw new IllegalArgumentException(
                        "Gas namespace '" + namespace + "' counters must be an object");
            }
            Map<String, Long> counters = new LinkedHashMap<>();
            for (Map.Entry<?, ?> counterEntry : ((Map<?, ?>) countersValue).entrySet()) {
                String counter = requiredKey(counterEntry.getKey(), "counter");
                long weight = nonNegativeLong(counterEntry.getValue(),
                        "weight for " + namespace + "." + counter);
                if (counters.put(counter, weight) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate gas counter " + namespace + "." + counter);
                }
            }
            long declaredCount = nonNegativeLong(namespaceObject.get("counterCount"),
                    "counterCount for " + namespace);
            if (declaredCount != counters.size()) {
                throw new IllegalArgumentException(
                        "Gas counterCount mismatch for " + namespace + ": declared "
                                + declaredCount + " but loaded " + counters.size());
            }
            namespaces.put(namespace, counters);
        }

        Map<String, Long> portableLimits = new LinkedHashMap<>();
        Object limitsValue = manifest.get("portableLimits");
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

    public String schedule() {
        return schedule;
    }

    public String packageIdentity() {
        return packageIdentity;
    }

    public long maxProcessGas() {
        return maxProcessGas;
    }

    public Map<String, Map<String, Long>> namespaces() {
        return weights;
    }

    public long weight(String namespace, String counter) {
        Map<String, Long> counters = weights.get(namespace);
        Long weight = counters != null ? counters.get(counter) : null;
        if (weight == null) {
            throw new IllegalArgumentException(
                    "Unknown gas counter " + namespace + "." + counter);
        }
        return weight;
    }

    public long portableLimit(String name) {
        Long value = portableLimits.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Unknown portable limit: " + name);
        }
        return value;
    }

    public Map<String, Long> portableLimits() {
        return portableLimits;
    }

    public long formulaParameter(String name) {
        Long value = formulaParameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Unknown gas formula parameter: " + name);
        }
        return value;
    }

    public Map<String, Long> formulaParameters() {
        return formulaParameters;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> parseFormulaParameters(
            Map<String, Object> manifest) {
        Object formulasValue = manifest.get("formulas");
        if (!(formulasValue instanceof Map)) {
            throw new IllegalArgumentException(
                    "Gas manifest formulas must be an object");
        }
        Map<?, ?> formulas = (Map<?, ?>) formulasValue;
        Map<?, ?> text = requiredObject(formulas, "textBlocks", "formula");
        Map<?, ?> integers = requiredObject(formulas, "integerLimbs", "formula");
        Map<?, ?> sorting = requiredObject(formulas, "sorting", "formula");
        Map<?, ?> identity = requiredObject(formulas, "identity", "formula");

        Map<String, Long> result = new LinkedHashMap<>();
        result.put("textBlockCodePoints",
                positiveLong(text.get("blockCodePoints"),
                        "textBlocks.blockCodePoints"));
        result.put("integerMinimumLimbs",
                positiveLong(integers.get("minimumLimbs"),
                        "integerLimbs.minimumLimbs"));
        String radix = requiredTextValue(
                integers.get("radix"), "integerLimbs.radix");
        Matcher radixMatcher = RADIX.matcher(radix);
        if (!radixMatcher.matches()) {
            throw new IllegalArgumentException(
                    "integerLimbs.radix must have 2^N form");
        }
        result.put("integerRadixBits",
                positiveLong(new BigInteger(radixMatcher.group(1)),
                        "integerLimbs.radix exponent"));
        result.put("sortingInitialRunWidth",
                positiveLong(sorting.get("initialRunWidth"),
                        "sorting.initialRunWidth"));

        String directHash = requiredTextValue(
                identity.get("directHashBlocks"),
                "identity.directHashBlocks");
        Matcher hashMatcher = DIRECT_HASH_BLOCKS.matcher(directHash);
        if (!hashMatcher.matches()) {
            throw new IllegalArgumentException(
                    "identity.directHashBlocks must expose domain and block bytes");
        }
        result.put("identityHashDomainBytes",
                positiveLong(new BigInteger(hashMatcher.group(1)),
                        "identity hash domain bytes"));
        result.put("identityHashBlockBytes",
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
        payload.put("packageIdentity", null);
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
