package blue.language.merge.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.SchemaEnumCanonicalizer;
import blue.language.merge.Merger;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.NodeWireForm;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/** Finite model expectations use parent indices and records, never production membership. */
final class FiniteConstraintOracleTest {
    private static final long SEED = 0xD2E6A11L;
    private static final int[] PARENT = {-1, -1, -1, -1, 0, 4, 0, 1, 2, 0};

    @Test
    void shouldAgreeWithIndependentForestMembershipAndSingletonIntersection() {
        // given
        Universe universe = new Universe();
        // when
        List<Entry> entries = universe.entries;
        // then
        for (Entry restriction : entries) {
            Node authored = universe.node(restriction);
            Object before = NodeWireForm.get(authored);
            for (int candidate = 0; candidate < universe.values.size(); candidate++) {
                Value value = universe.values.get(candidate);
                TypeEvidenceResolution proof = universe.proofs.get(candidate);
                assertEquals(accepts(value, restriction), EnumConstraintMembership.matches(
                        proof.resolvedRoot().toNode(), authored, proof.canonicalTypeIdentities()),
                        value + " in " + restriction);
            }
            assertEquals(before, NodeWireForm.get(authored));
            for (Entry other : entries) {
                List<Entry> expected = intersect(Collections.singletonList(restriction), Collections.singletonList(other));
                List<Node> actual = universe.merge(universe.nodes(restriction), universe.nodes(other));
                assertEquals(universe.keys(expected), keys(actual), restriction + " & " + other);
                assertEquals(keys(actual), keys(universe.merge(universe.nodes(other), universe.nodes(restriction))));
                for (int candidate = 0; candidate < universe.values.size(); candidate++) {
                    Value value = universe.values.get(candidate);
                    TypeEvidenceResolution proof = universe.proofs.get(candidate);
                    boolean actualMembership = actual.stream().anyMatch(entry -> EnumConstraintMembership.matches(
                            proof.resolvedRoot().toNode(), entry, proof.canonicalTypeIdentities()));
                    assertEquals(accepts(value, restriction) && accepts(value, other), actualMembership,
                            value + " in (" + restriction + " & " + other + ")");
                }
            }
        }
    }

    @Test
    void shouldPreserveSetAlgebraOrderingAndExactCustomIdentityForSeededUnions() {
        // given
        Universe universe = new Universe();
        Random random = new Random(SEED);
        // when
        List<List<Entry>> samples = new ArrayList<>();
        for (int sample = 0; sample < 60; sample++) {
            List<Entry> entries = new ArrayList<>();
            for (int member = 0; member < 1 + sample % 5; member++) {
                entries.add(universe.entries.get(random.nextInt(universe.entries.size())));
            }
            samples.add(entries);
        }
        // then
        for (int sample = 0; sample < samples.size() - 2; sample++) {
            List<Entry> a = samples.get(sample), b = samples.get(sample + 1), c = samples.get(sample + 2);
            List<Node> an = universe.nodes(a), bn = universe.nodes(b), cn = universe.nodes(c);
            List<Object> before = an.stream().map(NodeWireForm::get).collect(Collectors.toList());
            assertEquals(universe.keys(a), keys(universe.merge(an, an)), "idempotent seed=" + SEED + " sample=" + sample);
            List<Node> ab = universe.merge(an, bn);
            assertEquals(universe.keys(intersect(a, b)), keys(ab), "oracle sample=" + sample);
            assertEquals(keys(ab), keys(universe.merge(bn, an)), "commutative sample=" + sample);
            assertEquals(keys(universe.merge(ab, cn)), keys(universe.merge(an, universe.merge(bn, cn))),
                    "associative seed=" + SEED + " sample=" + sample);
            assertEquals(universe.keys(intersect(intersect(a, b), c)), keys(universe.merge(ab, cn)));
            assertEquals(before, an.stream().map(NodeWireForm::get).collect(Collectors.toList()));
        }
        Value custom = universe.values.stream().filter(value -> value.domain == 4).findFirst().get();
        assertNotEquals(DirectBlueIdCalculator.calculateBlueId(universe.node(new Entry(custom, false))),
                DirectBlueIdCalculator.calculateBlueId(new Node().value(custom.payload)));
    }

    @Test
    void shouldAgreeWithIndependentBinary64RationalOracleForBoundsMultiplesAndMergedDivisors() {
        // given
        List<Number> values = Arrays.asList(BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(-1),
                new BigInteger("9007199254740992"), new BigInteger("9007199254740993"),
                new BigInteger("99999999999999991611392"), new BigDecimal("1e23"),
                new BigDecimal("0.1"), new BigDecimal("0.3"), BigDecimal.valueOf(Double.MIN_VALUE),
                BigDecimal.valueOf(2 * Double.MIN_VALUE), new BigDecimal("1e-300"),
                BigDecimal.valueOf(Double.MAX_VALUE));
        // when
        List<Number> divisors = values.stream().filter(value -> fraction(value)[0].signum() > 0).collect(Collectors.toList());
        // then
        for (Number value : values) {
            for (Number bound : values) {
                BigInteger[] v = fraction(value), b = fraction(bound);
                int expected = v[0].multiply(b[1]).compareTo(b[0].multiply(v[1]));
                assertEquals(Integer.signum(expected), Integer.signum(ScalarConstraintPayload.compare(
                        new Node().value(value), new Node().value(bound), CanonicalTypeIdentityLookup.incomplete())));
            }
            for (Number divisor : divisors) {
                assertEquals(divides(value, divisor), ScalarConstraintPayload.isMultipleOf(value, divisor), value + " / " + divisor);
            }
        }
        for (Number left : divisors) {
            for (Number right : divisors) {
                Number merged = ScalarConstraintPayload.leastCommonMultiple(left, right);
                for (Number candidate : values) {
                    assertEquals(divides(candidate, left) && divides(candidate, right), divides(candidate, merged),
                            candidate + " / lcm(" + left + ", " + right + ")");
                }
                assertTrue(divides(merged, left) && divides(merged, right));
                BigInteger[] a = reduced(fraction(left)), b = reduced(fraction(right));
                BigInteger n = a[0].multiply(b[0]).divide(a[0].gcd(b[0]));
                BigInteger d = a[1].gcd(b[1]);
                BigInteger[] lcm = reduced(new BigInteger[]{n, d});
                double approximation = new BigDecimal(lcm[0]).divide(new BigDecimal(lcm[1])).doubleValue();
                boolean exactDouble = Double.isFinite(approximation)
                        && Arrays.equals(lcm, reduced(fraction(BigDecimal.valueOf(approximation))));
                BigInteger[] expected = exactDouble && !(left instanceof BigInteger && right instanceof BigInteger)
                        ? lcm : new BigInteger[]{lcm[0], BigInteger.ONE};
                assertArrayEquals(expected, reduced(fraction(merged)), "normalized lcm(" + left + ", " + right + ")");
                assertTrue(divides(lcm[0], merged));
                assertTrue(divides(lcm[0].multiply(BigInteger.valueOf(2)), merged));
            }
        }
    }

    private static boolean accepts(Value value, Entry restriction) {
        if (!value.payload.equals(restriction.value.payload) || primitive(value.domain) != primitive(restriction.value.domain)) return false;
        if (restriction.exact) return value.domain == restriction.value.domain;
        return subtype(value.domain, restriction.value.domain);
    }
    private static boolean subtype(int child, int parent) {
        for (int current = child; current >= 0; current = PARENT[current]) if (current == parent) return true;
        return false;
    }
    private static int primitive(int domain) {
        while (PARENT[domain] >= 0) domain = PARENT[domain];
        return domain;
    }
    private static List<Entry> intersect(List<Entry> left, List<Entry> right) {
        List<Entry> result = new ArrayList<>();
        for (Entry a : left) for (Entry b : right) {
            if (a.exact) { if (accepts(a.value, b)) result.add(a); }
            else if (b.exact) { if (accepts(b.value, a)) result.add(b); }
            else if (accepts(a.value, b)) result.add(a);
            else if (accepts(b.value, a)) result.add(b);
        }
        return result;
    }
    private static BigInteger[] reduced(BigInteger[] rational) {
        BigInteger gcd = rational[0].gcd(rational[1]);
        return new BigInteger[]{rational[0].divide(gcd), rational[1].divide(gcd)};
    }
    private static boolean divides(Number value, Number divisor) {
        BigInteger[] v = fraction(value), d = fraction(divisor);
        return v[0].multiply(d[1]).remainder(d[0].multiply(v[1])).signum() == 0;
    }
    // Independent IEEE-754 bit decomposition; production uses exact decimal arithmetic.
    private static BigInteger[] fraction(Number number) {
        if (number instanceof BigInteger) return new BigInteger[]{(BigInteger) number, BigInteger.ONE};
        long bits = Double.doubleToLongBits(number.doubleValue());
        int exponent = (int) ((bits >>> 52) & 2047);
        long significand = bits & ((1L << 52) - 1);
        if (exponent != 0) significand |= 1L << 52;
        int shift = exponent == 0 ? -1074 : exponent - 1023 - 52;
        BigInteger n = BigInteger.valueOf(significand);
        if (bits < 0) n = n.negate();
        return shift >= 0 ? new BigInteger[]{n.shiftLeft(shift), BigInteger.ONE}
                : new BigInteger[]{n, BigInteger.ONE.shiftLeft(-shift)};
    }
    private static List<String> keys(List<Node> nodes) {
        return nodes.stream().map(SchemaEnumCanonicalizer::canonicalKey).collect(Collectors.toList());
    }
    private static int compareUtf8(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8), right = b.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int comparison = Integer.compare(left[i] & 255, right[i] & 255);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }
    private static final class Value {
        private final int domain;
        private final Object payload;
        private Value(int domain, Object payload) { this.domain = domain; this.payload = payload; }
        public String toString() { return domain + ":" + payload; }
    }
    private static final class Entry {
        private final Value value;
        private final boolean exact;
        private Entry(Value value, boolean exact) { this.value = value; this.exact = exact; }
        public String toString() { return (exact ? "exact:" : "domain:") + value; }
    }
    private static final class Universe {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final Merger merger = new Merger(new SequentialMergingProcessor(Arrays.asList(
                new ValuePropagator(), new TypeAssigner(), new ListProcessor(), new DictionaryProcessor(),
                new SchemaPropagator(), new SchemaVerifier(), new BasicTypesVerifier())), provider);
        private final String[] ids = {TEXT_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID, DOUBLE_TYPE_BLUE_ID, BOOLEAN_TYPE_BLUE_ID,
                null, null, null, null, null, null};
        private final List<Value> values = new ArrayList<>();
        private final List<Entry> entries = new ArrayList<>();
        private final List<TypeEvidenceResolution> proofs = new ArrayList<>();
        private Universe() {
            for (int domain = 4; domain < PARENT.length; domain++) {
                Node type = new Node().name(domain == 9 ? "Integer" : "Domain " + domain)
                        .type(new Node().blueId(ids[PARENT[domain]]));
                ids[domain] = DirectBlueIdCalculator.calculateBlueId(type);
                provider.addSingleNodes(type);
            }
            for (int domain = 0; domain < PARENT.length; domain++) {
                List<?> payloads = primitive(domain) == 0 ? Arrays.asList("female", "1")
                        : primitive(domain) == 1 ? Arrays.asList(BigInteger.ONE, new BigInteger("9007199254740993"))
                        : primitive(domain) == 2 ? Arrays.asList(BigDecimal.ONE, new BigDecimal("1.5"))
                        : Arrays.asList(Boolean.TRUE, Boolean.FALSE);
                for (Object payload : payloads) {
                    Value value = new Value(domain, payload);
                    values.add(value);
                    Entry entry = new Entry(value, false);
                    entries.add(entry);
                    Node scalar = node(entry);
                    provider.addSingleNodes(scalar);
                    proofs.add(merger.resolveTypeEvidence(scalar.clone(), ResolutionLimits.NO_LIMITS));
                    if (domain == 0 || domain == 4 || domain == 5 || domain == 7) entries.add(new Entry(value, true));
                }
            }
        }
        private Node node(Entry entry) {
            Node scalar = new Node().type(new Node().blueId(ids[entry.value.domain])).value(entry.value.payload);
            return entry.exact ? new Node().blueId(DirectBlueIdCalculator.calculateBlueId(scalar)) : scalar;
        }
        private List<Node> nodes(Entry entry) { return Collections.singletonList(node(entry)); }
        private List<Node> nodes(List<Entry> entries) {
            List<Node> result = entries.stream().map(this::node).collect(Collectors.toList());
            // Include equivalent bare core spellings and duplicate entries.
            for (Entry entry : entries) if (!entry.exact && entry.value.domain < 4) result.add(new Node().value(entry.value.payload));
            return result;
        }
        private List<Node> merge(List<Node> a, List<Node> b) {
            Node target = new Node().schema(new Schema().enumValues(a));
            new SchemaPropagator().process(target, new Node().schema(new Schema().enumValues(b)), provider, merger,
                    CanonicalTypeIdentityLookup.incomplete());
            return target.getSchema().getEnum();
        }
        private List<String> keys(List<Entry> entries) {
            Set<String> result = new TreeSet<>(FiniteConstraintOracleTest::compareUtf8);
            for (Entry entry : entries) {
                if (entry.exact) result.add("{\"blueId\":\"" + node(entry).getBlueId() + "\"}");
                else {
                    Object payload = entry.value.payload;
                    String json = payload instanceof String || payload instanceof BigInteger && ((BigInteger) payload).bitLength() > 53
                            ? "\"" + payload + "\"" : payload.toString();
                    result.add("{\"type\":{\"blueId\":\"" + ids[entry.value.domain] + "\"},\"value\":" + json + "}");
                }
            }
            return new ArrayList<>(result);
        }
    }
}
