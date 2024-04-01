package blue.language.model.limits;

public class EndLimits implements Limits{
    @Override
    public boolean canReadNext() {
        return false;
    }

    @Override
    public Limits next(boolean forTypeInference) {
        return this;
    }

    @Override
    public boolean filter(String name) {
        return false;
    }

}
