package com.hassan.vidsave;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import androidx.appcompat.app.AppCompatActivity;


public class DisclaimerActivity extends AppCompatActivity {

    private CheckBox checkboxAgree;
    private Button btnContinue;
    private static final String PREFS_NAME = "AppPreferences";
    private static final String KEY_AGREED = "disclaimer_agreed";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if user has already agreed
        if (hasUserAgreed()) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_disclaimer);

        initializeViews();
        setupListeners();
    }

    private void initializeViews() {
        checkboxAgree = findViewById(R.id.checkboxAgree);
        btnContinue = findViewById(R.id.btnContinue);
    }

    private void setupListeners() {
        // Enable/disable button based on checkbox state
        checkboxAgree.setOnCheckedChangeListener((buttonView, isChecked) -> {
            btnContinue.setEnabled(isChecked);
        });

        // Handle continue button click
        btnContinue.setOnClickListener(v -> {
            if (checkboxAgree.isChecked()) {
                saveAgreement();
                navigateToMain();
            }
        });
    }

    private boolean hasUserAgreed() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_AGREED, false);
    }

    private void saveAgreement() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_AGREED, true);
        editor.apply();
    }

    private void navigateToMain() {
        Intent intent = new Intent(DisclaimerActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        // Prevent going back - user must agree to continue
        // You can optionally show a dialog asking if they want to exit the app
        super.onBackPressed();
        finishAffinity(); // This will close the app
    }
}