package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
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

    private TextView tvRoomHeaderTitle;
    private Button btnExitRoom;

    // 猜拳组件
    private TextView tvGameStatus;
    private TextView tvPlayer1Status;
    private TextView tvPlayer2Status;
    private TextView tvGameResult;
    private Button btnChoiceRock;
    private Button btnChoiceScissors;
    private Button btnChoicePaper;
    private Button btnResetGame;

    // 房间内聊天组件
    private RecyclerView rvRoomChat;
    private LinearLayoutManager chatLayoutManager;
    private EditText etRoomMessage;
    private Button btnSendRoomMsg;

    private List<ChatMessage> roomMessageList;
    private Map<String, String> userAvatarMap;
    private ChatAdapter chatAdapter;

    private String currentRoomId = "";
    private String currentRoomTitle = "";
    private String myNickname = "";
    private String myAvatarBase64 = "";

    private String myCurrentChoice = "";

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
        myAvatarBase64 = sp.getString("user_avatar_base64", "");

        currentRoomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        currentRoomTitle = getIntent().getStringExtra(EXTRA_ROOM_TITLE);

        initViews();
        initChat();
        initGameListeners();

        GameSocketBridge.setListener(this);
    }

    private void initViews() {
        tvRoomHeaderTitle = findViewById(R.id.tvRoomHeaderTitle);
        btnExitRoom = findViewById(R.id.btnExitRoom);

        tvGameStatus = findViewById(R.id.tvGameStatus);
        tvPlayer1Status = findViewById(R.id.tvPlayer1Status);
        tvPlayer2Status = findViewById(R.id.tvPlayer2Status);
        tvGameResult = findViewById(R.id.tvGameResult);
        btnChoiceRock = findViewById(R.id.btnChoiceRock);
        btnChoiceScissors = findViewById(R.id.btnChoiceScissors);
        btnChoicePaper = findViewById(R.id.btnChoicePaper);
        btnResetGame = findViewById(R.id.btnResetGame);

        rvRoomChat = findViewById(R.id.rvRoomChat);
        etRoomMessage = findViewById(R.id.etRoomMessage);
        btnSendRoomMsg = findViewById(R.id.btnSendRoomMsg);

        tvRoomHeaderTitle.setText(TextUtils.isEmpty(currentRoomTitle) ? "游戏对战室" : currentRoomTitle);

        btnExitRoom.setOnClickListener(v -> exitRoom());
    }

    private void initChat() {
        roomMessageList = new ArrayList<>();
        userAvatarMap = new HashMap<>();
        if (!TextUtils.isEmpty(myNickname) && !TextUtils.isEmpty(myAvatarBase64)) {
            userAvatarMap.put(myNickname, myAvatarBase64);
        }

        chatAdapter = new ChatAdapter(roomMessageList, userAvatarMap);
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
    }

    private void initGameListeners() {
        btnChoiceRock.setOnClickListener(v -> sendChoice("石头"));
        btnChoiceScissors.setOnClickListener(v -> sendChoice("剪刀"));
        btnChoicePaper.setOnClickListener(v -> sendChoice("布"));

        btnResetGame.setOnClickListener(v -> {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "rps_reset");
                GameSocketBridge.sendJson(json);
            } catch (JSONException ignored) {}
        });
    }

    private void sendChoice(String choice) {
        myCurrentChoice = choice;
        tvPlayer1Status.setText("我方：已出【" + choice + "】");
        tvGameResult.setText("等待对方出拳...");

        try {
            JSONObject json = new JSONObject();
            json.put("type", "rps_choice");
            json.put("choice", choice);
            GameSocketBridge.sendJson(json);
        } catch (JSONException ignored) {}
    }

    private void exitRoom() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "leave_room");
            GameSocketBridge.sendJson(json);
        } catch (JSONException ignored) {}
        finish();
    }

    @Override
    public void onMessageReceived(JSONObject json) {
        runOnUiThread(() -> {
            String type = json.optString("type", "");

            // 1. 房间专属历史记录
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

                // 2. 房间状态与出拳进度
            } else if ("room_state".equals(type)) {
                JSONArray members = json.optJSONArray("members");
                int count = json.optInt("count", 0);
                tvGameStatus.setText("房间成员 (" + count + "/2人) 对局进行中");

                JSONObject choices = json.optJSONObject("choices_status");
                if (choices != null && members != null) {
                    for (int i = 0; i < members.length(); i++) {
                        String name = members.optString(i);
                        boolean hasChosen = choices.optBoolean(name, false);
                        if (name.equals(myNickname)) {
                            if (!hasChosen) {
                                tvPlayer1Status.setText("我方：未出拳");
                            }
                        } else {
                            tvPlayer2Status.setText("对方(" + name + ")：" + (hasChosen ? "已出拳" : "思考中..."));
                        }
                    }
                }

                // 3. 猜拳对决揭晓
            } else if ("rps_result".equals(type)) {
                String resultText = json.optString("result_text");
                String p1Name = json.optString("p1_name");
                String p1Choice = json.optString("p1_choice");
                String p2Name = json.optString("p2_name");
                String p2Choice = json.optString("p2_choice");

                if (p1Name.equals(myNickname)) {
                    tvPlayer1Status.setText("我方：出【" + p1Choice + "】");
                    tvPlayer2Status.setText("对方：出【" + p2Choice + "】");
                } else {
                    tvPlayer1Status.setText("我方：出【" + p2Choice + "】");
                    tvPlayer2Status.setText("对方：出【" + p1Choice + "】");
                }

                tvGameResult.setText(resultText);

                // 4. 对局重置通知
            } else if ("rps_reset_notice".equals(type)) {
                myCurrentChoice = "";
                tvPlayer1Status.setText("我方：未出拳");
                tvPlayer2Status.setText("对方：未出拳");
                tvGameResult.setText("对局已重置，请出拳");

                // 5. 房间内文字与系统消息
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
        String content = json.optString("content", "");
        String avatar = json.optString("avatar", "");
        long timestamp = json.optLong("timestamp", System.currentTimeMillis());

        boolean isMe = false;
        if (!"system".equals(type) && !TextUtils.isEmpty(myNickname)) {
            isMe = myNickname.equals(sender);
        }

        return new ChatMessage(type, sender, content, avatar, isMe, timestamp);
    }

    @Override
    public void onBackPressed() {
        exitRoom();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GameSocketBridge.clearListener();
    }
}