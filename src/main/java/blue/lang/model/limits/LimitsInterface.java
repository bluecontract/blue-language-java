package blue.lang.model.limits;

public interface LimitsInterface {
    boolean canReadNext();
    default boolean canReadIndex(int index) {
        return canReadNext();
    }
    LimitsInterface next(boolean forTypeInference);
    default LimitsInterface next(String pathName) {
        return next(false);
    }
    boolean filter(String name);
    default LimitsInterface and(LimitsInterface other) {
        return this;
    }
}
