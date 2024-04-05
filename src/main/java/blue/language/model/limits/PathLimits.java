package blue.language.model.limits;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.Integer.parseInt;

public class PathLimits implements Limits {

    public static Limits path(String path) {
        return new PathLimits(path);
    }

    private List<String> path;

    private boolean isInitialPath = true;

    public PathLimits(String path) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Path must start with /");
        }
        if (path.charAt(0) != '/') {
            throw new IllegalArgumentException("Path must start with /");
        }

        path = path.substring(1);

        this.path = Arrays.asList(path.split("/"));
    }

    @Override
    public boolean canReadNext() {
        return !path.isEmpty();
    }

    @Override
    public boolean canReadIndex(int index) {
        if (path.get(0).equals("*")) {
            return true;
        }

        Optional<Integer> lowerBound = lowerBound();
        Optional<Integer> upperBound = upperBound();
        if (lowerBound.isPresent() && upperBound.isPresent()) {
            return index >= lowerBound.get() && index <= upperBound.get();
        }
        Optional<Integer> singleIndex = convertToIndex(path.get(0));
        return singleIndex.filter(integer -> integer == index).isPresent();
    }

    private Optional<Integer> lowerBound() {
        return boundIndex(0);
    }
    private Optional<Integer> upperBound() {
        return boundIndex(1);
    }

    private Optional<Integer> boundIndex(int index) {
        String[] bounds = path.get(0).split("-");
        if (bounds.length != 2) {
            return Optional.empty();
        }
        return convertToIndex(bounds[index]);
    }

    private boolean isRange(String path) {
        return path.contains("-");
    }

    private boolean isSingleIndex(String path) {
        return !isRange(path) && convertToIndex(path).isPresent();
    }

    private boolean isHandlingIndex(String path) {
        return isRange(path) || isSingleIndex(path);
    }

    private Optional<Integer> convertToIndex(String index) {
        try {
            int number = parseInt(index);
            return Optional.of(number);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public Limits next(boolean forTypeInference) {
        if (forTypeInference) {
            return this;
        }
        if (path.size() <= 1) {
            return Limits.END_LIMITS;
        }
        List<String> subList = path.subList(1, path.size());

        // Replace indexes with wildcards for future merges
        path = path.stream().map(e -> {
            if (isHandlingIndex(e)) {
                return "*";
            }
            return e;
        }).collect(Collectors.toList());

        if (subList.get(0).equals("**")) {
            return Limits.NO_LIMITS;
        }

        String newPath = "/" + String.join("/", subList);
        PathLimits l = (PathLimits) Limits.path(newPath);
        l.isInitialPath = false;
        return l;
    }

    @Override
    public Limits nextForTypeStrip() {
        if (path.size() <= 1) {
            return Limits.END_LIMITS;
        }
        List<String> subList = path.subList(1, path.size());

        if (subList.get(0).equals("**")) {
            return Limits.NO_LIMITS;
        }

        String newPath = "/" + String.join("/", subList);
        PathLimits l = (PathLimits) Limits.path(newPath);
        l.isInitialPath = false;
        return l;
    }

    @Override
    public Limits next(String pathName) {
        if (path.get(0).equals("*")) {
            return next(false);
        }
        if (path.get(0).equals("**")) {
            return Limits.NO_LIMITS;
        }
        if (path.get(0).equals(pathName)) {
            return next(false);
        }
        return Limits.END_LIMITS;
    }

    @Override
    public boolean filter(String name) {
        if (path.get(0).equals("*")) {
            return true;
        }
        return path.get(0).equals(name);
    }

    @Override
    public Limits and(Limits other) {
        if (other instanceof DepthLimits) {
            return new QueryLimits(Collections.singletonList(this), other);
        }
        if (other instanceof PathLimits) {
            return new QueryLimits(Arrays.asList(this, other), Limits.NO_LIMITS);
        }
        if (other instanceof QueryLimits) {
            return other.and(this);
        }
        return this;
    }

    @Override
    public boolean canCopyMetadata() {
        return isInitialPath ? true : path.get(0).equals("*") || path.get(0).equals("**");
    }

    public Limits copy() {
        String newPath = "/" + String.join("/", path);
        PathLimits l = (PathLimits) Limits.path(newPath);
        l.isInitialPath = isInitialPath;
        return l;
    }
}
