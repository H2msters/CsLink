package cn.edu.hbuas.coursetest;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
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
    private View layoutRoomToolPanel;

    private LinearLayout itemToolCamera;
    private LinearLayout itemToolGallery;
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

    // 1. 相机权限申请器
    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    launchCameraActivity();
                } else {
                    Toast.makeText(this, "需要相机权限以完成随堂拍摄", Toast.LENGTH_SHORT).show();
                }
            }
    );

    // 2. 拍照完成结果回调
    private final ActivityResultLauncher<Intent> cameraCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String photoPath = result.getData().getStringExtra(CameraCaptureActivity.RESULT_IMAGE_PATH);
                    if (!TextUtils.isEmpty(photoPath)) {
                        File photoFile = new File(photoPath);
                        if (photoFile.exists()) {
                            sendRoomImage(Uri.fromFile(photoFile));
                        }
                    }
                }
            }
    );

    // 3. 相册选取图片结果回调
    private final ActivityResultLauncher<Intent> chatImagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedImageUri = result.getData().getData();
                    if (selectedImageUri != null) {
                        sendRoomImage(selectedImageUri);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_room);

        // 关键：监听键盘（IME）与状态栏高度，键盘弹起时自动把输入框与消息列表顶起
        View rootLayout = findViewById(R.id.roomRootLayout);
        if (rootLayout != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
                Insets sysInsets = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
                int bottomPadding = Math.max(sysInsets.bottom, imeInsets.bottom);
                v.setPadding(sysInsets.left, sysInsets.top, sysInsets.right, bottomPadding);
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

        try {
            JSONObject req = new JSONObject();
            req.put("type", "get_my_identity");
            GameSocketBridge.sendJson(req);
        } catch (JSONException ignored) {}

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

        itemToolCamera = findViewById(R.id.itemToolCamera);
        itemToolGallery = findViewById(R.id.itemToolGallery);
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

        chatAdapter.setOnInteractiveCardClickListener((cardType, title) -> {
            if ("card_rps".equals(cardType)) {
                sendJoinRpsGame();
                showRpsToolDialog();
            }
        });

        // 点击图片放大预览
        chatAdapter.setOnImageClickListener(this::showImagePreviewDialog);

        chatLayoutManager = new LinearLayoutManager(this);
        rvRoomChat.setLayoutManager(chatLayoutManager);
        rvRoomChat.setAdapter(chatAdapter);

        // 键盘弹起或高度变化时，自动滚动到最新消息
        rvRoomChat.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom && !roomMessageList.isEmpty()) {
                rvRoomChat.post(() -> rvRoomChat.smoothScrollToPosition(roomMessageList.size() - 1));
            }
        });

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
        Button btnToolCameraAction = findViewById(R.id.btnToolCameraAction);
        Button btnToolGalleryAction = findViewById(R.id.btnToolGalleryAction);
        Button btnLaunchRpsTool = findViewById(R.id.btnLaunchRpsTool);
        Button btnToolCanvasAction = findViewById(R.id.btnToolCanvasAction);

        // 1. 随堂拍摄入口
        View.OnClickListener cameraClickListener = v -> {
            if (layoutRoomToolPanel != null) layoutRoomToolPanel.setVisibility(View.GONE);
            if (btnToggleRoomToolPanel != null) btnToggleRoomToolPanel.setText("+");

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraActivity();
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        };
        if (itemToolCamera != null) itemToolCamera.setOnClickListener(cameraClickListener);
        if (btnToolCameraAction != null) btnToolCameraAction.setOnClickListener(cameraClickListener);

        // 2. 相册选图入口
        View.OnClickListener galleryClickListener = v -> {
            if (layoutRoomToolPanel != null) layoutRoomToolPanel.setVisibility(View.GONE);
            if (btnToggleRoomToolPanel != null) btnToggleRoomToolPanel.setText("+");

            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            chatImagePickerLauncher.launch(intent);
        };
        if (itemToolGallery != null) itemToolGallery.setOnClickListener(galleryClickListener);
        if (btnToolGalleryAction != null) btnToolGalleryAction.setOnClickListener(galleryClickListener);

        // 3. 猜拳对决
        View.OnClickListener rpsClickListener = v -> {
            if (layoutRoomToolPanel != null) layoutRoomToolPanel.setVisibility(View.GONE);
            if (btnToggleRoomToolPanel != null) btnToggleRoomToolPanel.setText("+");
            showLaunchRpsConfigDialog();
        };
        if (itemToolRps != null) itemToolRps.setOnClickListener(rpsClickListener);
        if (btnLaunchRpsTool != null) btnLaunchRpsTool.setOnClickListener(rpsClickListener);

        // 4. 协同草稿
        View.OnClickListener canvasClickListener = v -> {
            if (layoutRoomToolPanel != null) layoutRoomToolPanel.setVisibility(View.GONE);
            if (btnToggleRoomToolPanel != null) btnToggleRoomToolPanel.setText("+");
            Toast.makeText(this, "协同白板草稿即将接入", Toast.LENGTH_SHORT).show();
        };
        if (itemToolCanvas != null) itemToolCanvas.setOnClickListener(canvasClickListener);
        if (btnToolCanvasAction != null) btnToolCanvasAction.setOnClickListener(canvasClickListener);
    }

    private void launchCameraActivity() {
        Intent intent = new Intent(GameRoomActivity.this, CameraCaptureActivity.class);
        cameraCaptureLauncher.launch(intent);
    }

    private void sendRoomImage(Uri imageUri) {
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

                GameSocketBridge.sendJson(json);
            }
        } catch (Exception ignored) {}
    }

    private void showImagePreviewDialog(String base64) {
        if (TextUtils.isEmpty(base64)) return;
        try {
            byte[] decoded = Base64.decode(base64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
            if (bitmap == null) return;

            Dialog previewDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bitmap);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.BLACK);
            iv.setOnClickListener(v -> previewDialog.dismiss());
            previewDialog.setContentView(iv);
            previewDialog.show();
        } catch (Exception ignored) {}
    }

    private void showLaunchRpsConfigDialog() {
        final String[] rules = new String[]{"三局两胜 (BO3)", "单局决胜 (BO1)"};
        final int[] selectedIdx = {0};

        new AlertDialog.Builder(this)
                .setTitle("发起猜拳 - 选择赛制")
                .setSingleChoiceItems(rules, 0, (dialog, which) -> {
                    selectedIdx[0] = which;
                })
                .setPositiveButton("立即发起", (dialog, which) -> {
                    int targetWins = (selectedIdx[0] == 0) ? 2 : 1;
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

        String ruleDesc = (targetWins == 1) ? "单局决胜" : "三局两胜";
        tvToolRpsTopic.setText("猜拳 (" + ruleDesc + ")");
        tvP1Name.setText(TextUtils.isEmpty(p1) ? "红方" : p1);
        tvP1Score.setText(String.valueOf(s1));
        tvP2Name.setText(TextUtils.isEmpty(p2) ? "蓝方 (等待加入)" : p2);
        tvP2Score.setText(String.valueOf(s2));
        tvCurrentRoundIndicator.setText("第 " + curRound + " 局");

        boolean isP1 = !TextUtils.isEmpty(myNickname) && myNickname.equals(p1);
        boolean isP2 = !TextUtils.isEmpty(myNickname) && myNickname.equals(p2);
        boolean isPlayer = isP1 || isP2;
        String opponent = isP1 ? p2 : p1;

        boolean myChosen = choicesStatus != null && choicesStatus.optBoolean(myNickname, false);
        boolean oppChosen = (!TextUtils.isEmpty(opponent) && choicesStatus != null) && choicesStatus.optBoolean(opponent, false);

        if ("waiting_opponent".equals(status)) {
            btnNextRound.setVisibility(View.GONE);
            tvToolRpsStatus.setText("等待对手加入...");

            if (isP1) {
                tvToolRpsResult.setText("等待对手点击卡片应战");
                tvToolP1Status.setText("我方：已就绪");
                tvToolP2Status.setText("对方：等待加入");
                setChoiceButtonsVisibility(true);
                setChoiceButtonsEnabled(false);
            } else {
                tvToolRpsResult.setText("点击卡片即可加入应战");
                tvToolP1Status.setText(p1 + "：已发起");
                tvToolP2Status.setText("我方：未加入");
                setChoiceButtonsVisibility(false);
            }

        } else if ("playing".equals(status)) {
            btnNextRound.setVisibility(View.GONE);

            if (isPlayer) {
                tvToolRpsStatus.setText("第 " + curRound + " 局进行中");
                tvToolRpsResult.setText(myChosen ? "已提交，等待对方出拳..." : "请出拳");
                tvToolP1Status.setText("我方：" + (myChosen ? "已出拳" : "未出拳"));
                tvToolP2Status.setText("对方：" + (oppChosen ? "已出拳" : "未出拳"));
                setChoiceButtonsVisibility(true);
                setChoiceButtonsEnabled(!myChosen);
            } else {
                tvToolRpsStatus.setText("正在观战");
                tvToolRpsResult.setText("对决进行中，双方思考中...");
                tvToolP1Status.setText(p1 + "：" + (choicesStatus != null && choicesStatus.optBoolean(p1, false) ? "已出拳" : "未出拳"));
                tvToolP2Status.setText(p2 + "：" + (choicesStatus != null && choicesStatus.optBoolean(p2, false) ? "已出拳" : "未出拳"));
                setChoiceButtonsVisibility(false);
            }

        } else if ("round_finished".equals(status)) {
            setChoiceButtonsVisibility(false);

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
            btnNextRound.setVisibility(isPlayer ? View.VISIBLE : View.GONE);

        } else if ("match_finished".equals(status)) {
            btnNextRound.setVisibility(View.GONE);
            setChoiceButtonsVisibility(false);

            String c1 = (choices != null) ? choices.optString(p1, "") : "";
            String c2 = (choices != null) ? choices.optString(p2, "") : "";
            if (isPlayer) {
                tvToolP1Status.setText("我方：出【" + (isP1 ? c1 : c2) + "】");
                tvToolP2Status.setText("对方：出【" + (isP1 ? c2 : c1) + "】");
            } else {
                tvToolP1Status.setText(p1 + "：出【" + c1 + "】");
                tvToolP2Status.setText(p2 + "：出【" + c2 + "】");
            }

            tvToolRpsStatus.setText("对决结束");

            String loser = p1.equals(finalWinner) ? p2 : p1;
            if (!TextUtils.isEmpty(loser)) {
                tvToolRpsResult.setText("【" + finalWinner + "】战胜了【" + loser + "】");
            } else {
                tvToolRpsResult.setText("【" + finalWinner + "】获胜");
            }
        }
    }

    private void setChoiceButtonsVisibility(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (btnChoiceRock != null) btnChoiceRock.setVisibility(visibility);
        if (btnChoiceScissors != null) btnChoiceScissors.setVisibility(visibility);
        if (btnChoicePaper != null) btnChoicePaper.setVisibility(visibility);
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

            if ("my_identity".equals(type)) {
                String sName = json.optString("my_name", "");
                String sRole = json.optString("my_role", "");
                if (!TextUtils.isEmpty(sName)) {
                    myNickname = sName;
                    SharedPreferences sp = getSharedPreferences("CampusChatPrefs", Context.MODE_PRIVATE);
                    sp.edit().putString("user_nickname", myNickname).apply();
                }
                if (!TextUtils.isEmpty(sRole)) {
                    myRole = sRole;
                }
                if (latestRpsState != null && rpsDialog != null && rpsDialog.isShowing()) {
                    renderRpsStateToDialog(latestRpsState);
                }
                return;
            }

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