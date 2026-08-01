package blue.language.provider.ipfs;

import blue.language.utils.Base58;

/**
 * Converts a Base58 SHA-256 BlueId to a CIDv1 raw-content identifier using the
 * Base32 multibase representation.
 */
public class BlueIdToCid {

    private static final byte MULTIHASH_SHA2_256_CODE = 0x12;
    private static final byte SHA_256_LENGTH_BYTES = 0x20;
    private static final byte CID_VERSION_1 = 0x01;
    private static final byte RAW_CODEC = 0x55;
    private static final String BASE32_MULTIBASE_PREFIX = "b";
    private static final char[] BASE32_ALPHABET =
            "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final int BASE32_BITS_PER_SYMBOL = 5;
    private static final int BASE32_ROUNDING_BITS =
            BASE32_BITS_PER_SYMBOL - 1;
    private static final int BASE32_VALUE_MASK = 0x1f;

    /**
     * Creates a compatibility facade over the static conversion operation.
     */
    public BlueIdToCid() {
    }

    /**
     * Converts one plain SHA-256 BlueId to its deterministic raw CIDv1.
     *
     * @param blueId Base58-encoded SHA-256 identity
     * @return lowercase Base32 multibase CIDv1
     * @throws IllegalArgumentException when the identity is not valid Base58
     */
    public static String convert(String blueId) {
        byte[] sha256Bytes = Base58.decode(blueId);

        // A CID embeds the hash algorithm and digest length before the digest.
        byte[] multihash = new byte[2 + sha256Bytes.length];
        multihash[0] = MULTIHASH_SHA2_256_CODE;
        multihash[1] = SHA_256_LENGTH_BYTES;
        System.arraycopy(sha256Bytes, 0, multihash, 2, sha256Bytes.length);

        // Blue content is addressed as a CIDv1 raw block.
        byte[] cidBytes = new byte[2 + multihash.length];
        cidBytes[0] = CID_VERSION_1;
        cidBytes[1] = RAW_CODEC;
        System.arraycopy(multihash, 0, cidBytes, 2, multihash.length);

        return BASE32_MULTIBASE_PREFIX + encodeBase32(cidBytes);
    }

    /** Encodes bytes with the lowercase, unpadded RFC 4648 Base32 alphabet. */
    private static String encodeBase32(byte[] bytes) {
        StringBuilder encoded = new StringBuilder(
                (bytes.length * Byte.SIZE + BASE32_ROUNDING_BITS)
                        / BASE32_BITS_PER_SYMBOL);
        int buffered = 0;
        int bufferedBits = 0;
        for (byte current : bytes) {
            buffered = (buffered << Byte.SIZE) | (current & 0xff);
            bufferedBits += Byte.SIZE;
            while (bufferedBits >= BASE32_BITS_PER_SYMBOL) {
                bufferedBits -= BASE32_BITS_PER_SYMBOL;
                encoded.append(BASE32_ALPHABET[
                        (buffered >>> bufferedBits) & BASE32_VALUE_MASK]);
            }
            buffered &= (1 << bufferedBits) - 1;
        }
        if (bufferedBits > 0) {
            encoded.append(BASE32_ALPHABET[
                    (buffered << (BASE32_BITS_PER_SYMBOL - bufferedBits))
                            & BASE32_VALUE_MASK]);
        }
        return encoded.toString();
    }
}
