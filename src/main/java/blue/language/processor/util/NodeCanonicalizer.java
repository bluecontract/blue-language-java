package blue.language.processor.util;

import blue.language.model.Node;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.nio.charset.StandardCharsets;

/**
 * Utility for producing canonical JSON sizes used in gas accounting.
 */
public final class NodeCanonicalizer {

    private NodeCanonicalizer() {
    }

    public static long canonicalSize(Node node) {
        if (node == null) {
            return 0L;
        }
        Object canonical = NodeToMapListOrValue.get(node);
        try {
            String json = UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(canonical);
            String canonicalJson = new JsonCanonicalizer(json).getEncodedString();
            return canonicalJson.getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize node", ex);
        }
    }
}
