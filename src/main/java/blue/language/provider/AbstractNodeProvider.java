package blue.language.provider;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIds;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;

/**
 * Base provider that converts stored JSON content into Blue nodes and resolves
 * {@code this} placeholders against the requested base identity.
 *
 * <p>Subclasses supply content only for the part before an optional
 * {@code #index}; this class selects cyclic/list members and assigns the
 * requested root identity.</p>
 */
public abstract class AbstractNodeProvider implements NodeProvider {

    /** Creates a provider backed by subclass-defined JSON content lookup. */
    public AbstractNodeProvider() {
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        final String baseBlueId =
                blueId.split(BlueIds.CYCLIC_MEMBER_SEPARATOR)[0];
        final JsonNode content = fetchContentByBlueId(baseBlueId);
        if (content == null) {
            return null;
        }

        boolean isMultipleDocuments = content.isArray() && content.size() > 1;
        final JsonNode resolvedContent = NodeContentHandler.resolveThisReferences(content, baseBlueId, isMultipleDocuments);

        if (BlueIds.hasCyclicMemberSeparator(blueId)) {
            String[] parts =
                    blueId.split(BlueIds.CYCLIC_MEMBER_SEPARATOR);
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
                    .mapToObj(i -> JSON_MAPPER.convertValue(resolvedContent.get(i), Node.class))
                    .collect(Collectors.toList());
        } else {
            Node node = JSON_MAPPER.convertValue(resolvedContent, Node.class);
            return Collections.singletonList(node.blueId(baseBlueId));
        }
    }

    /**
     * Returns stored content for a plain base BlueId.
     *
     * @param baseBlueId identity without a cyclic-member suffix
     * @return stored JSON content, or {@code null} on a miss
     */
    protected abstract JsonNode fetchContentByBlueId(String baseBlueId);
}
