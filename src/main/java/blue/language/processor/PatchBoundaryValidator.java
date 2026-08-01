package blue.language.processor;

import blue.language.processor.util.PointerUtils;
import blue.language.utils.JsonPointer;

/**
 * Validates that one authored patch remains within its active scope boundary.
 *
 * <p>This validator is intentionally independent of patch planning. It checks
 * only scope ownership and declared embedded-scope boundaries before the
 * runtime can demand providers or construct a tentative mutation.</p>
 */
final class PatchBoundaryValidator {

    private PatchBoundaryValidator() {
    }

    static void validate(String scopePath,
                         ContractBundle bundle,
                         PatchInput patch) {
        if (bundle == null) {
            return;
        }
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        String targetPath = PointerUtils.assertValidRuntimePointer(
                patch.authoredPath());

        if (JsonPointer.ROOT.equals(targetPath)) {
            throw new ProcessorEngine.BoundaryViolationException(
                    "Patch path '/' is forbidden");
        }
        if (targetPath.equals(normalizedScope)) {
            throw new ProcessorEngine.BoundaryViolationException(
                    "Self-root mutation is forbidden at scope "
                            + normalizedScope);
        }
        if (!JsonPointer.ROOT.equals(normalizedScope)
                && !PointerUtils.strictlyInside(
                targetPath, normalizedScope)) {
            throw new ProcessorEngine.BoundaryViolationException(
                    "Patch path " + targetPath
                            + " is outside scope " + normalizedScope);
        }

        for (String embeddedPointer : bundle.embeddedPaths()) {
            String embeddedScope = ProcessorEngine.resolvePointer(
                    normalizedScope, embeddedPointer);
            if (PointerUtils.strictlyInside(
                    targetPath, embeddedScope)) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Boundary violation: patch " + targetPath
                                + " enters embedded scope "
                                + embeddedScope);
            }
            if (PointerUtils.strictlyInside(
                    embeddedScope, targetPath)) {
                throw new ProcessorEngine.BoundaryViolationException(
                        "Boundary violation: patch " + targetPath
                                + " is a strict ancestor of embedded scope "
                                + embeddedScope);
            }
        }
    }
}
