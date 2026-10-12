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

    // 开发者暗门与身份切换组件
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

        // 保存网络 IP 配置
        btnSaveIp.setOnClickListener(v -> {
            String newIp = etServerIp.getText().toString().trim();
            if (!TextUtils.isEmpty(newIp)) {
                sharedPreferences.edit().putString(KEY_SERVER_IP, newIp).apply();
                Toast.makeText(this, "网络节点已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });

        // 清除缓存与凭证
        btnClearCache.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("清理配置")
                    .setMessage("确定要清除本地身份配置与缓存数据吗？")
                    .setPositiveButton("确定", (dialog, which) -> {
                        sharedPreferences.edit().clear().apply();
                        Toast.makeText(this, "缓存已清空，请重新进入", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // 核心彩蛋：连续点击版本号 5 次唤醒开发者特权卡片
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
                    Toast.makeText(this, "开发者调试模式已解锁！", Toast.LENGTH_SHORT).show();
                }
            } else if (versionClickCount >= 2) {
                int left = 5 - versionClickCount;
                Toast.makeText(this, "再点击 " + left + " 次解锁特权配置", Toast.LENGTH_SHORT).show();
            }
        });

        // 切换角色（学生端 / 教师端）
        btnDevToggleRole.setOnClickListener(v -> {
            if ("teacher".equalsIgnoreCase(selectedRole)) {
                selectedRole = "student";
            } else {
                selectedRole = "teacher";
            }
            updateRoleToggleButtonText();
        });

        // 保存特权身份与口令
        btnSaveDevConfig.setOnClickListener(v -> {
            String token = etDevToken.getText().toString().trim();
            if ("teacher".equalsIgnoreCase(selectedRole) && TextUtils.isEmpty(token)) {
                Toast.makeText(this, "切换为教师端必须输入授权口令！", Toast.LENGTH_SHORT).show();
                return;
            }

            sharedPreferences.edit()
                    .putString(KEY_USER_ROLE, selectedRole)
                    .putString(KEY_AUTH_TOKEN, token)
                    .apply();

            Toast.makeText(this, "身份已切换为【" + ("teacher".equalsIgnoreCase(selectedRole) ? "教师端" : "学生端") + "】，正在重载...", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }

    private void updateRoleToggleButtonText() {
        String roleName = "teacher".equalsIgnoreCase(selectedRole) ? "教师端" : "学生端";
        btnDevToggleRole.setText("切换当前模式：【" + roleName + "】(点击可换)");
    }
}