package blue.lang.samples.ipfs;

import blue.lang.Blue;
import blue.lang.Node;
import blue.lang.NodeProvider;
import blue.lang.utils.UncheckedObjectMapper;

import java.io.IOException;
import java.util.List;

import static java.util.Collections.singletonList;

public class IPFSNodeProvider implements NodeProvider {
    @Override
    public List<Node> fetchByBlueId(String blueId) {
        String cid = BlueIdToCid.convert(blueId);
        try {
            String content = IPFSContentFetcher.fetchContent(cid);
            Node node = UncheckedObjectMapper.JSON_MAPPER.readValue(content, Node.class);
            return singletonList(new Blue().resolve(node));
        } catch (IOException e) {
            return null;
        }
    }
}
