package cn.edu.hbuas.coursetest;

import android.content.Context;
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
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

public class RosterAdapter extends ArrayAdapter<MainActivity.RosterUser> {

    private final Context context;
    private final List<MainActivity.RosterUser> users;

    public RosterAdapter(@NonNull Context context, @NonNull List<MainActivity.RosterUser> users) {
        super(context, 0, users);
        this.context = context;
        this.users = users;
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
            holder.tvMuteBadge = convertView.findViewById(R.id.tvRosterMuteBadge);
            holder.tvRoleBadge = convertView.findViewById(R.id.tvRosterRoleBadge);
            holder.tvStatusBadge = convertView.findViewById(R.id.tvRosterStatusBadge);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        MainActivity.RosterUser user = users.get(position);
        if (user == null) return convertView;

        holder.tvName.setText(user.name);

        // 1. 头像渲染
        if (!TextUtils.isEmpty(user.avatar)) {
            try {
                byte[] decodedBytes = Base64.decode(user.avatar, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap != null) {
                    holder.ivAvatar.setImageBitmap(bitmap);
                } else {
                    holder.ivAvatar.setImageResource(R.mipmap.ic_launcher);
                }
            } catch (Exception e) {
                holder.ivAvatar.setImageResource(R.mipmap.ic_launcher);
            }
        } else {
            holder.ivAvatar.setImageResource(R.mipmap.ic_launcher);
        }

        // 2. 禁言标记
        holder.tvMuteBadge.setVisibility(user.isMuted ? View.VISIBLE : View.GONE);

        // 3. 班级身份徽章（普通学生隐藏，仅教师与班长显示专属色块）
        if ("teacher".equalsIgnoreCase(user.role)) {
            holder.tvRoleBadge.setVisibility(View.VISIBLE);
            holder.tvRoleBadge.setText("教师");
            GradientDrawable teacherBadge = new GradientDrawable();
            teacherBadge.setCornerRadius(10f);
            teacherBadge.setColor(Color.parseColor("#FEF3C7")); // 金黄底
            holder.tvRoleBadge.setTextColor(Color.parseColor("#B45309")); // 深棕字
            holder.tvRoleBadge.setBackground(teacherBadge);
        } else if ("monitor".equalsIgnoreCase(user.role)) {
            holder.tvRoleBadge.setVisibility(View.VISIBLE);
            holder.tvRoleBadge.setText("班长");
            GradientDrawable monitorBadge = new GradientDrawable();
            monitorBadge.setCornerRadius(10f);
            monitorBadge.setColor(Color.parseColor("#EDE9FE")); // 浅紫底
            holder.tvRoleBadge.setTextColor(Color.parseColor("#6D28D9")); // 紫色字
            holder.tvRoleBadge.setBackground(monitorBadge);
        } else {
            // 普通学生不占用位置，直接隐藏
            holder.tvRoleBadge.setVisibility(View.GONE);
        }

        // 4. 在线/离线状态色块（全员保留）
        GradientDrawable statusDrawable = new GradientDrawable();
        statusDrawable.setCornerRadius(10f);

        if (user.isOnline) {
            holder.tvStatusBadge.setText("在线");
            statusDrawable.setColor(Color.parseColor("#DCFCE7")); // 浅绿底
            holder.tvStatusBadge.setTextColor(Color.parseColor("#15803D")); // 绿色字
        } else {
            holder.tvStatusBadge.setText("离线");
            statusDrawable.setColor(Color.parseColor("#F3F4F6")); // 浅灰底
            holder.tvStatusBadge.setTextColor(Color.parseColor("#9CA3AF")); // 灰色字
        }
        holder.tvStatusBadge.setBackground(statusDrawable);

        return convertView;
    }

    private static class ViewHolder {
        ImageView ivAvatar;
        ..................
        TextView tvName;
        TextView tvMuteBadge;
        TextView tvRoleBadge;
        TextView tvStatusBadge;
    }
}