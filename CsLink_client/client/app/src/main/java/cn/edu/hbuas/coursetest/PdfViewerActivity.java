package cn.edu.hbuas.coursetest;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;

public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PDF_PATH = "extra_pdf_path";
    public static final String EXTRA_BOOK_TITLE = "extra_book_title";

    private TextView tvViewerTitle;
    private TextView tvPageIndicator;
    private Button btnViewerBack;
    private RecyclerView rvPdfPages;

    private ParcelFileDescriptor fileDescriptor;
    private PdfRenderer pdfRenderer;
    private int pageCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        View root = findViewById(R.id.pdfViewerRoot);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets systemBars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        String pdfPath = getIntent().getStringExtra(EXTRA_PDF_PATH);
        String bookTitle = getIntent().getStringExtra(EXTRA_BOOK_TITLE);

        tvViewerTitle = findViewById(R.id.tvViewerTitle);
        tvPageIndicator = findViewById(R.id.tvPageIndicator);
        btnViewerBack = findViewById(R.id.btnViewerBack);
        rvPdfPages = findViewById(R.id.rvPdfPages);

        tvViewerTitle.setText(bookTitle != null ? bookTitle : "PDF阅读");
        btnViewerBack.setOnClickListener(v -> finish());

        try {
            File file = new File(pdfPath);
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(fileDescriptor);
            pageCount = pdfRenderer.getPageCount();
            tvPageIndicator.setText("共 " + pageCount + " 页");

            LinearLayoutManager layoutManager = new LinearLayoutManager(this);
            rvPdfPages.setLayoutManager(layoutManager);
            rvPdfPages.setAdapter(new PdfPageAdapter());

            rvPdfPages.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    int firstVisible = layoutManager.findFirstVisibleItemPosition();
                    tvPageIndicator.setText((firstVisible + 1) + " / " + pageCount);
                }
            });

        } catch (Exception e) {
            Toast.makeText(this, "打开 PDF 失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.PageViewHolder> {
        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(parent.getContext());
            iv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            iv.setAdjustViewBounds(true);
            iv.setPadding(0, 0, 0, 16);
            return new PageViewHolder(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            try {
                PdfRenderer.Page page = pdfRenderer.openPage(position);
                int width = getResources().getDisplayMetrics().widthPixels - 32;
                int height = (int) ((float) width / page.getWidth() * page.getHeight());
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                holder.imageView.setImageBitmap(bitmap);
                page.close();
            } catch (Exception ignored) {}
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            ImageView imageView;
            public PageViewHolder(@NonNull View itemView) {
                super(itemView);
                this.imageView = (ImageView) itemView;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (pdfRenderer != null) pdfRenderer.close();
            if (fileDescriptor != null) fileDescriptor.close();
        } catch (Exception ignored) {}
    }
}