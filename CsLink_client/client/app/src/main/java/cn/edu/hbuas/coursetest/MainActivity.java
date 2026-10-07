package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "CampusChatPrefs";
    private static final String KEY_NICKNAME = "user_nickname";
    private static final String KEY_AVATAR_BASE64 = "user_avatar_base64";
    private static final String KEY_SERVER_IP = "server_ip";
    private static final String DEFAULT_SERVER_IP = "192.168.137.1";

    private TextView tvTitle;
    private Button btnReconnect;

    // 页面1：房间大厅与成员
    private LinearLayout layoutUserListContainer;
    private TextView tvOnlineCount;
    private Button btnCreateGameRoom;
    private ListView lvGameRooms;
    private ListView lvOnlineUsers;

    // 页面2：大厅交流面板
    private RecyclerView recyclerViewChat;
    private LinearLayoutManager chatLayoutManager;
    private LinearLayout layoutInputBar;
    private Button btnPickImage;
    private EditText etMessage;
    private Button btnSend;

    // 页面3：个人中心
    private LinearLayout layoutProfileContainer;
    private ImageView ivUserAvatar;
    private TextView tvProfileNickname;
    private TextView tvProfileStatus;
    private Button btnEditProfileName;
    private Button btnSettings;

    // 底部导航
    private Button btnTabUsers;
    private Button btnTabChat;
    private Button btnTabProfile;

    private List<ChatMessage> messageList;
    private Map<String, String> userAvatarMap;
    private ChatAdapter chatAdapter;

    private List<String> onlineUserList;
    private ArrayAdapter<String> userListAdapter;

    private List<String> gameRoomDisplayList;
    private List<String> gameRoomIdList;
    private ArrayAdapter<String> gameRoomAdapter;

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private SharedPreferences sharedPreferences;

    private String currentServerIp = DEFAULT_SERVER_IP;
    private String myNickname = "";
    private String myAvatarBase64 = "";
    private boolean isConnected = false;
    private boolean isDestroyed = false;

    // 标记当前是否正处于独立房间中
    private boolean isInIndependentRoom = false;

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected && !isDestroyed) {
                connectWebSocket();
            }
        }
    };

    private final ActivityResultLauncher<Intent> cropLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String croppedBase64 = result.getData().getStringExtra(CropAvatarActivity.RESULT_CROPPED_BASE64);
                    if (!TextUtils.isEmpty(croppedBase64)) {
                        applyAndSaveCroppedAvatar(croppedBase64);
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> avatarPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedImageUri = result.getData().getData();
                    if (selectedImageUri != null) {
                        Intent cropIntent = new Intent(MainActivity.this, CropAvatarActivity.class);
                        cropIntent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, selectedImageUri.toString());
                        cropLauncher.launch(cropIntent);
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> chatImagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedImageUri = result.getData().getData();
                    if (selectedImageUri != null) {
                        sendChatImage(selectedImageUri);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        myNickname = sharedPreferences.getString(KEY_NICKNAME, "");
        myAvatarBase64 = sharedPreferences.getString(KEY_AVATAR_BASE64, "");
        currentServerIp = sharedPreferences.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP);

        initViews();
        initAdapters();
        initListeners();
        updateProfileDisplay();
        switchPage(1);
        connectWebSocket();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isInIndependentRoom = false;
        String latestIp = sharedPreferences.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP);
        if (!latestIp.equals(currentServerIp)) {
            currentServerIp = latestIp;
            updateProfileDisplay();
            connectWebSocket();
        } else if (!isConnected) {
            connectWebSocket();
        }
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tvTitle);
        btnReconnect = findViewById(R.id.btnReconnect);

        layoutUserListContainer = findViewById(R.id.layoutUserListContainer);
        tvOnlineCount = findViewById(R.id.tvOnlineCount);
        btnCreateGameRoom = findViewById(R.id.btnCreateGameRoom);
        lvGameRooms = findViewById(R.id.lvGameRooms);
        lvOnlineUsers = findViewById(R.id.lvOnlineUsers);

        recyclerViewChat = findViewById(R.id.recyclerViewChat);
        layoutInputBar = findViewById(R.id.layoutInputBar);
        btnPickImage = findViewById(R.id.btnPickImage);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);

        layoutProfileContainer = findViewById(R.id.layoutProfileContainer);
        ivUserAvatar = findViewById(R.id.ivUserAvatar);
        tvProfileNickname = findViewById(R.id.tvProfileNickname);
        tvProfileStatus = findViewById(R.id.tvProfileStatus);
        btnEditProfileName = findViewById(R.id.btnEditProfileName);
        btnSettings = findViewById(R.id.btnSettings);

        btnTabUsers = findViewById(R.id.btnTabUsers);
        btnTabChat = findViewById(R.id.btnTabChat);
        btnTabProfile = findViewById(R.id.btnTabProfile);
    }

    private void initAdapters() {
        messageList = new ArrayList<>();
        userAvatarMap = new HashMap<>();

        if (!TextUtils.isEmpty(myNickname) && !TextUtils.isEmpty(myAvatarBase64)) {
            userAvatarMap.put(myNickname, myAvatarBase64);
        }

        chatAdapter = new ChatAdapter(messageList, userAvatarMap);
        chatLayoutManager = new LinearLayoutManager(this);
        recyclerViewChat.setLayoutManager(chatLayoutManager);
        recyclerViewChat.setAdapter(chatAdapter);

        onlineUserList = new ArrayList<>();
        userListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, onlineUserList);
        lvOnlineUsers.setAdapter(userListAdapter);

        gameRoomDisplayList = new ArrayList<>();
        gameRoomIdList = new ArrayList<>();
        gameRoomAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, gameRoomDisplayList);
        lvGameRooms.setAdapter(gameRoomAdapter);
    }

    private void initListeners() {
        btnReconnect.setOnClickListener(v -> {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            connectWebSocket();
        });

        btnSend.setOnClickListener(v -> {
            String content = etMessage.getText().toString().trim();
            if (TextUtils.isEmpty(content)) return;

            if (webSocket != null && isConnected) {
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "chat");
                    json.put("content", content);
                    json.put("avatar", myAvatarBase64);
                    webSocket.send(json.toString());
                    etMessage.setText("");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });

        btnPickImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            chatImagePickerLauncher.launch(intent);
        });

        // 创建新房间
        btnCreateGameRoom.setOnClickListener(v -> showCreateRoomDialog());

        // 加入选中房间
        lvGameRooms.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < gameRoomIdList.size()) {
                String targetId = gameRoomIdList.get(position);
                if (!TextUtils.isEmpty(targetId)) {
                    sendJoinRoomCommand(targetId);
                }
            }
        });

        btnEditProfileName.setOnClickListener(v -> showChangeNameDialog());

        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        ivUserAvatar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            avatarPickerLauncher.launch(intent);
        });

        btnTabUsers.setOnClickListener(v -> switchPage(0));
        btnTabChat.setOnClickListener(v -> switchPage(1));
        btnTabProfile.setOnClickListener(v -> switchPage(2));
    }

    private void switchPage(int pageIndex) {
        layoutUserListContainer.setVisibility(pageIndex == 0 ? View.VISIBLE : View.GONE);
        recyclerViewChat.setVisibility(pageIndex == 1 ? View.VISIBLE : View.GONE);
        layoutInputBar.setVisibility(pageIndex == 1 ? View.VISIBLE : View.GONE);
        layoutProfileContainer.setVisibility(pageIndex == 2 ? View.VISIBLE : View.GONE);

        int activeColor = Color.parseColor("#1E88E5");
        int inactiveColor = Color.parseColor("#E0E0E0");
        int activeText = Color.WHITE;
        int inactiveText = Color.parseColor("#333333");

        ViewCompat.setBackgroundTintList(btnTabUsers, ColorStateList.valueOf(pageIndex == 0 ? activeColor : inactiveColor));
        btnTabUsers.setTextColor(pageIndex == 0 ? activeText : inactiveText);

        ViewCompat.setBackgroundTintList(btnTabChat, ColorStateList.valueOf(pageIndex == 1 ? activeColor : inactiveColor));
        btnTabChat.setTextColor(pageIndex == 1 ? activeText : inactiveText);

        ViewCompat.setBackgroundTintList(btnTabProfile, ColorStateList.valueOf(pageIndex == 2 ? activeColor : inactiveColor));
        btnTabProfile.setTextColor(pageIndex == 2 ? activeText : inactiveText);
    }

    private void showCreateRoomDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入房间名称");
        input.setText(myNickname + "的猜拳对战");

        new AlertDialog.Builder(this)
                .setTitle("创建独立游戏房间")
                .setView(input)
                .setPositiveButton("创建并进入", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(title) && webSocket != null && isConnected) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "create_room");
                            json.put("title", title);
                            webSocket.send(json.toString());
                        } catch (JSONException ignored) {}
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void sendJoinRoomCommand(String roomId) {
        if (webSocket != null && isConnected) {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "join_room");
                json.put("room_id", roomId);
                webSocket.send(json.toString());
            } catch (JSONException ignored) {}
        }
    }

    private void applyAndSaveCroppedAvatar(String croppedBase64) {
        myAvatarBase64 = croppedBase64;
        sharedPreferences.edit().putString(KEY_AVATAR_BASE64, myAvatarBase64).apply();

        if (!TextUtils.isEmpty(myNickname)) {
            userAvatarMap.put(myNickname, myAvatarBase64);
            chatAdapter.notifyDataSetChanged();
        }

        try {
            byte[] decoded = Base64.decode(myAvatarBase64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
            if (bitmap != null) {
                ivUserAvatar.setImageBitmap(bitmap);
            }
        } catch (Exception ignored) {}

        if (webSocket != null && isConnected) {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "set_name");
                json.put("username", myNickname);
                json.put("avatar", myAvatarBase64);
                webSocket.send(json.toString());
            } catch (JSONException ignored) {}
        }
    }

    private void sendChatImage(Uri imageUri) {
        if (!isConnected || webSocket == null) return;

        try {
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            Bitmap originalBitmap = BitmapFactory.decodeStream(inputStream);
            if (originalBitmap != null) {
                int maxDim = 450;
                int width = originalBitmap.getWidth();
                int height = originalBitmap.getHeight();
                float ratio = Math.min((float) maxDim / width, (float) maxDim / height);
                if (ratio > 1.0f) ratio = 1.0f;

                int finalW = Math.max(1, Math.round(ratio * width));
                int finalH = Math.max(1, Math.round(ratio * height));

                Bitmap scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, finalW, finalH, true);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream);
                String imgBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP);

                JSONObject json = new JSONObject();
                json.put("type", "image");
                json.put("content", imgBase64);
                json.put("avatar", myAvatarBase64);

                webSocket.send(json.toString());
            }
        } catch (Exception ignored) {}
    }

    private void updateProfileDisplay() {
        String displayName = TextUtils.isEmpty(myNickname) ? "未设置昵称" : myNickname;
        tvProfileNickname.setText("昵称：" + displayName);
        String connectionInfo = isConnected ? "已连接到服务器" : "重试连接中";
        tvProfileStatus.setText("服务器节点：" + currentServerIp + ":8000\n当前模式：公共讨论区\n连接状态：" + connectionInfo);

        if (!TextUtils.isEmpty(myAvatarBase64)) {
            try {
                byte[] decodedBytes = Base64.decode(myAvatarBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap != null) {
                    ivUserAvatar.setImageBitmap(bitmap);
                }
            } catch (Exception ignored) {}
        }
    }

    private void showChangeNameDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入新昵称");
        if (!TextUtils.isEmpty(myNickname)) {
            input.setText(myNickname);
        }

        new AlertDialog.Builder(this)
                .setTitle("设置论坛昵称")
                .setView(input)
                .setPositiveButton("确认修改", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(newName)) {
                        myNickname = newName;
                        sharedPreferences.edit().putString(KEY_NICKNAME, myNickname).apply();
                        if (!TextUtils.isEmpty(myAvatarBase64)) {
                            userAvatarMap.put(myNickname, myAvatarBase64);
                        }
                        updateProfileDisplay();

                        if (webSocket != null && isConnected) {
                            try {
                                JSONObject json = new JSONObject();
                                json.put("type", "set_name");
                                json.put("username", myNickname);
                                json.put("avatar", myAvatarBase64);
                                webSocket.send(json.toString());
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private synchronized void connectWebSocket() {
        if (webSocket != null) {
            webSocket.close(1000, "重新连接");
            webSocket = null;
        }

        runOnUiThread(() -> {
            tvTitle.setText("校园论坛 [公共大厅] (连接中...)");
            updateProfileDisplay();
        });

        if (httpClient == null) {
            httpClient = new OkHttpClient();
        }

        String fullUrl = "ws://" + currentServerIp + ":8000/ws/chat?room=lobby";
        if (!TextUtils.isEmpty(myNickname)) {
            try {
                fullUrl += "&name=" + URLEncoder.encode(myNickname, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                fullUrl += "&name=" + myNickname;
            }
        }

        Request request = new Request.Builder().url(fullUrl).build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                isConnected = true;
                reconnectHandler.removeCallbacks(reconnectRunnable);
                GameSocketBridge.setWebSocket(ws);

                runOnUiThread(() -> {
                    tvTitle.setText("校园论坛 [公共大厅] (在线)");
                    updateProfileDisplay();

                    if (!TextUtils.isEmpty(myAvatarBase64)) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "set_name");
                            json.put("username", myNickname);
                            json.put("avatar", myAvatarBase64);
                            ws.send(json.toString());
                        } catch (JSONException ignored) {}
                    }
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                runOnUiThread(() -> {
                    try {
                        JSONObject json = new JSONObject(text);
                        String type = json.optString("type", "chat");

                        // 关键拦截：如果此时用户已经身在独立房间里，所有消息直接路由给 GameRoomActivity
                        if (isInIndependentRoom) {
                            GameSocketBridge.dispatchMessage(json);
                            return;
                        }

                        // 服务端同意进入独立房间：启动 GameRoomActivity
                        if ("enter_room_success".equals(type)) {
                            isInIndependentRoom = true;
                            String rId = json.optString("room_id");
                            String rTitle = json.optString("title");

                            Intent intent = new Intent(MainActivity.this, GameRoomActivity.class);
                            intent.putExtra(GameRoomActivity.EXTRA_ROOM_ID, rId);
                            intent.putExtra(GameRoomActivity.EXTRA_ROOM_TITLE, rTitle);
                            startActivity(intent);
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
                            messageList.clear();
                            if (historyArray != null) {
                                for (int i = 0; i < historyArray.length(); i++) {
                                    JSONObject msgObj = historyArray.optJSONObject(i);
                                    if (msgObj != null) {
                                        messageList.add(parseChatMessage(msgObj));
                                    }
                                }
                            }
                            chatAdapter.notifyDataSetChanged();
                            if (!messageList.isEmpty()) {
                                recyclerViewChat.scrollToPosition(messageList.size() - 1);
                            }

                        } else if ("room_state".equals(type)) {
                            JSONArray membersArray = json.optJSONArray("members");
                            onlineUserList.clear();
                            if (membersArray != null) {
                                for (int i = 0; i < membersArray.length(); i++) {
                                    onlineUserList.add(membersArray.optString(i));
                                }
                            }
                            userListAdapter.notifyDataSetChanged();
                            tvOnlineCount.setText("公共大厅在线 (" + onlineUserList.size() + "人)");

                        } else if ("room_list".equals(type)) {
                            JSONArray roomsArr = json.optJSONArray("rooms");
                            gameRoomDisplayList.clear();
                            gameRoomIdList.clear();
                            if (roomsArr != null) {
                                for (int i = 0; i < roomsArr.length(); i++) {
                                    JSONObject r = roomsArr.optJSONObject(i);
                                    if (r != null) {
                                        String rId = r.optString("room_id");
                                        String rTitle = r.optString("title");
                                        int curP = r.optInt("current_players", 0);
                                        int maxP = r.optInt("max_players", 2);

                                        gameRoomDisplayList.add("[活动房间] " + rTitle + " (" + curP + "/" + maxP + "人)");
                                        gameRoomIdList.add(rId);
                                    }
                                }
                            }
                            if (gameRoomDisplayList.isEmpty()) {
                                gameRoomDisplayList.add("暂无进行中的活动房间，点击上方可创建");
                                gameRoomIdList.add("");
                            }
                            gameRoomAdapter.notifyDataSetChanged();

                        } else if ("avatar_update".equals(type)) {
                            String username = json.optString("username");
                            String avatar = json.optString("avatar");
                            if (!TextUtils.isEmpty(username) && !TextUtils.isEmpty(avatar)) {
                                userAvatarMap.put(username, avatar);
                                chatAdapter.notifyDataSetChanged();
                            }

                        } else {
                            int lastVisibleItem = chatLayoutManager.findLastVisibleItemPosition();
                            boolean isAtBottom = (lastVisibleItem >= messageList.size() - 2);

                            ChatMessage newMsg = parseChatMessage(json);
                            if (!TextUtils.isEmpty(newMsg.getSender()) && !TextUtils.isEmpty(newMsg.getAvatar())) {
                                userAvatarMap.put(newMsg.getSender(), newMsg.getAvatar());
                            }
                            messageList.add(newMsg);
                            chatAdapter.notifyItemInserted(messageList.size() - 1);

                            if (newMsg.isMe() || isAtBottom) {
                                recyclerViewChat.smoothScrollToPosition(messageList.size() - 1);
                            }
                        }

                    } catch (JSONException ignored) {}
                });
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        isConnected = false;
        runOnUiThread(() -> {
            tvTitle.setText("校园论坛 [公共大厅] (重连中...)");
            updateProfileDisplay();
        });

        if (!isDestroyed) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectHandler.postDelayed(reconnectRunnable, 3000);
        }
    }

    private ChatMessage parseChatMessage(JSONObject json) {
        String type = json.optString("type", "chat");
        String sender = json.optString("sender", "某同学");
        String content = json.optString("content", "");
        String avatar = json.optString("avatar", "");
        long timestamp = json.optLong("timestamp", System.currentTimeMillis());

        if (!TextUtils.isEmpty(sender) && !TextUtils.isEmpty(avatar)) {
            userAvatarMap.put(sender, avatar);
        }

        boolean isMe = false;
        if (!"system".equals(type) && !TextUtils.isEmpty(myNickname)) {
            isMe = myNickname.equals(sender);
        }

        return new ChatMessage(type, sender, content, avatar, isMe, timestamp);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        isConnected = false;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        GameSocketBridge.clearListener();
        if (webSocket != null) {
            webSocket.close(1000, "退出应用");
        }
    }
}