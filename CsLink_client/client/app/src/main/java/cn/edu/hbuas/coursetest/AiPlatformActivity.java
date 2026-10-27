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
import androidx.recyclerview.widget.SimpleItemAnimator;
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

    private SharedPreferences sp;
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

        sp = getSharedPreferences("CampusChatPrefs", Context.MODE_PRIVATE);
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

    @Override
    protected void onPause() {
        super.onPause();
        // 页面退出或进入后台时自动持久化保存当前对话记录
        saveChatHistory();
    }

    private void initModelPresets() {
        modelOptions.add(new ModelOption("qwen", "通义千问", "qwen3.8-flash", "通义千问 · qwen3.8-flash", "ICON_QWEN", R.drawable.ic_model_qwen));
        modelOptions.add(new ModelOption("deepseek", "DeepSeek", "deepseek-v4.1-flash-1017", "deepseek-v4.1-flash-1017", "ICON_DEEPSEEK", R.drawable.ic_model_deepseek));
        modelOptions.add(new ModelOption("glm", "智谱清言", "glm-5.3", "智谱清言 · glm-5.3", "ICON_GLM", R.drawable.ic_model_glm));

        int lastIdx = sp.getInt("ai_last_model_idx_" + myNickname, 0);
        if (lastIdx < 0 || lastIdx >= modelOptions.size()) {
            lastIdx = 0;
        }
        currentSelectedModel = modelOptions.get(lastIdx);
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

        if (!TextUtils.isEmpty(myNickname) && !TextUtils.isEmpty(myAvatar)) {
            avatarMap.put(myNickname, myAvatar);
        }
        chatAdapter = new ChatAdapter(aiMessages, avatarMap);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        rvAiChat.setLayoutManager(lm);
        rvAiChat.setAdapter(chatAdapter);

        // 关闭 RecyclerView 默认变更动画，彻底消除打字重影
        if (rvAiChat.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) rvAiChat.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        rvAiChat.setItemAnimator(null);

        List<String> labels = new ArrayList<>();
        for (ModelOption opt : modelOptions) {
            labels.add(opt.displayLabel);
        }
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spAiModelSelector.setAdapter(spinnerAdapter);

        int initPos = modelOptions.indexOf(currentSelectedModel);
        if (initPos >= 0) {
            spAiModelSelector.setSelection(initPos, false);
            ivSelectedModelIcon.setImageResource(currentSelectedModel.iconResId);
        }

        // 首次进入加载本地保存的历史聊天记录
        loadChatHistory();

        spAiModelSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ModelOption selected = modelOptions.get(position);
                if (currentSelectedModel == null || !currentSelectedModel.modelId.equals(selected.modelId)) {
                    // 切换前先保存旧模型的聊天记录
                    saveChatHistory();

                    currentSelectedModel = selected;
                    ivSelectedModelIcon.setImageResource(currentSelectedModel.iconResId);
                    sp.edit().putInt("ai_last_model_idx_" + myNickname, position).apply();

                    // 加载新模型的历史记录（若无记录则显示欢迎语）
                    loadChatHistory();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 清空对话增加二次确认弹窗
        btnAiClearChat.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("清空对话")
                    .setMessage("确定要清空当前模型（" + currentSelectedModel.brandName + "）的聊天记录吗？清空后无法恢复。")
                    .setPositiveButton("确定清空", (dialog, which) -> {
                        contextHistory.clear();
                        aiMessages.clear();
                        addWelcomeMessage();
                        saveChatHistory();
                        chatAdapter.notifyDataSetChanged();
                        Toast.makeText(this, "当前对话已清空", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnAiSend.setOnClickListener(v -> sendPromptToAi());
    }

    /**
     * 将当前模型的聊天界面消息与上下文历史持久化保存到本地 SharedPreferences
     */
    private void saveChatHistory() {
        if (sp == null || currentSelectedModel == null) return;
        try {
            JSONArray msgArr = new JSONArray();
            for (ChatMessage m : aiMessages) {
                JSONObject obj = new JSONObject();
                obj.put("type", m.getType());
                obj.put("sender", m.getSender());
                obj.put("role", m.getRole());
                String content = m.getContent();
                if ("正在思考......".equals(content)) {
                    content = "（上次生成未完成）";
                }
                obj.put("content", content);
                // 自己发送的消息不重复存储大体积头像 Base64，读取时直接关联 myAvatar
                obj.put("avatar", m.isMe() ? "" : m.getAvatar());
                obj.put("isMe", m.isMe());
                obj.put("timestamp", m.getTimestamp());
                msgArr.put(obj);
            }

            JSONArray ctxArr = new JSONArray();
            for (JSONObject ctx : contextHistory) {
                ctxArr.put(ctx);
            }

            String keyPrefix = "ai_history_" + myNickname + "_" + currentSelectedModel.modelId;
            sp.edit()
                    .putString(keyPrefix + "_msgs", msgArr.toString())
                    .putString(keyPrefix + "_ctx", ctxArr.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    /**
     * 从本地 SharedPreferences 恢复当前模型的聊天记录与上下文
     */
    private void loadChatHistory() {
        aiMessages.clear();
        contextHistory.clear();

        if (sp != null && currentSelectedModel != null) {
            String keyPrefix = "ai_history_" + myNickname + "_" + currentSelectedModel.modelId;
            String msgsStr = sp.getString(keyPrefix + "_msgs", "");
            String ctxStr = sp.getString(keyPrefix + "_ctx", "");

            if (!TextUtils.isEmpty(msgsStr)) {
                try {
                    JSONArray msgArr = new JSONArray(msgsStr);
                    for (int i = 0; i < msgArr.length(); i++) {
                        JSONObject o = msgArr.getJSONObject(i);
                        boolean isMe = o.optBoolean("isMe", false);
                        String avatar = isMe ? myAvatar : o.optString("avatar", currentSelectedModel.avatarCode);
                        aiMessages.add(new ChatMessage(
                                o.optString("type", "chat"),
                                o.optString("sender", ""),
                                o.optString("role", "student"),
                                o.optString("content", ""),
                                avatar,
                                isMe,
                                o.optLong("timestamp", System.currentTimeMillis())
                        ));
                    }
                } catch (Exception ignored) {}
            }

            if (!TextUtils.isEmpty(ctxStr)) {
                try {
                    JSONArray ctxArr = new JSONArray(ctxStr);
                    for (int i = 0; i < ctxArr.length(); i++) {
                        contextHistory.add(ctxArr.getJSONObject(i));
                    }
                } catch (Exception ignored) {}
            }
        }

        if (aiMessages.isEmpty()) {
            addWelcomeMessage();
        }

        if (chatAdapter != null) {
            chatAdapter.notifyDataSetChanged();
            rvAiChat.scrollToPosition(Math.max(0, aiMessages.size() - 1));
        }
    }

    private void addWelcomeMessage() {
        String text = "我是班级专属的 **AI 图书管理员**。无论是查找馆藏电子书、解答学科难题，还是探索各类百科知识，我都能随时为你解答！";

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
            etAiPrompt.setHint("检索图书角藏书或提问百科知识...");
        } else {
            etAiPrompt.setHint("找书或提问百科（消耗 " + costPerMsg + " 积分）");
        }
    }

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
        rvAiChat.scrollToPosition(aiMessages.size() - 1);

        // 2. 预先添加带有“正在思考......”占位的 AI 气泡
        ChatMessage aiMsg = new ChatMessage(
                "chat",
                activeModel.brandName,
                "model:" + activeModel.modelId,
                "正在思考......",
                activeModel.avatarCode,
                false,
                System.currentTimeMillis()
        );
        aiMessages.add(aiMsg);
        int aiMsgIdx = aiMessages.size() - 1;
        chatAdapter.notifyItemInserted(aiMsgIdx);
        rvAiChat.scrollToPosition(aiMsgIdx);
        saveChatHistory();

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
                    final boolean[] isFirstChunk = {true};
                    long lastUiUpdateTime = 0L;

                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line != null && line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) break;

                            try {
                                JSONObject json = new JSONObject(data);
                                if (json.has("error")) {
                                    String err = json.getString("error");
                                    runOnUiThread(() -> {
                                        aiMsg.setContent("生成中断：" + err);
                                        chatAdapter.notifyItemChanged(aiMsgIdx, "STREAM_TEXT");
                                        saveChatHistory();
                                        Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                                    });
                                    break;
                                }
                                if (json.has("content")) {
                                    String delta = json.getString("content");
                                    fullReply.append(delta);

                                    long now = System.currentTimeMillis();
                                    if (isFirstChunk[0] || (now - lastUiUpdateTime) >= 60) {
                                        lastUiUpdateTime = now;
                                        final String snapshot = fullReply.toString();
                                        runOnUiThread(() -> {
                                            aiMsg.setContent(snapshot);
                                            chatAdapter.notifyItemChanged(aiMsgIdx, "STREAM_TEXT");
                                            if (isFirstChunk[0]) {
                                                rvAiChat.scrollToPosition(aiMsgIdx);
                                                isFirstChunk[0] = false;
                                            }
                                        });
                                    }
                                }
                                if (json.has("score")) {
                                    currentScore = json.getInt("score");
                                    runOnUiThread(this::updateScoreAndHintUI);
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (fullReply.length() > 0) {
                        final String finalText = fullReply.toString();

                        JSONObject uTurn = new JSONObject();
                        uTurn.put("role", "user");
                        uTurn.put("content", prompt);
                        contextHistory.add(uTurn);

                        JSONObject aTurn = new JSONObject();
                        aTurn.put("role", "assistant");
                        aTurn.put("content", finalText);
                        contextHistory.add(aTurn);

                        runOnUiThread(() -> {
                            aiMsg.setContent(finalText);
                            chatAdapter.notifyItemChanged(aiMsgIdx, "STREAM_TEXT");
                            saveChatHistory();
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        aiMsg.setContent("服务响应异常，请稍后重试。");
                        chatAdapter.notifyItemChanged(aiMsgIdx, "STREAM_TEXT");
                        saveChatHistory();
                    });
                }

                runOnUiThread(() -> {
                    btnAiSend.setEnabled(true);
                    btnAiSend.setText("发送");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    aiMsg.setContent("网络异常：" + e.getMessage());
                    chatAdapter.notifyItemChanged(aiMsgIdx, "STREAM_TEXT");
                    saveChatHistory();
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