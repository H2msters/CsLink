package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

public class AiPlatformActivity extends AppCompatActivity {

    private static class ModelOption {
        String provider;
        String brandName;
        String modelId;
        String displayLabel;
        String avatarCode;
        int iconResId;

        ModelOption(String provider, String brandName, String modelId, String displayLabel, String avatarCode, int iconResId) {
            this.provider = provider;
            this.brandName = brandName;
            this.modelId = modelId;
            this.displayLabel = displayLabel;
            this.avatarCode = avatarCode;
            this.iconResId = iconResId;
        }
    }

    private Button btnAiBack;
    private TextView tvAiUserScore;
    private Button btnAiConfig;
    private ImageView ivSelectedModelIcon;
    private Spinner spAiModelSelector;
    private Button btnAiClearChat;
    private RecyclerView rvAiChat;
    private EditText etAiPrompt;
    private Button btnAiSend;

    private final List<ModelOption> modelOptions = new ArrayList<>();
    private ModelOption currentSelectedModel;

    private final List<ChatMessage> aiMessages = new ArrayList<>();
    private final Map<String, String> avatarMap = new HashMap<>();
    private final List<JSONObject> contextHistory = new ArrayList<>();
    private ChatAdapter chatAdapter;

    private String serverIp = "192.168.137.1";
    private String myNickname = "";
    private String myRole = "student";
    private String myAvatar = "";
    private int currentScore = 0;
    private int costPerMsg = 1;
    private JSONObject providerUrlsJson = new JSONObject();

    private OkHttpClient httpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_platform);

        View root = findViewById(R.id.aiPlatformRoot);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets sysInsets = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
                int bottom = Math.max(sysInsets.bottom, imeInsets.bottom);
                v.setPadding(sysInsets.left, sysInsets.top, sysInsets.right, bottom);
                return insets;
            });
        }

        SharedPreferences sp = getSharedPreferences("CampusChatPrefs", Context.MODE_PRIVATE);
        serverIp = sp.getString("server_ip", "192.168.137.1");
        myNickname = sp.getString("user_nickname", "学生");
        myRole = sp.getString("user_role", "student");
        myAvatar = sp.getString("user_avatar_base64", "");

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();

        initModelPresets();
        initViews();
        fetchAiStatus();
    }

    private void initModelPresets() {
        modelOptions.add(new ModelOption("qwen", "通义千问", "qwen3.8-flash", "通义千问 · qwen3.8-flash", "ICON_QWEN", R.drawable.ic_model_qwen));
        modelOptions.add(new ModelOption("deepseek", "DeepSeek", "deepseek-chat", "DeepSeek · V4.1 Flash", "ICON_DEEPSEEK", R.drawable.ic_model_deepseek));
        modelOptions.add(new ModelOption("glm", "智谱清言", "glm-4-flash", "智谱清言 · glm-4-flash", "ICON_GLM", R.drawable.ic_model_glm));

        currentSelectedModel = modelOptions.get(0);
    }

    private void initViews() {
        btnAiBack = findViewById(R.id.btnAiBack);
        tvAiUserScore = findViewById(R.id.tvAiUserScore);
        btnAiConfig = findViewById(R.id.btnAiConfig);
        ivSelectedModelIcon = findViewById(R.id.ivSelectedModelIcon);
        spAiModelSelector = findViewById(R.id.spAiModelSelector);
        btnAiClearChat = findViewById(R.id.btnAiClearChat);
        rvAiChat = findViewById(R.id.rvAiChat);
        etAiPrompt = findViewById(R.id.etAiPrompt);
        btnAiSend = findViewById(R.id.btnAiSend);

        btnAiBack.setOnClickListener(v -> finish());

        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        btnAiConfig.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
        tvAiUserScore.setVisibility(isTeacher ? View.GONE : View.VISIBLE);

        btnAiConfig.setOnClickListener(v -> showTeacherConfigDialog());

        List<String> labels = new ArrayList<>();
        for (ModelOption opt : modelOptions) {
            labels.add(opt.displayLabel);
        }
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spAiModelSelector.setAdapter(spinnerAdapter);

        spAiModelSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ModelOption selected = modelOptions.get(position);
                if (currentSelectedModel == null || !currentSelectedModel.modelId.equals(selected.modelId)) {
                    currentSelectedModel = selected;
                    ivSelectedModelIcon.setImageResource(currentSelectedModel.iconResId);

                    contextHistory.clear();
                    aiMessages.clear();
                    addWelcomeMessage();
                    if (chatAdapter != null) {
                        chatAdapter.notifyDataSetChanged();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnAiClearChat.setOnClickListener(v -> {
            contextHistory.clear();
            aiMessages.clear();
            addWelcomeMessage();
            chatAdapter.notifyDataSetChanged();
        });

        if (!TextUtils.isEmpty(myNickname) && !TextUtils.isEmpty(myAvatar)) {
            avatarMap.put(myNickname, myAvatar);
        }
        chatAdapter = new ChatAdapter(aiMessages, avatarMap);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        rvAiChat.setLayoutManager(lm);
        rvAiChat.setAdapter(chatAdapter);

        addWelcomeMessage();

        btnAiSend.setOnClickListener(v -> sendPromptToAi());
    }

    private void addWelcomeMessage() {
        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        String text = isTeacher
                ? "你好，当前对话模型为 " + currentSelectedModel.modelId + "。"
                : "你好，欢迎使用 AI 平台（每次提问消耗 " + costPerMsg + " 积分）。";

        aiMessages.add(new ChatMessage(
                "chat",
                currentSelectedModel.brandName,
                "model:" + currentSelectedModel.modelId,
                text,
                currentSelectedModel.avatarCode,
                false,
                System.currentTimeMillis()
        ));
    }

    private void fetchAiStatus() {
        new Thread(() -> {
            try {
                String url = "http://" + serverIp + ":8000/api/ai/status?username=" + myNickname;
                Request req = new Request.Builder().url(url).build();
                Response resp = httpClient.newCall(req).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONObject json = new JSONObject(resp.body().string());
                    currentScore = json.optInt("score", 0);
                    costPerMsg = json.optInt("cost", 1);
                    JSONObject urls = json.optJSONObject("provider_urls");
                    if (urls != null) {
                        providerUrlsJson = urls;
                    }
                    runOnUiThread(this::updateScoreAndHintUI);
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void updateScoreAndHintUI() {
        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        tvAiUserScore.setText("积分: " + currentScore);
        if (isTeacher) {
            etAiPrompt.setHint("输入提问内容...");
        } else {
            etAiPrompt.setHint("输入提问内容（消耗 " + costPerMsg + " 积分）");
        }
    }

    /**
     * 防抽搐流式打字机交互核心实现
     */
    private void sendPromptToAi() {
        String prompt = etAiPrompt.getText().toString().trim();
        if (TextUtils.isEmpty(prompt)) return;

        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        if (!isTeacher && currentScore < costPerMsg) {
            Toast.makeText(this, "积分不足（当前 " + currentScore + " 分）", Toast.LENGTH_SHORT).show();
            return;
        }

        final ModelOption activeModel = currentSelectedModel;

        etAiPrompt.setText("");
        btnAiSend.setEnabled(false);
        btnAiSend.setText("生成中");

        // 1. 用户消息上屏
        ChatMessage userMsg = new ChatMessage("chat", myNickname, myRole, prompt, myAvatar, true, System.currentTimeMillis());
        aiMessages.add(userMsg);
        chatAdapter.notifyItemInserted(aiMessages.size() - 1);
        rvAiChat.smoothScrollToPosition(aiMessages.size() - 1);

        // 2. 预先添加一个空的 AI 气泡
        ChatMessage aiMsg = new ChatMessage(
                "chat",
                activeModel.brandName,
                "model:" + activeModel.modelId,
                "",
                activeModel.avatarCode,
                false,
                System.currentTimeMillis()
        );
        aiMessages.add(aiMsg);
        int aiMsgIdx = aiMessages.size() - 1;
        chatAdapter.notifyItemInserted(aiMsgIdx);
        rvAiChat.scrollToPosition(aiMsgIdx);

        new Thread(() -> {
            try {
                JSONObject bodyJson = new JSONObject();
                bodyJson.put("username", myNickname);
                bodyJson.put("role", myRole);
                bodyJson.put("provider", activeModel.provider);
                bodyJson.put("model", activeModel.modelId);
                bodyJson.put("prompt", prompt);

                JSONArray histArr = new JSONArray();
                for (JSONObject h : contextHistory) {
                    histArr.put(h);
                }
                bodyJson.put("history", histArr);

                RequestBody body = RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        bodyJson.toString()
                );

                Request request = new Request.Builder()
                        .url("http://" + serverIp + ":8000/api/ai/stream_chat")
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    BufferedSource source = response.body().source();
                    StringBuilder fullReply = new StringBuilder();
                    final boolean[] isFirstChunk = {true}; // 修复：用数组包装使其在Lambda中可用

                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line != null && line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) break;

                            try {
                                JSONObject json = new JSONObject(data);
                                if (json.has("error")) {
                                    String err = json.getString("error");
                                    runOnUiThread(() -> Toast.makeText(this, err, Toast.LENGTH_LONG).show());
                                    break;
                                }
                                if (json.has("content")) {
                                    String delta = json.getString("content");
                                    fullReply.append(delta);

                                    // 仅更新文本，不频繁触发滚动防止抖动
                                    runOnUiThread(() -> {
                                        aiMsg.setContent(fullReply.toString());
                                        chatAdapter.notifyItemChanged(aiMsgIdx);
                                        if (isFirstChunk[0]) {
                                            rvAiChat.scrollToPosition(aiMsgIdx);
                                            isFirstChunk[0] = false;
                                        }
                                    });
                                }
                                if (json.has("score")) {
                                    currentScore = json.getInt("score");
                                    runOnUiThread(this::updateScoreAndHintUI);
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    JSONObject uTurn = new JSONObject();
                    uTurn.put("role", "user");
                    uTurn.put("content", prompt);
                    contextHistory.add(uTurn);

                    JSONObject aTurn = new JSONObject();
                    aTurn.put("role", "assistant");
                    aTurn.put("content", fullReply.toString());
                    contextHistory.add(aTurn);
                }

                runOnUiThread(() -> {
                    btnAiSend.setEnabled(true);
                    btnAiSend.setText("发送");
                    rvAiChat.smoothScrollToPosition(aiMsgIdx); // 生成完毕后平滑定位到底部
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "流式传输异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    btnAiSend.setEnabled(true);
                    btnAiSend.setText("发送");
                });
            }
        }).start();
    }

    private void showTeacherConfigDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 24, 60, 12);

        TextView tvProviderLabel = new TextView(this);
        tvProviderLabel.setText("选择配置厂商：");
        tvProviderLabel.setTextColor(Color.parseColor("#475569"));
        tvProviderLabel.setTextSize(12);
        layout.addView(tvProviderLabel);

        final String[] providerNames = {"通义千问 (Qwen)", "DeepSeek", "智谱清言 (GLM)"};
        final String[] providerCodes = {"qwen", "deepseek", "glm"};
        final String[] defaultUrls = {
                "https://maas.qianwenalapi.com/compatible-mode/v1",
                "https://api.deepseek.com/v1",
                "https://open.bigmodel.cn/api/paas/v4"
        };

        Spinner spProvider = new Spinner(this);
        spProvider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, providerNames));
        layout.addView(spProvider);

        final EditText etUrl = new EditText(this);
        etUrl.setHint("OpenAI 兼容 Base URL");
        etUrl.setTextSize(13);
        layout.addView(etUrl);

        final EditText etKey = new EditText(this);
        etKey.setHint("输入对应厂商的 API Key (sk-...)");
        etKey.setTextSize(13);
        layout.addView(etKey);

        final EditText etCost = new EditText(this);
        etCost.setHint("学生单次提问消耗积分");
        etCost.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCost.setText(String.valueOf(costPerMsg));
        etCost.setTextSize(13);
        layout.addView(etCost);

        int initIdx = 0;
        if ("deepseek".equals(currentSelectedModel.provider)) initIdx = 1;
        else if ("glm".equals(currentSelectedModel.provider)) initIdx = 2;
        spProvider.setSelection(initIdx);

        spProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String code = providerCodes[position];
                String savedUrl = providerUrlsJson.optString(code, defaultUrls[position]);
                etUrl.setText(savedUrl);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        new AlertDialog.Builder(this)
                .setTitle("API 管理")
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    int idx = spProvider.getSelectedItemPosition();
                    String provider = providerCodes[idx];
                    String url = etUrl.getText().toString().trim();
                    String key = etKey.getText().toString().trim();
                    int cost = 1;
                    try {
                        cost = Integer.parseInt(etCost.getText().toString().trim());
                    } catch (Exception ignored) {}

                    int finalCost = cost;
                    new Thread(() -> {
                        try {
                            JSONObject reqJson = new JSONObject();
                            reqJson.put("provider", provider);
                            reqJson.put("api_key", key);
                            reqJson.put("base_url", url);
                            reqJson.put("cost", finalCost);

                            RequestBody body = RequestBody.create(
                                    MediaType.parse("application/json; charset=utf-8"),
                                    reqJson.toString()
                            );
                            Request req = new Request.Builder()
                                    .url("http://" + serverIp + ":8000/api/ai/config")
                                    .post(body)
                                    .build();
                            Response resp = httpClient.newCall(req).execute();
                            if (resp.isSuccessful()) {
                                runOnUiThread(() -> {
                                    Toast.makeText(this, "API 配置已保存", Toast.LENGTH_SHORT).show();
                                    fetchAiStatus();
                                });
                            }
                        } catch (Exception ignored) {}
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}