package com.company.facelogin.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.company.facelogin.R;
import com.company.facelogin.adapters.AccountAdapter;
import com.company.facelogin.database.DatabaseHelper;
import com.company.facelogin.database.UserDao;
import com.company.facelogin.models.User;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccountSelectionActivity extends AppCompatActivity {

    private AccountAdapter adapter;
    private TextView       tvEmpty;
    private DatabaseHelper dbHelper;
    private UserDao        userDao;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_selection);

        dbHelper = new DatabaseHelper(this);
        userDao  = new UserDao(dbHelper);
        executor = Executors.newSingleThreadExecutor();

        tvEmpty = findViewById(R.id.tvEmpty);
        setupRecyclerView();
        setupNewRegistrationButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUsers();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) dbHelper.close();
        if (executor != null) executor.shutdown();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        adapter = new AccountAdapter(this::onUserSelected);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void setupNewRegistrationButton() {
        Button btnNewRegistration = findViewById(R.id.btnNewRegistration);
        btnNewRegistration.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private void loadUsers() {
        executor.execute(() -> {
            java.util.List<User> users = userDao.getAllUsers();
            runOnUiThread(() -> {
                adapter.updateUsers(users);
                tvEmpty.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void onUserSelected(User user) {
        Intent intent = new Intent(this, FaceVerificationActivity.class);
        intent.putExtra(FaceVerificationActivity.EXTRA_USER_ID, user.getId());
        startActivity(intent);
    }
}
