package blue.lang.model.limits;

public class Limits {
    public static LimitsInterface depth(int maxDepth) {
        return new DepthLimits(maxDepth);
    }

    public static LimitsInterface path(String path) {
        return new PathLimits(path);
    }

    public static final LimitsInterface NO_LIMITS = new NoLimits();
}