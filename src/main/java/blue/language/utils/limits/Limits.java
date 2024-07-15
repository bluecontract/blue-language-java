package blue.language.utils.limits;

public interface Limits {

    Limits NO_LIMITS = new NoLimits();

    boolean shouldProcessPathSegment(String pathSegment);

    void enterPathSegment(String pathSegment);

    void exitPathSegment();
}