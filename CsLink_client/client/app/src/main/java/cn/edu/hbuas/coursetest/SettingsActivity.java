package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREF_NAME = "CampusChatPrefs";
    private static final String KEY_SERVER_IP = "server_ip";
    private static final String DEFAULT_SERVER_IP = "192.168.137.1";

    private EditText etServerIp;
    private Button btnSaveIp;
    private Button btnClearCache;
    private Button btnBack;

    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        View settingsLayout = findViewById(R.id.settingsLayout);
        if (settingsLayout != null) {
            ViewCompat.setOnApplyWindowInsetsListener(settingsLayout, (v, insets) -> {
                Insets systemBars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        etServerIp = findViewById(R.id.etServerIp);
        btnSaveIp = findViewById(R.id.btnSaveIp);
        btnClearCache = findViewById(R.id.btnClearCache);
        btnBack = findViewById(R.id.btnBack);

        String savedIp = sharedPreferences.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP);
        etServerIp.setText(savedIp);

        btnBack.setOnClickListener(v -> finish());

        btnSaveIp.setOnClickListener(v -> {
            String newIp = etServerIp.getText().toString().trim();
            if (!TextUtils.isEmpty(newIp)) {
                sharedPreferences.edit().putString(KEY_SERVER_IP, newIp).apply();
                setResult(RESULT_OK);
                finish();
            }
        });

        btnClearCache.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("确定要清除本地缓存与配置吗？")
                    .setPositiveButton("确定", (dialog, which) -> {
                        sharedPreferences.edit().clear().apply();
                        finish();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }
}