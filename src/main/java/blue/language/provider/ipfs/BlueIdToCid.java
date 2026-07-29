package blue.language.provider.ipfs;

import blue.language.utils.Base58;
import org.apache.commons.codec.binary.Base32;

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

        Base32 base32 = new Base32();
        String cid = BASE32_MULTIBASE_PREFIX
                + base32.encodeAsString(cidBytes).toLowerCase().replaceAll("=", "");

        return cid;
    }

}
