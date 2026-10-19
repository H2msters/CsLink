package cn.edu.hbuas.coursetest;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private Button btnHeaderRoster;
    private String currentClassroomTitle = "数字化课堂 [讨论频道]";

    // 页面0：功能区（小组大厅 + 班级图书角）
    private LinearLayout layoutUserListContainer;
    private TextView tvOnlineCount;
    private Button btnCreateGameRoom;
    private ListView lvGameRooms;
    private View cardBookCorner;

    // 页面1：随堂讨论与抽屉
    private View pageChatLayout;
    private RecyclerView recyclerViewChat;
    private LinearLayoutManager chatLayoutManager;
    private LinearLayout layoutInputBar;
    private EditText etMessage;
    private Button btnSend;
    private Button btnToggleMorePanel;
    private LinearLayout layoutMorePanel;
    private Button btnPickImage;
    private LinearLayout itemPanelStartBuzz;
    private Button btnPanelBuzzAction;
    private LinearLayout itemPanelRandomPick;
    private Button btnPanelRandomPick;
    private LinearLayout itemPanelStartVote;
    private Button btnPanelStartVote;

    // 页面2：班级事务
    private View pageAffairsLayout;
    private TextView tvAffairsSummary;
    private Button btnPublishAffair;
    private Button btnFilterNotice;
    private Button btnFilterHomework;
    private RecyclerView rvAffairs;
    private TextView tvEmptyAffairs;
    private List<AffairItem> allAffairList;
    private List<AffairItem> displayAffairList;
    private AffairsAdapter affairsAdapter;
    private String currentAffairFilter = "notice";

    // 页面3：个人中心
    private LinearLayout layoutProfileContainer;
    private ImageView ivUserAvatar;
    private TextView tvProfileNickname;
    private TextView tvProfileStatus;
    private Button btnProfileReconnect;
    private Button btnEditProfileName;
    private Button btnSettings;

    // 底部四大导航
    private Button btnTabUsers;
    private Button btnTabChat;
    private Button btnTabAffairs;
    private Button btnTabProfile;

    // 数据缓存
    private List<ChatMessage> messageList;
    private Map<String, String> userAvatarMap;
    private ChatAdapter chatAdapter;

    private List<RosterUser> rosterUserList;

    private List<String> gameRoomDisplayList;
    private List<String> gameRoomIdList;
    private ArrayAdapter<String> gameRoomAdapter;

    // 弹窗管理器引用与最新活动状态
    private InteractiveDialogManager dialogManager;
    private JSONObject latestVoteData = null;
    private boolean latestBuzzActive = false;
    private String latestBuzzTitle = "随堂即时抢答";
    private String latestBuzzWinner = "";
    private int latestBuzzCostMs = 0;

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private SharedPreferences sharedPreferences;

    private String currentServerIp = DEFAULT_SERVER_IP;
    private String myNickname = "";
    private String myAvatarBase64 = "";
    private String myRole = DEFAULT_ROLE;
    private String myAuthToken = "";
    private boolean isConnected = false;
    private boolean isConnecting = false;
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

        initDialogManager();
        initViews();
        initAdapters();
        initListeners();
        updateProfileDisplay();
        switchPage(1);
        connectWebSocket();
    }

    private void initDialogManager() {
        dialogManager = new InteractiveDialogManager(this, new InteractiveDialogManager.OnDialogActionListener() {
            @Override
            public void onSubmitVote(String option) {
                if (webSocket != null && isConnected) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("type", "submit_vote");
                        json.put("option", option);
                        webSocket.send(json.toString());
                    } catch (JSONException ignored) {}
                }
            }

            @Override
            public void onStopVote() {
                if ("teacher".equalsIgnoreCase(myRole) && webSocket != null && isConnected) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("type", "stop_vote");
                        webSocket.send(json.toString());
                    } catch (JSONException ignored) {}
                }
            }

            @Override
            public void onSubmitBuzz() {
                if (webSocket != null && isConnected) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("type", "submit_buzz");
                        webSocket.send(json.toString());
                    } catch (JSONException ignored) {}
                }
            }

            @Override
            public void onStopBuzz() {
                if ("teacher".equalsIgnoreCase(myRole) && webSocket != null && isConnected) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("type", "stop_buzz");
                        webSocket.send(json.toString());
                    } catch (JSONException ignored) {}
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        isInIndependentRoom = false;

        if (webSocket != null && isConnected) {
            try {
                JSONObject req = new JSONObject();
                req.put("type", "refresh_lobby");
                webSocket.send(req.toString());
            } catch (JSONException ignored) {}
        }

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

        if (needReconnect) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            isConnected = false;
            isConnecting = false;
            if (webSocket != null) {
                WebSocket oldSocket = webSocket;
                webSocket = null;
                try {
                    oldSocket.cancel();
                } catch (Exception ignored) {}
            }
            connectWebSocket();
        } else if (!isConnected && !isConnecting) {
            connectWebSocket();
        }
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tvTitle);
        btnHeaderRoster = findViewById(R.id.btnHeaderRoster);

        layoutUserListContainer = findViewById(R.id.layoutUserListContainer);
        tvOnlineCount = findViewById(R.id.tvOnlineCount);
        btnCreateGameRoom = findViewById(R.id.btnCreateGameRoom);
        lvGameRooms = findViewById(R.id.lvGameRooms);
        cardBookCorner = findViewById(R.id.cardBookCorner);

        pageChatLayout = findViewById(R.id.pageChatLayout);
        recyclerViewChat = findViewById(R.id.recyclerViewChat);
        layoutInputBar = findViewById(R.id.layoutInputBar);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        btnToggleMorePanel = findViewById(R.id.btnToggleMorePanel);
        layoutMorePanel = findViewById(R.id.layoutMorePanel);
        btnPickImage = findViewById(R.id.btnPickImage);
        itemPanelStartBuzz = findViewById(R.id.itemPanelStartBuzz);
        btnPanelBuzzAction = findViewById(R.id.btnPanelBuzzAction);
        itemPanelRandomPick = findViewById(R.id.itemPanelRandomPick);
        btnPanelRandomPick = findViewById(R.id.btnPanelRandomPick);
        itemPanelStartVote = findViewById(R.id.itemPanelStartVote);
        btnPanelStartVote = findViewById(R.id.btnPanelStartVote);

        pageAffairsLayout = findViewById(R.id.pageAffairsLayout);
        tvAffairsSummary = findViewById(R.id.tvAffairsSummary);
        btnPublishAffair = findViewById(R.id.btnPublishAffair);
        btnFilterNotice = findViewById(R.id.btnFilterNotice);
        btnFilterHomework = findViewById(R.id.btnFilterHomework);
        rvAffairs = findViewById(R.id.rvAffairs);
        tvEmptyAffairs = findViewById(R.id.tvEmptyAffairs);

        layoutProfileContainer = findViewById(R.id.layoutProfileContainer);
        ivUserAvatar = findViewById(R.id.ivUserAvatar);
        tvProfileNickname = findViewById(R.id.tvProfileNickname);
        tvProfileStatus = findViewById(R.id.tvProfileStatus);
        btnProfileReconnect = findViewById(R.id.btnProfileReconnect);
        btnEditProfileName = findViewById(R.id.btnEditProfileName);
        btnSettings = findViewById(R.id.btnSettings);

        btnTabUsers = findViewById(R.id.btnTabUsers);
        btnTabChat = findViewById(R.id.btnTabChat);
        btnTabAffairs = findViewById(R.id.btnTabAffairs);
        btnTabProfile = findViewById(R.id.btnTabProfile);

        if (btnTabUsers != null) {
            btnTabUsers.setText("功能区");
        }
    }

    private void initAdapters() {
        messageList = new ArrayList<>();
        userAvatarMap = new HashMap<>();
        if (!TextUtils.isEmpty(myNickname) && !TextUtils.isEmpty(myAvatarBase64)) {
            userAvatarMap.put(myNickname, myAvatarBase64);
        }
        chatAdapter = new ChatAdapter(messageList, userAvatarMap);

        chatAdapter.setOnInteractiveCardClickListener((cardType, title) -> {
            if ("card_buzz".equals(cardType)) {
                dialogManager.showBuzzerWindow(
                        TextUtils.isEmpty(title) ? latestBuzzTitle : title,
                        myRole,
                        latestBuzzActive,
                        latestBuzzWinner,
                        latestBuzzCostMs,
                        myNickname
                );
            } else if ("card_vote".equals(cardType)) {
                if (latestVoteData != null) {
                    dialogManager.showVoteWindow(latestVoteData, myRole, myNickname);
                } else {
                    try {
                        JSONObject dummy = new JSONObject();
                        dummy.put("title", title);
                        dummy.put("is_active", false);
                        dialogManager.showVoteWindow(dummy, myRole, myNickname);
                    } catch (JSONException ignored) {}
                }
            }
        });

        chatAdapter.setOnImageClickListener(imageBase64 -> {
            dialogManager.showImagePreviewDialog(imageBase64);
        });

        chatLayoutManager = new LinearLayoutManager(this);
        recyclerViewChat.setLayoutManager(chatLayoutManager);
        recyclerViewChat.setAdapter(chatAdapter);

        rosterUserList = new ArrayList<>();

        gameRoomDisplayList = new ArrayList<>();
        gameRoomIdList = new ArrayList<>();
        gameRoomAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, gameRoomDisplayList);
        lvGameRooms.setAdapter(gameRoomAdapter);

        allAffairList = new ArrayList<>();
        displayAffairList = new ArrayList<>();
        affairsAdapter = new AffairsAdapter(displayAffairList, myNickname, myRole, new AffairsAdapter.OnAffairActionListener() {
            @Override
            public void onConfirm(String affairId) {
                sendConfirmAffair(affairId);
            }

            @Override
            public void onViewUnreadList(AffairItem item, List<String> unreadStudents) {
                showUnreadStudentsDialog(unreadStudents);
            }

            @Override
            public void onEdit(AffairItem item) {
                showEditAffairDialog(item);
            }

            @Override
            public void onDelete(String affairId) {
                confirmDeleteAffair(affairId);
            }
        });
        rvAffairs.setLayoutManager(new LinearLayoutManager(this));
        rvAffairs.setAdapter(affairsAdapter);
    }

    private void initListeners() {
        if (btnHeaderRoster != null) {
            btnHeaderRoster.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, RosterActivity.class);
                startActivity(intent);
            });
        }

        if (cardBookCorner != null) {
            cardBookCorner.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, BookCornerActivity.class);
                startActivity(intent);
            });
        }

        btnProfileReconnect.setOnClickListener(v -> {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            isConnected = false;
            isConnecting = false;
            if (webSocket != null) {
                WebSocket oldSocket = webSocket;
                webSocket = null;
                try {
                    oldSocket.cancel();
                } catch (Exception ignored) {}
            }
            connectWebSocket();
            Toast.makeText(this, "正在重新连接中枢 (" + currentServerIp + ")...", Toast.LENGTH_SHORT).show();
        });

        btnToggleMorePanel.setOnClickListener(v -> {
            if (layoutMorePanel.getVisibility() == View.VISIBLE) {
                layoutMorePanel.setVisibility(View.GONE);
                btnToggleMorePanel.setText("+");
            } else {
                layoutMorePanel.setVisibility(View.VISIBLE);
                btnToggleMorePanel.setText("收起");
            }
        });

        btnPickImage.setOnClickListener(v -> {
            layoutMorePanel.setVisibility(View.GONE);
            btnToggleMorePanel.setText("+");
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            chatImagePickerLauncher.launch(intent);
        });

        btnPanelBuzzAction.setOnClickListener(v -> {
            layoutMorePanel.setVisibility(View.GONE);
            btnToggleMorePanel.setText("+");
            showInitiateBuzzDialog();
        });

        btnPanelRandomPick.setOnClickListener(v -> {
            layoutMorePanel.setVisibility(View.GONE);
            btnToggleMorePanel.setText("+");
            if (webSocket != null && isConnected) {
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "random_pick");
                    webSocket.send(json.toString());
                } catch (JSONException ignored) {}
            }
        });

        btnPanelStartVote.setOnClickListener(v -> {
            layoutMorePanel.setVisibility(View.GONE);
            btnToggleMorePanel.setText("+");
            showInitiateVoteDialog();
        });

        btnFilterNotice.setOnClickListener(v -> switchAffairTab("notice"));
        btnFilterHomework.setOnClickListener(v -> switchAffairTab("homework"));

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

        btnCreateGameRoom.setOnClickListener(v -> showCreateRoomDialog());

        lvGameRooms.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < gameRoomIdList.size()) {
                String targetId = gameRoomIdList.get(position);
                if (!TextUtils.isEmpty(targetId)) {
                    sendJoinRoomCommand(targetId);
                }
            }
        });

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

        btnTabUsers.setOnClickListener(v -> switchPage(0));
        btnTabChat.setOnClickListener(v -> switchPage(1));
        btnTabAffairs.setOnClickListener(v -> switchPage(2));
        btnTabProfile.setOnClickListener(v -> switchPage(3));
    }

    private void switchPage(int pageIndex) {
        if (layoutUserListContainer != null) {
            layoutUserListContainer.setVisibility(pageIndex == 0 ? View.VISIBLE : View.GONE);
        }
        if (pageChatLayout != null) {
            pageChatLayout.setVisibility(pageIndex == 1 ? View.VISIBLE : View.GONE);
        }
        if (pageAffairsLayout != null) {
            pageAffairsLayout.setVisibility(pageIndex == 2 ? View.VISIBLE : View.GONE);
        }
        if (layoutProfileContainer != null) {
            layoutProfileContainer.setVisibility(pageIndex == 3 ? View.VISIBLE : View.GONE);
        }

        if (layoutMorePanel != null && pageIndex != 1) {
            layoutMorePanel.setVisibility(View.GONE);
            if (btnToggleMorePanel != null) btnToggleMorePanel.setText("+");
        }

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

    private void switchAffairTab(String category) {
        currentAffairFilter = category;
        boolean isNotice = "notice".equalsIgnoreCase(category);

        int actBg = Color.parseColor("#1E88E5");
        int inactBg = Color.parseColor("#E5E7EB");
        int actText = Color.WHITE;
        int inactText = Color.parseColor("#4B5563");

        ViewCompat.setBackgroundTintList(btnFilterNotice, ColorStateList.valueOf(isNotice ? actBg : inactBg));
        btnFilterNotice.setTextColor(isNotice ? actText : inactText);

        ViewCompat.setBackgroundTintList(btnFilterHomework, ColorStateList.valueOf(!isNotice ? actBg : inactBg));
        btnFilterHomework.setTextColor(!isNotice ? actText : inactText);

        applyAffairFilter();
    }

    private void applyAffairFilter() {
        displayAffairList.clear();
        for (AffairItem item : allAffairList) {
            if (currentAffairFilter.equalsIgnoreCase(item.getType())) {
                displayAffairList.add(item);
            }
        }
        if (affairsAdapter != null) {
            affairsAdapter.notifyDataSetChanged();
        }
        if (tvEmptyAffairs != null) {
            tvEmptyAffairs.setText("notice".equals(currentAffairFilter) ? "暂无班级通知" : "暂无班级作业");
            tvEmptyAffairs.setVisibility(displayAffairList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void showInitiateVoteDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入投票题目");
        input.setText("随堂单选测验第 1 题");

        new AlertDialog.Builder(this)
                .setTitle("发起随堂投票")
                .setView(input)
                .setPositiveButton("全员开始投票", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (webSocket != null && isConnected) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "start_vote");
                            json.put("title", TextUtils.isEmpty(title) ? "随堂单选测验" : title);
                            webSocket.send(json.toString());
                        } catch (JSONException ignored) {}
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showInitiateBuzzDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入本次抢答题目或环节说明");
        input.setText("随堂第 1 题快速抢答");

        new AlertDialog.Builder(this)
                .setTitle("发起到全班设备")
                .setView(input)
                .setPositiveButton("全员开始抢答", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    if (webSocket != null && isConnected) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "start_buzz");
                            json.put("title", TextUtils.isEmpty(title) ? "随堂抢答环节" : title);
                            webSocket.send(json.toString());
                        } catch (JSONException ignored) {}
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDateTimePickerDialog(EditText targetEditText) {
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH);
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        DatePickerDialog datePickerDialog = new DatePickerDialog(this, (view, pickedYear, pickedMonth, pickedDay) -> {
            TimePickerDialog timePickerDialog = new TimePickerDialog(this, (timeView, pickedHour, pickedMinute) -> {
                String formatted = String.format(Locale.getDefault(), "%02d-%02d %02d:%02d",
                        pickedMonth + 1, pickedDay, pickedHour, pickedMinute);
                targetEditText.setText(formatted);
            }, hour, minute, true);
            timePickerDialog.show();
        }, year, month, day);

        datePickerDialog.show();
    }

    private void showPublishAffairDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_publish_affair, null);
        dialog.setContentView(view);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        RadioButton rbTypeNotice = view.findViewById(R.id.rbTypeNotice);
        RadioButton rbTypeHomework = view.findViewById(R.id.rbTypeHomework);
        EditText etAffairTitle = view.findViewById(R.id.etAffairTitle);
        EditText etAffairDeadline = view.findViewById(R.id.etAffairDeadline);
        Button btnClearDeadline = view.findViewById(R.id.btnClearDeadline);
        EditText etAffairContent = view.findViewById(R.id.etAffairContent);
        Button btnCancel = view.findViewById(R.id.btnCancelAffair);
        Button btnSubmit = view.findViewById(R.id.btnSubmitAffair);

        if ("homework".equalsIgnoreCase(currentAffairFilter)) {
            rbTypeHomework.setChecked(true);
        } else {
            rbTypeNotice.setChecked(true);
        }

        etAffairDeadline.setOnClickListener(v -> showDateTimePickerDialog(etAffairDeadline));
        if (btnClearDeadline != null) {
            btnClearDeadline.setOnClickListener(v -> etAffairDeadline.setText(""));
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSubmit.setOnClickListener(v -> {
            String title = etAffairTitle.getText().toString().trim();
            String content = etAffairContent.getText().toString().trim();
            String deadline = etAffairDeadline.getText().toString().trim();
            String aType = rbTypeHomework.isChecked() ? "homework" : "notice";

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
                    json.put("deadline", deadline);
                    webSocket.send(json.toString());
                    dialog.dismiss();
                } catch (JSONException ignored) {}
            }
        });

        dialog.show();
    }

    private void showEditAffairDialog(AffairItem item) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_publish_affair, null);
        dialog.setContentView(view);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvDialogTitle = view.findViewById(R.id.tvDialogAffairTitle);
        tvDialogTitle.setText("编辑班级事务");

        RadioButton rbTypeNotice = view.findViewById(R.id.rbTypeNotice);
        RadioButton rbTypeHomework = view.findViewById(R.id.rbTypeHomework);
        EditText etAffairTitle = view.findViewById(R.id.etAffairTitle);
        EditText etAffairDeadline = view.findViewById(R.id.etAffairDeadline);
        Button btnClearDeadline = view.findViewById(R.id.btnClearDeadline);
        EditText etAffairContent = view.findViewById(R.id.etAffairContent);
        Button btnCancel = view.findViewById(R.id.btnCancelAffair);
        Button btnSubmit = view.findViewById(R.id.btnSubmitAffair);
        btnSubmit.setText("保存修改");

        etAffairTitle.setText(item.getTitle());
        etAffairContent.setText(item.getContent());
        etAffairDeadline.setText(item.getDeadline());
        if ("homework".equalsIgnoreCase(item.getType())) {
            rbTypeHomework.setChecked(true);
        } else {
            rbTypeNotice.setChecked(true);
        }

        etAffairDeadline.setOnClickListener(v -> showDateTimePickerDialog(etAffairDeadline));
        if (btnClearDeadline != null) {
            btnClearDeadline.setOnClickListener(v -> etAffairDeadline.setText(""));
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSubmit.setOnClickListener(v -> {
            String title = etAffairTitle.getText().toString().trim();
            String content = etAffairContent.getText().toString().trim();
            String deadline = etAffairDeadline.getText().toString().trim();
            String aType = rbTypeHomework.isChecked() ? "homework" : "notice";

            if (TextUtils.isEmpty(title) || TextUtils.isEmpty(content)) {
                Toast.makeText(this, "标题和内容不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            if (webSocket != null && isConnected) {
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "edit_affair");
                    json.put("affair_id", item.getAffairId());
                    json.put("title", title);
                    json.put("content", content);
                    json.put("affair_type", aType);
                    json.put("deadline", deadline);
                    webSocket.send(json.toString());
                    dialog.dismiss();
                } catch (JSONException ignored) {}
            }
        });

        dialog.show();
    }

    private void confirmDeleteAffair(String affairId) {
        new AlertDialog.Builder(this)
                .setTitle("删除事务确认")
                .setMessage("确定要删除此条班级事务吗？删除后所有学生的记录将同步移除。")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (webSocket != null && isConnected) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "delete_affair");
                            json.put("affair_id", affairId);
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

    private void showUnreadStudentsDialog(List<String> unreadStudents) {
        StringBuilder sb = new StringBuilder();
        if (unreadStudents == null || unreadStudents.isEmpty()) {
            sb.append("全员均已完成阅读。");
        } else {
            for (int i = 0; i < unreadStudents.size(); i++) {
                sb.append(i + 1).append(". ").append(unreadStudents.get(i)).append("\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("未读名单 (" + (unreadStudents != null ? unreadStudents.size() : 0) + "人)")
                .setMessage(sb.toString().trim())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showCreateRoomDialog() {
        LinearLayout dialogLayout = new LinearLayout(this);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(60, 20, 60, 20);

        final EditText input = new EditText(this);
        input.setHint("输入小组名称");
        input.setText(myNickname + "的协作组");
        dialogLayout.addView(input);

        TextView tvCapLabel = new TextView(this);
        tvCapLabel.setText("选择小组人数上限：");
        tvCapLabel.setTextColor(Color.parseColor("#4B5563"));
        tvCapLabel.setTextSize(13);
        tvCapLabel.setPadding(0, 24, 0, 8);
        dialogLayout.addView(tvCapLabel);

        final RadioGroup rgCapacity = new RadioGroup(this);
        rgCapacity.setOrientation(RadioGroup.HORIZONTAL);
        int[] caps = {2, 4, 6, 8};
        for (int cap : caps) {
            RadioButton rb = new RadioButton(this);
            rb.setText(cap + "人");
            rb.setId(cap);
            if (cap == 4) rb.setChecked(true);
            rgCapacity.addView(rb);
        }
        dialogLayout.addView(rgCapacity);

        new AlertDialog.Builder(this)
                .setTitle("创建协作小组 / 休息室")
                .setView(dialogLayout)
                .setPositiveButton("创建并进入", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    int selectedCap = rgCapacity.getCheckedRadioButtonId();
                    if (selectedCap <= 0) selectedCap = 4;

                    if (!TextUtils.isEmpty(title) && webSocket != null && isConnected) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "create_room");
                            json.put("title", title);
                            json.put("max_players", selectedCap);
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
        String compressedBase64 = croppedBase64;
        try {
            byte[] rawBytes = Base64.decode(croppedBase64, Base64.DEFAULT);
            Bitmap rawBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length);
            if (rawBitmap != null) {
                int maxDim = 120;
                int w = rawBitmap.getWidth();
                int h = rawBitmap.getHeight();
                float ratio = Math.min((float) maxDim / w, (float) maxDim / h);
                if (ratio > 1.0f) ratio = 1.0f;
                int finalW = Math.max(1, Math.round(ratio * w));
                int finalH = Math.max(1, Math.round(ratio * h));

                Bitmap scaled = Bitmap.createScaledBitmap(rawBitmap, finalW, finalH, true);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                compressedBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Exception ignored) {}

        myAvatarBase64 = compressedBase64;
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
        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        String roleText = isTeacher ? "教师端 (管理权限)" : "学生端 (普通学员)";
        tvProfileStatus.setText("教师中枢节点：" + currentServerIp + ":8000\n当前权限：" + roleText + "\n网络状态：" + connectionInfo);

        if (btnPublishAffair != null) {
            btnPublishAffair.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
        }
        if (itemPanelStartBuzz != null) {
            itemPanelStartBuzz.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
        }
        if (itemPanelRandomPick != null) {
            itemPanelRandomPick.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
        }
        if (itemPanelStartVote != null) {
            itemPanelStartVote.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
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
        if (isConnected || isConnecting) {
            return;
        }
        isConnecting = true;

        if (webSocket != null) {
            WebSocket oldSocket = webSocket;
            webSocket = null;
            try {
                oldSocket.cancel();
            } catch (Exception ignored) {}
        }

        runOnUiThread(() -> {
            tvTitle.setText(currentClassroomTitle + " (连接中...)");
            updateProfileDisplay();
        });

        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
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
                if (ws != webSocket) {
                    ws.cancel();
                    return;
                }

                isConnected = true;
                isConnecting = false;
                reconnectHandler.removeCallbacks(reconnectRunnable);
                GameSocketBridge.setWebSocket(ws);

                runOnUiThread(() -> {
                    tvTitle.setText(currentClassroomTitle + " (在线)");
                    updateProfileDisplay();
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (ws != webSocket) return;

                runOnUiThread(() -> {
                    try {
                        JSONObject json = new JSONObject(text);
                        String type = json.optString("type", "chat");

                        if ("room_list".equals(type)) {
                            parseAndRefreshRoomList(json.optJSONArray("rooms"));
                            return;
                        } else if ("roster_update".equals(type)) {
                            parseAndRefreshRoster(json.optJSONArray("roster"));
                            return;
                        } else if ("classroom_title_update".equals(type)) {
                            String newTitle = json.optString("classroom_title", "");
                            if (!TextUtils.isEmpty(newTitle)) {
                                currentClassroomTitle = newTitle;
                                tvTitle.setText(currentClassroomTitle + (isConnected ? " (在线)" : ""));
                                RosterDataBridge.setClassroomTitle(currentClassroomTitle);
                            }
                            return;
                        }

                        if (isInIndependentRoom) {
                            GameSocketBridge.dispatchMessage(json);
                            return;
                        }

                        if ("enter_room_success".equals(type)) {
                            isInIndependentRoom = true;
                            String rId = json.optString("room_id");
                            String rTitle = json.optString("title");
                            String creator = json.optString("creator");
                            int maxP = json.optInt("max_players", 4);

                            Intent intent = new Intent(MainActivity.this, GameRoomActivity.class);
                            intent.putExtra(GameRoomActivity.EXTRA_ROOM_ID, rId);
                            intent.putExtra(GameRoomActivity.EXTRA_ROOM_TITLE, rTitle);
                            intent.putExtra(GameRoomActivity.EXTRA_CREATOR, creator);
                            intent.putExtra(GameRoomActivity.EXTRA_MAX_PLAYERS, maxP);
                            startActivity(intent);
                            return;
                        }

                        if ("vote_state".equals(type)) {
                            latestVoteData = json;
                            if (dialogManager.isVoteDialogShowing()) {
                                dialogManager.renderVoteState(json, myRole, myNickname);
                            }
                            return;
                        }

                        if ("buzz_started".equals(type)) {
                            String bTitle = json.optString("title", "快速抢答");
                            latestBuzzTitle = bTitle;
                            latestBuzzActive = true;
                            latestBuzzWinner = "";
                            latestBuzzCostMs = 0;
                            dialogManager.showBuzzerWindow(bTitle, myRole, true, "", 0, myNickname);
                            return;
                        }

                        if ("buzz_result".equals(type)) {
                            String winner = json.optString("winner");
                            int costMs = json.optInt("cost_ms", 100);
                            latestBuzzActive = false;
                            latestBuzzWinner = winner;
                            latestBuzzCostMs = costMs;
                            dialogManager.renderBuzzResult(winner, costMs, myRole, myNickname);
                            return;
                        }

                        if ("buzz_stopped".equals(type)) {
                            latestBuzzActive = false;
                            dialogManager.dismissBuzzer();
                            return;
                        }

                        if ("random_pick_result".equals(type)) {
                            String winner = json.optString("winner", "未知");
                            JSONArray candArr = json.optJSONArray("candidates");
                            List<String> candidates = new ArrayList<>();
                            if (candArr != null) {
                                for (int i = 0; i < candArr.length(); i++) {
                                    candidates.add(candArr.optString(i));
                                }
                            }
                            dialogManager.showRandomPickAnimationDialog(winner, candidates, myNickname);
                            return;
                        }

                        if ("init_classroom_state".equals(type)) {
                            String titleFromHub = json.optString("classroom_title", "");
                            if (!TextUtils.isEmpty(titleFromHub)) {
                                currentClassroomTitle = titleFromHub;
                                tvTitle.setText(currentClassroomTitle + (isConnected ? " (在线)" : ""));
                                RosterDataBridge.setClassroomTitle(currentClassroomTitle);
                            }

                            String verifiedRole = json.optString("my_verified_role", "");
                            if (!TextUtils.isEmpty(verifiedRole)) {
                                myRole = verifiedRole;
                                sharedPreferences.edit().putString(KEY_USER_ROLE, myRole).apply();
                                updateProfileDisplay();
                            }

                            isGlobalMuted = json.optBoolean("is_global_muted", false);
                            updateMuteUI();

                            latestBuzzActive = json.optBoolean("buzz_active", false);
                            JSONObject buzzData = json.optJSONObject("buzz_data");
                            if (buzzData != null) {
                                latestBuzzTitle = buzzData.optString("title", "随堂即时抢答");
                                latestBuzzWinner = buzzData.optString("winner", "");
                                latestBuzzCostMs = buzzData.optInt("cost_ms", 0);
                            }

                            JSONObject vData = json.optJSONObject("vote_data");
                            if (vData != null) {
                                latestVoteData = vData;
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

                            parseAndRefreshRoomList(json.optJSONArray("rooms"));
                            parseAndRefreshRoster(json.optJSONArray("roster"));
                            parseAndRefreshAffairs(json.optJSONArray("affairs"));

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

                            if ("card_vote".equals(newMsg.getType())) {
                                if (latestVoteData != null) {
                                    dialogManager.showVoteWindow(latestVoteData, myRole, myNickname);
                                }
                            }
                        }

                    } catch (JSONException ignored) {}
                });
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (ws != webSocket) {
                    return;
                }
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                if (ws != webSocket) {
                    return;
                }
                scheduleReconnect();
            }
        });
    }

    private void parseAndRefreshRoomList(JSONArray roomsArr) {
        gameRoomDisplayList.clear();
        gameRoomIdList.clear();
        if (roomsArr != null && roomsArr.length() > 0) {
            for (int i = 0; i < roomsArr.length(); i++) {
                JSONObject r = roomsArr.optJSONObject(i);
                if (r != null) {
                    String rId = r.optString("room_id");
                    String rTitle = r.optString("title");
                    String creator = r.optString("creator");
                    int curP = r.optInt("current_players", 0);
                    int maxP = r.optInt("max_players", 4);
                    boolean hasTeacher = r.optBoolean("has_teacher", false);

                    String statusText = (curP >= maxP) ? " [已满员]" : " [可加入]";
                    String teacherTag = hasTeacher ? " (教师旁听中)" : "";
                    String itemStr = "[协作组] " + rTitle + " (" + curP + "/" + maxP + "人)"
                            + statusText + teacherTag + "\n创建者: " + creator;

                    gameRoomDisplayList.add(itemStr);
                    gameRoomIdList.add(rId);
                }
            }
        }
        if (gameRoomDisplayList.isEmpty()) {
            gameRoomDisplayList.add("暂无进行中的协作小组，点击上方按钮创建");
            gameRoomIdList.add("");
        }
        if (gameRoomAdapter != null) {
            gameRoomAdapter.notifyDataSetChanged();
        }
    }

    private void parseAndRefreshRoster(JSONArray rosterArr) {
        if (rosterArr == null) return;
        rosterUserList.clear();
        int onlineCount = 0;

        for (int i = 0; i < rosterArr.length(); i++) {
            JSONObject u = rosterArr.optJSONObject(i);
            if (u != null) {
                String uName = u.optString("name");
                String uRole = u.optString("role", "student");
                String uAvatar = u.optString("avatar", "");
                boolean uOnline = u.optBoolean("is_online", false);
                boolean uMuted = u.optBoolean("is_muted", false);
                int uScore = u.optInt("score", 0);
                if (uOnline) onlineCount++;

                rosterUserList.add(new RosterUser(uName, uRole, uAvatar, uOnline, uMuted, uScore));
            }
        }
        tvOnlineCount.setText("小组大厅");

        RosterDataBridge.updateRoster(rosterUserList);
    }

    private void parseAndRefreshAffairs(JSONArray affairsArr) {
        if (affairsArr == null) return;
        allAffairList.clear();

        for (int i = 0; i < affairsArr.length(); i++) {
            JSONObject a = affairsArr.optJSONObject(i);
            if (a != null) {
                String aId = a.optString("affair_id");
                String aTitle = a.optString("title");
                String aContent = a.optString("content");
                String aType = a.optString("type", "notice");
                String aPublisher = a.optString("publisher", "教师");
                String aDeadline = a.optString("deadline", "");
                long aTime = a.optLong("timestamp", System.currentTimeMillis());
                int aCount = a.optInt("confirmed_count", 0);

                List<String> confirmedList = new ArrayList<>();
                JSONArray cArr = a.optJSONArray("confirmed_students");
                if (cArr != null) {
                    for (int j = 0; j < cArr.length(); j++) {
                        confirmedList.add(cArr.optString(j));
                    }
                }

                allAffairList.add(new AffairItem(aId, aTitle, aContent, aType, aPublisher, aDeadline, aTime, aCount, confirmedList));
            }
        }

        applyAffairFilter();
    }

    private void scheduleReconnect() {
        isConnected = false;
        isConnecting = false;

        runOnUiThread(() -> {
            tvTitle.setText(currentClassroomTitle + " (正在重连...)");
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
        isConnecting = false;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        GameSocketBridge.clearListener();
        if (dialogManager != null) {
            dialogManager.dismissAll();
        }
        if (webSocket != null) {
            webSocket.close(1000, "退出应用");
        }
    }

    public static class RosterUser {
        public String name;
        public String role;
        public String avatar;
        public boolean isOnline;
        public boolean isMuted;
        public int score;

        public RosterUser(String name, String role, String avatar, boolean isOnline, boolean isMuted, int score) {
            this.name = name;
            this.role = role;
            this.avatar = avatar;
            this.isOnline = isOnline;
            this.isMuted = isMuted;
            this.score = score;
        }

        public RosterUser(String name, String role, String avatar, boolean isOnline, boolean isMuted) {
            this(name, role, avatar, isOnline, isMuted, 0);
        }
    }
}