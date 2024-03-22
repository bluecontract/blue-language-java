package blue.lang.model.limits;

public class EndLimits implements LimitsInterface {
    @Override
    public boolean canReadNext() {
        return false;
    }

    @Override
    public LimitsInterface next(boolean forTypeInference) {
        return this;
    }

    @Override
    public boolean filter(String name) {
        return false;
    }
}
