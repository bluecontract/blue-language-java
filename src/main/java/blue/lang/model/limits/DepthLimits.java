package blue.lang.model.limits;

public class DepthLimits implements LimitsInterface {
    private int maxDepth;

    public DepthLimits(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    @Override
    public boolean canReadNext() {
        return maxDepth > 0;
    }

    @Override
    public LimitsInterface next(boolean forTypeInference) {
        if (maxDepth == 0) { return new EndLimits(); }

        if (forTypeInference) { return this; }

        return new DepthLimits(maxDepth - 1);
    }

    @Override
    public boolean filter(String name) {
        return true;
    }
}
