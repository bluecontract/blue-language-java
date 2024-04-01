package blue.language.model.limits;

import java.util.Collections;

public class DepthLimits implements Limits {
    private int maxDepth;

    public DepthLimits(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    @Override
    public boolean canReadNext() {
        return maxDepth > 0;
    }

    @Override
    public Limits next(boolean forTypeInference) {
        if (maxDepth <= 1) { return Limits.END_LIMITS; }

        return new DepthLimits(maxDepth - 1);
    }

    @Override
    public boolean filter(String name) {
        return true;
    }

    @Override
    public Limits and(Limits other) {
        if (other instanceof DepthLimits) {
            return new DepthLimits(Integer.max(maxDepth, ((DepthLimits) other).maxDepth));
        }
        if (other instanceof PathLimits) {
            return new QueryLimits(Collections.singletonList(other), this);
        }
        if (other instanceof QueryLimits) {
            return other.and(this);
        }
        return this;
    }

    public Limits copy() {
        return new DepthLimits(maxDepth);
    }
}
