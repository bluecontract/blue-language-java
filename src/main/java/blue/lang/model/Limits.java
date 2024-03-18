package blue.lang.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Limits {
    enum LimitType {
        DEPTH,
        PATH,
        NO_LIMITS_TYPE,
        EMPTY_TYPE,
    }

    private LimitType limitType = LimitType.NO_LIMITS_TYPE;

    private List<String> path = Collections.emptyList();
    private int maxDepth = Integer.MAX_VALUE;
    public static final Limits NO_LIMITS = new Limits();

    private static final Limits EMPTY = new Limits(-1);

    public Limits() {
        this.limitType = LimitType.NO_LIMITS_TYPE;
    }

    public Limits(Integer maxDepth) {
        if (maxDepth <= 0) {
            this.limitType = LimitType.EMPTY_TYPE;
            return;
        }
        this.limitType = LimitType.DEPTH;
        this.maxDepth = maxDepth;
    }

    public Limits(String path) {
        if (path.isEmpty()) {
            this.limitType = LimitType.EMPTY_TYPE;
            return;
        }
        this.limitType = LimitType.PATH;
        this.path = Arrays.asList(path.split("/"));
    }

    public boolean canReadNext() {
        return limitType != LimitType.EMPTY_TYPE;
    }

    public Limits next() {
        switch (limitType) {
            case EMPTY_TYPE:
                return this;
            case DEPTH:
                if (maxDepth == 0) { return EMPTY; }
                return new Limits(maxDepth - 1);
            case PATH:
                if (path.size() <= 1) { return EMPTY; }
                List<String> subList = path.subList(1, path.size() - 1);
                return new Limits(String.join("/", subList));
            case NO_LIMITS_TYPE:
                return this;
        }
        return this;
    }

    public boolean filter(String name) {
        if (limitType == LimitType.EMPTY_TYPE) {
            return false;
        }
        if (limitType == LimitType.PATH) {
            if (path.get(0).equals("*")) { return true; }
            return path.get(0).equals(name);
        }
        return true;
    }
}
