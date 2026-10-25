package cn.edu.hbuas.coursetest;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CalendarView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RosterActivity extends AppCompatActivity implements RosterDataBridge.OnRosterChangeListener {

    private Button btnBack;
    private Button btnAttendanceCalendar;
    private TextView tvClassroomName;
    private Button btnEditClassName;
    private TextView tvRosterSummary;
    private TextView btnSortDefault;
    private TextView btnSortScore;
    private ListView lvRosterMembers;

    private List<MainActivity.RosterUser> displayRosterList;
    private RosterAdapter rosterAdapter;

    private String myNickname = "";
    private String myRole = "student";
    private boolean isHonorRollMode = false;

    // 考勤日历弹窗组件
    private Dialog calendarDialog;
    private TextView tvSelectedDateTitle;
    private TextView tvSelectedDateSummary;
    private TextView tvEmptyAttendanceLogs;
    private final List<String> attendanceLogDisplayList = new ArrayList<>();
    private ArrayAdapter<String> attendanceLogAdapter;
    private String currentSelectedDateStr = "";

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
        btnAttendanceCalendar = findViewById(R.id.btnAttendanceCalendar);
        tvClassroomName = findViewById(R.id.tvClassroomName);
        btnEditClassName = findViewById(R.id.btnEditClassName);
        tvRosterSummary = findViewById(R.id.tvRosterSummary);
        btnSortDefault = findViewById(R.id.btnSortDefault);
        btnSortScore = findViewById(R.id.btnSortScore);
        lvRosterMembers = findViewById(R.id.lvRosterMembers);

        btnBack.setOnClickListener(v -> finish());
        if (btnAttendanceCalendar != null) {
            btnAttendanceCalendar.setOnClickListener(v -> showAttendanceCalendarDialog());
        }

        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        btnEditClassName.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
        btnEditClassName.setOnClickListener(v -> showEditClassroomTitleDialog());

        btnSortDefault.setOnClickListener(v -> {
            if (isHonorRollMode) {
                isHonorRollMode = false;
                updateSortButtonsUI();
                applySortAndRefresh();
            }
        });

        btnSortScore.setOnClickListener(v -> {
            if (!isHonorRollMode) {
                isHonorRollMode = true;
                updateSortButtonsUI();
                applySortAndRefresh();
            }
        });
    }

    private void showAttendanceCalendarDialog() {
        if (calendarDialog != null && calendarDialog.isShowing()) {
            calendarDialog.dismiss();
        }

        calendarDialog = new Dialog(this);
        calendarDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_attendance_calendar, null);
        calendarDialog.setContentView(view);

        if (calendarDialog.getWindow() != null) {
            calendarDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            calendarDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        Button btnClose = view.findViewById(R.id.btnCloseCalendar);
        CalendarView calendarView = view.findViewById(R.id.calendarViewAttendance);
        tvSelectedDateTitle = view.findViewById(R.id.tvSelectedDateTitle);
        tvSelectedDateSummary = view.findViewById(R.id.tvSelectedDateSummary);
        ListView lvLogs = view.findViewById(R.id.lvAttendanceLogs);
        tvEmptyAttendanceLogs = view.findViewById(R.id.tvEmptyAttendanceLogs);

        attendanceLogAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, attendanceLogDisplayList);
        lvLogs.setAdapter(attendanceLogAdapter);

        btnClose.setOnClickListener(v -> calendarDialog.dismiss());

        currentSelectedDateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        requestAttendanceLogsForDate(currentSelectedDateStr);

        calendarView.setOnDateChangeListener((cv, year, month, dayOfMonth) -> {
            currentSelectedDateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month + 1, dayOfMonth);
            requestAttendanceLogsForDate(currentSelectedDateStr);
        });

        calendarDialog.show();
    }

    private void requestAttendanceLogsForDate(String dateStr) {
        if (tvSelectedDateTitle != null) {
            tvSelectedDateTitle.setText(dateStr + " 考勤加分明细");
        }
        try {
            JSONObject req = new JSONObject();
            req.put("type", "get_attendance_logs");
            req.put("date", dateStr);
            GameSocketBridge.sendJson(req);
        } catch (JSONException ignored) {}
    }

    @Override
    public void onAttendanceLogsReceived(String date, JSONArray logs, JSONArray activeDates) {
        runOnUiThread(() -> {
            if (calendarDialog == null || !calendarDialog.isShowing()) return;

            attendanceLogDisplayList.clear();
            int totalScoreAdded = 0;
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

            if (logs != null && logs.length() > 0) {
                for (int i = 0; i < logs.length(); i++) {
                    JSONObject item = logs.optJSONObject(i);
                    if (item != null) {
                        String stu = item.optString("student_name", "未知");
                        int delta = item.optInt("delta", 1);
                        String reason = item.optString("reason", "课堂表现");
                        long ts = item.optLong("timestamp", System.currentTimeMillis());
                        totalScoreAdded += delta;

                        String sign = delta >= 0 ? ("+" + delta) : String.valueOf(delta);
                        String timeStr = timeFmt.format(new Date(ts));
                        String line = "[" + timeStr + "]  " + stu + "   " + sign + "分\n原因：" + reason;
                        attendanceLogDisplayList.add(line);
                    }
                }
            }

            if (tvSelectedDateTitle != null) {
                tvSelectedDateTitle.setText(date + " 考勤加分明细");
            }
            if (tvSelectedDateSummary != null) {
                int count = logs != null ? logs.length() : 0;
                String signTotal = totalScoreAdded >= 0 ? ("+" + totalScoreAdded) : String.valueOf(totalScoreAdded);
                tvSelectedDateSummary.setText("共 " + count + " 人次 (累计 " + signTotal + " 分)");
            }
            if (tvEmptyAttendanceLogs != null) {
                tvEmptyAttendanceLogs.setVisibility(attendanceLogDisplayList.isEmpty() ? View.VISIBLE : View.GONE);
            }
            if (attendanceLogAdapter != null) {
                attendanceLogAdapter.notifyDataSetChanged();
            }
        });
    }

    private void updateSortButtonsUI() {
        if (!isHonorRollMode) {
            btnSortDefault.setBackgroundColor(Color.parseColor("#1E88E5"));
            btnSortDefault.setTextColor(Color.WHITE);
            btnSortScore.setBackgroundColor(Color.TRANSPARENT);
            btnSortScore.setTextColor(Color.parseColor("#64748B"));
        } else {
            btnSortDefault.setBackgroundColor(Color.TRANSPARENT);
            btnSortDefault.setTextColor(Color.parseColor("#64748B"));
            btnSortScore.setBackgroundColor(Color.parseColor("#1E88E5"));
            btnSortScore.setTextColor(Color.WHITE);
        }
    }

    private void initData() {
        tvClassroomName.setText("班级：" + RosterDataBridge.getCachedClassroomTitle());
        displayRosterList = new ArrayList<>();
        rosterAdapter = new RosterAdapter(this, displayRosterList);

        rosterAdapter.setOnRosterActionListener(new RosterAdapter.OnRosterActionListener() {
            @Override
            public void onAwardScore(MainActivity.RosterUser user) {
                showAwardScoreDialog(user);
            }

            @Override
            public void onToggleMute(MainActivity.RosterUser user) {
                sendAdminManageCommand(user.name, "toggle_mute", null, null);
            }
        });

        lvRosterMembers.setAdapter(rosterAdapter);
        updateSortButtonsUI();
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
        List<MainActivity.RosterUser> source = RosterDataBridge.getCachedRoster();
        displayRosterList.clear();

        if (source != null) {
            if (isHonorRollMode) {
                for (MainActivity.RosterUser u : source) {
                    if (!"teacher".equalsIgnoreCase(u.role)) {
                        displayRosterList.add(u);
                    }
                }
                Collections.sort(displayRosterList, (u1, u2) -> {
                    if (u2.score != u1.score) {
                        return Integer.compare(u2.score, u1.score);
                    }
                    return u1.name.compareTo(u2.name);
                });
            } else {
                displayRosterList.addAll(source);
                Collections.sort(displayRosterList, (u1, u2) -> {
                    boolean t1 = "teacher".equalsIgnoreCase(u1.role);
                    boolean t2 = "teacher".equalsIgnoreCase(u2.role);
                    if (t1 != t2) return t1 ? -1 : 1;
                    if (u1.isOnline != u2.isOnline) return u1.isOnline ? -1 : 1;
                    return u1.name.compareTo(u2.name);
                });
            }
        }

        if (rosterAdapter != null) {
            rosterAdapter.setHonorRollMode(isHonorRollMode);
        }
        refreshSummaryHeader();
    }

    private void refreshSummaryHeader() {
        List<MainActivity.RosterUser> fullRoster = RosterDataBridge.getCachedRoster();
        int total = fullRoster != null ? fullRoster.size() : 0;
        int onlineCount = 0;
        if (fullRoster != null) {
            for (MainActivity.RosterUser u : fullRoster) {
                if (u.isOnline) onlineCount++;
            }
        }

        if (isHonorRollMode) {
            tvRosterSummary.setText("共 " + displayRosterList.size() + " 位学生参与评比");
        } else {
            tvRosterSummary.setText("共 " + total + " 人 · " + onlineCount + " 在线");
        }
    }

    private void showEditClassroomTitleDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入班级名称");
        input.setText(RosterDataBridge.getCachedClassroomTitle());

        new AlertDialog.Builder(this)
                .setTitle("修改班级名称")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
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

    @Override
    public void onRosterChanged(List<MainActivity.RosterUser> latestRoster) {
        runOnUiThread(this::applySortAndRefresh);
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
        String[] options = {
                "奖励加分",
                muteActionText,
                "变更角色身份 (设班长/学生)",
                "修改姓名",
                "移出班级 (删除该成员)"
        };

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
                    } else if (which == 4) {
                        showDeleteUserConfirmDialog(target);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAwardScoreDialog(MainActivity.RosterUser target) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 24, 60, 10);

        TextView tvScoreLabel = new TextView(this);
        tvScoreLabel.setText("奖励分值：");
        tvScoreLabel.setTextColor(Color.parseColor("#4B5563"));
        tvScoreLabel.setTextSize(13);
        layout.addView(tvScoreLabel);

        final String[] deltaPresets = new String[]{"+1 分", "+2 分", "+3 分", "+5 分", "自定义分值"};
        final String[] deltaValues = new String[]{"1", "2", "3", "5", ""};
        Spinner spDelta = new Spinner(this);
        ArrayAdapter<String> deltaAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, deltaPresets);
        spDelta.setAdapter(deltaAdapter);
        layout.addView(spDelta);

        final EditText etDelta = new EditText(this);
        etDelta.setHint("输入具体分值");
        etDelta.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etDelta.setVisibility(View.GONE);
        layout.addView(etDelta);

        spDelta.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == deltaPresets.length - 1) {
                    etDelta.setVisibility(View.VISIBLE);
                    etDelta.setText("");
                    etDelta.requestFocus();
                } else {
                    etDelta.setVisibility(View.GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        TextView tvReasonLabel = new TextView(this);
        tvReasonLabel.setText("加分说明：");
        tvReasonLabel.setTextColor(Color.parseColor("#4B5563"));
        tvReasonLabel.setTextSize(13);
        tvReasonLabel.setPadding(0, 20, 0, 0);
        layout.addView(tvReasonLabel);

        final String[] reasonPresets = new String[]{
                "积极回答问题",
                "课堂表现优异",
                "随堂练习全对",
                "小组研讨积极",
                "思维创新独特",
                "自定义说明"
        };
        Spinner spReason = new Spinner(this);
        ArrayAdapter<String> reasonAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, reasonPresets);
        spReason.setAdapter(reasonAdapter);
        layout.addView(spReason);

        final EditText etReason = new EditText(this);
        etReason.setHint("输入自定义加分说明");
        etReason.setVisibility(View.GONE);
        layout.addView(etReason);

        spReason.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == reasonPresets.length - 1) {
                    etReason.setVisibility(View.VISIBLE);
                    etReason.setText("");
                    etReason.requestFocus();
                } else {
                    etReason.setVisibility(View.GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        new AlertDialog.Builder(this)
                .setTitle("奖励学生：" + target.name)
                .setView(layout)
                .setPositiveButton("确认加分", (dialog, which) -> {
                    int selectedDeltaIdx = spDelta.getSelectedItemPosition();
                    int delta = 1;
                    if (selectedDeltaIdx == deltaPresets.length - 1) {
                        String deltaStr = etDelta.getText().toString().trim();
                        try {
                            delta = Integer.parseInt(deltaStr);
                        } catch (Exception ignored) {}
                    } else {
                        try {
                            delta = Integer.parseInt(deltaValues[selectedDeltaIdx]);
                        } catch (Exception ignored) {}
                    }

                    int selectedReasonIdx = spReason.getSelectedItemPosition();
                    String reason;
                    if (selectedReasonIdx == reasonPresets.length - 1) {
                        reason = etReason.getText().toString().trim();
                        if (TextUtils.isEmpty(reason)) reason = "课堂表现优异";
                    } else {
                        reason = reasonPresets[selectedReasonIdx];
                    }

                    try {
                        JSONObject json = new JSONObject();
                        json.put("type", "award_score");
                        json.put("target_student", target.name);
                        json.put("delta", delta);
                        json.put("reason", reason);
                        GameSocketBridge.sendJson(json);

                        target.score += delta;
                        applySortAndRefresh();
                        Toast.makeText(this, "已为 " + target.name + " +" + delta + "分（已记入考勤日历）", Toast.LENGTH_SHORT).show();
                    } catch (JSONException ignored) {}
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteUserConfirmDialog(MainActivity.RosterUser target) {
        new AlertDialog.Builder(this)
                .setTitle("移出成员确认")
                .setMessage("确定要将【" + target.name + "】从班级名册彻底删除吗？删除后该学生的登录记录与积分数据将一并清理。")
                .setPositiveButton("确认删除", (dialog, which) -> {
                    sendAdminManageCommand(target.name, "delete_user", null, null);
                    displayRosterList.remove(target);
                    applySortAndRefresh();
                    Toast.makeText(this, "已将 " + target.name + " 移出班级", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showChangeRoleSubDialog(MainActivity.RosterUser target) {
        String[] roles = {"student", "monitor"};
        String[] roleLabels = {"设为普通学生", "任命为班长 (可发布与管理班级事务)"};
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
        if (calendarDialog != null && calendarDialog.isShowing()) {
            calendarDialog.dismiss();
        }
        RosterDataBridge.clearListener();
    }
}