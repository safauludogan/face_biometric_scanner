package com.company.facelogin.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
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
import com.company.facelogin.database.DatabaseHelper;
import com.company.facelogin.database.UserDao;
import com.company.facelogin.ml.EmbeddingComparator;
import com.company.facelogin.ml.FaceImageProcessor;
import com.company.facelogin.ml.FaceNetModel;
import com.company.facelogin.ml.FaceValidator;
import com.company.facelogin.models.User;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaceIdentificationActivity extends AppCompatActivity {

    private static final int  MIN_FACE_FRAMES   = 6;
    private static final long RESULT_DISPLAY_MS = 2500L;

    // Views
    private PreviewView  previewView;
    private TextView     tvStatus;
    private LinearLayout cardResult;
    private TextView     tvResultName;
    private TextView     tvResultSub;

    // Camera & ML
    private ProcessCameraProvider cameraProvider;
    private FaceDetector          faceDetector;
    private FaceNetModel          faceNetModel;
    private ExecutorService       analysisExecutor;

    // Database
    private DatabaseHelper dbHelper;
    private UserDao        userDao;
    private volatile List<User> allUsers;

    // State
    private int     faceLikeFrames  = 0;
    private boolean isIdentifying   = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else {
                    Toast.makeText(this, "Kamera izni gereklidir.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_identification);

        previewView  = findViewById(R.id.previewView);
        tvStatus     = findViewById(R.id.tvStatus);
        cardResult   = findViewById(R.id.cardResult);
        tvResultName = findViewById(R.id.tvResultName);
        tvResultSub  = findViewById(R.id.tvResultSub);

        dbHelper         = new DatabaseHelper(this);
        userDao          = new UserDao(dbHelper);
        analysisExecutor = Executors.newSingleThreadExecutor();
        faceDetector     = buildFaceDetector();
        faceNetModel     = new FaceNetModel();

        analysisExecutor.execute(() -> {
            faceNetModel.loadModel(this);
            allUsers = userDao.getAllUsers();
        });

        requestCameraPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetScan();
        analysisExecutor.execute(() -> allUsers = userDao.getAllUsers());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (cameraProvider   != null) cameraProvider.unbindAll();
        if (faceDetector     != null) faceDetector.close();
        if (analysisExecutor != null) analysisExecutor.shutdown();
        if (faceNetModel     != null) faceNetModel.close();
        if (dbHelper         != null) dbHelper.close();
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

    // ── Camera ────────────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyzeImage);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Kamera başlatılamadı.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Face detection ────────────────────────────────────────────────────────

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
        int    rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        Bitmap rawBitmap       = FaceImageProcessor.imageProxyToBitmap(imageProxy);
        if (rawBitmap == null) { imageProxy.close(); return; }

        InputImage inputImage = InputImage.fromBitmap(rawBitmap, rotationDegrees);
        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> onFacesDetected(faces, rawBitmap, rotationDegrees))
                .addOnFailureListener(e -> { /* ignore transient errors */ })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onFacesDetected(List<Face> faces, Bitmap rawBitmap, int rotationDegrees) {
        if (isIdentifying) return;

        if (faces.isEmpty() || faces.size() > 1) {
            faceLikeFrames = 0;
            updateStatus("Kameraya bakın");
            return;
        }

        Face face = faces.get(0);

        if (!FaceValidator.isFaceLike(face)) {
            faceLikeFrames = 0;
            updateStatus("Kameraya bakın");
            return;
        }

        faceLikeFrames++;

        if (faceLikeFrames < MIN_FACE_FRAMES) {
            updateStatus("Taranıyor...");
            return;
        }
        if (faceLikeFrames > MIN_FACE_FRAMES) return; // zaten dispatch edildi

        // Eşiğe tam ulaşıldı — bir kez dispatch et
        isIdentifying = true;
        updateStatus("Tanınıyor...");

        final Bitmap bmp  = rawBitmap;
        final Face   f    = face;
        analysisExecutor.execute(() -> extractAndIdentify(bmp, f, rotationDegrees));
    }

    // ── Recognition ──────────────────────────────────────────────────────────

    private void extractAndIdentify(Bitmap rawBitmap, Face face, int rotationDegrees) {
        Bitmap faceInput = FaceImageProcessor.prepareFaceInput(
                rawBitmap, rotationDegrees, face.getBoundingBox());
        if (faceInput == null) {
            runOnUiThread(this::resetScan);
            return;
        }

        float[] embedding = faceNetModel.getEmbedding(faceInput);
        if (embedding == null) {
            runOnUiThread(this::resetScan);
            return;
        }

        List<User> users = allUsers;
        User  bestMatch = null;
        float bestScore = 0f;

        if (users != null) {
            for (User user : users) {
                if (user.getFaceEmbedding() == null) continue;
                float[] stored = UserDao.bytesToEmbedding(user.getFaceEmbedding());
                float   score  = EmbeddingComparator.cosineSimilarity(embedding, stored);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = user;
                }
            }
        }

        final String resultName;
        final String resultSub;
        if (bestMatch != null && bestScore >= EmbeddingComparator.DEFAULT_THRESHOLD) {
            resultName = bestMatch.getFirstName() + " " + bestMatch.getLastName();
            resultSub  = String.format("Eşleşme skoru: %.0f%%", bestScore * 100);
        } else {
            resultName = "Tanımsız";
            resultSub  = "Kayıtlı kullanıcı bulunamadı";
        }

        runOnUiThread(() -> showResult(resultName, resultSub));
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showResult(String name, String sub) {
        tvResultName.setText(name);
        tvResultSub.setText(sub);
        cardResult.setVisibility(View.VISIBLE);
        updateStatus(name.equals("Tanımsız") ? "Tanımsız kişi" : "Tanındı");

        uiHandler.postDelayed(() -> {
            cardResult.setVisibility(View.GONE);
            resetScan();
        }, RESULT_DISPLAY_MS);
    }

    private void resetScan() {
        faceLikeFrames = 0;
        isIdentifying  = false;
        updateStatus("Kameraya bakın");
    }

    private void updateStatus(String text) {
        runOnUiThread(() -> tvStatus.setText(text));
    }
}
