package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RosterActivity extends AppCompatActivity implements RosterDataBridge.OnRosterChangeListener {

    private Button btnBack;
    private TextView tvClassroomName;
    private Button btnEditClassName;
    private TextView tvRosterSummary;
    private TextView tvRosterHint;
    private ListView lvRosterMembers;

    private List<MainActivity.RosterUser> displayRosterList;
    private RosterAdapter rosterAdapter;

    private String myNickname = "";
    private String myRole = "student";
    private boolean isHonorRollMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_roster);

        View rootLayout = findViewById(R.id.rosterRootLayout);
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

        initViews();
        initData();
        RosterDataBridge.setListener(this);
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        tvClassroomName = findViewById(R.id.tvClassroomName);
        btnEditClassName = findViewById(R.id.btnEditClassName);
        tvRosterSummary = findViewById(R.id.tvRosterSummary);
        tvRosterHint = findViewById(R.id.tvRosterHint);
        lvRosterMembers = findViewById(R.id.lvRosterMembers);

        btnBack.setOnClickListener(v -> finish());

        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        btnEditClassName.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
        btnEditClassName.setOnClickListener(v -> showEditClassroomTitleDialog());

        // 点击提示栏即可在“默认名册”与“积分光荣榜”之间切换排序
        tvRosterHint.setOnClickListener(v -> {
            isHonorRollMode = !isHonorRollMode;
            applySortAndRefresh();
        });
    }

    private void initData() {
        tvClassroomName.setText("班级：" + RosterDataBridge.getCachedClassroomTitle());
        displayRosterList = new ArrayList<>(RosterDataBridge.getCachedRoster());
        rosterAdapter = new RosterAdapter(this, displayRosterList);
        lvRosterMembers.setAdapter(rosterAdapter);
        applySortAndRefresh();

        lvRosterMembers.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < displayRosterList.size()) {
                MainActivity.RosterUser targetUser = displayRosterList.get(position);
                if ("teacher".equalsIgnoreCase(myRole)) {
                    showTeacherManageStudentDialog(targetUser);
                } else {
                    Toast.makeText(this, targetUser.name + " · 积分: " + targetUser.score + " · " + (targetUser.isOnline ? "在线" : "离线"), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void applySortAndRefresh() {
        if (displayRosterList == null) return;

        if (isHonorRollMode) {
            // 光荣榜：按积分降序排序
            Collections.sort(displayRosterList, (u1, u2) -> {
                if (u2.score != u1.score) {
                    return Integer.compare(u2.score, u1.score);
                }
                return u1.name.compareTo(u2.name);
            });
            tvRosterHint.setText("当前模式：积分光荣榜 (点击切换为默认名册)");
        } else {
            // 默认排序：教师在前，在线学生居中，离线学生在后
            Collections.sort(displayRosterList, (u1, u2) -> {
                boolean t1 = "teacher".equalsIgnoreCase(u1.role);
                boolean t2 = "teacher".equalsIgnoreCase(u2.role);
                if (t1 != t2) return t1 ? -1 : 1;
                if (u1.isOnline != u2.isOnline) return u1.isOnline ? -1 : 1;
                return u1.name.compareTo(u2.name);
            });
            tvRosterHint.setText("当前模式：默认名册 (点击切换为积分光荣榜)");
        }

        if (rosterAdapter != null) {
            rosterAdapter.notifyDataSetChanged();
        }
        refreshSummaryHeader();
    }

    private void showEditClassroomTitleDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入新的班级全称");
        input.setText(RosterDataBridge.getCachedClassroomTitle());

        new AlertDialog.Builder(this)
                .setTitle("修改班级名称")
                .setView(input)
                .setPositiveButton("保存并全员同步", (dialog, which) -> {
                    String newTitle = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(newTitle)) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", "set_classroom_title");
                            json.put("title", newTitle);
                            GameSocketBridge.sendJson(json);
                        } catch (JSONException ignored) {}
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshSummaryHeader() {
        int onlineCount = 0;
        for (MainActivity.RosterUser u : displayRosterList) {
            if (u.isOnline) onlineCount++;
        }
        if (isHonorRollMode) {
            tvRosterSummary.setText("课堂表现光荣榜 (" + displayRosterList.size() + " 人参与评比)");
        } else {
            tvRosterSummary.setText("班级总名册 (" + onlineCount + " 人在线 / " + displayRosterList.size() + " 人登记)");
        }
    }

    @Override
    public void onRosterChanged(List<MainActivity.RosterUser> latestRoster) {
        runOnUiThread(() -> {
            displayRosterList.clear();
            if (latestRoster != null) {
                displayRosterList.addAll(latestRoster);
            }
            applySortAndRefresh();
        });
    }

    @Override
    public void onClassroomTitleChanged(String title) {
        runOnUiThread(() -> {
            if (tvClassroomName != null) {
                tvClassroomName.setText("班级：" + title);
            }
        });
    }

    private void showTeacherManageStudentDialog(MainActivity.RosterUser target) {
        if (target.name.equals(myNickname)) {
            Toast.makeText(this, "不能对自己进行管理操作", Toast.LENGTH_SHORT).show();
            return;
        }

        String muteActionText = target.isMuted ? "解除禁言" : "单独禁言";
        String[] options = {"奖励积分 (+1分)", muteActionText, "变更角色身份 (学生/班长)", "修改姓名或学号"};

        new AlertDialog.Builder(this)
                .setTitle("管理学生：" + target.name + " (当前积分: " + target.score + ")")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showAwardScoreDialog(target);
                    } else if (which == 1) {
                        sendAdminManageCommand(target.name, "toggle_mute", null, null);
                    } else if (which == 2) {
                        showChangeRoleSubDialog(target);
                    } else if (which == 3) {
                        showRenameStudentSubDialog(target);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAwardScoreDialog(MainActivity.RosterUser target) {
        final EditText etReason = new EditText(this);
        etReason.setHint("输入加分理由 (如: 积极回答问题)");
        etReason.setText("积极回答问题");

        new AlertDialog.Builder(this)
                .setTitle("奖励学生表现")
                .setMessage("为【" + target.name + "】增加 1 积分")
                .setView(etReason)
                .setPositiveButton("确认奖励", (dialog, which) -> {
                    String reason = etReason.getText().toString().trim();
                    try {
                        JSONObject json = new JSONObject();
                        json.put("type", "award_score");
                        json.put("target_student", target.name);
                        json.put("delta", 1);
                        json.put("reason", TextUtils.isEmpty(reason) ? "课堂表现优异" : reason);
                        GameSocketBridge.sendJson(json);
                        target.score += 1;
                        applySortAndRefresh();
                        Toast.makeText(this, "已为 " + target.name + " +1分", Toast.LENGTH_SHORT).show();
                    } catch (JSONException ignored) {}
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showChangeRoleSubDialog(MainActivity.RosterUser target) {
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

    private void showRenameStudentSubDialog(MainActivity.RosterUser target) {
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
        try {
            JSONObject json = new JSONObject();
            json.put("type", "admin_manage_student");
            json.put("target_name", targetName);
            json.put("operation", operation);
            if (newRole != null) json.put("new_role", newRole);
            if (newName != null) json.put("new_name", newName);
            GameSocketBridge.sendJson(json);
        } catch (JSONException ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RosterDataBridge.clearListener();
    }
}