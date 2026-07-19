package blue.language.utils;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class Base58Test {

    private static final char[] LEGACY_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final String LEGACY_ALPHABET_STRING = new String(LEGACY_ALPHABET);
    private static final BigInteger LEGACY_BASE_58 = BigInteger.valueOf(58);

    @Test
    void knownVectorsPreserveLegacyZeroSemantics() {
        assertEquals("", Base58.encode(new byte[0]));
        assertEquals("1", Base58.encode(new byte[]{0}));
        assertEquals("11", Base58.encode(new byte[]{0, 0}));
        assertEquals("2", Base58.encode(new byte[]{1}));
        assertEquals("z", Base58.encode(new byte[]{57}));
        assertEquals("21", Base58.encode(new byte[]{58}));
        assertEquals("12", Base58.encode(new byte[]{0, 1}));
        assertEquals("JxF12TrwUP45BMd",
                Base58.encode("Hello World".getBytes(StandardCharsets.US_ASCII)));

        assertArrayEquals(new byte[]{0}, Base58.decode(""));
        assertArrayEquals(new byte[]{0, 0}, Base58.decode("1"));
        assertArrayEquals(new byte[]{0, 0, 0}, Base58.decode("11"));
        assertArrayEquals(new byte[]{0, 1}, Base58.decode("12"));
        assertArrayEquals("Hello World".getBytes(StandardCharsets.US_ASCII),
                Base58.decode("JxF12TrwUP45BMd"));
    }

    @Test
    void everyTwoByteValueMatchesLegacyOracle() {
        byte[] value = new byte[2];
        for (int unsigned = 0; unsigned <= 0xFFFF; unsigned++) {
            value[0] = (byte) (unsigned >>> 8);
            value[1] = (byte) unsigned;
            assertEncodingMatchesLegacy(value, "two-byte value " + unsigned);

            String encoded = legacyEncode(value);
            assertBytesEqual(legacyDecode(encoded), Base58.decode(encoded),
                    "two-byte decoding " + unsigned);
        }
    }

    @Test
    void oneHundredThousandShaSizedValuesMatchLegacyAndRoundTrip() {
        Random random = new Random(0x5A17B1E58L);
        byte[] value = new byte[32];
        for (int iteration = 0; iteration < 100_000; iteration++) {
            random.nextBytes(value);
            int leadingZeros = iteration % 5;
            Arrays.fill(value, 0, leadingZeros, (byte) 0);

            String description = "SHA-sized value " + iteration;
            String expected = legacyEncode(value);
            String encoded = Base58.encode(value);
            if (!expected.equals(encoded)) {
                fail(description + ": expected " + expected + " but got " + encoded);
            }
            assertBytesEqual(value, Base58.decode(encoded), description + " round trip");
            assertBytesEqual(legacyDecode(encoded), Base58.decode(encoded),
                    description + " legacy decode");
        }
    }

    @Test
    void arbitraryValidStringsMatchLegacyDecoder() {
        Random random = new Random(0xDEC0DE58L);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            int length = random.nextInt(96);
            char[] value = new char[length];
            if (iteration % 97 == 0) {
                Arrays.fill(value, LEGACY_ALPHABET[0]);
            } else {
                for (int index = 0; index < value.length; index++) {
                    value[index] = LEGACY_ALPHABET[random.nextInt(LEGACY_ALPHABET.length)];
                }
            }
            String encoded = new String(value);
            assertBytesEqual(legacyDecode(encoded), Base58.decode(encoded),
                    "valid Base58 string " + iteration);
        }
    }

    @Test
    void invalidCharactersRetainExactLegacyDiagnostic() {
        char[] invalid = {'0', 'O', 'I', 'l', '+', '/', ' ', '\t', '\u0000', '\u00E9', '\u20AC'};
        for (char character : invalid) {
            try {
                Base58.decode("2" + character + "3");
                fail("Expected invalid character to be rejected: " + (int) character);
            } catch (IllegalArgumentException exception) {
                assertEquals("Invalid character found: " + character, exception.getMessage());
            }
        }
    }

    @Test
    void encodingDoesNotMutateItsInput() {
        byte[] input = {0, 0, (byte) 0x80, 1, 2, 3, (byte) 0xFF};
        byte[] original = input.clone();

        Base58.encode(input);

        assertArrayEquals(original, input);
    }

    private static void assertEncodingMatchesLegacy(byte[] value, String description) {
        String expected = legacyEncode(value);
        String actual = Base58.encode(value);
        if (!expected.equals(actual)) {
            fail(description + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertBytesEqual(byte[] expected, byte[] actual, String description) {
        if (!Arrays.equals(expected, actual)) {
            fail(description + ": expected " + Arrays.toString(expected)
                    + " but got " + Arrays.toString(actual));
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
            int digit = LEGACY_ALPHABET_STRING.indexOf(character);
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
