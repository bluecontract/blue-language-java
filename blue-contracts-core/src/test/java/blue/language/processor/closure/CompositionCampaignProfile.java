package blue.language.processor.closure;

import java.util.Arrays;

/** Explicit local profiling harness; not part of the normal test task. */
public final class CompositionCampaignProfile {
    private CompositionCampaignProfile() { }

    public static void main(String[] args) {
        int warmups = 3;
        int samples = 12;
        long[] nanos = new long[samples];
        long gas = -1L;
        for (int i = -warmups; i < samples; i++) {
            try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
                ClosureInvocationInput input = fixture.admission(
                        CompositionReactionCycleTest.ring(fixture, "profile", 8, 17), 100_000L);
                long start = System.nanoTime();
                ClosureProcessResult result = fixture.admit(input).processResult();
                long elapsed = System.nanoTime() - start;
                if (result == null || !result.commits() || fixture.reactions.size() != 18
                        || result.managedTransitionReceipts().size() != 8) {
                    throw new AssertionError("Representative finite ring did not settle");
                }
                if (gas != -1L && gas != result.totalGas()) {
                    throw new AssertionError("Physical warmup changed logical gas");
                }
                gas = result.totalGas();
                if (i >= 0) nanos[i] = elapsed;
            }
        }
        Arrays.sort(nanos);
        System.out.println("R2_PROFILE samples=" + samples + " warmups=" + warmups
                + " members=8 reactions=18 receipts=8 logicalGas=" + gas
                + " medianMillis=" + nanos[samples / 2] / 1_000_000.0
                + " minMillis=" + nanos[0] / 1_000_000.0
                + " maxMillis=" + nanos[samples - 1] / 1_000_000.0);
    }
}
