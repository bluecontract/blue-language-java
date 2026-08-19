package blue.language.conformance.api;

import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.registry.RuntimeBlueIds;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ContractsReleaseIdentityTest {

    @Test
    void shouldRecomputeTheCanonicalContractsReleaseIdentity() {
        assertEquals(
                BlueContractsFixturePackage.CONTRACTS_RELEASE_IDENTITY,
                BlueContractsFixturePackage
                        .computeContractsReleaseIdentity());
    }

    @Test
    void shouldBindSpecificationsAndCyclicRuntimeToCanonicalRelease() {
        ObjectNode release = exactReleaseBindings();

        BlueContractsFixturePackage.validateContractsReleaseBindings(release);

        assertRejectedBinding(
                exactReleaseBindings(), "specificationDocument", "sha256");
        assertRejectedBinding(
                exactReleaseBindings(), "languageDependency",
                "specificationSha256");
        assertRejectedBinding(
                exactReleaseBindings(), "languageDependency",
                "cyclicSetFinalizerBaselineIdentity");
        assertRejectedBinding(
                exactReleaseBindings(), "languageDependency",
                "cyclicSetProofVerifierBaselineIdentity");
    }

    private static ObjectNode exactReleaseBindings() {
        ObjectNode release = BlueContractsFixturePackage.FIXTURE_YAML
                .createObjectNode();
        release.put("manifestType", "blue-contracts-release");
        release.put("specificationVersion", "1.0");
        release.put("releaseIdentity",
                BlueContractsFixturePackage.CONTRACTS_RELEASE_IDENTITY);
        release.putObject("specificationDocument").put(
                "sha256",
                BlueContractsConformanceReport.CONTRACTS_SPECIFICATION_SHA256);
        ObjectNode language = release.putObject("languageDependency");
        language.put("specificationSha256",
                BlueContractsConformanceReport.LANGUAGE_SPECIFICATION_SHA256);
        language.put("cyclicSetFinalizerBaselineIdentity",
                ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY);
        language.put("cyclicSetProofVerifierBaselineIdentity",
                ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY);
        release.putObject("contractsRegistry").put(
                "packageIdentity", RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY);
        release.putObject("gasManifest").put(
                "packageIdentity",
                BlueContractsConformanceReport
                        .CONTRACTS_GAS_PACKAGE_IDENTITY);
        release.putObject("fixturePackage").put(
                "packageIdentity",
                BlueContractsConformanceReport
                        .CONTRACTS_FIXTURE_PACKAGE_IDENTITY);
        return release;
    }

    private static void assertRejectedBinding(
            ObjectNode release,
            String object,
            String field) {
        ((ObjectNode) release.get(object)).put(
                field,
                "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        assertThrows(
                IllegalStateException.class,
                () -> BlueContractsFixturePackage
                        .validateContractsReleaseBindings(release));
    }
}
