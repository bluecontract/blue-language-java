package blue.language.identity;

/**
 * Encodes and decodes the canonical Bitcoin-style Base58 alphabet used by
 * BlueIds.
 *
 * <p>Leading zero bytes round-trip as leading {@code '1'} characters. The
 * decoder deliberately preserves the library's historical representation of
 * an empty or all-zero input.</p>
 */
public class Base58 {
    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final int CHUNK_DIGITS = 5;
    private static final int CHUNK_BASE = 656_356_768; // 58^5, small enough for signed-long limb division.
    private static final int[] ASCII_DECODE_TABLE = new int[128];

    static {
        java.util.Arrays.fill(ASCII_DECODE_TABLE, -1);
        for (int index = 0; index < ALPHABET.length; index++) {
            ASCII_DECODE_TABLE[ALPHABET[index]] = index;
        }
    }

    /**
     * Creates a compatibility codec instance.
     *
     * <p>Encoding and decoding operations are stateless static methods.</p>
     */
    public Base58() {
    }

    /**
     * Encodes an unsigned big-endian byte sequence without separators or
     * padding.
     *
     * @param input bytes to encode
     * @return canonical Base58 representation
     */
    public static String encode(byte[] input) {
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) {
            leadingZeros++;
        }
        if (leadingZeros == input.length) {
            return new String(repeatLeadingZeroCharacters(leadingZeros));
        }

        int remainingBytes = input.length - leadingZeros;
        int wordCount = (remainingBytes + 3) / 4;
        int[] words = new int[wordCount];
        int inputIndex = leadingZeros;
        int firstWordBytes = remainingBytes - (wordCount - 1) * 4;
        for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
            int bytesInWord = wordIndex == 0 ? firstWordBytes : 4;
            int word = 0;
            for (int byteIndex = 0; byteIndex < bytesInWord; byteIndex++) {
                word = (word << 8) | (input[inputIndex++] & 0xFF);
            }
            words[wordIndex] = word;
        }

        char[] encoded = new char[input.length * 2];
        int outputStart = encoded.length;
        int firstWord = 0;
        while (firstWord < wordCount) {
            long remainder = 0L;
            for (int wordIndex = firstWord; wordIndex < wordCount; wordIndex++) {
                long value = (remainder << 32) + (words[wordIndex] & 0xFFFFFFFFL);
                words[wordIndex] = (int) (value / CHUNK_BASE);
                remainder = value % CHUNK_BASE;
            }
            while (firstWord < wordCount && words[firstWord] == 0) {
                firstWord++;
            }
            int digits = firstWord == wordCount ? 0 : CHUNK_DIGITS;
            if (digits == 0) {
                do {
                    encoded[--outputStart] = ALPHABET[(int) (remainder % 58)];
                    remainder /= 58;
                } while (remainder != 0L);
            } else {
                for (int digit = 0; digit < digits; digit++) {
                    encoded[--outputStart] = ALPHABET[(int) (remainder % 58)];
                    remainder /= 58;
                }
            }
        }

        while (leadingZeros-- > 0) {
            encoded[--outputStart] = ALPHABET[0];
        }
        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    /**
     * Decodes a canonical-alphabet string into its unsigned big-endian bytes.
     *
     * @param input canonical Base58 representation
     * @return decoded unsigned big-endian bytes
     * @throws IllegalArgumentException if {@code input} contains a character
     *                                  outside the Base58 alphabet
     */
    public static byte[] decode(String input) {
        int leadingZeros = 0;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            int digit = character < ASCII_DECODE_TABLE.length
                    ? ASCII_DECODE_TABLE[character]
                    : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid character found: " + character);
            }
            if (index == leadingZeros && digit == 0) {
                leadingZeros++;
            }
        }

        // Preserve the historical BigInteger decoder's zero representation:
        // decode("") is {0}, and each all-'1' input has one additional zero.
        if (leadingZeros == input.length()) {
            return new byte[leadingZeros + 1];
        }

        int significantDigits = input.length() - leadingZeros;
        int[] words = new int[Math.max(1, (significantDigits * 6 + 31) / 32)];
        int wordCount = 0;
        for (int offset = leadingZeros; offset < input.length();) {
            int chunkLength = Math.min(CHUNK_DIGITS, input.length() - offset);
            int multiplier = 1;
            int chunk = 0;
            for (int index = 0; index < chunkLength; index++) {
                int digit = ASCII_DECODE_TABLE[input.charAt(offset + index)];
                chunk = chunk * 58 + digit;
                multiplier *= 58;
            }
            offset += chunkLength;

            long carry = chunk;
            for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
                long value = (words[wordIndex] & 0xFFFFFFFFL) * multiplier + carry;
                words[wordIndex] = (int) value;
                carry = value >>> 32;
            }
            if (carry != 0L) {
                words[wordCount++] = (int) carry;
            } else if (wordCount == 0 && chunk != 0) {
                wordCount = 1;
            }
        }

        long highestWord = words[wordCount - 1] & 0xFFFFFFFFL;
        int highestBytes = 1;
        while ((highestWord >>> (highestBytes * 8)) != 0L) {
            highestBytes++;
        }
        int magnitudeBytes = (wordCount - 1) * 4 + highestBytes;
        byte[] decoded = new byte[leadingZeros + magnitudeBytes];
        int outputIndex = decoded.length;
        for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
            int bytes = wordIndex == wordCount - 1 ? highestBytes : 4;
            int word = words[wordIndex];
            for (int byteIndex = 0; byteIndex < bytes; byteIndex++) {
                decoded[--outputIndex] = (byte) (word >>> (byteIndex * 8));
            }
        }
        return decoded;
    }

    private static char[] repeatLeadingZeroCharacters(int count) {
        char[] zeros = new char[count];
        java.util.Arrays.fill(zeros, ALPHABET[0]);
        return zeros;
    }
}
