package com.hassan.vidsavedownloader;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

public class HowToGuideActivity extends AppCompatActivity {

    private static final class Step {
        final int number;
        final int titleRes;
        final int descRes;
        final int mockLayoutRes;

        Step(int number, int titleRes, int descRes, int mockLayoutRes) {
            this.number = number;
            this.titleRes = titleRes;
            this.descRes = descRes;
            this.mockLayoutRes = mockLayoutRes;
        }
    }

    private static final Step[] STEPS = {
            new Step(1, R.string.how_to_step1_title, R.string.how_to_step1_desc, R.layout.guide_mock_paste),
            new Step(2, R.string.how_to_step2_title, R.string.how_to_step2_desc, R.layout.guide_mock_download),
            new Step(3, R.string.how_to_step3_title, R.string.how_to_step3_desc, R.layout.guide_mock_browser),
            new Step(4, R.string.how_to_step4_title, R.string.how_to_step4_desc, R.layout.guide_mock_library),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_how_to_guide);

        MaterialToolbar toolbar = findViewById(R.id.toolbarHowTo);
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout stepsContainer = findViewById(R.id.stepsContainer);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (Step step : STEPS) {
            View stepView = inflater.inflate(R.layout.item_how_to_step, stepsContainer, false);

            TextView tvStepNumber = stepView.findViewById(R.id.tvStepNumber);
            TextView tvStepTitle = stepView.findViewById(R.id.tvStepTitle);
            TextView tvStepDesc = stepView.findViewById(R.id.tvStepDesc);
            FrameLayout visualContainer = stepView.findViewById(R.id.stepVisualContainer);

            tvStepNumber.setText(String.valueOf(step.number));
            tvStepTitle.setText(step.titleRes);
            tvStepDesc.setText(step.descRes);

            View mock = inflater.inflate(step.mockLayoutRes, visualContainer, false);
            visualContainer.addView(mock);

            stepsContainer.addView(stepView);
        }
    }
}
