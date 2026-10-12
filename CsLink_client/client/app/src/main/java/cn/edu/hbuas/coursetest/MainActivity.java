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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
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
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String DEFAULT_SERVER_IP = "192.168.137.1";
    private static final String DEFAULT_ROLE = "student";

    private TextView tvTitle;
    private Button btnReconnect;

    // 页面0：花名册与小组房间
    private LinearLayout layoutUserListContainer;
    private TextView tvOnlineCount;
    private Button btnCreateGameRoom;
    private ListView lvGameRooms;
    private ListView lvOnlineUsers;

    // 页面1：随堂讨论交流区
    private RecyclerView recyclerViewChat;
    private LinearLayoutManager chatLayoutManager;
    private LinearLayout layoutInputBar;
    private Button btnPickImage;
    private EditText etMessage;
    private Button btnSend;

    // 页面2：班级事务与作业
    private View pageAffairsLayout;
    private TextView tvAffairsSummary;
    private Button btnPublishAffair;
    private RecyclerView rvAffairs;
    private TextView tvEmptyAffairs;
    private List<AffairItem> affairList;
    private AffairsAdapter affairsAdapter;

    // 页面3：个人中心
    private LinearLayout layoutProfileContainer;
    private ImageView ivUserAvatar;
    private TextView tvProfileNickname;
    private TextView tvProfileStatus;
    private Button btnEditProfileName;
    private Button btnSettings;

    // 底部四大导航按钮
    private Button btnTabUsers;
    private Button btnTabChat;
    private Button btnTabAffairs;
    private Button btnTabProfile;

    // 数据缓存
    private List<ChatMessage> messageList;
    private Map<String, String> userAvatarMap;
    private ChatAdapter chatAdapter;

    private List<RosterUser> rosterUserList;
    private List<String> onlineUserDisplayList;
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
    private String myRole = DEFAULT_ROLE;
    private String myAuthToken = "";
    private boolean isConnected = false;
    private boolean isDestroyed = false;
    private boolean isGlobalMuted = false;
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
        myRole = sharedPreferences.getString(KEY_USER_ROLE, DEFAULT_ROLE);
        myAuthToken = sharedPreferences.getString(KEY_AUTH_TOKEN, "");

        initViews();
        initAdapters();
        initListeners();
        updateProfileDisplay();
        switchPage(1); // 默认进入随堂讨论
        connectWebSocket();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isInIndependentRoom = false;
        String latestIp = sharedPreferences.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP);
        String latestRole = sharedPreferences.getString(KEY_USER_ROLE, DEFAULT_ROLE);
        String latestToken = sharedPreferences.getString(KEY_AUTH_TOKEN, "");

        boolean needReconnect = false;
        if (!latestIp.equals(currentServerIp)) {
            currentServerIp = latestIp;
            needReconnect = true;
        }
        if (!latestRole.equals(myRole)) {
            myRole = latestRole;
            needReconnect = true;
        }
        if (!latestToken.equals(myAuthToken)) {
            myAuthToken = latestToken;
            needReconnect = true;
        }

        updateProfileDisplay();
        if (needReconnect || !isConnected) {
            connectWebSocket();
        }
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tvTitle);
        btnReconnect = findViewById(R.id.btnReconnect);

        // 页面0
        layoutUserListContainer = findViewById(R.id.layoutUserListContainer);
        tvOnlineCount = findViewById(R.id.tvOnlineCount);
        btnCreateGameRoom = findViewById(R.id.btnCreateGameRoom);
        lvGameRooms = findViewById(R.id.lvGameRooms);
        lvOnlineUsers = findViewById(R.id.lvOnlineUsers);

        // 页面1
        recyclerViewChat = findViewById(R.id.recyclerViewChat);
        layoutInputBar = findViewById(R.id.layoutInputBar);
        btnPickImage = findViewById(R.id.btnPickImage);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);

        // 页面2：班级事务
        pageAffairsLayout = findViewById(R.id.pageAffairsLayout);
        tvAffairsSummary = findViewById(R.id.tvAffairsSummary);
        btnPublishAffair = findViewById(R.id.btnPublishAffair);
        rvAffairs = findViewById(R.id.rvAffairs);
        tvEmptyAffairs = findViewById(R.id.tvEmptyAffairs);

        // 页面3：个人中心
        layoutProfileContainer = findViewById(R.id.layoutProfileContainer);
        ivUserAvatar = findViewById(R.id.ivUserAvatar);
        tvProfileNickname = findViewById(R.id.tvProfileNickname);
        tvProfileStatus = findViewById(R.id.tvProfileStatus);
        btnEditProfileName = findViewById(R.id.btnEditProfileName);
        btnSettings = findViewById(R.id.btnSettings);

        // 底部四个 Tab 键
        btnTabUsers = findViewById(R.id.btnTabUsers);
        btnTabChat = findViewById(R.id.btnTabChat);
        btnTabAffairs = findViewById(R.id.btnTabAffairs);
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

        // 花名册列表
        rosterUserList = new ArrayList<>();
        onlineUserDisplayList = new ArrayList<>();
        userListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, onlineUserDisplayList);
        lvOnlineUsers.setAdapter(userListAdapter);

        // 互动房间列表
        gameRoomDisplayList = new ArrayList<>();
        gameRoomIdList = new ArrayList<>();
        gameRoomAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, gameRoomDisplayList);
        lvGameRooms.setAdapter(gameRoomAdapter);

        // 班级事务列表与适配器
        affairList = new ArrayList<>();
        affairsAdapter = new AffairsAdapter(affairList, myNickname, myRole, new AffairsAdapter.OnAffairActionListener() {
            @Override
            public void onConfirm(String affairId) {
                sendConfirmAffair(affairId);
            }

            @Override
            public void onViewConfirmedList(AffairItem item) {
                showConfirmedStudentsDialog(item);
            }
        });
        rvAffairs.setLayoutManager(new LinearLayoutManager(this));
        rvAffairs.setAdapter(affairsAdapter);
    }

    private void initListeners() {
        btnReconnect.setOnClickListener(v -> {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            connectWebSocket();
        });

        // 教师长按标题栏可切换全员禁言
        tvTitle.setOnLongClickListener(v -> {
            if ("teacher".equalsIgnoreCase(myRole) && webSocket != null && isConnected) {
                boolean targetMute = !isGlobalMuted;
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "set_global_mute");
                    json.put("is_muted", targetMute);
                    webSocket.send(json.toString());
                    Toast.makeText(this, targetMute ? "已开启全员禁言" : "已解除全员禁言", Toast.LENGTH_SHORT).show();
                } catch (JSONException ignored) {}
                return true;
            }
            return false;
        });

        btnSend.setOnClickListener(v -> {
            String content = etMessage.getText().toString().trim();
            if (TextUtils.isEmpty(content)) return;

            if (webSocket != null && isConnected) {
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "chat");
                    json.put("content", content);
                    json.put("role", myRole);
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

        btnCreateGameRoom.setOnClickListener(v -> showCreateRoomDialog());

        lvGameRooms.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < gameRoomIdList.size()) {
                String targetId = gameRoomIdList.get(position);
                if (!TextUtils.isEmpty(targetId)) {
                    sendJoinRoomCommand(targetId);
                }
            }
        });

        // 教师点击花名册学生弹出管理面板
        lvOnlineUsers.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < rosterUserList.size()) {
                RosterUser targetUser = rosterUserList.get(position);
                if ("teacher".equalsIgnoreCase(myRole)) {
                    showTeacherManageStudentDialog(targetUser);
                } else {
                    Toast.makeText(this, targetUser.name + " · " + (targetUser.isOnline ? "在线" : "离线"), Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 教师发布新事务
        btnPublishAffair.setOnClickListener(v -> showPublishAffairDialog());

        btnEditProfileName.setOnClickListener(v -> showChangeNameDialog());

        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        ivUserAvatar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            avatarPickerLauncher.launch(intent);
        });

        // 导航切换
        btnTabUsers.setOnClickListener(v -> switchPage(0));
        btnTabChat.setOnClickListener(v -> switchPage(1));
        btnTabAffairs.setOnClickListener(v -> switchPage(2));
        btnTabProfile.setOnClickListener(v -> switchPage(3));
    }

    private void switchPage(int pageIndex) {
        layoutUserListContainer.setVisibility(pageIndex == 0 ? View.VISIBLE : View.GONE);
        recyclerViewChat.setVisibility(pageIndex == 1 ? View.VISIBLE : View.GONE);
        layoutInputBar.setVisibility(pageIndex == 1 ? View.VISIBLE : View.GONE);
        if (pageAffairsLayout != null) {
            pageAffairsLayout.setVisibility(pageIndex == 2 ? View.VISIBLE : View.GONE);
        }
        layoutProfileContainer.setVisibility(pageIndex == 3 ? View.VISIBLE : View.GONE);

        int activeColor = Color.parseColor("#1E88E5");
        int inactiveColor = Color.parseColor("#E0E0E0");
        int activeText = Color.WHITE;
        int inactiveText = Color.parseColor("#333333");

        updateTabButton(btnTabUsers, pageIndex == 0, activeColor, inactiveColor, activeText, inactiveText);
        updateTabButton(btnTabChat, pageIndex == 1, activeColor, inactiveColor, activeText, inactiveText);
        updateTabButton(btnTabAffairs, pageIndex == 2, activeColor, inactiveColor, activeText, inactiveText);
        updateTabButton(btnTabProfile, pageIndex == 3, activeColor, inactiveColor, activeText, inactiveText);
    }

    private void updateTabButton(Button btn, boolean isSelected, int actBg, int inactBg, int actText, int inactText) {
        ViewCompat.setBackgroundTintList(btn, ColorStateList.valueOf(isSelected ? actBg : inactBg));
        btn.setTextColor(isSelected ? actText : inactText);
    }

    private void showPublishAffairDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(60, 30, 60, 20);

        final RadioGroup rgType = new RadioGroup(this);
        rgType.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton rbNotice = new RadioButton(this);
        rbNotice.setText("班级通知");
        rbNotice.setChecked(true);
        final RadioButton rbHomework = new RadioButton(this);
        rbHomework.setText("随堂作业");
        rgType.addView(rbNotice);
        rgType.addView(rbHomework);
        dialogLayout.addView(rgType);

        final EditText etTitleInput = new EditText(this);
        etTitleInput.setHint("输入事务标题 (例如: 今日实训作业)");
        dialogLayout.addView(etTitleInput);

        final EditText etContentInput = new EditText(this);
        etContentInput.setHint("输入具体内容与要求");
        etContentInput.setMinLines(3);
        dialogLayout.addView(etContentInput);

        new AlertDialog.Builder(this)
                .setTitle("发布班级事务")
                .setView(dialogLayout)
                .setPositiveButton("立即发布", (dialog, which) -> {
                    String title = etTitleInput.getText().toString().trim();
                    String content = etContentInput.getText().toString().trim();
                    String aType = rbHomework.isChecked() ? "homework" : "notice";

                    if (TextUtils.isEmpty(title) || TextUtils.isEmpty(content)) {
                        Toast.makeText(this, "标题和内容不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (webSocket != null && isConnected) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "publish_affair");
                            json.put("title", title);
                            json.put("content", content);
                            json.put("affair_type", aType);
                            webSocket.send(json.toString());
                        } catch (JSONException ignored) {}
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void sendConfirmAffair(String affairId) {
        if (webSocket != null && isConnected) {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "confirm_affair");
                json.put("affair_id", affairId);
                webSocket.send(json.toString());
            } catch (JSONException ignored) {}
        }
    }

    private void showConfirmedStudentsDialog(AffairItem item) {
        List<String> confirmed = item.getConfirmedStudents();
        StringBuilder sb = new StringBuilder();
        if (confirmed == null || confirmed.isEmpty()) {
            sb.append("暂无学生完成确认。");
        } else {
            for (int i = 0; i < confirmed.size(); i++) {
                sb.append(i + 1).append(". ").append(confirmed.get(i)).append("\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("已确认名单 (" + (confirmed != null ? confirmed.size() : 0) + "人)")
                .setMessage(sb.toString().trim())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showTeacherManageStudentDialog(RosterUser target) {
        if (target.name.equals(myNickname)) {
            Toast.makeText(this, "不能对自己进行管理操作", Toast.LENGTH_SHORT).show();
            return;
        }

        String muteActionText = target.isMuted ? "解除禁言" : "单独禁言";
        String[] options = {muteActionText, "变更角色身份 (学生/班长)", "修改姓名或学号"};

        new AlertDialog.Builder(this)
                .setTitle("管理学生：" + target.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        sendAdminManageCommand(target.name, "toggle_mute", null, null);
                    } else if (which == 1) {
                        showChangeRoleSubDialog(target);
                    } else if (which == 2) {
                        showRenameStudentSubDialog(target);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showChangeRoleSubDialog(RosterUser target) {
        String[] roles = {"student", "monitor"};
        String[] roleLabels = {"设为普通学生", "任命为班长"};
        new AlertDialog.Builder(this)
                .setTitle("调整【" + target.name + "】的身份")
                .setItems(roleLabels, (dialog, which) -> {
                    sendAdminManageCommand(target.name, "set_role", roles[which], null);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameStudentSubDialog(RosterUser target) {
        final EditText etNewName = new EditText(this);
        etNewName.setHint("输入新姓名或学号");
        etNewName.setText(target.name);

        new AlertDialog.Builder(this)
                .setTitle("修改【" + target.name + "】的姓名")
                .setView(etNewName)
                .setPositiveButton("确认", (dialog, which) -> {
                    String newName = etNewName.getText().toString().trim();
                    if (!TextUtils.isEmpty(newName)) {
                        sendAdminManageCommand(target.name, "rename", null, newName);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void sendAdminManageCommand(String targetName, String operation, String newRole, String newName) {
        if (webSocket != null && isConnected) {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "admin_manage_student");
                json.put("target_name", targetName);
                json.put("operation", operation);
                if (newRole != null) json.put("new_role", newRole);
                if (newName != null) json.put("new_name", newName);
                webSocket.send(json.toString());
            } catch (JSONException ignored) {}
        }
    }

    private void showCreateRoomDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入小组互动室名称");
        input.setText(myNickname + "的互动组");

        new AlertDialog.Builder(this)
                .setTitle("创建独立互动房间")
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
                json.put("role", myRole);
                json.put("token", myAuthToken);
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
                json.put("role", myRole);
                json.put("avatar", myAvatarBase64);

                webSocket.send(json.toString());
            }
        } catch (Exception ignored) {}
    }

    private void updateProfileDisplay() {
        String displayName = TextUtils.isEmpty(myNickname) ? "未设置姓名" : myNickname;
        tvProfileNickname.setText("姓名：" + displayName);
        String connectionInfo = isConnected ? "已连接到教学中枢" : "正在连接...";
        String roleText = "teacher".equalsIgnoreCase(myRole) ? "教师端 (管理权限)" : "学生端 (普通学员)";
        tvProfileStatus.setText("教师中枢节点：" + currentServerIp + ":8000\n当前权限：" + roleText + "\n网络状态：" + connectionInfo);

        if (btnPublishAffair != null) {
            btnPublishAffair.setVisibility("teacher".equalsIgnoreCase(myRole) ? View.VISIBLE : View.GONE);
        }

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

    private void updateMuteUI() {
        if (isGlobalMuted) {
            if ("teacher".equalsIgnoreCase(myRole)) {
                etMessage.setHint("全员禁言中 (教师发言不受限)");
                etMessage.setEnabled(true);
                btnSend.setEnabled(true);
            } else {
                etMessage.setHint("全员禁言中，禁止发言");
                etMessage.setEnabled(false);
                btnSend.setEnabled(false);
            }
        } else {
            etMessage.setHint("输入随堂讨论内容...");
            etMessage.setEnabled(true);
            btnSend.setEnabled(true);
        }
    }

    private void showChangeNameDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入真实姓名或学号");
        if (!TextUtils.isEmpty(myNickname)) {
            input.setText(myNickname);
        }

        new AlertDialog.Builder(this)
                .setTitle("设置姓名与学号")
                .setView(input)
                .setPositiveButton("确认保存", (dialog, which) -> {
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
                                json.put("role", myRole);
                                json.put("token", myAuthToken);
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
            tvTitle.setText("数字化课堂 [讨论频道] (连接中...)");
            updateProfileDisplay();
        });

        if (httpClient == null) {
            httpClient = new OkHttpClient();
        }

        String fullUrl = "ws://" + currentServerIp + ":8000/ws/chat?room=lobby";
        try {
            if (!TextUtils.isEmpty(myNickname)) {
                fullUrl += "&name=" + URLEncoder.encode(myNickname, "UTF-8");
            }
            fullUrl += "&role=" + URLEncoder.encode(myRole, "UTF-8");
            if (!TextUtils.isEmpty(myAuthToken)) {
                fullUrl += "&token=" + URLEncoder.encode(myAuthToken, "UTF-8");
            }
        } catch (UnsupportedEncodingException e) {
            fullUrl += "&name=" + myNickname + "&role=" + myRole + "&token=" + myAuthToken;
        }

        Request request = new Request.Builder().url(fullUrl).build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                isConnected = true;
                reconnectHandler.removeCallbacks(reconnectRunnable);
                GameSocketBridge.setWebSocket(ws);

                runOnUiThread(() -> {
                    tvTitle.setText("数字化课堂 [讨论频道] (在线)");
                    updateProfileDisplay();

                    if (!TextUtils.isEmpty(myAvatarBase64)) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "set_name");
                            json.put("username", myNickname);
                            json.put("role", myRole);
                            json.put("token", myAuthToken);
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

                        if (isInIndependentRoom) {
                            GameSocketBridge.dispatchMessage(json);
                            return;
                        }

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

                        // 1. 服务端下发的全量课堂初始包
                        if ("init_classroom_state".equals(type)) {
                            String verifiedRole = json.optString("my_verified_role", "");
                            if (!TextUtils.isEmpty(verifiedRole)) {
                                myRole = verifiedRole;
                                sharedPreferences.edit().putString(KEY_USER_ROLE, myRole).apply();
                                updateProfileDisplay();
                            }

                            isGlobalMuted = json.optBoolean("is_global_muted", false);
                            updateMuteUI();

                            // 历史消息
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

                            // 花名册
                            JSONArray rosterArr = json.optJSONArray("roster");
                            parseAndRefreshRoster(rosterArr);

                            // 事务列表
                            JSONArray affairsArr = json.optJSONArray("affairs");
                            parseAndRefreshAffairs(affairsArr);

                        } else if ("roster_update".equals(type)) {
                            JSONArray rosterArr = json.optJSONArray("roster");
                            parseAndRefreshRoster(rosterArr);

                        } else if ("affair_update".equals(type)) {
                            JSONArray affairsArr = json.optJSONArray("affairs");
                            parseAndRefreshAffairs(affairsArr);

                        } else if ("global_mute_state".equals(type)) {
                            isGlobalMuted = json.optBoolean("is_muted", false);
                            updateMuteUI();

                        } else if ("role_verified".equals(type)) {
                            myRole = json.optString("role", DEFAULT_ROLE);
                            sharedPreferences.edit().putString(KEY_USER_ROLE, myRole).apply();
                            updateProfileDisplay();

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

                                        gameRoomDisplayList.add("[互动小组] " + rTitle + " (" + curP + "/" + maxP + "人)");
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

    private void parseAndRefreshRoster(JSONArray rosterArr) {
        if (rosterArr == null) return;
        rosterUserList.clear();
        onlineUserDisplayList.clear();
        int onlineCount = 0;

        for (int i = 0; i < rosterArr.length(); i++) {
            JSONObject u = rosterArr.optJSONObject(i);
            if (u != null) {
                String uName = u.optString("name");
                String uRole = u.optString("role", "student");
                boolean uOnline = u.optBoolean("is_online", false);
                boolean uMuted = u.optBoolean("is_muted", false);
                if (uOnline) onlineCount++;

                rosterUserList.add(new RosterUser(uName, uRole, uOnline, uMuted));

                String statusStr = uOnline ? "[在线]" : "[离线]";
                String roleStr = "teacher".equals(uRole) ? "教师" : ("monitor".equals(uRole) ? "班长" : "学生");
                String muteStr = uMuted ? " [已禁言]" : "";
                onlineUserDisplayList.add(statusStr + " " + uName + " (" + roleStr + ")" + muteStr);
            }
        }
        userListAdapter.notifyDataSetChanged();
        tvOnlineCount.setText("班级总名册 (" + onlineCount + " 人在线 / " + rosterUserList.size() + " 人登记)");
    }

    private void parseAndRefreshAffairs(JSONArray affairsArr) {
        if (affairsArr == null) return;
        affairList.clear();

        for (int i = 0; i < affairsArr.length(); i++) {
            JSONObject a = affairsArr.optJSONObject(i);
            if (a != null) {
                String aId = a.optString("affair_id");
                String aTitle = a.optString("title");
                String aContent = a.optString("content");
                String aType = a.optString("type", "notice");
                String aPublisher = a.optString("publisher", "教师");
                long aTime = a.optLong("timestamp", System.currentTimeMillis());
                int aCount = a.optInt("confirmed_count", 0);

                List<String> confirmedList = new ArrayList<>();
                JSONArray cArr = a.optJSONArray("confirmed_students");
                if (cArr != null) {
                    for (int j = 0; j < cArr.length(); j++) {
                        confirmedList.add(cArr.optString(j));
                    }
                }

                affairList.add(new AffairItem(aId, aTitle, aContent, aType, aPublisher, aTime, aCount, confirmedList));
            }
        }

        if (affairsAdapter != null) {
            affairsAdapter = new AffairsAdapter(affairList, myNickname, myRole, new AffairsAdapter.OnAffairActionListener() {
                @Override
                public void onConfirm(String affairId) {
                    sendConfirmAffair(affairId);
                }

                @Override
                public void onViewConfirmedList(AffairItem item) {
                    showConfirmedStudentsDialog(item);
                }
            });
            rvAffairs.setAdapter(affairsAdapter);
            affairsAdapter.notifyDataSetChanged();
        }

        if (tvEmptyAffairs != null) {
            tvEmptyAffairs.setVisibility(affairList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void scheduleReconnect() {
        isConnected = false;
        runOnUiThread(() -> {
            tvTitle.setText("数字化课堂 [讨论频道] (正在重连...)");
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
        String role = json.optString("role", "student");
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

        return new ChatMessage(type, sender, role, content, avatar, isMe, timestamp);
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

    // 内部类：花名册数据结构
    static class RosterUser {
        String name;
        String role;
        boolean isOnline;
        boolean isMuted;

        RosterUser(String name, String role, boolean isOnline, boolean isMuted) {
            this.name = name;
            this.role = role;
            this.isOnline = isOnline;
            this.isMuted = isMuted;
        }
    }
}