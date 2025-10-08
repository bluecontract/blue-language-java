package blue.language.processor.model;

import blue.language.model.TypeBlueId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@TypeBlueId("ProcessEmbedded")
public class ProcessEmbedded extends MarkerContract {

    private final List<String> paths = new ArrayList<>();

    public List<String> getPaths() {
        return Collections.unmodifiableList(paths);
    }

    public void setPaths(List<String> newPaths) {
        paths.clear();
        if (newPaths != null) {
            paths.addAll(newPaths);
        }
    }

    public ProcessEmbedded addPath(String path) {
        if (path != null) {
            paths.add(path);
        }
        return this;
    }
}
