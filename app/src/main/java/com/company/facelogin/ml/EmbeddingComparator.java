package com.company.facelogin.ml;

public final class EmbeddingComparator {

    public static final float DEFAULT_THRESHOLD = 0.8f;

    private EmbeddingComparator() {}

    /**
     * Returns cosine similarity in [-1, 1].
     * Returns 0 if either vector is a zero vector.
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        float dot = 0f, normASq = 0f, normBSq = 0f;
        for (int i = 0; i < a.length; i++) {
            dot    += a[i] * b[i];
            normASq += a[i] * a[i];
            normBSq += b[i] * b[i];
        }
        float denom = (float) Math.sqrt((double) normASq * normBSq);
        if (denom < 1e-10f) return 0f;
        return dot / denom;
    }

    public static boolean isMatch(float[] a, float[] b, float threshold) {
        return cosineSimilarity(a, b) >= threshold;
    }
}
