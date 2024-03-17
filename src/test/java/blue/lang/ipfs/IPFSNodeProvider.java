package blue.lang.ipfs;

import blue.lang.Blue;
import blue.lang.Node;
import blue.lang.NodeProvider;
import blue.lang.model.BlueObject;
import blue.lang.utils.UncheckedObjectMapper;

import java.io.IOException;

public class IPFSNodeProvider implements NodeProvider {
    @Override
    public Node fetchByBlueId(String blueId) {
        String cid = BlueIdToCid.convert(blueId);
        try {
            String content = IPFSContentFetcher.fetchContent(cid);
            BlueObject object = UncheckedObjectMapper.JSON_MAPPER.readValue(content, BlueObject.class);
            return new Blue().resolve(object);
        } catch (IOException e) {
            return null;
        }
    }
}
