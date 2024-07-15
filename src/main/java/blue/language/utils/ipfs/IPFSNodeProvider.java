package blue.language.utils.ipfs;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.UncheckedObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class IPFSNodeProvider implements NodeProvider {
    @Override
    public List<Node> fetchByBlueId(String blueId) {
        String cid = BlueIdToCid.convert(blueId);
        try {
            String content = IPFSContentFetcher.fetchContent(cid);
            Object contentObject = UncheckedObjectMapper.JSON_MAPPER.readValue(content, Object.class);
            if (contentObject instanceof List) {
                List<?> contentList = (List<?>) contentObject;
                List<Node> nodes = contentList.stream()
                        .map(item -> UncheckedObjectMapper.JSON_MAPPER.convertValue(item, Node.class))
                        .collect(Collectors.toList());
                return nodes;
            } else {
                Node node = UncheckedObjectMapper.JSON_MAPPER.convertValue(contentObject, Node.class);
                return Collections.singletonList(node);
            }
        } catch (IOException e) {
            return null;
        }
    }
}
