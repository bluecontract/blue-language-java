package blue.lang.model.limits;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class QueryLimits implements Limits {

    public static Limits paths(List<String> paths) {
        return new QueryLimits(paths);
    }

    public static Limits paths(List<String> paths, int depth) {
        return new QueryLimits(paths, depth);
    }

    public static Limits pathLimits(List<Limits> paths, Limits depthLimit) {
        return new QueryLimits(paths, depthLimit);
    }

    private final List<Limits> pathLimits;
    private Limits depthLimits;

    public QueryLimits(List<String> paths, int depth) {
        this.pathLimits = paths.stream().map(PathLimits::path).collect(Collectors.toList());
        this.depthLimits = new DepthLimits(depth);
    }
    public QueryLimits(List<String> paths) {
        this.pathLimits = paths.stream().map(Limits::path).collect(Collectors.toList());
        this.depthLimits = Limits.NO_LIMITS;
    }

    public QueryLimits(List<Limits> paths, Limits depthLimit) {
        this.pathLimits = paths;
        this.depthLimits = depthLimit;
    }

    @Override
    public boolean canReadNext() {
        if (!depthLimits.canReadNext()) {
            return false;
        }

        return pathLimits.stream()
                .map(Limits::canReadNext)
                .filter(e -> e)
                .count() > 0;
    }

    @Override
    public Limits next(boolean forTypeInference) {
        return QueryLimits.pathLimits(pathLimits.stream()
                        .map(e -> e.next(forTypeInference))
                        .collect(Collectors.toList()),
                depthLimits.next(forTypeInference));
    }

    @Override
    public Limits next(String pathName) {
        List<Limits> paths = pathLimits.stream()
                .map(e -> e.next(pathName))
                .filter(e -> e != Limits.END_LIMITS)
                .collect(Collectors.toList());
        if (paths.isEmpty()) {
            return Limits.END_LIMITS;
        }
        Limits depth = depthLimits.next(pathName);
        if (depth == Limits.END_LIMITS) {
            return Limits.END_LIMITS;
        }
        return QueryLimits.pathLimits(paths,
                depthLimits.next(pathName));
    }

    @Override
    public boolean filter(String name) {
        return pathLimits.stream()
                .anyMatch(e -> e.filter(name));
    }

    @Override
    public boolean canReadIndex(int index) {
        return pathLimits.stream()
                .anyMatch(e -> e.canReadIndex(index));
    }

    @Override
    public Limits and(Limits other) {
        if (other instanceof DepthLimits) {
            depthLimits = depthLimits.and(other);
            return this;
        }
        if (other instanceof PathLimits) {
            pathLimits.add(other);
            return this;
        }
        if (other instanceof QueryLimits) {
            pathLimits.addAll(((QueryLimits) other).pathLimits);
            depthLimits = depthLimits.and(((QueryLimits) other).depthLimits);
            return this;
        }
        return this;
    }

    @Override
    public boolean canCopyMetadata() {
        return pathLimits.stream()
                .anyMatch(Limits::canCopyMetadata);
    }

    public Limits copy() {
        return new QueryLimits(pathLimits.stream()
                .map(Limits::copy)
                .collect(Collectors.toList()),
                depthLimits.copy());
    }
}
