package blue.language.processor;

/**
 * Captures a failure during the {@code when} phase so its type and details can
 * be asserted independently during the {@code then} phase.
 */
public final class FailureCapture {

    private FailureCapture() {
    }

    /**
     * Executes an action and returns the failure it raises.
     *
     * <p>The generic return type keeps failure-focused tests concise. A
     * non-throwing action returns {@code null}, which the test must reject in
     * its assertion phase.</p>
     *
     * @param action behavior expected to fail
     * @param <T> expected failure type
     * @return the raised failure, or {@code null} when the action succeeds
     */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T captureFailure(ThrowingAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return (T) failure;
        }
    }

    /**
     * Action whose checked or unchecked failure should be captured.
     */
    @FunctionalInterface
    public interface ThrowingAction {

        /**
         * Executes the behavior under test.
         *
         * @throws Throwable when the behavior fails
         */
        void run() throws Throwable;
    }
}
