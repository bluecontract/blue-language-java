package blue.language.samples.ipfs;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.NodeProvider;
import blue.language.utils.UncheckedObjectMapper;

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
