package cn.edu.hbuas.coursetest;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class InteractiveDialogManager {

    public interface OnDialogActionListener {
        void onSubmitVote(String option);
        void onStopVote();
        void onSubmitBuzz();
        void onStopBuzz();
    }

    private final Activity activity;
    private final OnDialogActionListener listener;

    // 1. 随堂投票组件
    private Dialog currentVoteDialog;
    private TextView tvVoteTitle;
    private TextView tvVoteStatusHint;
    private LinearLayout layoutVoteButtons;
    private Button btnVoteA, btnVoteB, btnVoteC, btnVoteD;
    private LinearLayout layoutVoteStats;
    private TextView tvVoteTotalCount, tvStatA, tvStatB, tvStatC, tvStatD;
    private ProgressBar pbStatA, pbStatB, pbStatC, pbStatD;
    private Button btnStopVoteTeacher, btnCloseVoteDialog;
    private String mySelectedVoteOption = "";

    // 2. 随堂抢答组件
    private Dialog currentBuzzerDialog;
    private TextView tvBuzzerStatus;
    private Button btnBuzzAction;
    private LinearLayout layoutBuzzResult;
    private TextView tvWinnerName;
    private TextView tvCostTime;

    // 3. 随机点名组件
    private Dialog currentPickDialog;

    // 4. 全屏图片预览组件
    private Dialog currentImagePreviewDialog;

    public InteractiveDialogManager(Activity activity, OnDialogActionListener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    // =========================================================================
    // 随堂讨论图片全屏放大预览
    // =========================================================================

    public void showImagePreviewDialog(String imageBase64) {
        if (activity.isFinishing() || activity.isDestroyed() || TextUtils.isEmpty(imageBase64)) {
            return;
        }

        Bitmap decodedBitmap = null;
        try {
            byte[] bytes = Base64.decode(imageBase64, Base64.DEFAULT);
            decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {}

        if (decodedBitmap == null) return;

        if (currentImagePreviewDialog != null && currentImagePreviewDialog.isShowing()) {
            currentImagePreviewDialog.dismiss();
        }

        currentImagePreviewDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        currentImagePreviewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout rootContainer = new FrameLayout(activity);
        rootContainer.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootContainer.setBackgroundColor(Color.parseColor("#E6000000"));

        ImageView fullImageView = new ImageView(activity);
        FrameLayout.LayoutParams imgParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        imgParams.setMargins(24, 64, 24, 160);
        fullImageView.setLayoutParams(imgParams);
        fullImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullImageView.setImageBitmap(decodedBitmap);

        Button btnClosePreview = new Button(activity);
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        btnParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        btnParams.bottomMargin = 64;
        btnClosePreview.setLayoutParams(btnParams);
        btnClosePreview.setText("关闭大图预览");
        btnClosePreview.setTextColor(Color.WHITE);
        btnClosePreview.setTextSize(13);
        btnClosePreview.setPadding(48, 16, 48, 16);
        ViewCompat.setBackgroundTintList(btnClosePreview, ColorStateList.valueOf(Color.parseColor("#374151")));

        View.OnClickListener dismissAction = v -> currentImagePreviewDialog.dismiss();
        rootContainer.setOnClickListener(dismissAction);
        fullImageView.setOnClickListener(dismissAction);
        btnClosePreview.setOnClickListener(dismissAction);

        rootContainer.addView(fullImageView);
        rootContainer.addView(btnClosePreview);

        currentImagePreviewDialog.setContentView(rootContainer);
        if (currentImagePreviewDialog.getWindow() != null) {
            currentImagePreviewDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            currentImagePreviewDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
        }
        currentImagePreviewDialog.show();
    }

    // =========================================================================
    // 随堂单选投票
    // =========================================================================

    public boolean isVoteDialogShowing() {
        return currentVoteDialog != null && currentVoteDialog.isShowing();
    }

    public void showVoteWindow(JSONObject voteData, String myRole, String myNickname) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        if (currentVoteDialog == null || !currentVoteDialog.isShowing()) {
            currentVoteDialog = new Dialog(activity);
            currentVoteDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            View view = LayoutInflater.from(activity).inflate(R.layout.dialog_vote, null);
            currentVoteDialog.setContentView(view);

            if (currentVoteDialog.getWindow() != null) {
                currentVoteDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                currentVoteDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            currentVoteDialog.setCancelable(false);

            layoutVoteButtons = view.findViewById(R.id.layoutVoteButtons);
            tvVoteTitle = view.findViewById(R.id.tvVoteTitle);
            tvVoteStatusHint = view.findViewById(R.id.tvVoteStatusHint);
            btnVoteA = view.findViewById(R.id.btnVoteA);
            btnVoteB = view.findViewById(R.id.btnVoteB);
            btnVoteC = view.findViewById(R.id.btnVoteC);
            btnVoteD = view.findViewById(R.id.btnVoteD);
            layoutVoteStats = view.findViewById(R.id.layoutVoteStats);
            tvVoteTotalCount = view.findViewById(R.id.tvVoteTotalCount);
            tvStatA = view.findViewById(R.id.tvStatA);
            tvStatB = view.findViewById(R.id.tvStatB);
            tvStatC = view.findViewById(R.id.tvStatC);
            tvStatD = view.findViewById(R.id.tvStatD);
            pbStatA = view.findViewById(R.id.pbStatA);
            pbStatB = view.findViewById(R.id.pbStatB);
            pbStatC = view.findViewById(R.id.pbStatC);
            pbStatD = view.findViewById(R.id.pbStatD);
            btnStopVoteTeacher = view.findViewById(R.id.btnStopVoteTeacher);
            btnCloseVoteDialog = view.findViewById(R.id.btnCloseVoteDialog);

            btnVoteA.setOnClickListener(v -> handleVoteOptionClick("A", myRole));
            btnVoteB.setOnClickListener(v -> handleVoteOptionClick("B", myRole));
            btnVoteC.setOnClickListener(v -> handleVoteOptionClick("C", myRole));
            btnVoteD.setOnClickListener(v -> handleVoteOptionClick("D", myRole));

            btnStopVoteTeacher.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStopVote();
                }
            });

            btnCloseVoteDialog.setOnClickListener(v -> currentVoteDialog.dismiss());
            currentVoteDialog.show();
            triggerHapticFeedback(100);
        }

        renderVoteState(voteData, myRole, myNickname);
    }

    private void handleVoteOptionClick(String option, String myRole) {
        if ("teacher".equalsIgnoreCase(myRole)) return;

        mySelectedVoteOption = option;
        triggerHapticFeedback(80);
        updateVoteOptionButtonsVisual();

        if (listener != null) {
            listener.onSubmitVote(option);
        }
    }

    public void renderVoteState(JSONObject voteData, String myRole, String myNickname) {
        if (voteData == null) return;

        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        boolean isActive = voteData.optBoolean("is_active", false);
        String title = voteData.optString("title", "随堂单选测验");
        int total = voteData.optInt("total", 0);
        JSONObject counts = voteData.optJSONObject("counts");

        int countA = counts != null ? counts.optInt("A", 0) : 0;
        int countB = counts != null ? counts.optInt("B", 0) : 0;
        int countC = counts != null ? counts.optInt("C", 0) : 0;
        int countD = counts != null ? counts.optInt("D", 0) : 0;

        int percentA = total > 0 ? (countA * 100 / total) : 0;
        int percentB = total > 0 ? (countB * 100 / total) : 0;
        int percentC = total > 0 ? (countC * 100 / total) : 0;
        int percentD = total > 0 ? (countD * 100 / total) : 0;

        if (tvVoteTitle != null) tvVoteTitle.setText(title);

        JSONObject voters = voteData.optJSONObject("voters");
        if (voters != null && !TextUtils.isEmpty(myNickname) && voters.has(myNickname)) {
            mySelectedVoteOption = voters.optString(myNickname, "");
        }

        if (layoutVoteButtons != null) {
            layoutVoteButtons.setVisibility(isTeacher ? View.GONE : View.VISIBLE);
        }

        if (layoutVoteStats != null) {
            boolean shouldShowStats = isTeacher || (!isActive);
            layoutVoteStats.setVisibility(shouldShowStats ? View.VISIBLE : View.GONE);
        }

        if (tvVoteStatusHint != null) {
            if (isTeacher) {
                tvVoteStatusHint.setText(isActive
                        ? "教师监票视角：实时监看全班作答进度 (教师不参与答题)"
                        : "本次投票已截止，最终统计结果如下：");
            } else {
                if (isActive) {
                    tvVoteStatusHint.setText(TextUtils.isEmpty(mySelectedVoteOption)
                            ? "测验进行中：请点击选项作答 (统计结果将在教师公布后展示)"
                            : "已提交选项 " + mySelectedVoteOption + " (点击其他选项可改选)，等待教师公布结果");
                } else {
                    tvVoteStatusHint.setText(TextUtils.isEmpty(mySelectedVoteOption)
                            ? "本次测验已截止 (你未参与作答)，公布统计结果如下："
                            : "本次测验已截止，你的作答是【" + mySelectedVoteOption + "】，统计结果如下：");
                }
            }
        }

        if (tvVoteTotalCount != null) {
            tvVoteTotalCount.setText("统计结果 (共 " + total + " 人作答)");
        }
        if (tvStatA != null) tvStatA.setText("选项 A：" + countA + " 票 (" + percentA + "%)");
        if (tvStatB != null) tvStatB.setText("选项 B：" + countB + " 票 (" + percentB + "%)");
        if (tvStatC != null) tvStatC.setText("选项 C：" + countC + " 票 (" + percentC + "%)");
        if (tvStatD != null) tvStatD.setText("选项 D：" + countD + " 票 (" + percentD + "%)");

        if (pbStatA != null) pbStatA.setProgress(percentA);
        if (pbStatB != null) pbStatB.setProgress(percentB);
        if (pbStatC != null) pbStatC.setProgress(percentC);
        if (pbStatD != null) pbStatD.setProgress(percentD);

        boolean canClickOption = isActive && (!isTeacher);
        if (btnVoteA != null) btnVoteA.setEnabled(canClickOption);
        if (btnVoteB != null) btnVoteB.setEnabled(canClickOption);
        if (btnVoteC != null) btnVoteC.setEnabled(canClickOption);
        if (btnVoteD != null) btnVoteD.setEnabled(canClickOption);

        updateVoteOptionButtonsVisual();

        if (btnStopVoteTeacher != null) {
            btnStopVoteTeacher.setVisibility((isTeacher && isActive) ? View.VISIBLE : View.GONE);
        }
    }

    private void updateVoteOptionButtonsVisual() {
        int selectedBg = Color.parseColor("#15803D");
        int defaultBg = Color.parseColor("#E5E7EB");
        int selectedText = Color.WHITE;
        int defaultText = Color.parseColor("#1F2937");

        updateSingleVoteBtnVisual(btnVoteA, "A".equals(mySelectedVoteOption), selectedBg, defaultBg, selectedText, defaultText);
        updateSingleVoteBtnVisual(btnVoteB, "B".equals(mySelectedVoteOption), selectedBg, defaultBg, selectedText, defaultText);
        updateSingleVoteBtnVisual(btnVoteC, "C".equals(mySelectedVoteOption), selectedBg, defaultBg, selectedText, defaultText);
        updateSingleVoteBtnVisual(btnVoteD, "D".equals(mySelectedVoteOption), selectedBg, defaultBg, selectedText, defaultText);
    }

    private void updateSingleVoteBtnVisual(Button btn, boolean isSelected, int selBg, int defBg, int selTxt, int defTxt) {
        if (btn == null) return;
        ViewCompat.setBackgroundTintList(btn, ColorStateList.valueOf(isSelected ? selBg : defBg));
        btn.setTextColor(isSelected ? selTxt : defTxt);
    }

    // =========================================================================
    // 随堂即时抢答
    // =========================================================================

    public void showBuzzerWindow(String title, String myRole, boolean isActive, String winner, int costMs, String myNickname) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        if (currentBuzzerDialog != null && currentBuzzerDialog.isShowing()) {
            currentBuzzerDialog.dismiss();
        }

        currentBuzzerDialog = new Dialog(activity);
        currentBuzzerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_buzzer, null);
        currentBuzzerDialog.setContentView(dialogView);

        if (currentBuzzerDialog.getWindow() != null) {
            currentBuzzerDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            currentBuzzerDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        currentBuzzerDialog.setCancelable(false);

        TextView tvBuzzerTitle = dialogView.findViewById(R.id.tvBuzzerTitle);
        tvBuzzerStatus = dialogView.findViewById(R.id.tvBuzzerStatus);
        btnBuzzAction = dialogView.findViewById(R.id.btnBuzzAction);
        layoutBuzzResult = dialogView.findViewById(R.id.layoutBuzzResult);
        tvWinnerName = dialogView.findViewById(R.id.tvWinnerName);
        tvCostTime = dialogView.findViewById(R.id.tvCostTime);
        Button btnDismissBuzzer = dialogView.findViewById(R.id.btnDismissBuzzer);

        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        tvBuzzerTitle.setText(title);

        if (!isActive) {
            if (!TextUtils.isEmpty(winner)) {
                btnBuzzAction.setVisibility(View.GONE);
                layoutBuzzResult.setVisibility(View.VISIBLE);
                tvWinnerName.setText(winner);
                tvCostTime.setText("用时：" + costMs + " ms");
                tvBuzzerStatus.setText("本次抢答已结束，第一名：【" + winner + "】");
            } else {
                layoutBuzzResult.setVisibility(View.GONE);
                btnBuzzAction.setVisibility(View.VISIBLE);
                btnBuzzAction.setEnabled(false);
                btnBuzzAction.setText("已截止");
                btnBuzzAction.setTextSize(26);
                ViewCompat.setBackgroundTintList(btnBuzzAction, ColorStateList.valueOf(Color.parseColor("#94A3B8")));
                tvBuzzerStatus.setText("本次抢答已截止或无人抢答");
            }
            btnDismissBuzzer.setText("关闭窗口");
            btnDismissBuzzer.setOnClickListener(v -> currentBuzzerDialog.dismiss());
            currentBuzzerDialog.show();
            return;
        }

        layoutBuzzResult.setVisibility(View.GONE);
        btnBuzzAction.setVisibility(View.VISIBLE);

        if (isTeacher) {
            tvBuzzerStatus.setText("教师主持视角：抢答通道已开启，等待学生抢答");
            btnBuzzAction.setEnabled(false);
            btnBuzzAction.setText("等待\n抢答");
            btnBuzzAction.setTextSize(26);
            ViewCompat.setBackgroundTintList(btnBuzzAction, ColorStateList.valueOf(Color.parseColor("#94A3B8")));
            btnBuzzAction.setTextColor(Color.WHITE);
            btnDismissBuzzer.setText("提前终止本次抢答");
        } else {
            tvBuzzerStatus.setText("抢答进行中，点击下方按钮参与抢答");
            btnBuzzAction.setEnabled(true);
            btnBuzzAction.setText("抢！");
            btnBuzzAction.setTextSize(44);
            ViewCompat.setBackgroundTintList(btnBuzzAction, ColorStateList.valueOf(Color.parseColor("#EF4444")));
            btnBuzzAction.setTextColor(Color.WHITE);
            btnDismissBuzzer.setText("退出抢答");

            btnBuzzAction.setOnClickListener(v -> {
                triggerHapticFeedback(80);
                btnBuzzAction.setEnabled(false);
                btnBuzzAction.setText("已提交");
                tvBuzzerStatus.setText("正在裁决第一名...");

                if (listener != null) {
                    listener.onSubmitBuzz();
                }
            });
        }

        btnDismissBuzzer.setOnClickListener(v -> {
            if (isTeacher && listener != null) {
                listener.onStopBuzz();
            }
            if (currentBuzzerDialog != null) {
                currentBuzzerDialog.dismiss();
            }
        });

        triggerHapticFeedback(120);
        currentBuzzerDialog.show();
    }

    public void renderBuzzResult(String winner, int costMs, String myRole, String myNickname) {
        if (currentBuzzerDialog != null && currentBuzzerDialog.isShowing()) {
            if (btnBuzzAction != null) {
                btnBuzzAction.setVisibility(View.GONE);
            }
            if (layoutBuzzResult != null) {
                layoutBuzzResult.setVisibility(View.VISIBLE);
            }
            if (tvWinnerName != null) {
                tvWinnerName.setText(winner);
            }
            if (tvCostTime != null) {
                tvCostTime.setText("用时：" + costMs + " ms");
            }
            if (tvBuzzerStatus != null) {
                boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
                if (isTeacher) {
                    tvBuzzerStatus.setText("已决出胜者：【" + winner + "】同学率先抢到！");
                } else if (winner.equals(myNickname)) {
                    tvBuzzerStatus.setText("恭喜，你获得了本次抢答第一名");
                    triggerHapticFeedback(300);
                } else {
                    tvBuzzerStatus.setText("本次抢答第一名：" + winner);
                }
            }
        }
    }

    public void dismissBuzzer() {
        if (currentBuzzerDialog != null && currentBuzzerDialog.isShowing()) {
            currentBuzzerDialog.dismiss();
        }
    }

    // =========================================================================
    // 随机点名
    // =========================================================================

    public void showRandomPickAnimationDialog(String finalWinner, List<String> candidates, String myNickname) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        if (currentPickDialog != null && currentPickDialog.isShowing()) {
            currentPickDialog.dismiss();
        }

        currentPickDialog = new Dialog(activity);
        currentPickDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_random_pick, null);
        currentPickDialog.setContentView(view);

        if (currentPickDialog.getWindow() != null) {
            currentPickDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            currentPickDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvStatus = view.findViewById(R.id.tvPickStatus);
        TextView tvRollingName = view.findViewById(R.id.tvPickRollingName);
        Button btnClose = view.findViewById(R.id.btnClosePickDialog);
        btnClose.setEnabled(false);
        btnClose.setOnClickListener(v -> currentPickDialog.dismiss());

        currentPickDialog.show();

        final List<String> pool = (candidates != null && !candidates.isEmpty()) ? candidates : new ArrayList<>();
        if (pool.isEmpty()) pool.add(finalWinner);

        final Handler animHandler = new Handler(Looper.getMainLooper());
        final int totalSteps = 15;
        final int[] currentStep = {0};

        Runnable rollTask = new Runnable() {
            @Override
            public void run() {
                if (currentStep[0] < totalSteps) {
                    String tempName = pool.get(currentStep[0] % pool.size());
                    tvRollingName.setText(tempName);
                    tvRollingName.setTextColor(Color.parseColor("#4B5563"));
                    currentStep[0]++;
                    animHandler.postDelayed(this, 80);
                } else {
                    tvStatus.setText("抽中结果已揭晓，请以下同学回答：");
                    tvRollingName.setText(finalWinner);
                    tvRollingName.setTextColor(Color.parseColor("#6D28D9"));
                    btnClose.setEnabled(true);
                    if (finalWinner.equals(myNickname)) {
                        triggerHapticFeedback(250);
                    }
                }
            }
        };
        animHandler.post(rollTask);
    }

    public void dismissAll() {
        if (currentVoteDialog != null && currentVoteDialog.isShowing()) currentVoteDialog.dismiss();
        if (currentBuzzerDialog != null && currentBuzzerDialog.isShowing()) currentBuzzerDialog.dismiss();
        if (currentPickDialog != null && currentPickDialog.isShowing()) currentPickDialog.dismiss();
        if (currentImagePreviewDialog != null && currentImagePreviewDialog.isShowing()) currentImagePreviewDialog.dismiss();
    }

    private void triggerHapticFeedback(long ms) {
        try {
            Vibrator vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(ms);
                }
            }
        } catch (Exception ignored) {}
    }
}