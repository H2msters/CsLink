package cn.edu.hbuas.coursetest;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class CameraCaptureActivity extends AppCompatActivity {

    public static final String RESULT_IMAGE_PATH = "result_captured_image_path";

    private PreviewView previewView;
    private ImageView ivCapturedPreview;
    private ImageButton btnCameraBack;
    private ImageButton btnTorchToggle;
    private Button btnShutter;
    private Button btnRetake;
    private Button btnConfirmSend;
    private RelativeLayout layoutCameraBottomBar;
    private LinearLayout layoutConfirmControls;

    private Camera camera;
    private ImageCapture imageCapture;
    private boolean isTorchOn = false;
    private File currentPhotoFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_capture);

        initViews();
        startCamera();
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        ivCapturedPreview = findViewById(R.id.ivCapturedPreview);
        btnCameraBack = findViewById(R.id.btnCameraBack);
        btnTorchToggle = findViewById(R.id.btnTorchToggle);
        btnShutter = findViewById(R.id.btnShutter);
        btnRetake = findViewById(R.id.btnRetake);
        btnConfirmSend = findViewById(R.id.btnConfirmSend);
        layoutCameraBottomBar = findViewById(R.id.layoutCameraBottomBar);
        layoutConfirmControls = findViewById(R.id.layoutConfirmControls);

        btnCameraBack.setOnClickListener(v -> finish());
        btnTorchToggle.setOnClickListener(v -> toggleTorch());
        btnShutter.setOnClickListener(v -> takePhoto());

        btnRetake.setOnClickListener(v -> {
            ivCapturedPreview.setVisibility(View.GONE);
            previewView.setVisibility(View.VISIBLE);
            layoutConfirmControls.setVisibility(View.GONE);
            layoutCameraBottomBar.setVisibility(View.VISIBLE);
            if (currentPhotoFile != null && currentPhotoFile.exists()) {
                currentPhotoFile.delete();
            }
        });

        btnConfirmSend.setOnClickListener(v -> {
            if (currentPhotoFile != null && currentPhotoFile.exists()) {
                Intent result = new Intent();
                result.putExtra(RESULT_IMAGE_PATH, currentPhotoFile.getAbsolutePath());
                setResult(RESULT_OK, result);
                finish();
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (Exception e) {
                Toast.makeText(this, "相机启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleTorch() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isTorchOn = !isTorchOn;
            camera.getCameraControl().enableTorch(isTorchOn);
            btnTorchToggle.setColorFilter(isTorchOn ? 0xFFEAB308 : 0xFFFFFFFF);
            Toast.makeText(this, isTorchOn ? "手电筒已开启" : "手电筒已关闭", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "当前设备无可用闪光灯", Toast.LENGTH_SHORT).show();
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        File outputDir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
        String fileName = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis()) + ".jpg";
        currentPhotoFile = new File(outputDir, fileName);

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(currentPhotoFile).build();

        btnShutter.setEnabled(false);

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                btnShutter.setEnabled(true);

                if (isTorchOn && camera != null) {
                    camera.getCameraControl().enableTorch(false);
                    isTorchOn = false;
                    btnTorchToggle.setColorFilter(0xFFFFFFFF);
                }

                Bitmap bitmap = BitmapFactory.decodeFile(currentPhotoFile.getAbsolutePath());
                ivCapturedPreview.setImageBitmap(bitmap);
                ivCapturedPreview.setVisibility(View.VISIBLE);
                previewView.setVisibility(View.GONE);

                layoutCameraBottomBar.setVisibility(View.GONE);
                layoutConfirmControls.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                btnShutter.setEnabled(true);
                Toast.makeText(CameraCaptureActivity.this, "拍摄失败：" + exception.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isTorchOn && camera != null) {
            camera.getCameraControl().enableTorch(false);
        }
    }
}