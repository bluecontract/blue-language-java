package blue.language.utils.ipfs;

import blue.language.utils.Base58;
import org.apache.commons.codec.binary.Base32;

public class BlueIdToCid {

    public static String convert(String blueId) {
        byte[] sha256Bytes = Base58.decode(blueId);

        // Create the multihash bytes for SHA-256 (0x12 for the hash function and 0x20 for the length)
        byte[] multihash = new byte[2 + sha256Bytes.length];
        multihash[0] = 0x12; // SHA-256
        multihash[1] = 0x20; // 32 bytes (256 bits)
        System.arraycopy(sha256Bytes, 0, multihash, 2, sha256Bytes.length);

        // Create the CIDv1 bytes with version byte (0x01) and codec for raw (0x55)
        byte[] cidBytes = new byte[2 + multihash.length];
        cidBytes[0] = 0x01; // CIDv1
        cidBytes[1] = 0x55; // raw binary data
        System.arraycopy(multihash, 0, cidBytes, 2, multihash.length);

        // Encode the CIDv1 with Base32
        Base32 base32 = new Base32();
        String cid = "b" + base32.encodeAsString(cidBytes).toLowerCase().replaceAll("=", "");

        return cid;
    }

}