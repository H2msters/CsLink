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

        if (!isTeacher) {
            tvRosterHint.setText("班级在线出勤状态实时同步中");
        }
    }

    private void initData() {
        tvClassroomName.setText("班级：" + RosterDataBridge.getCachedClassroomTitle());
        displayRosterList = new ArrayList<>(RosterDataBridge.getCachedRoster());
        rosterAdapter = new RosterAdapter(this, displayRosterList);
        lvRosterMembers.setAdapter(rosterAdapter);
        refreshSummaryHeader();

        lvRosterMembers.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < displayRosterList.size()) {
                MainActivity.RosterUser targetUser = displayRosterList.get(position);
                if ("teacher".equalsIgnoreCase(myRole)) {
                    showTeacherManageStudentDialog(targetUser);
                } else {
                    Toast.makeText(this, targetUser.name + " · " + (targetUser.isOnline ? "在线" : "离线"), Toast.LENGTH_SHORT).show();
                }
            }
        });
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
        tvRosterSummary.setText("班级总名册 (" + onlineCount + " 人在线 / " + displayRosterList.size() + " 人登记)");
    }

    @Override
    public void onRosterChanged(List<MainActivity.RosterUser> latestRoster) {
        runOnUiThread(() -> {
            displayRosterList.clear();
            if (latestRoster != null) {
                displayRosterList.addAll(latestRoster);
            }
            if (rosterAdapter != null) {
                rosterAdapter.notifyDataSetChanged();
            }
            refreshSummaryHeader();
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