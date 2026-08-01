package blue.language.provider.ipfs;

import blue.language.provider.AbstractNodeProvider;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;

/**
 * Read-only provider that maps BlueIds to raw CIDv1 values and fetches their
 * JSON content from the IPFS gateway.
 *
 * <p>Transport failures are exposed through the legacy provider API as
 * misses.</p>
 */
public class IPFSNodeProvider extends AbstractNodeProvider {

    private static final ObjectMapper JSON = createJsonMapper();

    /** Creates a read-only provider using the configured public IPFS gateway. */
    public IPFSNodeProvider() {
    }

    @Override
    protected JsonNode fetchContentByBlueId(String baseBlueId) {
        String cid = BlueIdToCid.convert(baseBlueId);
        String content;
        try {
            content = IPFSContentFetcher.fetchContent(cid);
        } catch (IOException e) {
            return null;
        }
        return parseContent(content);
    }

    /** Parses a successful gateway response using Language-compatible JSON rules. */
    static JsonNode parseContent(String content) {
        try {
            return JSON.readTree(content);
        } catch (IOException e) {
            throw new MalformedIpfsContentException(e);
        }
    }

    private static ObjectMapper createJsonMapper() {
        ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        mapper.setNodeFactory(new JsonNodeFactory(true));
        return mapper;
    }
}

/** Signals malformed JSON returned by a successful IPFS gateway request. */
final class MalformedIpfsContentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Retains the Jackson parsing failure without converting it to a miss. */
    MalformedIpfsContentException(IOException cause) {
        super(cause);
    }
}
