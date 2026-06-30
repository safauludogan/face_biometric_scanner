package com.company.facelogin.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class FaceNetModel {

    private static final String TAG = "FaceNetModel";
    private static final String MODEL_FILE = "mobilefacenet.tflite";

    private static final int INPUT_SIZE = FaceImageProcessor.FACE_INPUT_SIZE; // 112
    private static final int PIXEL_CHANNELS = 3;                              // RGB
    private static final int BYTES_PER_FLOAT = 4;

    private Interpreter interpreter;
    private int embeddingSize;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void loadModel(Context context) {
        try {
            MappedByteBuffer modelBuffer = loadModelFile(context);
            interpreter = new Interpreter(modelBuffer, new Interpreter.Options());
            embeddingSize = interpreter.getOutputTensor(0).shape()[1];
            Log.d(TAG, "FaceNet modeli yüklendi. Embedding boyutu: " + embeddingSize);
        } catch (IOException e) {
            Log.e(TAG, "Model yüklenemedi — assets/" + MODEL_FILE + " mevcut mu? Hata: " + e.getMessage());
        }
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Converts a 112×112 Bitmap to a float embedding vector.
     * Returns null if the model is not loaded.
     * Safe to call from a background thread; not re-entrant.
     */
    public float[] getEmbedding(Bitmap bitmap) {
        if (interpreter == null) {
            Log.e(TAG, "getEmbedding: model yüklenmemiş.");
            return null;
        }

        ByteBuffer inputBuffer = bitmapToByteBuffer(bitmap);
        float[][] output = new float[1][embeddingSize];

        interpreter.run(inputBuffer, output);
        return output[0];
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Memory-maps the model file from assets.
     * Requires aaptOptions { noCompress "tflite" } in build.gradle.
     */
    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
        FileInputStream stream = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = stream.getChannel();
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    /**
     * Converts a 112×112 ARGB Bitmap to a flat float ByteBuffer normalized to [-1, 1].
     * Formula per channel: (pixelValue - 127.5) / 128.0
     */
    private ByteBuffer bitmapToByteBuffer(Bitmap bitmap) {
        int capacity = INPUT_SIZE * INPUT_SIZE * PIXEL_CHANNELS * BYTES_PER_FLOAT;
        ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            buffer.putFloat((((pixel >> 16) & 0xFF) - 127.5f) / 128.0f); // R
            buffer.putFloat((((pixel >> 8)  & 0xFF) - 127.5f) / 128.0f); // G
            buffer.putFloat(((pixel         & 0xFF) - 127.5f) / 128.0f); // B
        }

        buffer.rewind();
        return buffer;
    }
}
