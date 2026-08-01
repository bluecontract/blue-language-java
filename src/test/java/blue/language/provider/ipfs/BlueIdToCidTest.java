package blue.language.provider.ipfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies dependency-free CIDv1 conversion against fixed compatibility vectors. */
final class BlueIdToCidTest {

    private static final String ZERO_SHA_256_BLUE_ID =
            "11111111111111111111111111111111";
    private static final String ZERO_SHA_256_CID =
            "bafkreiaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SEQUENTIAL_SHA_256_BLUE_ID =
            "1thX6LZfHDZZKUs92febYZhYRcXddmzfzF2NvTkPNE";
    private static final String SEQUENTIAL_SHA_256_CID =
            "bafkreiaaaebagbafaydqqcikbmga2dqpcaireeyuculbogazdinryhi6d4";

    @Test
    void shouldConvertZeroDigestToLowercaseUnpaddedRawCidV1() {
        // given
        String blueId = ZERO_SHA_256_BLUE_ID;

        // when
        String cid = BlueIdToCid.convert(blueId);

        // then
        assertEquals(ZERO_SHA_256_CID, cid);
    }

    @Test
    void shouldPreserveEveryBase32AlphabetBitAcrossKnownDigest() {
        // given
        String blueId = SEQUENTIAL_SHA_256_BLUE_ID;

        // when
        String cid = BlueIdToCid.convert(blueId);

        // then
        assertEquals(SEQUENTIAL_SHA_256_CID, cid);
    }
}
