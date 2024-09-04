package blue.language;

import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.provider.DirectoryBasedNodeProvider;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class TestUtils {

    public static DirectoryBasedNodeProvider samplesDirectoryNodeProvider() throws IOException {
        return new DirectoryBasedNodeProvider("src/test/resources/samples");
    }

    public static NodeProvider fakeNameBasedNodeProvider(Collection<Node> nodes) {
        return new NodeProvider() {
            private final Map<String, Node> nodeMap = nodes.stream()
                    .collect(Collectors.toMap(
                            node -> "blueId-" + node.getName(),
                            node -> node
                    ));

            @Override
            public List<Node> fetchByBlueId(String blueId) {
                Node node = nodeMap.get(blueId);
                return node != null ? Collections.singletonList(node) : new ArrayList<>();
            }
        };
    }

    public static NodeProvider useNodeNameAsBlueIdProvider(List<Node> nodes) {
        return (blueId) -> nodes.stream()
                .filter(e -> blueId.equals(e.getName()))
                .findAny()
                .map(Node::clone)
                .map(Collections::singletonList)
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

    public static String indent(String input, int spaces) {
        String indentation = new String(new char[spaces]).replace('\0', ' ');
        return Arrays.stream(input.split("\n"))
                .map(line -> indentation + line)
                .collect(Collectors.joining("\n"));
    }

}
