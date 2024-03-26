package blue.lang.model.limits;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.lang.Integer.parseInt;

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

    private Optional<Integer> convertToIndex(String index) {
        try {
            int number = parseInt(index);
            return Optional.of(number);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public LimitsInterface next(boolean forTypeInference) {
        if (forTypeInference) {
            return this;
        }
        if (path.size() <= 1) {
            return Limits.END_LIMITS;
        }
        List<String> subList = path.subList(1, path.size());

        if (subList.get(0).equals("**")) {
            return Limits.NO_LIMITS;
        }
        return new PathLimits(String.join("/", subList));
    }

    @Override
    public LimitsInterface next(String pathName) {
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
    public LimitsInterface and(LimitsInterface other) {
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
}
