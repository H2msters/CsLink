package cn.edu.hbuas.coursetest;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Xml;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BookCornerActivity extends AppCompatActivity {

    private Button btnBack;
    private Button btnUploadBook;
    private TextView tvEmptyBooks;
    private RecyclerView rvBooks;

    private final List<BookItem> bookList = new ArrayList<>();
    private BookAdapter bookAdapter;

    private String serverIp = "192.168.137.1";
    private String myRole = "student";
    private String myNickname = "";
    private OkHttpClient httpClient;

    // 支持同时选择 PDF 与 Word (.docx / .doc) 文档
    private final ActivityResultLauncher<String[]> bookPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    showUploadDialog(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_corner);

        View root = findViewById(R.id.bookCornerRoot);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets systemBars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        SharedPreferences sp = getSharedPreferences("CampusChatPrefs", Context.MODE_PRIVATE);
        serverIp = sp.getString("server_ip", "192.168.137.1");
        myRole = sp.getString("user_role", "student");
        myNickname = sp.getString("user_nickname", "教师");

        httpClient = new OkHttpClient();

        initViews();
        loadBookList();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        btnUploadBook = findViewById(R.id.btnUploadBook);
        tvEmptyBooks = findViewById(R.id.tvEmptyBooks);
        rvBooks = findViewById(R.id.rvBooks);

        btnBack.setOnClickListener(v -> finish());

        boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
        btnUploadBook.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
        btnUploadBook.setOnClickListener(v -> bookPickerLauncher.launch(new String[]{
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword"
        }));

        bookAdapter = new BookAdapter();
        rvBooks.setLayoutManager(new LinearLayoutManager(this));
        rvBooks.setAdapter(bookAdapter);
    }

    private void loadBookList() {
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url("http://" + serverIp + ":8000/api/books/list")
                        .build();
                Response response = httpClient.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    String jsonStr = response.body().string();
                    JSONObject json = new JSONObject(jsonStr);
                    JSONArray arr = json.optJSONArray("books");

                    bookList.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject b = arr.optJSONObject(i);
                            if (b != null) {
                                bookList.add(new BookItem(
                                        b.optString("book_id"),
                                        b.optString("title"),
                                        b.optString("filename"),
                                        b.optLong("file_size"),
                                        b.optString("uploader"),
                                        b.optLong("timestamp")
                                ));
                            }
                        }
                    }

                    runOnUiThread(() -> {
                        bookAdapter.notifyDataSetChanged();
                        tvEmptyBooks.setVisibility(bookList.isEmpty() ? View.VISIBLE : View.GONE);
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void showUploadDialog(Uri uri) {
        String originalFileName = getFileNameFromUri(uri);
        String lowerName = originalFileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".pdf") && !lowerName.endsWith(".docx") && !lowerName.endsWith(".doc")) {
            Toast.makeText(this, "请选择 PDF 或 Word (.docx/.doc) 格式文件", Toast.LENGTH_SHORT).show();
            return;
        }

        String defaultTitle = originalFileName;
        int dotIdx = originalFileName.lastIndexOf('.');
        if (dotIdx > 0) {
            defaultTitle = originalFileName.substring(0, dotIdx);
        }

        // 定义一个 effectively final 的常量供 Lambda 表达式安全调用
        final String finalDefaultTitle = defaultTitle;
        final String finalOriginalFileName = originalFileName;

        final EditText input = new EditText(this);
        input.setHint("输入书籍或文档展示名称");
        input.setText(finalDefaultTitle);

        new AlertDialog.Builder(this)
                .setTitle("上传图书 / Word 文档")
                .setView(input)
                .setPositiveButton("开始上传", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    uploadBookFile(uri, TextUtils.isEmpty(title) ? finalDefaultTitle : title, finalOriginalFileName);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void uploadBookFile(Uri uri, String bookTitle, String originalFileName) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在上传文档到中枢...");
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(uri);
                if (inputStream == null) {
                    runOnUiThread(() -> {
                        progress.dismiss();
                        Toast.makeText(this, "无法读取所选文件", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = inputStream.read(buf)) != -1) {
                    byteBuffer.write(buf, 0, len);
                }
                inputStream.close();
                byte[] bytes = byteBuffer.toByteArray();

                // 保留原始文件真实扩展名 (.pdf / .docx / .doc)
                String ext = ".pdf";
                String lowerOrig = originalFileName.toLowerCase(Locale.ROOT);
                if (lowerOrig.endsWith(".docx")) {
                    ext = ".docx";
                } else if (lowerOrig.endsWith(".doc")) {
                    ext = ".doc";
                }

                String mimeType = "application/pdf";
                if (".docx".equals(ext)) {
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                } else if (".doc".equals(ext)) {
                    mimeType = "application/msword";
                }

                String uploadFileName = bookTitle + ext;

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("title", bookTitle)
                        .addFormDataPart("uploader", myNickname)
                        .addFormDataPart("file", uploadFileName,
                                RequestBody.create(MediaType.parse(mimeType), bytes))
                        .build();

                Request request = new Request.Builder()
                        .url("http://" + serverIp + ":8000/api/books/upload")
                        .post(requestBody)
                        .build();

                Response response = httpClient.newCall(request).execute();
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (response.isSuccessful()) {
                        Toast.makeText(this, "上传成功", Toast.LENGTH_SHORT).show();
                        loadBookList();
                    } else {
                        Toast.makeText(this, "上传失败（状态码: " + response.code() + "）", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "上传异常: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void openBook(BookItem book) {
        File cacheDir = new File(getExternalFilesDir(null), "books");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File localFile = new File(cacheDir, book.filename);

        if (localFile.exists() && localFile.length() > 0) {
            launchReader(localFile, book.title);
            return;
        }

        ProgressDialog downloadDialog = new ProgressDialog(this);
        downloadDialog.setMessage("首次打开，正在下载文档...");
        downloadDialog.setCancelable(false);
        downloadDialog.show();

        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url("http://" + serverIp + ":8000/api/books/file/" + book.filename)
                        .build();
                Response response = httpClient.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    InputStream is = response.body().byteStream();
                    FileOutputStream fos = new FileOutputStream(localFile);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                    fos.close();
                    is.close();

                    runOnUiThread(() -> {
                        downloadDialog.dismiss();
                        launchReader(localFile, book.title);
                    });
                } else {
                    runOnUiThread(() -> {
                        downloadDialog.dismiss();
                        Toast.makeText(this, "下载文档失败", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    downloadDialog.dismiss();
                    Toast.makeText(this, "网络异常", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void launchReader(File file, String title) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            openWordReaderDialog(file, title);
        } else {
            Intent intent = new Intent(this, PdfViewerActivity.class);
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_PATH, file.getAbsolutePath());
            intent.putExtra(PdfViewerActivity.EXTRA_BOOK_TITLE, title);
            startActivity(intent);
        }
    }

    /**
     * 内置轻量级 Word (.docx) 阅读器弹窗，无需手机安装第三方 Office 软件即可直接阅读全文
     */
    private void openWordReaderDialog(File file, String title) {
        ProgressDialog loading = new ProgressDialog(this);
        loading.setMessage("正在解析 Word 文档内容...");
        loading.show();

        new Thread(() -> {
            String extracted = extractDocxText(file);
            runOnUiThread(() -> {
                loading.dismiss();
                ScrollView scrollView = new ScrollView(this);
                TextView tvContent = new TextView(this);
                tvContent.setPadding(52, 36, 52, 36);
                tvContent.setTextSize(15);
                tvContent.setLineSpacing(0, 1.35f);
                tvContent.setTextIsSelectable(true);
                tvContent.setText(TextUtils.isEmpty(extracted)
                        ? "（未能直接预览该格式文本，你可在 AI 平台向图书管理员提问该文档内容）"
                        : extracted);
                scrollView.addView(tvContent);

                new AlertDialog.Builder(this)
                        .setTitle("📖 " + title)
                        .setView(scrollView)
                        .setPositiveButton("关闭阅读", null)
                        .show();
            });
        }).start();
    }

    private String extractDocxText(File docxFile) {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(docxFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(zis, "UTF-8");
                    int eventType = parser.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && "t".equals(parser.getName())) {
                            String text = parser.nextText();
                            if (text != null) {
                                sb.append(text);
                            }
                        } else if (eventType == XmlPullParser.END_TAG && "p".equals(parser.getName())) {
                            sb.append("\n\n");
                        }
                        eventType = parser.next();
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    private void deleteBook(BookItem book) {
        new AlertDialog.Builder(this)
                .setTitle("删除书籍确认")
                .setMessage("确定删除【" + book.title + "】吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    new Thread(() -> {
                        try {
                            Request request = new Request.Builder()
                                    .url("http://" + serverIp + ":8000/api/books/" + book.bookId)
                                    .delete()
                                    .build();
                            Response res = httpClient.newCall(request).execute();
                            if (res.isSuccessful()) {
                                runOnUiThread(this::loadBookList);
                            }
                        } catch (Exception ignored) {}
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String getFileNameFromUri(Uri uri) {
        String name = "文档.pdf";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index != -1) name = cursor.getString(index);
            }
        } catch (Exception ignored) {}
        return name;
    }

    class BookAdapter extends RecyclerView.Adapter<BookAdapter.BookViewHolder> {
        @NonNull
        @Override
        public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_book, parent, false);
            return new BookViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
            BookItem item = bookList.get(position);
            String lower = item.filename != null ? item.filename.toLowerCase(Locale.ROOT) : "";
            String badge = (lower.endsWith(".docx") || lower.endsWith(".doc")) ? "[Word] " : "[PDF] ";
            holder.tvBookTitle.setText(badge + item.title);

            String sizeStr = item.fileSize < 1024 * 1024
                    ? String.format(Locale.getDefault(), "%.1f KB", Math.max(1.0, item.fileSize / 1024.0))
                    : String.format(Locale.getDefault(), "%.1f MB", item.fileSize / (1024.0 * 1024.0));
            holder.tvBookMeta.setText("上传者: " + item.uploader + " · " + sizeStr);

            boolean isTeacher = "teacher".equalsIgnoreCase(myRole);
            holder.btnDeleteBook.setVisibility(isTeacher ? View.VISIBLE : View.GONE);
            holder.btnDeleteBook.setOnClickListener(v -> deleteBook(item));
            holder.btnReadBook.setOnClickListener(v -> openBook(item));
            holder.itemView.setOnClickListener(v -> openBook(item));
        }

        @Override
        public int getItemCount() {
            return bookList.size();
        }

        class BookViewHolder extends RecyclerView.ViewHolder {
            TextView tvBookTitle, tvBookMeta;
            Button btnDeleteBook, btnReadBook;

            public BookViewHolder(@NonNull View itemView) {
                super(itemView);
                tvBookTitle = itemView.findViewById(R.id.tvBookTitle);
                tvBookMeta = itemView.findViewById(R.id.tvBookMeta);
                btnDeleteBook = itemView.findViewById(R.id.btnDeleteBook);
                btnReadBook = itemView.findViewById(R.id.btnReadBook);
            }
        }
    }

    static class BookItem {
        String bookId, title, filename, uploader;
        long fileSize, timestamp;

        public BookItem(String bookId, String title, String filename, long fileSize, String uploader, long timestamp) {
            this.bookId = bookId;
            this.title = title;
            this.filename = filename;
            this.fileSize = fileSize;
            this.uploader = uploader;
            this.timestamp = timestamp;
        }
    }
}