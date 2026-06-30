package com.company.facelogin.activities;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
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
import com.company.facelogin.ml.FaceImageProcessor;
import com.company.facelogin.ml.FaceNetModel;
import com.company.facelogin.ml.LivenessDetector;
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

public class RegisterActivity extends AppCompatActivity {

    private static final String TAG                  = "RegisterActivity";
    private static final long   LIVENESS_THROTTLE_MS = 200;

    private enum RegisterPhase { LIVENESS, READY }

    // Views
    private PreviewView previewView;
    private EditText    etFirstName;
    private EditText    etLastName;
    private TextView    tvFaceStatus;
    private TextView    tvStatus;
    private Button      btnRegister;

    // Camera & ML
    private ProcessCameraProvider cameraProvider;
    private FaceDetector          faceDetector;
    private FaceNetModel          faceNetModel;
    private ExecutorService       analysisExecutor;

    // Liveness
    private LivenessDetector livenessDetector;
    private RegisterPhase    registerPhase  = RegisterPhase.LIVENESS;
    private long             lastLivenessMs = 0;

    // Database
    private DatabaseHelper dbHelper;
    private UserDao        userDao;

    // Embedding set after liveness PASS
    private volatile float[] pendingEmbedding;

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

        bindViews();
        setupDatabase();
        setupAnalysis();
        setupRegisterButton();
        requestCameraPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetLiveness();
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

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void bindViews() {
        previewView  = findViewById(R.id.previewView);
        etFirstName  = findViewById(R.id.etFirstName);
        etLastName   = findViewById(R.id.etLastName);
        tvFaceStatus = findViewById(R.id.tvFaceStatus);
        tvStatus     = findViewById(R.id.tvStatus);
        btnRegister  = findViewById(R.id.btnRegister);
        btnRegister.setEnabled(false);
    }

    private void setupDatabase() {
        dbHelper = new DatabaseHelper(this);
        userDao  = new UserDao(dbHelper);
    }

    private void setupAnalysis() {
        analysisExecutor = Executors.newSingleThreadExecutor();
        faceDetector     = buildFaceDetector();
        faceNetModel     = new FaceNetModel();
        livenessDetector = new LivenessDetector();
        analysisExecutor.execute(() -> faceNetModel.loadModel(this));
    }

    private void setupRegisterButton() {
        btnRegister.setOnClickListener(v -> {
            String  firstName = etFirstName.getText().toString().trim();
            String  lastName  = etLastName.getText().toString().trim();
            float[] embedding = pendingEmbedding;

            if (firstName.isEmpty() || lastName.isEmpty()) {
                tvStatus.setText("Ad ve soyad gerekli.");
                return;
            }
            if (embedding == null) {
                tvStatus.setText("Yüz doğrulama tamamlanmadı.");
                return;
            }

            btnRegister.setEnabled(false);
            analysisExecutor.execute(() -> saveUser(firstName, lastName, embedding));
        });
    }

    private void resetLiveness() {
        registerPhase    = RegisterPhase.LIVENESS;
        livenessDetector = new LivenessDetector();
        pendingEmbedding = null;
        lastLivenessMs   = 0;
        runOnUiThread(() -> {
            btnRegister.setEnabled(false);
            tvFaceStatus.setText(livenessDetector.getInstructionText());
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
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeImage);
        return imageAnalysis;
    }

    // ── ML Kit face detection ─────────────────────────────────────────────────

    private FaceDetector buildFaceDetector() {
        // ACCURATE + ALL required for eye probabilities and Euler angles used by liveness
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
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
                .addOnFailureListener(e -> updateFaceStatus("Algılama hatası"))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onFacesDetected(List<Face> faces, Bitmap rawBitmap, int rotationDegrees) {
        if (faces.isEmpty()) {
            updateFaceStatus("Yüz bulunamadı");
            return;
        }
        if (faces.size() > 1) {
            updateFaceStatus("Birden fazla yüz bulundu");
            return;
        }

        Face face = faces.get(0);

        if (registerPhase == RegisterPhase.LIVENESS) {
            checkLiveness(face, rawBitmap, rotationDegrees);
        }
        // READY: embedding already captured, camera still previews
    }

    // ── Liveness detection ────────────────────────────────────────────────────

    private void checkLiveness(Face face, Bitmap rawBitmap, int rotationDegrees) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLivenessMs < LIVENESS_THROTTLE_MS) return;
        lastLivenessMs = now;

        LivenessDetector.State state = livenessDetector.process(face);
        tvFaceStatus.setText(livenessDetector.getInstructionText());

        if (state == LivenessDetector.State.PASS) {
            registerPhase = RegisterPhase.READY;
            tvFaceStatus.setText("Yüz doğrulandı! Kaydet tuşuna basın.");
            if (!analysisExecutor.isShutdown()) {
                final Bitmap bmp = rawBitmap;
                final Face   f   = face;
                analysisExecutor.execute(() -> computeAndStorePendingEmbedding(bmp, f, rotationDegrees));
            }
        } else if (state == LivenessDetector.State.FAIL) {
            livenessDetector = new LivenessDetector();
            lastLivenessMs   = 0;
            tvFaceStatus.setText("Süre doldu, tekrar deneyin");
        }
    }

    private void computeAndStorePendingEmbedding(Bitmap rawBitmap, Face face, int rotationDegrees) {
        Bitmap faceInput = FaceImageProcessor.prepareFaceInput(
                rawBitmap, rotationDegrees, face.getBoundingBox());
        if (faceInput == null) return;

        float[] embedding = faceNetModel.getEmbedding(faceInput);
        if (embedding == null) return;

        pendingEmbedding = embedding;
        runOnUiThread(() -> btnRegister.setEnabled(true));
    }

    // ── Registration ──────────────────────────────────────────────────────────

    private void saveUser(String firstName, String lastName, float[] embedding) {
        User user = new User(firstName, lastName);
        user.setFaceEmbedding(UserDao.embeddingToBytes(embedding));

        long rowId = userDao.insertUser(user);

        runOnUiThread(() -> {
            if (rowId > 0) {
                Log.d(TAG, "Kullanıcı kaydedildi: " + firstName + " " + lastName + " (id=" + rowId + ")");
                Toast.makeText(this,
                        firstName + " " + lastName + " başarıyla kaydedildi.", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Log.e(TAG, "insertUser başarısız.");
                tvStatus.setText("Kayıt başarısız, tekrar deneyin.");
                btnRegister.setEnabled(true);
            }
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateFaceStatus(String text) {
        runOnUiThread(() -> tvFaceStatus.setText(text));
    }
}
