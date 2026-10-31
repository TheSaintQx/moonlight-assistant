package com.limelight.preferences;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.limelight.R;

public class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView eulaTitle = findViewById(R.id.eula_title);
        TextView eulaText = findViewById(R.id.eula_text);
        TextView privacyTitle = findViewById(R.id.privacy_title);
        TextView privacyText = findViewById(R.id.privacy_text);

        eulaTitle.setText(R.string.about_eula_title);
        eulaText.setText(R.string.about_eula_text);
        privacyTitle.setText(R.string.about_privacy_title);
        privacyText.setText(R.string.about_privacy_text);
    }
}
