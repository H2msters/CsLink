package cn.edu.hbuas.coursetest;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

public class RosterAdapter extends ArrayAdapter<MainActivity.RosterUser> {

    public interface OnRosterActionListener {
        void onAwardScore(MainActivity.RosterUser user);
        void onToggleMute(MainActivity.RosterUser user);
    }

    private final Context context;
    private final List<MainActivity.RosterUser> users;
    private final String myRole;
    private boolean isHonorRollMode = false;
    private OnRosterActionListener actionListener;

    public RosterAdapter(@NonNull Context context, @NonNull List<MainActivity.RosterUser> users) {
        super(context, 0, users);
        this.context = context;
        this.users = users;
        SharedPreferences sp = context.getSharedPreferences("CampusChatPrefs", Context.MODE_PRIVATE);
        this.myRole = sp.getString("user_role", "student");
    }

    public void setOnRosterActionListener(OnRosterActionListener listener) {
        this.actionListener = listener;
    }

    public void setHonorRollMode(boolean honorRollMode) {
        this.isHonorRollMode = honorRollMode;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_roster_user, parent, false);
            holder = new ViewHolder();
            holder.ivAvatar = convertView.findViewById(R.id.ivRosterAvatar);
            holder.tvName = convertView.findViewById(R.id.tvRosterName);
            holder.tvRoleBadge = convertView.findViewById(R.id.tvRosterRoleBadge);
            holder.tvStatus = convertView.findViewById(R.id.tvRosterStatus);
            holder.tvScore = convertView.findViewById(R.id.tvRosterScore);
            holder.btnAwardScore = convertView.findViewById(R.id.btnAwardScore);
            holder.btnRosterMuteAction = convertView.findViewById(R.id.btnRosterMuteAction);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        MainActivity.RosterUser user = users.get(position);
        if (user == null) return convertView;

        // 1. 姓名与光荣榜名次联动（去除冗长汉字前缀）
        boolean isTeacher = "teacher".equalsIgnoreCase(user.role);
        if (isHonorRollMode && !isTeacher) {
            holder.tvName.setText((position + 1) + ". " + user.name);
        } else {
            holder.tvName.setText(user.name);
        }

        // 2. 头像渲染
        if (!TextUtils.isEmpty(user.avatar)) {
            try {
                byte[] decodedBytes = Base64.decode(user.avatar, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap != null) {
                    holder.ivAvatar.setImageBitmap(bitmap);
                } else {
                    holder.ivAvatar.setImageResource(R.mipmap.ic_launcher_round);
                }
            } catch (Exception e) {
                holder.ivAvatar.setImageResource(R.mipmap.ic_launcher_round);
            }
        } else {
            holder.ivAvatar.setImageResource(R.mipmap.ic_launcher_round);
        }

        // 3. 角色身份徽章
        if (isTeacher) {
            holder.tvRoleBadge.setVisibility(View.VISIBLE);
            holder.tvRoleBadge.setText("教师");
            GradientDrawable teacherBadge = new GradientDrawable();
            teacherBadge.setCornerRadius(8f);
            teacherBadge.setColor(Color.parseColor("#FEF3C7"));
            holder.tvRoleBadge.setTextColor(Color.parseColor("#B45309"));
            holder.tvRoleBadge.setBackground(teacherBadge);
        } else if ("monitor".equalsIgnoreCase(user.role)) {
            holder.tvRoleBadge.setVisibility(View.VISIBLE);
            holder.tvRoleBadge.setText("班长");
            GradientDrawable monitorBadge = new GradientDrawable();
            monitorBadge.setCornerRadius(8f);
            monitorBadge.setColor(Color.parseColor("#EDE9FE"));
            holder.tvRoleBadge.setTextColor(Color.parseColor("#6D28D9"));
            holder.tvRoleBadge.setBackground(monitorBadge);
        } else {
            holder.tvRoleBadge.setVisibility(View.GONE);
        }

        // 4. 在线状态与禁言标识联动
        if (user.isMuted) {
            holder.tvStatus.setText((user.isOnline ? "在线" : "离线") + " · 已禁言");
            holder.tvStatus.setTextColor(Color.parseColor("#DC2626"));
        } else {
            holder.tvStatus.setText(user.isOnline ? "在线" : "离线");
            holder.tvStatus.setTextColor(user.isOnline ? Color.parseColor("#10B981") : Color.parseColor("#9CA3AF"));
        }

        // 5. 课堂积分显示
        if (isTeacher) {
            holder.tvScore.setVisibility(View.GONE);
        } else {
            holder.tvScore.setVisibility(View.VISIBLE);
            holder.tvScore.setText("积分: " + user.score);
        }

        // 6. 教师端行内快捷控制按钮
        boolean amITeacher = "teacher".equalsIgnoreCase(myRole);
        if (amITeacher && !isTeacher) {
            holder.btnAwardScore.setVisibility(View.VISIBLE);
            holder.btnRosterMuteAction.setVisibility(View.VISIBLE);

            // 防止按钮拦截整行 Item 的点击事件
            holder.btnAwardScore.setFocusable(false);
            holder.btnRosterMuteAction.setFocusable(false);

            holder.btnRosterMuteAction.setText(user.isMuted ? "解禁" : "禁言");
            holder.btnRosterMuteAction.setTextColor(user.isMuted ? Color.parseColor("#10B981") : Color.parseColor("#DC2626"));

            holder.btnAwardScore.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onAwardScore(user);
                }
            });

            holder.btnRosterMuteAction.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onToggleMute(user);
                }
            });
        } else {
            holder.btnAwardScore.setVisibility(View.GONE);
            holder.btnRosterMuteAction.setVisibility(View.GONE);
        }

        return convertView;
    }

    private static class ViewHolder {
        ImageView ivAvatar;
        TextView tvName;
        TextView tvRoleBadge;
        TextView tvStatus;
        TextView tvScore;
        Button btnAwardScore;
        Button btnRosterMuteAction;
    }
}