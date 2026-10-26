package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "CampusChatPrefs";
    public static final String KEY_SERVER_IP = "server_ip";
    public static final String KEY_USER_ROLE = "user_role";
    public static final String KEY_AUTH_TOKEN = "auth_token";
    public static final String DEFAULT_SERVER_IP = "192.168.137.1";
    public static final String DEFAULT_ROLE = "student";

    private EditText etServerIp;
    private Button btnSaveIp;
    private Button btnClearCache;
    private Button btnBack;

    private TextView tvAppVersion;
    private LinearLayout layoutDevOptions;
    private EditText etDevToken;
    private Button btnDevToggleRole;
    private Button btnSaveDevConfig;

    private SharedPreferences sharedPreferences;

    private int versionClickCount = 0;
    private long lastClickTimestamp = 0;
    private String selectedRole = DEFAULT_ROLE;

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

        initViews();
        loadSavedConfig();
        initListeners();
    }

    private void initViews() {
        etServerIp = findViewById(R.id.etServerIp);
        btnSaveIp = findViewById(R.id.btnSaveIp);
        btnClearCache = findViewById(R.id.btnClearCache);
        btnBack = findViewById(R.id.btnBack);

        tvAppVersion = findViewById(R.id.tvAppVersion);
        layoutDevOptions = findViewById(R.id.layoutDevOptions);
        etDevToken = findViewById(R.id.etDevToken);
        btnDevToggleRole = findViewById(R.id.btnDevToggleRole);
        btnSaveDevConfig = findViewById(R.id.btnSaveDevConfig);
    }

    private void loadSavedConfig() {
        String savedIp = sharedPreferences.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP);
        etServerIp.setText(savedIp);

        selectedRole = sharedPreferences.getString(KEY_USER_ROLE, DEFAULT_ROLE);
        updateRoleToggleButtonText();

        String savedToken = sharedPreferences.getString(KEY_AUTH_TOKEN, "");
        etDevToken.setText(savedToken);
    }

    private void initListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnSaveIp.setOnClickListener(v -> {
            String newIp = etServerIp.getText().toString().trim();
            if (!TextUtils.isEmpty(newIp)) {
                sharedPreferences.edit().putString(KEY_SERVER_IP, newIp).apply();
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });

        btnClearCache.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("清除缓存")
                    .setMessage("确认清除本地缓存与配置？")
                    .setPositiveButton("确定", (dialog, which) -> {
                        sharedPreferences.edit().clear().apply();
                        Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        tvAppVersion.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastClickTimestamp > 1500) {
                versionClickCount = 0;
            }
            lastClickTimestamp = now;
            versionClickCount++;

            if (versionClickCount >= 5) {
                if (layoutDevOptions.getVisibility() != View.VISIBLE) {
                    layoutDevOptions.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "已展开权限配置", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnDevToggleRole.setOnClickListener(v -> {
            if ("student".equalsIgnoreCase(selectedRole)) {
                selectedRole = "monitor";
            } else if ("monitor".equalsIgnoreCase(selectedRole)) {
                selectedRole = "teacher";
            } else {
                selectedRole = "student";
            }
            updateRoleToggleButtonText();
        });

        btnSaveDevConfig.setOnClickListener(v -> {
            String token = etDevToken.getText().toString().trim();
            if (("teacher".equalsIgnoreCase(selectedRole) || "monitor".equalsIgnoreCase(selectedRole)) && TextUtils.isEmpty(token)) {
                Toast.makeText(this, "请输入授权口令", Toast.LENGTH_SHORT).show();
                return;
            }

            sharedPreferences.edit()
                    .putString(KEY_USER_ROLE, selectedRole)
                    .putString(KEY_AUTH_TOKEN, token)
                    .apply();

            Toast.makeText(this, "权限已更新", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }

    private void updateRoleToggleButtonText() {
        String roleName = "teacher".equalsIgnoreCase(selectedRole) ? "教师"
                : ("monitor".equalsIgnoreCase(selectedRole) ? "班长" : "学生");
        btnDevToggleRole.setText("当前权限：" + roleName + "（点击切换）");
    }
}