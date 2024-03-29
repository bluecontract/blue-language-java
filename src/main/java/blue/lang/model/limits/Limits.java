package blue.lang.model.limits;

import java.util.List;

public interface Limits {
    static Limits depth(int maxDepth) {
        if (maxDepth < 1) {
            return END_LIMITS;
        }
        return new DepthLimits(maxDepth);
    }

    static Limits path(String path) {
        if (path.equals("/")) {
            return END_LIMITS;
        }
        return new PathLimits(path);
    }

    static Limits NO_LIMITS = new NoLimits();

    static Limits END_LIMITS = new EndLimits();

    public static Limits query(List<String> paths, int depth) {
        return new QueryLimits(paths, depth);
    }

    public static Limits query(List<String> paths) {
        return new QueryLimits(paths);
    }
    boolean canReadNext();
    default boolean canReadIndex(int index) {
        return canReadNext();
    }
    Limits next(boolean forTypeInference);
    default Limits next(String pathName) {
        return next(false);
    }
    boolean filter(String name);
    default Limits and(Limits other) {
        return this;
    }

    default boolean canCopyMetadata() {
        return true;
    }

    default Limits copy() {
        return this;
    }
}
