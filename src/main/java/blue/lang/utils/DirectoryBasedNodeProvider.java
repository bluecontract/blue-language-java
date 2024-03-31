package blue.lang.utils;

import blue.lang.Node;
import blue.lang.NodeProvider;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.lang.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;
import static java.util.Collections.singletonList;

public class DirectoryBasedNodeProvider implements NodeProvider {

    private List<Node> nodes;
    private Map<String, Node> blueIdToNodeMap;

    public DirectoryBasedNodeProvider(String... directories) throws IOException {
        this.nodes = load(directories);
        this.blueIdToNodeMap = nodes.stream()
                .collect(Collectors.toMap(BlueIdCalculator::calculateBlueId, Function.identity()));
    }

    private List<Node> load(String... directories) throws IOException {
        List<Node> result = new ArrayList<>();
        for (String directory : directories) {
            Path path;
            try {
                path = Paths.get(ClassLoader.getSystemResource(directory).toURI());
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            try (Stream<Path> paths = Files.walk(path)) {
                List<Path> pathList = paths
                        .filter(Files::isRegularFile)
                        .collect(Collectors.toList());
                for (Path p : pathList) {
                    try {
                        result.add(YAML_MAPPER.readValue(p.toFile(), Node.class));
                    } catch(RuntimeException ex) {
                        result.add(JSON_MAPPER.readValue(p.toFile(), Node.class));
                    }
                }
            }
        }
        return result;
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        Node node = blueIdToNodeMap.get(blueId);
        if (node == null)
            return null;
        return singletonList(node.clone());
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public Map<String, Node> getBlueIdToNodeMap() {
        return blueIdToNodeMap;
    }
}