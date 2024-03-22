package blue.lang.model.limits;

public interface LimitsInterface {
    boolean canReadNext();
    LimitsInterface next(boolean forTypeInference);
    boolean filter(String name);
}
