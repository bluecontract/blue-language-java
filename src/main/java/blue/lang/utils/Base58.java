package blue.lang.utils;

import java.math.BigInteger;

public class Base58 {
    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final BigInteger BASE_58 = BigInteger.valueOf(58);

    public static String encode(byte[] input) {
        BigInteger value = new BigInteger(1, input);
        StringBuilder base58 = new StringBuilder();

        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divmod = value.divideAndRemainder(BASE_58);
            base58.insert(0, ALPHABET[divmod[1].intValue()]);
            value = divmod[0];
        }

        // Encode leading zeros as '1's.
        int i = 0;
        while (i < input.length && input[i] == 0) {
            base58.insert(0, ALPHABET[0]);
            i++;
        }

        return base58.toString();
    }

    public static byte[] decode(String input) {
        BigInteger num = BigInteger.ZERO;
        for (char t : input.toCharArray()) {
            int p = new String(ALPHABET).indexOf(t);
            if (p == -1) {
                throw new IllegalArgumentException("Invalid character found: " + t);
            }
            num = num.multiply(BASE_58).add(BigInteger.valueOf(p));
        }

        byte[] bytes = num.toByteArray();
        boolean stripSignByte = bytes.length > 1 && bytes[0] == 0 && bytes[1] < 0;
        int leadingZeros = 0;
        for (int i = 0; i < input.length() && input.charAt(i) == ALPHABET[0]; i++) {
            leadingZeros++;
        }

        byte[] tmp = new byte[bytes.length - (stripSignByte ? 1 : 0) + leadingZeros];
        System.arraycopy(bytes, stripSignByte ? 1 : 0, tmp, leadingZeros, tmp.length - leadingZeros);
        return tmp;
    }
}