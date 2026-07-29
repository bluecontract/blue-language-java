package blue.language.provider.ipfs;

import blue.language.provider.AbstractNodeProvider;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Read-only provider that maps BlueIds to raw CIDv1 values and fetches their
 * JSON content from the IPFS gateway.
 *
 * <p>Transport failures are exposed through the legacy provider API as
 * misses.</p>
 */
public class IPFSNodeProvider extends AbstractNodeProvider {

    /** Creates a read-only provider using the configured public IPFS gateway. */
    public IPFSNodeProvider() {
    }

    @Override
    protected JsonNode fetchContentByBlueId(String baseBlueId) {
        String cid = BlueIdToCid.convert(baseBlueId);
        try {
            String content = IPFSContentFetcher.fetchContent(cid);
            return UncheckedObjectMapper.JSON_MAPPER.readTree(content);
        } catch (IOException e) {
            return null;
        }
    }
}
