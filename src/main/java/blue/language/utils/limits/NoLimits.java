package blue.language.utils.limits;

class NoLimits implements Limits {
    @Override
    public boolean shouldProcessPathSegment(String pathSegment) {
        return true;
    }

    @Override
    public void enterPathSegment(String pathSegment) {
    }

    @Override
    public void exitPathSegment() {
    }
}