package blue.lang.model.limits;

public class NoLimits implements LimitsInterface {
    @Override
    public boolean canReadNext() {
        return true;
    }

    @Override
    public LimitsInterface next(boolean forTypeInference) {
        return this;
    }

    @Override
    public boolean filter(String name) {
        return true;
    }
}
