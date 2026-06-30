package com.company.facelogin.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
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
import com.company.facelogin.models.User;
import com.company.facelogin.utils.SessionManager;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaceVerificationActivity extends AppCompatActivity {

    public static final String EXTRA_USER_ID = "extra_user_id";

    private static final String TAG           = "FaceVerification";
    private static final String TAG_EMBEDDING = "FACE_EMBEDDING";

    // Views
    private PreviewView previewView;
    private TextView    tvHeader;

    // Camera & ML
    private ProcessCameraProvider cameraProvider;
    private FaceDetector          faceDetector;
    private FaceNetModel          faceNetModel;
    private ExecutorService       analysisExecutor;

    // Database & session
    private DatabaseHelper dbHelper;
    private UserDao        userDao;
    private SessionManager sessionManager;

    // State
    private long          selectedUserId = -1;
    private volatile User selectedUser;
    private volatile boolean verified = false;

    // ── Permission launcher ───────────────────────────────────────────────────

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Kamera izni gereklidir.", Toast.LENGTH_LONG).show();
                }
            });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_verification);

        previewView = findViewById(R.id.previewView);
        tvHeader    = findViewById(R.id.tvHeader);

        selectedUserId = getIntent().getLongExtra(EXTRA_USER_ID, -1);

        if (selectedUserId == -1) {
            Toast.makeText(this, "Geçersiz kullanıcı.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        sessionManager   = new SessionManager(this);
        dbHelper         = new DatabaseHelper(this);
        userDao          = new UserDao(dbHelper);
        analysisExecutor = Executors.newSingleThreadExecutor();
        faceDetector     = buildFaceDetector();

        faceNetModel = new FaceNetModel();
        analysisExecutor.execute(() -> faceNetModel.loadModel(this));

        requestCameraPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        verified = false;
        updateStatusText("Yüzünüzü kameraya bakın");
        analysisExecutor.execute(() -> selectedUser = userDao.getUserById(selectedUserId));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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

    // ── Camera setup ──────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Kamera başlatılamadı.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider provider) {
        Preview       preview       = buildPreview();
        ImageAnalysis imageAnalysis = buildImageAnalysis();

        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
    }

    private Preview buildPreview() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        return preview;
    }

    private ImageAnalysis buildImageAnalysis() {
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyzeImage);
        return analysis;
    }

    // ── ML Kit face detection ─────────────────────────────────────────────────

    private FaceDetector buildFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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

        if (rawBitmap == null) {
            imageProxy.close();
            return;
        }

        InputImage inputImage = InputImage.fromBitmap(rawBitmap, rotationDegrees);

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> onFacesDetected(faces, rawBitmap, rotationDegrees))
                .addOnFailureListener(e -> updateStatusText("Algılama hatası"))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onFacesDetected(List<Face> faces, Bitmap rawBitmap, int rotationDegrees) {
        if (verified) return;

        if (faces.isEmpty()) {
            updateStatusText("Yüz bulunamadı");
            return;
        }
        if (faces.size() > 1) {
            updateStatusText("Birden fazla yüz bulundu");
            return;
        }

        Face face = faces.get(0);

        if (!isFaceLike(face)) {
            updateStatusText("Yüzünüzü kameraya bakın");
            return;
        }

        updateStatusText("Yüz algılandı");
        if (!analysisExecutor.isShutdown()) {
            final Face capturedFace = face;
            analysisExecutor.execute(() ->
                    prepareFaceForRecognition(rawBitmap, capturedFace, rotationDegrees));
        }
    }

    // ── Face validity (anti-spoofing) ─────────────────────────────────────────

    private boolean isFaceLike(Face face) {
        Float leftEye  = face.getLeftEyeOpenProbability();
        Float rightEye = face.getRightEyeOpenProbability();
        if (leftEye == null || rightEye == null)  return false;
        if (Math.max(leftEye, rightEye) < 0.05f) return false;
        if (face.getLandmark(FaceLandmark.NOSE_BASE) == null) return false;
        if (face.getLandmark(FaceLandmark.LEFT_EYE)  == null) return false;
        if (face.getLandmark(FaceLandmark.RIGHT_EYE) == null) return false;
        if (Math.abs(face.getHeadEulerAngleZ()) > 45f) return false;
        return true;
    }

    // ── FaceNet recognition ───────────────────────────────────────────────────

    private void prepareFaceForRecognition(Bitmap rawBitmap, Face face, int rotationDegrees) {
        if (verified) return;

        Bitmap faceInput = FaceImageProcessor.prepareFaceInput(
                rawBitmap, rotationDegrees, face.getBoundingBox());
        if (faceInput == null) return;

        float[] embedding = faceNetModel.getEmbedding(faceInput);
        if (embedding == null) return;

        Log.d(TAG_EMBEDDING, Arrays.toString(embedding));
        verifyIdentity(embedding);
    }

    private void verifyIdentity(float[] embedding) {
        User user = selectedUser;

        if (user == null || user.getFaceEmbedding() == null) {
            updateStatusText("Kullanıcı bilgisi yüklenemedi");
            return;
        }

        float[] stored = UserDao.bytesToEmbedding(user.getFaceEmbedding());
        float   score  = EmbeddingComparator.cosineSimilarity(embedding, stored);
        String  name   = user.getFirstName() + " " + user.getLastName();

        Log.d(TAG, "MATCH_SCORE: user=" + name + " score=" + String.format("%.4f", score));

        if (score >= EmbeddingComparator.DEFAULT_THRESHOLD) {
            Log.d(TAG, "LOGIN_SUCCESS: " + name);
            verified = true;
            runOnUiThread(() -> navigateToHome(user));
        } else {
            Log.d(TAG, "LOGIN_FAILED: no match (score=" + String.format("%.4f", score) + ")");
            updateStatusText("Kullanıcı tanınamadı");
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateToHome(User user) {
        String fullName = user.getFirstName() + " " + user.getLastName();
        Toast.makeText(this, "Hoş geldiniz, " + fullName, Toast.LENGTH_SHORT).show();
        sessionManager.saveSession(user.getId(), user.getFirstName(), user.getLastName());
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateStatusText(String text) {
        runOnUiThread(() -> tvHeader.setText(text));
    }
}
