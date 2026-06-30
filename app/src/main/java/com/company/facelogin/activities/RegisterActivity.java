package com.company.facelogin.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.company.facelogin.R;
import com.company.facelogin.database.UserDao;
import com.company.facelogin.ml.FaceImageProcessor;
import com.company.facelogin.ml.FaceNetModel;
import com.company.facelogin.ml.LivenessDetector;
import com.company.facelogin.views.FaceGuideOverlayView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterActivity extends AppCompatActivity {

    private static final long LIVENESS_THROTTLE_MS = 200;
    private static final int  MIN_FACE_FRAMES      = 6;

    private enum RegisterPhase { LIVENESS, DONE }

    // Views
    private PreviewView          previewView;
    private FaceGuideOverlayView faceGuideOverlay;
    private TextView             tvFaceStatus;

    // Camera & ML
    private ProcessCameraProvider cameraProvider;
    private FaceDetector          faceDetector;
    private FaceNetModel          faceNetModel;
    private ExecutorService       analysisExecutor;

    // Liveness
    private LivenessDetector livenessDetector;
    private RegisterPhase    registerPhase   = RegisterPhase.LIVENESS;
    private boolean          livenessStarted = false;
    private long             lastLivenessMs  = 0;
    private int              faceLikeFrames  = 0;

    // Launched after liveness PASS; on RESULT_OK this activity also finishes
    private final ActivityResultLauncher<Intent> registerInfoLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    finish(); // registration complete → back to AccountSelectionActivity
                } else {
                    resetLiveness(); // user cancelled → allow retry
                }
            });

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Kamera izni gereklidir.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        previewView      = findViewById(R.id.previewView);
        faceGuideOverlay = findViewById(R.id.faceGuideOverlay);
        tvFaceStatus     = findViewById(R.id.tvFaceStatus);

        setupAnalysis();
        requestCameraPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (registerPhase != RegisterPhase.DONE) {
            resetLiveness();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider   != null) cameraProvider.unbindAll();
        if (faceDetector     != null) faceDetector.close();
        if (analysisExecutor != null) analysisExecutor.shutdown();
        if (faceNetModel     != null) faceNetModel.close();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void setupAnalysis() {
        analysisExecutor = Executors.newSingleThreadExecutor();
        faceDetector     = buildFaceDetector();
        faceNetModel     = new FaceNetModel();
        livenessDetector = new LivenessDetector();
        analysisExecutor.execute(() -> faceNetModel.loadModel(this));
    }

    private void resetLiveness() {
        registerPhase    = RegisterPhase.LIVENESS;
        livenessDetector = new LivenessDetector();
        livenessStarted  = false;
        lastLivenessMs   = 0;
        faceLikeFrames   = 0;
        runOnUiThread(() -> {
            faceGuideOverlay.setFaceState(FaceGuideOverlayView.FaceState.SEARCHING);
            tvFaceStatus.setText("Yüzünüzü çerçeveye yerleştirin");
        });
    }

    // ── Camera permission ─────────────────────────────────────────────────────

    private void requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    // ── Camera setup ──────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview       preview       = new Preview.Builder().build();
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeImage);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Kamera başlatılamadı.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── ML Kit face detection ─────────────────────────────────────────────────

    private FaceDetector buildFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build();
        return FaceDetection.getClient(options);
    }

    private void analyzeImage(ImageProxy imageProxy) {
        if (registerPhase == RegisterPhase.DONE) {
            imageProxy.close();
            return;
        }

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        int imageWidth  = (rotationDegrees == 90 || rotationDegrees == 270)
                ? imageProxy.getHeight() : imageProxy.getWidth();
        int imageHeight = (rotationDegrees == 90 || rotationDegrees == 270)
                ? imageProxy.getWidth() : imageProxy.getHeight();

        Bitmap rawBitmap = FaceImageProcessor.imageProxyToBitmap(imageProxy);
        if (rawBitmap == null) { imageProxy.close(); return; }

        InputImage inputImage = InputImage.fromBitmap(rawBitmap, rotationDegrees);
        faceDetector.process(inputImage)
                .addOnSuccessListener(faces ->
                        onFacesDetected(faces, rawBitmap, rotationDegrees, imageWidth, imageHeight))
                .addOnFailureListener(e -> updateStatus("Algılama hatası"))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onFacesDetected(List<Face> faces, Bitmap rawBitmap,
                                  int rotationDegrees, int imageWidth, int imageHeight) {
        if (registerPhase == RegisterPhase.DONE) return;

        if (faces.isEmpty()) {
            faceGuideOverlay.setFaceState(FaceGuideOverlayView.FaceState.SEARCHING);
            updateStatus("Yüzünüzü çerçeveye yerleştirin");
            return;
        }
        if (faces.size() > 1) {
            faceGuideOverlay.setFaceState(FaceGuideOverlayView.FaceState.SEARCHING);
            updateStatus("Birden fazla yüz bulundu");
            return;
        }

        Face face = faces.get(0);

        if (!isFaceLike(face)) {
            faceLikeFrames = 0;
            faceGuideOverlay.setFaceState(FaceGuideOverlayView.FaceState.SEARCHING);
            updateStatus("Yüzünüzü kameraya bakın");
            return;
        }
        faceLikeFrames++;

        if (livenessStarted || (faceLikeFrames >= MIN_FACE_FRAMES && isFaceInOval(face, imageWidth, imageHeight))) {
            livenessStarted = true;
            faceGuideOverlay.setFaceState(FaceGuideOverlayView.FaceState.INSIDE);
            checkLiveness(face, rawBitmap, rotationDegrees);
        } else {
            faceGuideOverlay.setFaceState(FaceGuideOverlayView.FaceState.SEARCHING);
            updateStatus("Yüzünüzü çerçeveye yerleştirin");
        }
    }

    // ── Face validity (anti-spoofing) ─────────────────────────────────────────

    /**
     * Returns true only for a plausible human face.
     * Rejects hands, objects, and phantom detections.
     * Requires CLASSIFICATION_MODE_ALL (eye open probabilities).
     */
    private boolean isFaceLike(Face face) {
        // Göz sınıflandırmaları mevcut ve makul olmalı
        Float leftEyeProb  = face.getLeftEyeOpenProbability();
        Float rightEyeProb = face.getRightEyeOpenProbability();
        if (leftEyeProb == null || rightEyeProb == null)  return false;
        if (Math.max(leftEyeProb, rightEyeProb) < 0.1f) return false;

        // Gülümseme olasılığı — ML Kit bunu ancak tam yüz yapısında hesaplar
        if (face.getSmilingProbability() == null) return false;

        // Beş temel landmark'ın tamamı mevcut olmalı
        FaceLandmark noseBase   = face.getLandmark(FaceLandmark.NOSE_BASE);
        FaceLandmark leftEye    = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEye   = face.getLandmark(FaceLandmark.RIGHT_EYE);
        FaceLandmark mouthLeft  = face.getLandmark(FaceLandmark.MOUTH_LEFT);
        FaceLandmark mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT);
        if (noseBase == null || leftEye == null || rightEye == null
                || mouthLeft == null || mouthRight == null) return false;

        // Dikey sıralama: göz → burun → ağız (Y büyüdükçe aşağı)
        float eyeMidY   = (leftEye.getPosition().y + rightEye.getPosition().y) / 2f;
        float noseY     = noseBase.getPosition().y;
        float mouthMidY = (mouthLeft.getPosition().y + mouthRight.getPosition().y) / 2f;
        if (noseY <= eyeMidY)   return false; // burun gözlerin altında değil
        if (mouthMidY <= noseY) return false; // ağız burnun altında değil

        // Gözler arası mesafe yüz genişliğinin en az %20'si
        float eyeDist   = Math.abs(leftEye.getPosition().x - rightEye.getPosition().x);
        float faceWidth = face.getBoundingBox().width();
        if (eyeDist < faceWidth * 0.20f) return false;

        if (Math.abs(face.getHeadEulerAngleZ()) > 45f) return false;
        return true;
    }

    // ── Oval validation ───────────────────────────────────────────────────────

    /**
     * Yüzü doğrudan view piksel koordinatlarına çevirir ve oval rect ile kıyaslar.
     * FILL_CENTER dönüşümü: image → view (ölçek + ofset).
     * imgW/imgH = rotasyon uygulanmış (portrait) image boyutları.
     */
    private boolean isFaceInOval(Face face, int imgW, int imgH) {
        int   viewW = faceGuideOverlay.getWidth();
        int   viewH = faceGuideOverlay.getHeight();
        RectF oval  = faceGuideOverlay.getOvalRect();
        if (viewW == 0 || viewH == 0 || oval.isEmpty()) return false;

        // FILL_CENTER: büyük boyutu sığdıracak scale, küçük boyut kırpılır
        float scale   = Math.max((float) viewW / imgW, (float) viewH / imgH);
        float offX    = (viewW - imgW * scale) / 2f;   // negatif → yanlarda kırpma
        float offY    = (viewH - imgH * scale) / 2f;   // negatif → üst/altta kırpma

        // Yüz bounding box → view piksel koordinatları
        RectF box     = new RectF(face.getBoundingBox());
        float fL      = box.left   * scale + offX;
        float fR      = box.right  * scale + offX;
        float fT      = box.top    * scale + offY;
        float fB      = box.bottom * scale + offY;
        float faceCX  = (fL + fR) / 2f;
        float faceCY  = (fT + fB) / 2f;
        float faceW   = fR - fL;
        float faceH   = fB - fT;

        // Yüz oval boyutunun %65–110'u arasında olmalı
        boolean goodSize  = faceW > oval.width()  * 0.65f && faceW < oval.width()  * 1.10f
                         && faceH > oval.height() * 0.65f && faceH < oval.height() * 1.10f;

        // Yüz merkezi oval merkezi etrafında ±%15 (yatay), ±%20 (dikey)
        boolean centered  = Math.abs(faceCX - oval.centerX()) < viewW * 0.15f
                         && Math.abs(faceCY - oval.centerY()) < viewH * 0.20f;

        // Yüzün hiçbir kenarı view sınırını belirgin şekilde aşmamalı
        boolean notClipped = fL > -viewW * 0.05f && fR < viewW * 1.05f
                          && fT > -viewH * 0.05f && fB < viewH * 1.05f;

        return goodSize && centered && notClipped;
    }

    // ── Liveness detection ────────────────────────────────────────────────────

    private void checkLiveness(Face face, Bitmap rawBitmap, int rotationDegrees) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLivenessMs < LIVENESS_THROTTLE_MS) return;
        lastLivenessMs = now;

        LivenessDetector.State state = livenessDetector.process(face);
        tvFaceStatus.setText(livenessDetector.getInstructionText());

        if (state == LivenessDetector.State.PASS) {
            registerPhase = RegisterPhase.DONE;
            faceGuideOverlay.setFaceState(FaceGuideOverlayView.FaceState.PASS);
            tvFaceStatus.setText("Yüz doğrulandı! Bilgileriniz alınıyor...");
            if (!analysisExecutor.isShutdown()) {
                final Bitmap bmp = rawBitmap;
                final Face   f   = face;
                analysisExecutor.execute(() -> computeEmbeddingAndNavigate(bmp, f, rotationDegrees));
            }
        } else if (state == LivenessDetector.State.FAIL) {
            livenessDetector = new LivenessDetector();
            livenessStarted  = false;
            lastLivenessMs   = 0;
            registerPhase    = RegisterPhase.LIVENESS;
            faceGuideOverlay.setFaceState(FaceGuideOverlayView.FaceState.SEARCHING);
            tvFaceStatus.setText("Süre doldu, tekrar deneyin");
        }
    }

    private void computeEmbeddingAndNavigate(Bitmap rawBitmap, Face face, int rotationDegrees) {
        Bitmap faceInput = FaceImageProcessor.prepareFaceInput(
                rawBitmap, rotationDegrees, face.getBoundingBox());
        if (faceInput == null) { runOnUiThread(this::resetLiveness); return; }

        float[] embedding = faceNetModel.getEmbedding(faceInput);
        if (embedding == null) { runOnUiThread(this::resetLiveness); return; }

        byte[] embeddingBytes = UserDao.embeddingToBytes(embedding);
        runOnUiThread(() -> {
            Intent intent = new Intent(this, RegisterInfoActivity.class);
            intent.putExtra(RegisterInfoActivity.EXTRA_EMBEDDING, embeddingBytes);
            registerInfoLauncher.launch(intent);
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateStatus(String text) {
        runOnUiThread(() -> tvFaceStatus.setText(text));
    }
}
