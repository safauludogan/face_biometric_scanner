package com.company.facelogin.activities;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.company.facelogin.R;
import com.company.facelogin.database.DatabaseHelper;
import com.company.facelogin.database.UserDao;
import com.company.facelogin.models.User;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterInfoActivity extends AppCompatActivity {

    public static final String EXTRA_EMBEDDING = "extra_embedding";

    private EditText        etFirstName;
    private EditText        etLastName;
    private Button          btnRegister;
    private TextView        tvStatus;

    private DatabaseHelper  dbHelper;
    private UserDao         userDao;
    private ExecutorService executor;

    private byte[] embeddingBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register_info);

        embeddingBytes = getIntent().getByteArrayExtra(EXTRA_EMBEDDING);
        if (embeddingBytes == null) {
            Toast.makeText(this, "Yüz verisi alınamadı.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        etFirstName = findViewById(R.id.etFirstName);
        etLastName  = findViewById(R.id.etLastName);
        btnRegister = findViewById(R.id.btnRegister);
        tvStatus    = findViewById(R.id.tvStatus);

        dbHelper = new DatabaseHelper(this);
        userDao  = new UserDao(dbHelper);
        executor = Executors.newSingleThreadExecutor();

        btnRegister.setOnClickListener(v -> attemptRegister());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdown();
        if (dbHelper != null) dbHelper.close();
    }

    private void attemptRegister() {
        String firstName = etFirstName.getText().toString().trim();
        String lastName  = etLastName.getText().toString().trim();

        if (firstName.isEmpty() || lastName.isEmpty()) {
            tvStatus.setText("Ad ve soyad gerekli.");
            return;
        }

        btnRegister.setEnabled(false);
        executor.execute(() -> saveUser(firstName, lastName));
    }

    private void saveUser(String firstName, String lastName) {
        User user = new User(firstName, lastName);
        user.setFaceEmbedding(embeddingBytes);

        long rowId = userDao.insertUser(user);

        runOnUiThread(() -> {
            if (rowId > 0) {
                Toast.makeText(this,
                        firstName + " " + lastName + " başarıyla kaydedildi.",
                        Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            } else {
                tvStatus.setText("Kayıt başarısız, tekrar deneyin.");
                btnRegister.setEnabled(true);
            }
        });
    }
}
