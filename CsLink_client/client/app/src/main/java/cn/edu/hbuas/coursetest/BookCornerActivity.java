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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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

    private final ActivityResultLauncher<String> pdfPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
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
        btnUploadBook.setOnClickListener(v -> pdfPickerLauncher.launch("application/pdf"));

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
        String defaultName = getFileNameFromUri(uri);
        final EditText input = new EditText(this);
        input.setHint("输入书籍展示名称");
        input.setText(defaultName.replace(".pdf", ""));

        new AlertDialog.Builder(this)
                .setTitle("上传 PDF 书籍")
                .setView(input)
                .setPositiveButton("开始上传", (dialog, which) -> {
                    String title = input.getText().toString().trim();
                    uploadPdfFile(uri, TextUtils.isEmpty(title) ? defaultName : title);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void uploadPdfFile(Uri uri, String bookTitle) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在上传书籍到中枢...");
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(uri);
                byte[] bytes = new byte[inputStream.available()];
                inputStream.read(bytes);
                inputStream.close();

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("title", bookTitle)
                        .addFormDataPart("uploader", myNickname)
                        .addFormDataPart("file", bookTitle + ".pdf",
                                RequestBody.create(MediaType.parse("application/pdf"), bytes))
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
                        Toast.makeText(this, "上传失败", Toast.LENGTH_SHORT).show();
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

        if (localFile.exists()) {
            launchReader(localFile, book.title);
            return;
        }

        ProgressDialog downloadDialog = new ProgressDialog(this);
        downloadDialog.setMessage("首次打开，正在下载书籍...");
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
                        Toast.makeText(this, "下载书籍失败", Toast.LENGTH_SHORT).show();
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
        Intent intent = new Intent(this, PdfViewerActivity.class);
        intent.putExtra(PdfViewerActivity.EXTRA_PDF_PATH, file.getAbsolutePath());
        intent.putExtra(PdfViewerActivity.EXTRA_BOOK_TITLE, title);
        startActivity(intent);
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
        String name = "书籍.pdf";
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
            holder.tvBookTitle.setText(item.title);
            String sizeStr = String.format("%.1f MB", item.fileSize / (1024.0 * 1024.0));
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