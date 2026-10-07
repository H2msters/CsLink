package cn.edu.hbuas.coursetest;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class CropAvatarActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "extra_image_uri";
    public static final String RESULT_CROPPED_BASE64 = "result_cropped_base64";

    private CropImageView cropImageView;
    private Button btnCancelCrop;
    private Button btnConfirmCrop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop_avatar);

        View cropLayout = findViewById(R.id.cropLayout);
        if (cropLayout != null) {
            ViewCompat.setOnApplyWindowInsetsListener(cropLayout, (v, insets) -> {
                Insets systemBars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        cropImageView = findViewById(R.id.cropImageView);
        btnCancelCrop = findViewById(R.id.btnCancelCrop);
        btnConfirmCrop = findViewById(R.id.btnConfirmCrop);

        String uriString = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriString == null) {
            finish();
            return;
        }

        loadSourceBitmap(Uri.parse(uriString));

        btnCancelCrop.setOnClickListener(v -> finish());

        btnConfirmCrop.setOnClickListener(v -> {
            Bitmap cropped = cropImageView.getCroppedBitmap();
            if (cropped != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                cropped.compress(Bitmap.CompressFormat.JPEG, 80, out);
                String base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);

                Intent resultIntent = new Intent();
                resultIntent.putExtra(RESULT_CROPPED_BASE64, base64);
                setResult(RESULT_OK, resultIntent);
                finish();
            }
        });
    }

    private void loadSourceBitmap(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, options);
            if (in != null) in.close();

            int maxDim = 1080;
            int scale = 1;
            while (options.outWidth / scale > maxDim || options.outHeight / scale > maxDim) {
                scale *= 2;
            }

            options.inJustDecodeBounds = false;
            options.inSampleSize = scale;

            in = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (in != null) in.close();

            if (bitmap != null) {
                cropImageView.setSourceBitmap(bitmap);
            }
        } catch (Exception e) {
            finish();
        }
    }
}