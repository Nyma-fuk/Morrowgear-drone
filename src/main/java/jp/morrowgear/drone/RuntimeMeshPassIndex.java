package jp.morrowgear.drone;

/** Index immutable triangles once instead of rescanning every surface for every light pass. */
public final class RuntimeMeshPassIndex {
    private RuntimeMeshPassIndex() {}

    public static int[] triangles(byte[] flags, int surface) {
        if (flags.length % 3 != 0 || surface < 1 || surface > 2) throw new IllegalArgumentException("Invalid triangle index");
        int count = 0;
        for (int i = 0; i < flags.length; i += 3) if (flags[i] == surface) count++;
        int[] result = new int[count];
        int index = 0;
        for (int i = 0; i < flags.length; i += 3) if (flags[i] == surface) result[index++] = i;
        return result;
    }

    public static int fixedPose(float landed, float action) {
        return landed == 1 && action == 0 ? 6 : landed == 0 && action == 1 ? 12
            : landed == 0 && action == 0 ? 0 : -1;
    }
}
