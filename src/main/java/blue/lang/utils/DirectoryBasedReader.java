package blue.lang.utils;

import blue.lang.model.BlueObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class DirectoryBasedReader {

    public static void main(String[] args) throws IOException {
        String dir = "blue/1";
        List<BlueObject> objs = new DirectoryBasedReader().load(dir);
        System.out.println(objs.size());
    }

    public static List<BlueObject> load(String... directories) throws IOException {
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
                        .filter(p -> p.toString().endsWith(".blue"))
                        .collect(Collectors.toList());
                for (Path p : pathList)
                    result.add(YAML_MAPPER.readValue(p.toFile(), BlueObject.class));
            }
        }
        return result;
    }

}
