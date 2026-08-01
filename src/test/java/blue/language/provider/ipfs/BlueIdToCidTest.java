package blue.language.provider.ipfs;

import blue.language.identity.Base58;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    private static final char[] BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
                    .toCharArray();
    private static final String[] LEADING_ZERO_INPUTS = {
            "", "1", "11", "12", "1112", ZERO_SHA_256_BLUE_ID
    };
    private static final char[] INVALID_BASE58_CHARACTERS = {
            '0', 'O', 'I', 'l', '+', '/', ' ', '\t', '\u0000', '\u00e9', '\u20ac'
    };
    private static final long DIFFERENTIAL_RANDOM_SEED = 0x1F55BA5E58L;
    private static final int DIFFERENTIAL_CASE_COUNT = 10_000;
    private static final int MAX_DIFFERENTIAL_INPUT_LENGTH = 96;

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

    @Test
    void shouldPreserveHistoricalLeadingZeroDecoding() {
        // given
        byte[][] decoded = new byte[LEADING_ZERO_INPUTS.length][];

        // when
        for (int index = 0; index < LEADING_ZERO_INPUTS.length; index++) {
            decoded[index] = IpfsBase58.decode(LEADING_ZERO_INPUTS[index]);
        }

        // then
        for (int index = 0; index < LEADING_ZERO_INPUTS.length; index++) {
            assertArrayEquals(
                    Base58.decode(LEADING_ZERO_INPUTS[index]),
                    decoded[index],
                    "leading-zero input " + index);
        }
    }

    @Test
    void shouldMatchLanguageDecoderAcrossSeededValidInputs() {
        // given
        Random random = new Random(DIFFERENTIAL_RANDOM_SEED);
        String[] inputs = new String[DIFFERENTIAL_CASE_COUNT];
        for (int iteration = 0; iteration < inputs.length; iteration++) {
            char[] input = new char[random.nextInt(MAX_DIFFERENTIAL_INPUT_LENGTH)];
            if (iteration % 97 == 0) {
                Arrays.fill(input, BASE58_ALPHABET[0]);
            } else {
                for (int index = 0; index < input.length; index++) {
                    input[index] = BASE58_ALPHABET[
                            random.nextInt(BASE58_ALPHABET.length)];
                }
            }
            inputs[iteration] = new String(input);
        }
        byte[][] decoded = new byte[inputs.length][];

        // when
        for (int index = 0; index < inputs.length; index++) {
            decoded[index] = IpfsBase58.decode(inputs[index]);
        }

        // then
        for (int index = 0; index < inputs.length; index++) {
            assertArrayEquals(
                    Base58.decode(inputs[index]),
                    decoded[index],
                    "seeded Base58 input " + index);
        }
    }

    @Test
    void shouldRejectEveryCharacterOutsideTheBlueIdBase58Alphabet() {
        // given
        char[] invalidCharacters = INVALID_BASE58_CHARACTERS;

        // when
        Executable[] conversions = new Executable[invalidCharacters.length];
        for (int index = 0; index < invalidCharacters.length; index++) {
            char character = invalidCharacters[index];
            conversions[index] = () -> BlueIdToCid.convert("2" + character + "3");
        }

        // then
        for (int index = 0; index < conversions.length; index++) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    conversions[index]);
            assertEquals(
                    "Invalid character found: " + invalidCharacters[index],
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectNullBlueIdLikeTheLanguageDecoder() {
        // given
        String absentBlueId = null;

        // when
        Executable conversion = () -> BlueIdToCid.convert(absentBlueId);

        // then
        assertThrows(NullPointerException.class, conversion);
    }

    @Test
    void shouldSurfaceMalformedIpfsContentAsRuntimeFailure() {
        // given
        String malformedContent = "{\"value\":";

        // when
        Executable parsing = () -> IPFSNodeProvider.parseContent(malformedContent);

        // then
        assertThrows(MalformedIpfsContentException.class, parsing);
    }
}
