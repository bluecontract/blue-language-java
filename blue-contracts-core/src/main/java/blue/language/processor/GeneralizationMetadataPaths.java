package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.List;

/** Traverses and reads processor-generated type-metadata paths. */
final class GeneralizationMetadataPaths {

    private GeneralizationMetadataPaths() {
    }

    static boolean isMetadataPath(String path) {
        List<String> segments = JsonPointer.split(path);
        if (segments.isEmpty()) {
            return false;
        }
        String field = segments.get(segments.size() - 1);
        return BlueLanguageConstants.OBJECT_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_KEY_TYPE.equals(field)
                || BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(field);
    }

    static FrozenNode read(FrozenNode root, String path) {
        List<String> segments = JsonPointer.split(path);
        if (segments.isEmpty()) {
            return null;
        }
        String field = segments.get(segments.size() - 1);
        String parentPath = JsonPointer.toPointer(
                segments.subList(0, segments.size() - 1));
        FrozenNode parent = ImmutablePatchPlanner.forFrozen(root)
                .read(parentPath);
        if (parent == null) {
            return null;
        }
        if (BlueLanguageConstants.OBJECT_TYPE.equals(field)) {
            return parent.getType();
        }
        if (BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(field)) {
            return parent.getItemType();
        }
        if (BlueLanguageConstants.OBJECT_KEY_TYPE.equals(field)) {
            return parent.getKeyType();
        }
        return BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(field)
                ? parent.getValueType()
                : null;
    }
}
