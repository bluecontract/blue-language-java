package blue.lang.utils;

import blue.lang.Blue;
import blue.lang.Node;
import blue.lang.NodeProvider;
import blue.lang.model.BlueObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.lang.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class DirectoryBasedNodeProvider implements NodeProvider {

    private List<BlueObject> objects;
    private Map<String, Node> blueIdToNodeMap;

    public DirectoryBasedNodeProvider(String... directories) throws IOException {
        this.objects = load(directories);
        Blue blue = new Blue();
        this.blueIdToNodeMap = objects.stream()
                .collect(Collectors.toMap(blue::resolveToBlueId, BlueObjectToNode::convert));
    }

    private List<BlueObject> load(String... directories) throws IOException {
        List<BlueObject> result = new ArrayList<>();
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
                        result.add(YAML_MAPPER.readValue(p.toFile(), BlueObject.class));
                    } catch(RuntimeException ex) {
                        result.add(JSON_MAPPER.readValue(p.toFile(), BlueObject.class));
                    }
                }
            }
        }
        return result;
    }

    @Override
    public Node fetchByBlueId(String blueId) {
        return blueIdToNodeMap.get(blueId);
    }

    public List<BlueObject> getObjects() {
        return objects;
    }

    public Map<String, Node> getBlueIdToNodeMap() {
        return blueIdToNodeMap;
    }
}