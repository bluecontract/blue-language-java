package blue.lang.model.limits;

import java.util.Arrays;
import java.util.List;

public class PathLimits implements LimitsInterface {

    public static LimitsInterface path(String path) {
        return new PathLimits(path);
    }

    private List<String> path;

    public PathLimits(String path) {
        this.path = Arrays.asList(path.split("/"));
    }

    @Override
    public boolean canReadNext() {
        return !path.isEmpty();
    }

    @Override
    public LimitsInterface next(boolean forTypeInference) {
        if (path.size() <= 1) {
            return new EndLimits();
        }
        if (forTypeInference) {
            return this;
        }
        List<String> subList = path.subList(1, path.size());
        return new PathLimits(String.join("/", subList));
    }

    @Override
    public boolean filter(String name) {
        if (path.get(0).equals("*")) {
            return true;
        }
        return path.get(0).equals(name);
    }
}
