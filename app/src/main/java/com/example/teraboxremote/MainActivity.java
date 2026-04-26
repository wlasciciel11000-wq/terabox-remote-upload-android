package com.example.teraboxremote;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Button btnLogin, btnStart, btnRefresh;
    private EditText etLink;
    private TextView tvStatus;
    private RecyclerView rvTasks;
    private TaskAdapter taskAdapter;
    private List<TaskItem> taskList = new ArrayList<>();

    private String ndus = "";
    private String jsToken = "";
    private String bdstoken = "";
    private String allCookies = "";
    private final String APP_ID = "250528";
    private final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
            
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview_login);
        btnLogin = findViewById(R.id.btn_login);
        btnStart = findViewById(R.id.btn_start);
        btnRefresh = findViewById(R.id.btn_refresh);
        etLink = findViewById(R.id.et_link);
        tvStatus = findViewById(R.id.tv_status);
        rvTasks = findViewById(R.id.rv_tasks);

        rvTasks.setLayoutManager(new LinearLayoutManager(this));
        taskAdapter = new TaskAdapter(taskList);
        rvTasks.setAdapter(taskAdapter);

        setupWebView();

        btnLogin.setOnClickListener(v -> {
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl("https://www.1024terabox.com/main");
        });

        btnStart.setOnClickListener(v -> startRemoteUpload());
        btnRefresh.setOnClickListener(v -> fetchTaskList());
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUserAgentString(USER_AGENT);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        
        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_UP) {
                if (!v.hasFocus()) v.requestFocus();
            }
            return false;
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkCookies(url);
                extractTokens();
            }
        });
    }

    private void checkCookies(String url) {
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null && cookies.contains("ndus=")) {
            allCookies = cookies;
            String[] parts = cookies.split(";");
            for (String part : parts) {
                if (part.trim().startsWith("ndus=")) {
                    ndus = part.trim().substring(5);
                    mainHandler.post(() -> tvStatus.setText("Status: ndus captured, fetching bdstoken..."));
                    fetchBdstokenFromHtml();
                    break;
                }
            }
        }
    }

    private void fetchBdstokenFromHtml() {
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url("https://www.1024terabox.com/main")
                        .addHeader("Cookie", allCookies)
                        .addHeader("User-Agent", USER_AGENT)
                        .get()
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String html = response.body().string();
                        String extractedBdstoken = extractTokenFromHtml(html, "bdstoken");
                        if (!extractedBdstoken.isEmpty()) {
                            bdstoken = extractedBdstoken;
                        }
                        
                        mainHandler.post(() -> {
                            if (!bdstoken.isEmpty()) {
                                tvStatus.setText("Status: All tokens captured!");
                                webView.setVisibility(View.GONE);
                                fetchTaskList();
                            } else {
                                tvStatus.setText("Status: ndus OK, bdstoken missing");
                            }
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private String extractTokenFromHtml(String html, String tokenName) {
        String[] patterns = {
                "\"" + tokenName + "\"\\s*:\\s*\"([^\"]+)\"",
                tokenName + "\\s*=\\s*\"([^\"]+)\"",
                "\"" + tokenName + "\"\\s*:\\s*'([^']+)'",
                tokenName + "\\s*=\\s*'([^']+)'"
        };

        for (String pattern : patterns) {
            try {
                Pattern p = Pattern.compile(pattern);
                Matcher m = p.matcher(html);
                if (m.find()) {
                    return m.group(1);
                }
            } catch (Exception e) { }
        }
        return "";
    }

    private void extractTokens() {
        String js = "javascript:(function() { " +
                "var result = {jsToken: ''}; " +
                "try { " +
                "  if (window.jsToken) result.jsToken = window.jsToken; " +
                "  if (!result.jsToken) { " +
                "    var scripts = document.getElementsByTagName('script'); " +
                "    for (var i = 0; i < scripts.length; i++) { " +
                "      var content = scripts[i].innerHTML; " +
                "      var m1 = content.match(/jsToken\\s*[:=]\\s*[\"']([^\"']+)[\"']/); " +
                "      if (m1) { result.jsToken = m1[1]; break; } " +
                "    } " +
                "  } " +
                "} catch(e) {} " +
                "return JSON.stringify(result); " +
                "})()";
        
        webView.evaluateJavascript(js, value -> {
            if (value != null && !value.equals("null")) {
                try {
                    String jsonStr = value;
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        jsonStr = value.substring(1, value.length() - 1).replace("\\\"", "\"");
                    }
                    JSONObject json = new JSONObject(jsonStr);
                    String capturedJsToken = json.optString("jsToken", "");
                    if (!capturedJsToken.isEmpty()) {
                        jsToken = capturedJsToken;
                    }
                } catch (Exception e) { }
            }
        });
    }

    private String generateDpLogId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(String.format("%02x", random.nextInt(256)));
        }
        return sb.toString().toUpperCase();
    }

    private void startRemoteUpload() {
        String url = etLink.getText().toString().trim();
        if (ndus.isEmpty() || bdstoken.isEmpty()) {
            Toast.makeText(this, "Please login and wait for tokens", Toast.LENGTH_SHORT).show();
            return;
        }
        if (url.isEmpty()) {
            Toast.makeText(this, "Please paste a link", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                mainHandler.post(() -> tvStatus.setText("Status: Getting file size..."));
                
                // KROK 0: Pobranie rozmiaru pliku (kluczowe dla uniknięcia 0B)
                long tempSize = getRemoteFileSize(url);
                if (tempSize <= 0) {
                    // Jeśli nie udało się pobrać rozmiaru, spróbujemy z domyślną wartością
                    tempSize = 1024 * 1024; // 1MB dummy
                }
                final long fileSize = tempSize;

                mainHandler.post(() -> tvStatus.setText("Status: Adding task (Size: " + (fileSize/1024) + " KB)..."));
                String dpLogId = generateDpLogId();
                
                // KROK 1: Precreate
                String precreateUrl = "https://www.1024terabox.com/api/precreate?app_id=" + APP_ID 
                        + "&web=1&channel=dubox&clienttype=0"
                        + "&jsToken=" + jsToken
                        + "&dp-logid=" + dpLogId;

                String fileName = "remote_" + System.currentTimeMillis();
                if (url.contains("/")) {
                    String lastPart = url.substring(url.lastIndexOf("/") + 1);
                    if (lastPart.contains("?")) lastPart = lastPart.substring(0, lastPart.indexOf("?"));
                    if (!lastPart.isEmpty()) fileName = lastPart;
                }

                FormBody precreateBody = new FormBody.Builder()
                        .add("path", "/" + fileName)
                        .add("autoinit", "1")
                        .add("target_path", "/")
                        .add("source_url", url)
                        .add("size", String.valueOf(fileSize))
                        .add("block_list", "[\"d41d8cd98f00b204e9800998ecf8427e\"]")
                        .build();

                Request precreateRequest = new Request.Builder()
                        .url(precreateUrl)
                        .addHeader("Cookie", allCookies)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Referer", "https://www.1024terabox.com/main")
                        .post(precreateBody)
                        .build();

                try (Response response = client.newCall(precreateRequest).execute()) {
                    String responseData = response.body() != null ? response.body().string() : "{}";
                    JSONObject json = new JSONObject(responseData);
                    int errno = json.optInt("errno", -1);

                    if (errno == 0) {
                        finalizeUpload(fileName, json.optString("uploadid", ""), url, fileSize);
                    } else {
                        mainHandler.post(() -> tvStatus.setText("Error: " + json.optString("errmsg", "Unknown")));
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> tvStatus.setText("Exception: " + e.getMessage()));
            }
        });
    }

    private long getRemoteFileSize(String url) {
        try {
            // Wiele serwerów blokuje zapytania bez User-Agent
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .head()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String contentLength = response.header("Content-Length");
                    if (contentLength != null && !contentLength.isEmpty()) {
                        return Long.parseLong(contentLength);
                    }
                }
            }
            
            // Fallback: GET z limitem bajtów
            Request getRequest = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Range", "bytes=0-1")
                    .get()
                    .build();
            try (Response response = client.newCall(getRequest).execute()) {
                if (response.isSuccessful()) {
                    String contentRange = response.header("Content-Range");
                    if (contentRange != null && contentRange.contains("/")) {
                        return Long.parseLong(contentRange.substring(contentRange.lastIndexOf("/") + 1));
                    }
                    String contentLength = response.header("Content-Length");
                    if (contentLength != null && !contentLength.isEmpty()) {
                        return Long.parseLong(contentLength);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    private void finalizeUpload(String fileName, String uploadId, String sourceUrl, long fileSize) {
        executor.execute(() -> {
            try {
                String dpLogId = generateDpLogId();
                String createUrl = "https://www.1024terabox.com/api/create?app_id=" + APP_ID 
                        + "&web=1&channel=dubox&clienttype=0"
                        + "&jsToken=" + jsToken
                        + "&dp-logid=" + dpLogId;

                long currentTime = System.currentTimeMillis() / 1000;
                FormBody createBody = new FormBody.Builder()
                        .add("path", "/" + fileName)
                        .add("uploadid", uploadId)
                        .add("target_path", "/")
                        .add("size", String.valueOf(fileSize))
                        .add("isdir", "0")
                        .add("rtype", "1")
                        .add("mtime", String.valueOf(currentTime))
                        .add("local_mtime", String.valueOf(currentTime))
                        .add("source_url", sourceUrl)
                        .add("block_list", "[\"d41d8cd98f00b204e9800998ecf8427e\"]")
                        .build();

                Request request = new Request.Builder()
                        .url(createUrl)
                        .addHeader("Cookie", allCookies)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Referer", "https://www.1024terabox.com/main")
                        .addHeader("Origin", "https://www.1024terabox.com")
                        .addHeader("X-Requested-With", "XMLHttpRequest")
                        .post(createBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    String responseData = response.body() != null ? response.body().string() : "{}";
                    JSONObject json = new JSONObject(responseData);
                    int errno = json.optInt("errno", -1);
                    
                    mainHandler.post(() -> {
                        if (errno == 0) {
                            tvStatus.setText("Status: Task Finalized Successfully");
                            etLink.setText("");
                            fetchTaskList();
                        } else {
                            tvStatus.setText("Finalize Error: " + errno + " - " + json.optString("errmsg", "Unknown"));
                        }
                    });
                }
            } catch (Exception e) { }
        });
    }

    private void fetchTaskList() {
        if (ndus.isEmpty()) return;
        executor.execute(() -> {
            try {
                String dpLogId = generateDpLogId();
                String listUrl = "https://www.1024terabox.com/api/list?app_id=" + APP_ID 
                        + "&web=1&channel=dubox&clienttype=0"
                        + "&jsToken=" + jsToken
                        + "&dp-logid=" + dpLogId
                        + "&dir=%2F&num=100&page=1";

                Request request = new Request.Builder()
                        .url(listUrl)
                        .addHeader("Cookie", allCookies)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Referer", "https://www.1024terabox.com/main")
                        .get()
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String data = response.body().string();
                        JSONObject json = new JSONObject(data);
                        if (json.has("list")) {
                            JSONArray array = json.getJSONArray("list");
                            List<TaskItem> newTasks = new ArrayList<>();
                            for (int i = 0; i < array.length(); i++) {
                                JSONObject obj = array.getJSONObject(i);
                                TaskItem item = new TaskItem();
                                item.id = obj.optString("fs_id", "0");
                                item.name = obj.optString("server_filename", "Unknown");
                                item.status = obj.optInt("status", 0);
                                item.progress = 100;
                                newTasks.add(item);
                            }
                            mainHandler.post(() -> {
                                taskList.clear();
                                taskList.addAll(newTasks);
                                taskAdapter.notifyDataSetChanged();
                            });
                        }
                    }
                }
            } catch (Exception e) { }
        });
    }

    private void deleteTask(String taskId) {
        executor.execute(() -> {
            try {
                String dpLogId = generateDpLogId();
                String delUrl = "https://www.1024terabox.com/api/filemanager?opera=delete"
                        + "&app_id=" + APP_ID 
                        + "&jsToken=" + jsToken
                        + "&dp-logid=" + dpLogId;
                
                String fileListJson = "[\"" + taskId + "\"]";
                FormBody formBody = new FormBody.Builder()
                        .add("filelist", fileListJson)
                        .build();

                Request request = new Request.Builder()
                        .url(delUrl)
                        .addHeader("Cookie", allCookies)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Referer", "https://www.1024terabox.com/main")
                        .post(formBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        mainHandler.post(() -> {
                            Toast.makeText(MainActivity.this, "Item deleted", Toast.LENGTH_SHORT).show();
                            fetchTaskList();
                        });
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    static class TaskItem {
        String id;
        String name;
        int status;
        int progress;
    }

    class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.ViewHolder> {
        private List<TaskItem> items;
        TaskAdapter(List<TaskItem> items) { this.items = items; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_task, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TaskItem item = items.get(position);
            holder.tvName.setText(item.name);
            holder.pbProgress.setProgress(item.progress);
            holder.tvStatus.setText("Status: " + (item.progress == 100 ? "Completed" : "Processing") + " (" + item.progress + "%)");
            holder.btnDelete.setOnClickListener(v -> deleteTask(item.id));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvStatus;
            ProgressBar pbProgress;
            Button btnDelete;
            ViewHolder(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_task_name);
                tvStatus = v.findViewById(R.id.tv_task_status);
                pbProgress = v.findViewById(R.id.pb_task_progress);
                btnDelete = v.findViewById(R.id.btn_delete_task);
            }
        }
    }
}
