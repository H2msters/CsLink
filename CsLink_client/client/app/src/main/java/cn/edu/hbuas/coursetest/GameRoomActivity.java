package cn.edu.hbuas.coursetest;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class GameRoomActivity extends AppCompatActivity implements GameSocketBridge.RoomMessageListener {

    public static final String EXTRA_ROOM_ID = "extra_room_id";
    public static final String EXTRA_ROOM_TITLE = "extra_room_title";
    public static final String EXTRA_CREATOR = "extra_creator";
    public static final String EXTRA_MAX_PLAYERS = "extra_max_players";

    private TextView tvRoomHeaderTitle;
    private Button btnDisbandRoom;
    private Button btnRoomMembers;
    private Button btnBackToLobby;

    private RecyclerView rvRoomChat;
    private LinearLayoutManager chatLayoutManager;
    private EditText etRoomMessage;
    private Button btnSendRoomMsg;
    private Button btnToggleRoomToolPanel;
    private LinearLayout layoutRoomToolPanel;

    private LinearLayout itemToolRps;
    private LinearLayout itemToolCanvas;

    private List<ChatMessage> roomMessageList;
    private Map<String, String> userAvatarMap;
    private ChatAdapter chatAdapter;

    private String currentRoomId = "";
    private String currentRoomTitle = "";
    private String roomCreator = "";
    private int maxPlayers = 4;
    private String myNickname = "";
    private String myRole = "student";
    private String myAvatarBase64 = "";

    private final List<String> currentGroupMembers = new ArrayList<>();

    // 对决弹窗与比分控件
    private Dialog rpsDialog;
    private TextView tvToolRpsTopic;
    private TextView tvP1Name, tvP1Score, tvP2Name, tvP2Score, tvCurrentRoundIndicator;
    private TextView tvToolRpsStatus;
    private TextView tvToolP1Status, tvToolP2Status, tvToolRpsResult;
    private Button btnChoiceRock, btnChoiceScissors, btnChoicePaper;
    private Button btnNextRound, btnCloseToolRps;

    private JSONObject latestRpsState = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_room);

        View rootLayout = findViewById(R.id.roomRootLayout);
        if (rootLayout != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
                Insets systemBars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        SharedPreferences sp = getSharedPreferences("CampusChatPrefs", Context.MODE_PRIVATE);
        myNickname = sp.getString("user_nickname", "");
        myRole = sp.getString("user_role", "student");
        myAvatarBase64 = sp.getString("user_avatar_base64", "");

        currentRoomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        currentRoomTitle = getIntent().getStringExtra(EXTRA_ROOM_TITLE);
        roomCreator = getIntent().getStringExtra(EXTRA_CREATOR);
        maxPlayers = getIntent().getIntExtra(EXTRA_MAX_PLAYERS, 4);

        initViews();
        initChat();
        initTools();

        GameSocketBridge.setListener(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                returnToLobby();
            }
        });
    }

    private void initViews() {
        tvRoomHeaderTitle = findViewById(R.id.tvRoomHeaderTitle);
        btnDisbandRoom = findViewById(R.id.btnDisbandRoom);
        btnRoomMembers = findViewById(R.id.btnRoomMembers);
        btnBackToLobby = findViewById(R.id.btnBackToLobby);

        rvRoomChat = findViewById(R.id.rvRoomChat);
        etRoomMessage = findViewById(R.id.etRoomMessage);
        btnSendRoomMsg = findViewById(R.id.btnSendRoomMsg);
        btnToggleRoomToolPanel = findViewById(R.id.btnToggleRoomToolPanel);
        layoutRoomToolPanel = findViewById(R.id.layoutRoomToolPanel);

        itemToolRps = findViewById(R.id.itemToolRps);
        itemToolCanvas = findViewById(R.id.itemToolCanvas);

        tvRoomHeaderTitle.setText(TextUtils.isEmpty(currentRoomTitle) ? "协作研讨组" : currentRoomTitle);

        boolean canDisband = "teacher".equalsIgnoreCase(myRole) || (!TextUtils.isEmpty(roomCreator) && roomCreator.equals(myNickname));
        btnDisbandRoom.setVisibility(canDisband ? View.VISIBLE : View.GONE);

        btnDisbandRoom.setOnClickListener(v -> showDisbandConfirmDialog());
        btnRoomMembers.setOnClickListener(v -> showGroupMembersDialog());
        btnBackToLobby.setOnClickListener(v -> returnToLobby());
    }

    private void initChat() {
        roomMessageList = new ArrayList<>();
        userAvatarMap = new HashMap<>();
        if (!TextUtils.isEmpty(myNickname) && !TextUtils.isEmpty(myAvatarBase64)) {
            userAvatarMap.put(myNickname, myAvatarBase64);
        }

        chatAdapter = new ChatAdapter(roomMessageList, userAvatarMap);

        // 点击气泡卡片进入对决
        chatAdapter.setOnInteractiveCardClickListener((cardType, title) -> {
            if ("card_rps".equals(cardType)) {
                sendJoinRpsGame();
                showRpsToolDialog();
            }
        });

        chatLayoutManager = new LinearLayoutManager(this);
        rvRoomChat.setLayoutManager(chatLayoutManager);
        rvRoomChat.setAdapter(chatAdapter);

        btnSendRoomMsg.setOnClickListener(v -> {
            String text = etRoomMessage.getText().toString().trim();
            if (TextUtils.isEmpty(text)) return;

            try {
                JSONObject json = new JSONObject();
                json.put("type", "chat");
                json.put("content", text);
                json.put("avatar", myAvatarBase64);
                GameSocketBridge.sendJson(json);
                etRoomMessage.setText("");
            } catch (JSONException ignored) {}
        });

        btnToggleRoomToolPanel.setOnClickListener(v -> {
            if (layoutRoomToolPanel.getVisibility() == View.VISIBLE) {
                layoutRoomToolPanel.setVisibility(View.GONE);
                btnToggleRoomToolPanel.setText("+");
            } else {
                layoutRoomToolPanel.setVisibility(View.VISIBLE);
                btnToggleRoomToolPanel.setText("收起");
            }
        });
    }

    private void initTools() {
        Button btnLaunchRpsTool = findViewById(R.id.btnLaunchRpsTool);
        Button btnToolCanvasAction = findViewById(R.id.btnToolCanvasAction);

        View.OnClickListener rpsClickListener = v -> {
            layoutRoomToolPanel.setVisibility(View.GONE);
            btnToggleRoomToolPanel.setText("+");
            showLaunchRpsConfigDialog();
        };

        itemToolRps.setOnClickListener(rpsClickListener);
        if (btnLaunchRpsTool != null) {
            btnLaunchRpsTool.setOnClickListener(rpsClickListener);
        }

        View.OnClickListener canvasClickListener = v -> {
            layoutRoomToolPanel.setVisibility(View.GONE);
            btnToggleRoomToolPanel.setText("+");
            Toast.makeText(this, "协同白板草稿即将接入", Toast.LENGTH_SHORT).show();
        };

        itemToolCanvas.setOnClickListener(canvasClickListener);
        if (btnToolCanvasAction != null) {
            btnToolCanvasAction.setOnClickListener(canvasClickListener);
        }
    }

    // 精简后的发起选项（直接选择赛制）
    private void showLaunchRpsConfigDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 20, 60, 10);

        TextView tvRuleLabel = new TextView(this);
        tvRuleLabel.setText("选择赛制：");
        tvRuleLabel.setTextColor(Color.parseColor("#475569"));
        tvRuleLabel.setTextSize(14);
        tvRuleLabel.setPadding(0, 10, 0, 10);
        layout.addView(tvRuleLabel);

        final RadioGroup rgRule = new RadioGroup(this);
        rgRule.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton rbBo3 = new RadioButton(this);
        rbBo3.setText("三局两胜");
        rbBo3.setChecked(true);
        final RadioButton rbSingle = new RadioButton(this);
        rbSingle.setText("单局决胜");
        rbSingle.setPadding(30, 0, 0, 0);

        rgRule.addView(rbBo3);
        rgRule.addView(rbSingle);
        layout.addView(rgRule);

        new AlertDialog.Builder(this)
                .setTitle("发起猜拳")
                .setView(layout)
                .setPositiveButton("立即发起", (dialog, which) -> {
                    int targetWins = rbBo3.isChecked() ? 2 : 1;
                    try {
                        JSONObject json = new JSONObject();
                        json.put("type", "initiate_game");
                        json.put("game_type", "rps");
                        json.put("target_wins", targetWins);
                        GameSocketBridge.sendJson(json);
                        showRpsToolDialog();
                    } catch (JSONException ignored) {}
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void sendJoinRpsGame() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "rps_join");
            GameSocketBridge.sendJson(json);
        } catch (JSONException ignored) {}
    }

    private void showDisbandConfirmDialog() {
        new AlertDialog.Builder(this)
                .setTitle("解散小组确认")
                .setMessage("确定要解散该协作组吗？解散后组内讨论记录将关闭，全员自动返回大厅。")
                .setPositiveButton("确认解散", (dialog, which) -> {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("type", "disband_room");
                        json.put("room_id", currentRoomId);
                        GameSocketBridge.sendJson(json);
                    } catch (JSONException ignored) {}
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showGroupMembersDialog() {
        StringBuilder sb = new StringBuilder();
        if (currentGroupMembers.isEmpty()) {
            sb.append("当前暂无其他成员在席。");
        } else {
            for (int i = 0; i < currentGroupMembers.size(); i++) {
                String m = currentGroupMembers.get(i);
                String tag = m.equals(roomCreator) ? " (组长/创建人)" : "";
                sb.append(i + 1).append(". ").append(m).append(tag).append(" · 在席\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("组内成员列表 (" + currentGroupMembers.size() + "/" + maxPlayers + "人)")
                .setMessage(sb.toString().trim())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void returnToLobby() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "leave_room");
            GameSocketBridge.sendJson(json);
        } catch (JSONException ignored) {}
        finish();
    }

    // ---------------- 对决活动弹窗逻辑 ----------------

    private void showRpsToolDialog() {
        if (rpsDialog != null && rpsDialog.isShowing()) {
            renderRpsStateToDialog(latestRpsState);
            return;
        }

        rpsDialog = new Dialog(this);
        rpsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_tool_rps, null);
        rpsDialog.setContentView(view);

        if (rpsDialog.getWindow() != null) {
            rpsDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            rpsDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        tvToolRpsTopic = view.findViewById(R.id.tvToolRpsTopic);
        tvP1Name = view.findViewById(R.id.tvP1Name);
        tvP1Score = view.findViewById(R.id.tvP1Score);
        tvP2Name = view.findViewById(R.id.tvP2Name);
        tvP2Score = view.findViewById(R.id.tvP2Score);
        tvCurrentRoundIndicator = view.findViewById(R.id.tvCurrentRoundIndicator);

        tvToolRpsStatus = view.findViewById(R.id.tvToolRpsStatus);
        tvToolP1Status = view.findViewById(R.id.tvToolP1Status);
        tvToolP2Status = view.findViewById(R.id.tvToolP2Status);
        tvToolRpsResult = view.findViewById(R.id.tvToolRpsResult);

        btnChoiceRock = view.findViewById(R.id.btnChoiceRock);
        btnChoiceScissors = view.findViewById(R.id.btnChoiceScissors);
        btnChoicePaper = view.findViewById(R.id.btnChoicePaper);
        btnNextRound = view.findViewById(R.id.btnNextRound);
        btnCloseToolRps = view.findViewById(R.id.btnCloseToolRps);

        btnChoiceRock.setOnClickListener(v -> sendRpsChoice("石头"));
        btnChoiceScissors.setOnClickListener(v -> sendRpsChoice("剪刀"));
        btnChoicePaper.setOnClickListener(v -> sendRpsChoice("布"));

        btnNextRound.setOnClickListener(v -> {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "rps_next_round");
                GameSocketBridge.sendJson(json);
            } catch (JSONException ignored) {}
        });

        btnCloseToolRps.setOnClickListener(v -> rpsDialog.dismiss());
        rpsDialog.show();

        renderRpsStateToDialog(latestRpsState);
    }

    private void sendRpsChoice(String choice) {
        if (tvToolP1Status != null) tvToolP1Status.setText("我方：已出【" + choice + "】");
        if (tvToolRpsResult != null) tvToolRpsResult.setText("已提交，等待对方出拳...");
        setChoiceButtonsEnabled(false);

        try {
            JSONObject json = new JSONObject();
            json.put("type", "rps_choice");
            json.put("choice", choice);
            GameSocketBridge.sendJson(json);
        } catch (JSONException ignored) {}
    }

    private void renderRpsStateToDialog(JSONObject state) {
        if (state == null || rpsDialog == null || !rpsDialog.isShowing()) return;

        String status = state.optString("status", "idle");
        int targetWins = state.optInt("target_wins", 2);
        int curRound = state.optInt("current_round", 1);
        String p1 = state.optString("p1", "");
        String p2 = state.optString("p2", "");
        String roundResult = state.optString("round_result", "");
        String finalWinner = state.optString("final_winner", "");

        JSONObject scores = state.optJSONObject("scores");
        int s1 = (scores != null && !TextUtils.isEmpty(p1)) ? scores.optInt(p1, 0) : 0;
        int s2 = (scores != null && !TextUtils.isEmpty(p2)) ? scores.optInt(p2, 0) : 0;

        JSONObject choicesStatus = state.optJSONObject("choices_status");
        JSONObject choices = state.optJSONObject("choices");

        // 1. 比分与赛制指示
        String ruleDesc = (targetWins == 1) ? "单局决胜" : "三局两胜";
        tvToolRpsTopic.setText("猜拳 (" + ruleDesc + ")");
        tvP1Name.setText(TextUtils.isEmpty(p1) ? "红方" : p1);
        tvP1Score.setText(String.valueOf(s1));
        tvP2Name.setText(TextUtils.isEmpty(p2) ? "蓝方 (等待加入)" : p2);
        tvP2Score.setText(String.valueOf(s2));
        tvCurrentRoundIndicator.setText("第 " + curRound + " 局");

        boolean isP1 = myNickname.equals(p1);
        boolean isP2 = myNickname.equals(p2);
        boolean isPlayer = isP1 || isP2;
        String opponent = isP1 ? p2 : p1;

        boolean myChosen = choicesStatus != null && choicesStatus.optBoolean(myNickname, false);
        boolean oppChosen = (!TextUtils.isEmpty(opponent) && choicesStatus != null) && choicesStatus.optBoolean(opponent, false);

        // 2. 状态机流转与按钮可见度控制
        if ("waiting_opponent".equals(status)) {
            btnNextRound.setVisibility(View.GONE);
            tvToolRpsStatus.setText("等待对手点击卡片加入...");
            tvToolRpsResult.setText("等待加入");

            if (isP1) {
                tvToolP1Status.setText("我方：已就绪");
                tvToolP2Status.setText("对方：等待加入");
                setChoiceButtonsEnabled(true);
            } else {
                tvToolP1Status.setText(p1 + "：发起方");
                tvToolP2Status.setText("我方：出拳即可应战");
                setChoiceButtonsEnabled(true);
            }

        } else if ("playing".equals(status)) {
            btnNextRound.setVisibility(View.GONE);
            tvToolRpsStatus.setText("第 " + curRound + " 局进行中");
            tvToolRpsResult.setText("请出拳");

            if (isPlayer) {
                tvToolP1Status.setText("我方：" + (myChosen ? "已出拳" : "未出拳"));
                tvToolP2Status.setText("对方：" + (oppChosen ? "已出拳" : "未出拳"));
                setChoiceButtonsEnabled(!myChosen);
            } else {
                tvToolRpsStatus.setText("正在观战");
                tvToolP1Status.setText(p1 + "：" + (choicesStatus != null && choicesStatus.optBoolean(p1, false) ? "已出拳" : "未出拳"));
                tvToolP2Status.setText(p2 + "：" + (choicesStatus != null && choicesStatus.optBoolean(p2, false) ? "已出拳" : "未出拳"));
                setChoiceButtonsEnabled(false);
            }

        } else if ("round_finished".equals(status)) {
            String c1 = (choices != null) ? choices.optString(p1, "") : "";
            String c2 = (choices != null) ? choices.optString(p2, "") : "";

            if (isPlayer) {
                tvToolP1Status.setText("我方：出【" + (isP1 ? c1 : c2) + "】");
                tvToolP2Status.setText("对方：出【" + (isP1 ? c2 : c1) + "】");
            } else {
                tvToolP1Status.setText(p1 + "：出【" + c1 + "】");
                tvToolP2Status.setText(p2 + "：出【" + c2 + "】");
            }

            tvToolRpsStatus.setText("比分 " + s1 + " : " + s2);
            tvToolRpsResult.setText(roundResult);
            setChoiceButtonsEnabled(false);
            btnNextRound.setVisibility(isPlayer ? View.VISIBLE : View.GONE);

        } else if ("match_finished".equals(status)) {
            btnNextRound.setVisibility(View.GONE);
            setChoiceButtonsEnabled(false);

            String c1 = (choices != null) ? choices.optString(p1, "") : "";
            String c2 = (choices != null) ? choices.optString(p2, "") : "";
            if (isPlayer) {
                tvToolP1Status.setText("我方：出【" + (isP1 ? c1 : c2) + "】");
                tvToolP2Status.setText("对方：出【" + (isP1 ? c2 : c1) + "】");
            } else {
                tvToolP1Status.setText(p1 + "：出【" + c1 + "】");
                tvToolP2Status.setText(p2 + "：出【" + c2 + "】");
            }

            tvToolRpsStatus.setText("对局结束");

            // 获取败者姓名并标注对决胜负关系
            String loser = p1.equals(finalWinner) ? p2 : p1;
            if (!TextUtils.isEmpty(loser)) {
                tvToolRpsResult.setText("【" + finalWinner + "】战胜了【" + loser + "】");
            } else {
                tvToolRpsResult.setText("【" + finalWinner + "】获胜");
            }
        }
    }

    private void setChoiceButtonsEnabled(boolean enabled) {
        if (btnChoiceRock != null) btnChoiceRock.setEnabled(enabled);
        if (btnChoiceScissors != null) btnChoiceScissors.setEnabled(enabled);
        if (btnChoicePaper != null) btnChoicePaper.setEnabled(enabled);
    }

    @Override
    public void onMessageReceived(JSONObject json) {
        runOnUiThread(() -> {
            String type = json.optString("type", "");

            if ("room_disbanded".equals(type)) {
                String reason = json.optString("message", "小组已被解散");
                Toast.makeText(this, reason, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            if ("history".equals(type)) {
                JSONObject avatarsObj = json.optJSONObject("user_avatars");
                if (avatarsObj != null) {
                    Iterator<String> keys = avatarsObj.keys();
                    while (keys.hasNext()) {
                        String user = keys.next();
                        userAvatarMap.put(user, avatarsObj.optString(user));
                    }
                }

                JSONArray historyArray = json.optJSONArray("messages");
                roomMessageList.clear();
                if (historyArray != null) {
                    for (int i = 0; i < historyArray.length(); i++) {
                        JSONObject msgObj = historyArray.optJSONObject(i);
                        if (msgObj != null) {
                            roomMessageList.add(parseChatMessage(msgObj));
                        }
                    }
                }
                chatAdapter.notifyDataSetChanged();
                if (!roomMessageList.isEmpty()) {
                    rvRoomChat.scrollToPosition(roomMessageList.size() - 1);
                }

            } else if ("room_state".equals(type)) {
                JSONArray membersArr = json.optJSONArray("members");
                currentGroupMembers.clear();
                if (membersArr != null) {
                    for (int i = 0; i < membersArr.length(); i++) {
                        currentGroupMembers.add(membersArr.optString(i));
                    }
                }
                int studentCount = json.optInt("count", currentGroupMembers.size());
                maxPlayers = json.optInt("max_players", maxPlayers);
                String creatorFromHub = json.optString("creator", "");
                if (!TextUtils.isEmpty(creatorFromHub)) {
                    roomCreator = creatorFromHub;
                    boolean canDisband = "teacher".equalsIgnoreCase(myRole) || roomCreator.equals(myNickname);
                    btnDisbandRoom.setVisibility(canDisband ? View.VISIBLE : View.GONE);
                }

                btnRoomMembers.setText("组员 (" + studentCount + "/" + maxPlayers + "人)");

                // 统一分发猜拳最新状态
            } else if ("rps_state".equals(type)) {
                latestRpsState = json.optJSONObject("state");
                renderRpsStateToDialog(latestRpsState);

            } else {
                ChatMessage newMsg = parseChatMessage(json);
                if (!TextUtils.isEmpty(newMsg.getSender()) && !TextUtils.isEmpty(newMsg.getAvatar())) {
                    userAvatarMap.put(newMsg.getSender(), newMsg.getAvatar());
                }
                roomMessageList.add(newMsg);
                chatAdapter.notifyItemInserted(roomMessageList.size() - 1);
                rvRoomChat.smoothScrollToPosition(roomMessageList.size() - 1);
            }
        });
    }

    private ChatMessage parseChatMessage(JSONObject json) {
        String type = json.optString("type", "chat");
        String sender = json.optString("sender", "某同学");
        String role = json.optString("role", "student");
        String content = json.optString("content", "");
        String avatar = json.optString("avatar", "");
        long timestamp = json.optLong("timestamp", System.currentTimeMillis());

        boolean isMe = false;
        if (!"system".equals(type) && !TextUtils.isEmpty(myNickname)) {
            isMe = myNickname.equals(sender);
        }

        return new ChatMessage(type, sender, role, content, avatar, isMe, timestamp);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (rpsDialog != null && rpsDialog.isShowing()) {
            rpsDialog.dismiss();
        }
        GameSocketBridge.clearListener();
    }
}