package blue.language.identity;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Compares the byte-array Base58 implementation with the previous BigInteger
 * implementation, retained here only as a benchmark oracle.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Base58Benchmark {

    private static final char[] LEGACY_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final BigInteger LEGACY_BASE_58 = BigInteger.valueOf(58);

    private byte[] sha256SizedValue;
    private String encodedValue;
    private byte[] leadingZeroValue;
    private String leadingZeroEncoded;
    private byte[][] batchValues;
    private String[] batchEncoded;
    private String sha256Input;

    @Setup
    public void setUp() {
        sha256SizedValue = new byte[32];
        for (int index = 0; index < sha256SizedValue.length; index++) {
            sha256SizedValue[index] = (byte) (index * 37 + 11);
        }
        encodedValue = legacyEncode(sha256SizedValue);
        leadingZeroValue = sha256SizedValue.clone();
        for (int index = 0; index < 8; index++) {
            leadingZeroValue[index] = 0;
        }
        leadingZeroEncoded = legacyEncode(leadingZeroValue);
        batchValues = new byte[1_000][32];
        batchEncoded = new String[batchValues.length];
        for (int row = 0; row < batchValues.length; row++) {
            for (int column = 0; column < batchValues[row].length; column++) {
                batchValues[row][column] = (byte) (row * 31 + column * 17 + 3);
            }
            batchEncoded[row] = legacyEncode(batchValues[row]);
        }
        sha256Input = "Blue Base58 SHA-256 benchmark input";
        Base58Sha256Provider.sha256(sha256Input);
        if (!encodedValue.equals(Base58.encode(sha256SizedValue))) {
            throw new IllegalStateException("Benchmark implementations disagree");
        }
    }

    @Benchmark
    public String byteArrayEncode() {
        return Base58.encode(sha256SizedValue);
    }

    @Benchmark
    public String legacyBigIntegerEncode() {
        return legacyEncode(sha256SizedValue);
    }

    @Benchmark
    public byte[] byteArrayDecode() {
        return Base58.decode(encodedValue);
    }

    @Benchmark
    public byte[] legacyBigIntegerDecode() {
        return legacyDecode(encodedValue);
    }

    @Benchmark
    public String leadingZeroEncode() {
        return Base58.encode(leadingZeroValue);
    }

    @Benchmark
    public byte[] leadingZeroDecode() {
        return Base58.decode(leadingZeroEncoded);
    }

    @Benchmark
    public void byteArrayEncodeBatch1000(Blackhole blackhole) {
        for (byte[] value : batchValues) {
            blackhole.consume(Base58.encode(value));
        }
    }

    @Benchmark
    public void legacyBigIntegerEncodeBatch1000(Blackhole blackhole) {
        for (byte[] value : batchValues) {
            blackhole.consume(legacyEncode(value));
        }
    }

    @Benchmark
    public byte[] threadLocalSha256() {
        return Base58Sha256Provider.sha256(sha256Input);
    }

    @Benchmark
    public byte[] legacyNewDigestSha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(sha256Input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Error calculating SHA-256 hash", exception);
        }
    }

    private static String legacyEncode(byte[] input) {
        BigInteger value = new BigInteger(1, input);
        StringBuilder base58 = new StringBuilder();
        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divmod = value.divideAndRemainder(LEGACY_BASE_58);
            base58.insert(0, LEGACY_ALPHABET[divmod[1].intValue()]);
            value = divmod[0];
        }
        int index = 0;
        while (index < input.length && input[index] == 0) {
            base58.insert(0, LEGACY_ALPHABET[0]);
            index++;
        }
        return base58.toString();
    }

    private static byte[] legacyDecode(String input) {
        BigInteger number = BigInteger.ZERO;
        for (char character : input.toCharArray()) {
            int digit = new String(LEGACY_ALPHABET).indexOf(character);
            if (digit == -1) {
                throw new IllegalArgumentException("Invalid character found: " + character);
            }
            number = number.multiply(LEGACY_BASE_58).add(BigInteger.valueOf(digit));
        }

        byte[] bytes = number.toByteArray();
        boolean stripSignByte = bytes.length > 1 && bytes[0] == 0 && bytes[1] < 0;
        int leadingZeros = 0;
        while (leadingZeros < input.length()
                && input.charAt(leadingZeros) == LEGACY_ALPHABET[0]) {
            leadingZeros++;
        }
        byte[] decoded = new byte[bytes.length - (stripSignByte ? 1 : 0) + leadingZeros];
        System.arraycopy(bytes, stripSignByte ? 1 : 0, decoded, leadingZeros,
                decoded.length - leadingZeros);
        return decoded;
    }
}
