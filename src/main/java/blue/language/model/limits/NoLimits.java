package blue.language.model.limits;

public class NoLimits implements Limits {
    @Override
    public boolean canReadNext() {
        return true;
    }

    @Override
    public Limits next(boolean forTypeInference) {
        return this;
    }

    @Override
    public boolean filter(String name) {
        return true;
    }

}
