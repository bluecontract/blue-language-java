package blue.lang.model.limits;

import java.util.List;

public class Limits {
    public static LimitsInterface depth(int maxDepth) {
        if (maxDepth < 1) {
            return END_LIMITS;
        }
        return new DepthLimits(maxDepth);
    }

    public static LimitsInterface path(String path) {
        return new PathLimits(path);
    }

    public static final LimitsInterface NO_LIMITS = new NoLimits();

    public static final LimitsInterface END_LIMITS = new EndLimits();

    public static LimitsInterface query(List<String> paths, int depth) {
        return new QueryLimits(paths, depth);
    }

    public static LimitsInterface query(List<String> paths) {
        return new QueryLimits(paths);
    }
}