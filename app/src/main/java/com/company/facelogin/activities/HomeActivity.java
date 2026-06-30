package com.company.facelogin.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.company.facelogin.R;
import com.company.facelogin.database.DatabaseHelper;
import com.company.facelogin.database.UserDao;
import com.company.facelogin.utils.SessionManager;

public class HomeActivity extends AppCompatActivity {

    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SessionManager(this);

        // Security gate: no valid session → cannot stay on this screen
        if (!sessionManager.isLoggedIn()) {
            redirectToLogin();
            return;
        }

        // Guard against stale sessions (e.g. user deleted from DB while logged in)
        DatabaseHelper dbHelper = new DatabaseHelper(this);
        UserDao userDao = new UserDao(dbHelper);
        boolean userExists = userDao.getUserById(sessionManager.getLoggedInUserId()) != null;
        dbHelper.close();
        if (!userExists) {
            sessionManager.clearSession();
            redirectToLogin();
            return;
        }

        setContentView(R.layout.activity_home);

        String fullName = sessionManager.getFirstName() + " " + sessionManager.getLastName();
        ((TextView) findViewById(R.id.tvWelcome)).setText("Hoş geldin,\n" + fullName);
        ((Button) findViewById(R.id.btnScanFace)).setOnClickListener(v ->
                startActivity(new Intent(this, FaceIdentificationActivity.class)));
        ((Button) findViewById(R.id.btnLogout)).setOnClickListener(v -> logout());
    }

    @Override
    public void onBackPressed() {
        // Close the entire app — back must not expose the login screen
        finishAffinity();
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void logout() {
        sessionManager.clearSession();
        Intent intent = new Intent(this, AccountSelectionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void redirectToLogin() {
        Intent intent = new Intent(this, AccountSelectionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
}
