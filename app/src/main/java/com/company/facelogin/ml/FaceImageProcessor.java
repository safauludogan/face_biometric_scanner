package com.company.facelogin.ml;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Log;

import androidx.camera.core.ImageProxy;

public final class FaceImageProcessor {

    public static final int FACE_INPUT_SIZE = 112;

    private static final String TAG = "FaceImageProcessor";

    private FaceImageProcessor() {}

    // ── Public pipeline entry point ───────────────────────────────────────────

    /**
     * Full pipeline: rotate → crop → resize.
     * Returns null (and logs) if crop fails; never throws.
     */
    public static Bitmap prepareFaceInput(Bitmap rawBitmap, int rotationDegrees, Rect boundingBox) {
        Bitmap rotated = rotateBitmapIfNeeded(rawBitmap, rotationDegrees);
        Bitmap cropped = cropFace(rotated, boundingBox);
        if (cropped == null) return null;
        return resizeFace(cropped);
    }

    // ── Individual steps ──────────────────────────────────────────────────────

    /**
     * Converts an ImageProxy frame to a Bitmap in the camera's native orientation.
     * Rotation is NOT applied here; call rotateBitmapIfNeeded separately.
     */
    public static Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        return imageProxy.toBitmap();
    }

    /**
     * Rotates the bitmap clockwise by rotationDegrees.
     * Returns the original bitmap unchanged if rotationDegrees is 0.
     */
    public static Bitmap rotateBitmapIfNeeded(Bitmap bitmap, int rotationDegrees) {
        if (rotationDegrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * Crops the face region from the bitmap using the ML Kit bounding box.
     * Clamps the box to bitmap bounds to prevent out-of-range errors.
     * Returns null if the resulting crop region is invalid; logs the failure.
     */
    public static Bitmap cropFace(Bitmap bitmap, Rect boundingBox) {
        try {
            int left   = Math.max(0, boundingBox.left);
            int top    = Math.max(0, boundingBox.top);
            int right  = Math.min(bitmap.getWidth(),  boundingBox.right);
            int bottom = Math.min(bitmap.getHeight(), boundingBox.bottom);

            if (left >= right || top >= bottom) {
                Log.e(TAG, "cropFace: geçersiz bölge sonrası kırpma — " + boundingBox
                        + " (bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                return null;
            }

            return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
        } catch (Exception e) {
            Log.e(TAG, "cropFace başarısız: " + e.getMessage());
            return null;
        }
    }

    /**
     * Scales the bitmap to FACE_INPUT_SIZE × FACE_INPUT_SIZE (112×112).
     */
    public static Bitmap resizeFace(Bitmap bitmap) {
        return Bitmap.createScaledBitmap(bitmap, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true);
    }
}
