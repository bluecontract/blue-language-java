package blue.language.provider.ipfs;

import blue.language.provider.AbstractNodeProvider;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class IPFSNodeProvider extends AbstractNodeProvider {
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