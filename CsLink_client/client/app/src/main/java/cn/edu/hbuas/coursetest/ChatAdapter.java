package cn.edu.hbuas.coursetest;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Map;
import cn.edu.hbuas.coursetest.R;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private final List<ChatMessage> messageList;
    private final Map<String, String> avatarMap;

    public ChatAdapter(List<ChatMessage> messageList, Map<String, String> avatarMap) {
        this.messageList = messageList;
        this.avatarMap = avatarMap;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        ChatMessage message = messageList.get(position);

        if ("system".equals(message.getType())) {
            holder.tvSystemNotice.setVisibility(View.VISIBLE);
            holder.layoutOtherMsg.setVisibility(View.GONE);
            holder.layoutMyMsg.setVisibility(View.GONE);
            holder.tvSystemNotice.setText("[系统 " + message.getFormattedTime() + "] " + message.getContent());
        } else {
            String latestAvatar = avatarMap.get(message.getSender());
            if (TextUtils.isEmpty(latestAvatar)) {
                latestAvatar = message.getAvatar();
            }

            if (message.isMe()) {
                holder.tvSystemNotice.setVisibility(View.GONE);
                holder.layoutOtherMsg.setVisibility(View.GONE);
                holder.layoutMyMsg.setVisibility(View.VISIBLE);

                holder.tvMySender.setText(message.getSender());
                holder.tvMyTime.setText(message.getFormattedTime());
                loadAvatar(holder.ivMyAvatar, latestAvatar);

                if ("image".equals(message.getType())) {
                    holder.tvMyContent.setVisibility(View.GONE);
                    holder.ivMyImage.setVisibility(View.VISIBLE);
                    loadImage(holder.ivMyImage, message.getContent());
                    holder.ivMyImage.setOnClickListener(v -> showFullImageDialog(v.getContext(), message.getContent()));
                } else {
                    holder.tvMyContent.setVisibility(View.VISIBLE);
                    holder.ivMyImage.setVisibility(View.GONE);
                    holder.tvMyContent.setText(message.getContent());
                }
            } else {
                holder.tvSystemNotice.setVisibility(View.GONE);
                holder.layoutOtherMsg.setVisibility(View.VISIBLE);
                holder.layoutMyMsg.setVisibility(View.GONE);

                holder.tvOtherSender.setText(message.getSender());
                holder.tvOtherTime.setText(message.getFormattedTime());
                loadAvatar(holder.ivOtherAvatar, latestAvatar);

                if ("image".equals(message.getType())) {
                    holder.tvOtherContent.setVisibility(View.GONE);
                    holder.ivOtherImage.setVisibility(View.VISIBLE);
                    loadImage(holder.ivOtherImage, message.getContent());
                    holder.ivOtherImage.setOnClickListener(v -> showFullImageDialog(v.getContext(), message.getContent()));
                } else {
                    holder.tvOtherContent.setVisibility(View.VISIBLE);
                    holder.ivOtherImage.setVisibility(View.GONE);
                    holder.tvOtherContent.setText(message.getContent());
                }
            }
        }
    }

    private void loadAvatar(ImageView imageView, String avatarBase64) {
        if (!TextUtils.isEmpty(avatarBase64)) {
            try {
                byte[] decodedBytes = Base64.decode(avatarBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                    return;
                }
            } catch (Exception ignored) {}
        }
        imageView.setImageResource(R.mipmap.ic_launcher_round);
    }

    private void loadImage(ImageView imageView, String imageBase64) {
        if (!TextUtils.isEmpty(imageBase64)) {
            try {
                byte[] decodedBytes = Base64.decode(imageBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                    return;
                }
            } catch (Exception ignored) {}
        }
        imageView.setImageResource(R.mipmap.ic_launcher);
    }

    private void showFullImageDialog(Context context, String base64) {
        if (TextUtils.isEmpty(base64)) return;

        try {
            byte[] decodedBytes = Base64.decode(base64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
            if (bitmap == null) return;

            Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            ImageView fullImageView = new ImageView(context);
            fullImageView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            fullImageView.setBackgroundColor(Color.BLACK);
            fullImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fullImageView.setImageBitmap(bitmap);
            fullImageView.setOnClickListener(v -> dialog.dismiss());

            dialog.setContentView(fullImageView);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            }
            dialog.show();
        } catch (Exception ignored) {}
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        TextView tvSystemNotice;

        LinearLayout layoutOtherMsg;
        ImageView ivOtherAvatar;
        TextView tvOtherSender;
        TextView tvOtherTime;
        TextView tvOtherContent;
        ImageView ivOtherImage;

        LinearLayout layoutMyMsg;
        ImageView ivMyAvatar;
        TextView tvMySender;
        TextView tvMyTime;
        TextView tvMyContent;
        ImageView ivMyImage;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSystemNotice = itemView.findViewById(R.id.tvSystemNotice);

            layoutOtherMsg = itemView.findViewById(R.id.layoutOtherMsg);
            ivOtherAvatar = itemView.findViewById(R.id.ivOtherAvatar);
            tvOtherSender = itemView.findViewById(R.id.tvOtherSender);
            tvOtherTime = itemView.findViewById(R.id.tvOtherTime);
            tvOtherContent = itemView.findViewById(R.id.tvOtherContent);
            ivOtherImage = itemView.findViewById(R.id.ivOtherImage);

            layoutMyMsg = itemView.findViewById(R.id.layoutMyMsg);
            ivMyAvatar = itemView.findViewById(R.id.ivMyAvatar);
            tvMySender = itemView.findViewById(R.id.tvMySender);
            tvMyTime = itemView.findViewById(R.id.tvMyTime);
            tvMyContent = itemView.findViewById(R.id.tvMyContent);
            ivMyImage = itemView.findViewById(R.id.ivMyImage);
        }
    }
}