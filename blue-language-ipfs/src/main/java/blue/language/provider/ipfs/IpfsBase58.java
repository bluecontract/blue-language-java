package blue.language.provider.ipfs;

import java.util.Arrays;

/**
 * Minimal Base58 decoder owned by the optional IPFS integration.
 *
 * <p>Keeping this transport conversion local prevents the IPFS artifact from
 * depending on an implementation utility in the Language core. The alphabet
 * and leading-zero behavior are the same as the Base58 form used by BlueIds.
 * In particular, an empty input decodes to one zero byte and an all-{@code 1}
 * input decodes to one more zero byte than the number of characters. Those
 * representations preserve the historical CID conversion contract.</p>
 */
final class IpfsBase58 {

    private static final String ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final int RADIX = 58;
    private static final int[] ASCII_DIGITS = new int[128];

    static {
        Arrays.fill(ASCII_DIGITS, -1);
        for (int index = 0; index < ALPHABET.length(); index++) {
            ASCII_DIGITS[ALPHABET.charAt(index)] = index;
        }
    }

    private IpfsBase58() {
    }

    /**
     * Decodes an unsigned, big-endian Base58 value.
     *
     * @param input canonical Base58 representation
     * @return decoded bytes with historical leading-zero representation
     * @throws NullPointerException when {@code input} is {@code null}
     * @throws IllegalArgumentException when a character is outside the alphabet
     */
    static byte[] decode(String input) {
        byte[] digits = new byte[input.length()];
        int leadingZeros = 0;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            int digit = character < ASCII_DIGITS.length
                    ? ASCII_DIGITS[character]
                    : -1;
            if (digit < 0) {
                throw new IllegalArgumentException(
                        "Invalid character found: " + character);
            }
            digits[index] = (byte) digit;
            if (index == leadingZeros && digit == 0) {
                leadingZeros++;
            }
        }

        if (leadingZeros == input.length()) {
            return new byte[leadingZeros + 1];
        }

        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;
        int inputStart = leadingZeros;
        while (inputStart < digits.length) {
            int remainder = divideBy256(digits, inputStart);
            decoded[--outputStart] = (byte) remainder;
            if (digits[inputStart] == 0) {
                inputStart++;
            }
        }

        while (outputStart < decoded.length && decoded[outputStart] == 0) {
            outputStart++;
        }
        return Arrays.copyOfRange(
                decoded, outputStart - leadingZeros, decoded.length);
    }

    private static int divideBy256(byte[] digits, int start) {
        int remainder = 0;
        for (int index = start; index < digits.length; index++) {
            int value = remainder * RADIX + (digits[index] & 0xff);
            digits[index] = (byte) (value / 256);
            remainder = value % 256;
        }
        return remainder;
    }
}
