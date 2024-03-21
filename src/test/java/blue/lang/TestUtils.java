package blue.lang;

import blue.lang.utils.DirectoryBasedNodeProvider;

import java.io.IOException;
import java.util.List;

public class TestUtils {

    public static DirectoryBasedNodeProvider samplesDirectoryNodeProvider() throws IOException {
        return new DirectoryBasedNodeProvider("samples");
    }

    public static NodeProvider useNodeNameAsBlueIdProvider(List<Node> nodes) {
        return (blueId) -> nodes.stream()
                .filter(e -> blueId.equals(e.getName()))
                .findAny()
                .map(Node::clone)
                .orElse(null);
    }

    public static MergingProcessor numbersMustIncreasePayloadMerger() {
        return (target, source, nodeProvider, nodeResolver) -> {
            Integer targetValue = (Integer) target.getValue();
            Integer sourceValue = (Integer) source.getValue();
            if (sourceValue == null)
                return;
            if (targetValue != null && targetValue > sourceValue)
                throw new IllegalArgumentException("targetValue > sourceValue, " + targetValue + ", " + sourceValue);
            target.value(sourceValue);
        };
    }

}
