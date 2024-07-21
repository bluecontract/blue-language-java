package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

public abstract class AbstractNodeProvider implements NodeProvider {

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        final String baseBlueId = blueId.split("#")[0];
        final JsonNode content = fetchContentByBlueId(baseBlueId);
        if (content == null) {
            return null;
        }

        boolean isMultipleDocuments = content.isArray() && content.size() > 1;
        final JsonNode resolvedContent = NodeContentHandler.resolveThisReferences(content, baseBlueId, isMultipleDocuments);

        if (blueId.contains("#")) {
            String[] parts = blueId.split("#");
            if (parts.length > 1) {
                int index = Integer.parseInt(parts[1]);
                if (resolvedContent.isArray() && index < resolvedContent.size()) {
                    JsonNode item = resolvedContent.get(index);
                    Node node = JSON_MAPPER.convertValue(item, Node.class);
                    return Collections.singletonList(node.blueId(blueId));
                } else if (index == 0) {
                    Node node = JSON_MAPPER.convertValue(resolvedContent, Node.class);
                    return Collections.singletonList(node.blueId(blueId));
                } else {
                    return null;
                }
            }
        }

        if (resolvedContent.isArray()) {
            return IntStream.range(0, resolvedContent.size())
                    .mapToObj(i -> {
                        Node node = JSON_MAPPER.convertValue(resolvedContent.get(i), Node.class);
                        return node.blueId(baseBlueId + "#" + i);
                    })
                    .collect(Collectors.toList());
        } else {
            Node node = JSON_MAPPER.convertValue(resolvedContent, Node.class);
            return Collections.singletonList(node.blueId(baseBlueId));
        }
    }

    protected abstract JsonNode fetchContentByBlueId(String baseBlueId);
}