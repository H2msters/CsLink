package cn.edu.hbuas.coursetest;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class AffairsAdapter extends RecyclerView.Adapter<AffairsAdapter.AffairViewHolder> {

    public interface OnAffairActionListener {
        void onConfirm(String affairId);
        void onViewConfirmedList(AffairItem item);
    }

    private final List<AffairItem> affairList;
    private final String currentUserName;
    private final String currentUserRole;
    private final OnAffairActionListener actionListener;

    public AffairsAdapter(List<AffairItem> affairList, String currentUserName,
                          String currentUserRole, OnAffairActionListener actionListener) {
        this.affairList = affairList;
        this.currentUserName = currentUserName;
        this.currentUserRole = currentUserRole;
        this.actionListener = actionListener;
    }

    @NonNull
    @Override
    public AffairViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_affair, parent, false);
        return new AffairViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AffairViewHolder holder, int position) {
        AffairItem item = affairList.get(position);

        holder.tvAffairTitle.setText(item.getTitle());
        holder.tvAffairContent.setText(item.getContent());
        holder.tvAffairMeta.setText("发布人：" + item.getPublisher() + " · " + item.getFormattedTime());

        // 1. 类型标签颜色区分（作业为橙黄，通知为深蓝）
        if ("homework".equalsIgnoreCase(item.getType())) {
            holder.tvAffairTypeBadge.setText("随堂作业");
            holder.tvAffairTypeBadge.setBackgroundColor(Color.parseColor("#FEF3C7")); // 浅金黄
            holder.tvAffairTypeBadge.setTextColor(Color.parseColor("#B45309"));       // 深棕橙
        } else {
            holder.tvAffairTypeBadge.setText("班级通知");
            holder.tvAffairTypeBadge.setBackgroundColor(Color.parseColor("#DBEAFE")); // 浅蓝
            holder.tvAffairTypeBadge.setTextColor(Color.parseColor("#1E40AF"));       // 深蓝
        }

        // 2. 统计数字展示
        holder.tvConfirmedStats.setText("已确认人数：" + item.getConfirmedCount() + " 人");

        // 3. 身份差异化操作逻辑
        boolean hasConfirmed = item.isConfirmedBy(currentUserName);

        if ("teacher".equalsIgnoreCase(currentUserRole)) {
            // 教师端：按钮切换为查看名单入口
            holder.btnConfirmAffair.setText("查看确认名单 (" + item.getConfirmedCount() + ")");
            holder.btnConfirmAffair.setEnabled(true);
            holder.btnConfirmAffair.setBackgroundColor(Color.parseColor("#4B5563")); // 灰黑质感
            holder.btnConfirmAffair.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onViewConfirmedList(item);
                }
            });
        } else {
            // 学生端：处理确认回执状态
            if (hasConfirmed) {
                holder.btnConfirmAffair.setText("✓ 已确认");
                holder.btnConfirmAffair.setEnabled(false);
                holder.btnConfirmAffair.setBackgroundColor(Color.parseColor("#9CA3AF")); // 灰色不可点击
            } else {
                holder.btnConfirmAffair.setText("我已确认");
                holder.btnConfirmAffair.setEnabled(true);
                holder.btnConfirmAffair.setBackgroundColor(Color.parseColor("#1E88E5")); // 亮蓝可点击
                holder.btnConfirmAffair.setOnClickListener(v -> {
                    if (actionListener != null) {
                        actionListener.onConfirm(item.getAffairId());
                    }
                });
            }
        }
    }

    @Override
    public int getItemCount() {
        return affairList.size();
    }

    static class AffairViewHolder extends RecyclerView.ViewHolder {
        TextView tvAffairTypeBadge;
        TextView tvAffairTitle;
        TextView tvAffairMeta;
        TextView tvAffairContent;
        TextView tvConfirmedStats;
        Button btnConfirmAffair;

        public AffairViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAffairTypeBadge = itemView.findViewById(R.id.tvAffairTypeBadge);
            tvAffairTitle = itemView.findViewById(R.id.tvAffairTitle);
            tvAffairMeta = itemView.findViewById(R.id.tvAffairMeta);
            tvAffairContent = itemView.findViewById(R.id.tvAffairContent);
            tvConfirmedStats = itemView.findViewById(R.id.tvConfirmedStats);
            btnConfirmAffair = itemView.findViewById(R.id.btnConfirmAffair);
        }
    }
}