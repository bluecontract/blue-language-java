package blue.language.processor;

import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.ProcessorContractConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates declaration and concrete Process Embedded path overlap. */
final class EmbeddedScopeOverlapValidator {

    private EmbeddedScopeOverlapValidator() {
    }

    static void validateDeclarations(
            List<String> explicit,
            List<String> collections,
            String scopePath) {
        List<String> all = new ArrayList<>(
                explicit.size() + collections.size());
        all.addAll(explicit);
        all.addAll(collections);
        Map<String, String> declarations = new LinkedHashMap<>();
        for (String declaration : all) {
            String duplicate = declarations.put(declaration, declaration);
            if (duplicate != null) {
                throw overlap("Overlapping Process Embedded declarations: "
                        + duplicate + " and " + declaration, scopePath);
            }
        }
        for (String declaration : all) {
            String ancestor = strictAncestorIn(declarations, declaration);
            if (ancestor != null) {
                throw overlap("Overlapping Process Embedded declarations: "
                        + ancestor + " and " + declaration, scopePath);
            }
        }
    }

    static void rejectConcreteOverlap(
            List<EmbeddedConcretePath> concrete,
            String scopePath) {
        Map<String, EmbeddedConcretePath> byPath = new LinkedHashMap<>();
        for (EmbeddedConcretePath candidate : concrete) {
            EmbeddedConcretePath duplicate = byPath.put(
                    candidate.absolutePath(), candidate);
            if (duplicate != null) {
                throw concreteOverlap(duplicate.absolutePath(),
                        candidate.absolutePath(), scopePath);
            }
        }
        for (EmbeddedConcretePath candidate : concrete) {
            String ancestor = strictAncestorIn(
                    byPath, candidate.absolutePath());
            if (ancestor != null) {
                throw concreteOverlap(
                        ancestor, candidate.absolutePath(), scopePath);
            }
        }
    }

    private static String strictAncestorIn(
            Map<String, ?> pathsByPointer,
            String pointer) {
        List<String> segments = JsonPointer.split(pointer);
        for (int length = segments.size() - 1; length > 0; length--) {
            String ancestor = JsonPointer.toPointer(
                    segments.subList(0, length));
            if (pathsByPointer.containsKey(ancestor)) {
                return ancestor;
            }
        }
        return null;
    }

    private static SubscriptionSurfaceInvalidException concreteOverlap(
            String left,
            String right,
            String scopePath) {
        return overlap("Overlapping concrete embedded paths: "
                + left + " and " + right, scopePath);
    }

    private static SubscriptionSurfaceInvalidException overlap(
            String message,
            String scopePath) {
        return new SubscriptionSurfaceInvalidException(
                message,
                scopePath,
                ProcessorContractConstants.KEY_EMBEDDED,
                ProcessorErrorCategory.OverlappingEmbeddedDeclaration);
    }
}
