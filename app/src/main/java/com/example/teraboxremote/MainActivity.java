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
            webView.loadUrl("https://www.terabox.com/main");
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
                extractJsToken();
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
                    mainHandler.post(() -> {
                        tvStatus.setText("Status: Logged In (ndus captured)");
                        if (!jsToken.isEmpty()) {
                            webView.setVisibility(View.GONE);
                            fetchTaskList();
                        }
                    });
                    break;
                }
            }
        }
    }

    private void extractJsToken() {
        // JavaScript to find jsToken in the window object or script tags
        String js = "javascript:(function() { " +
                "if (window.jsToken) return window.jsToken; " +
                "var scripts = document.getElementsByTagName('script'); " +
                "for (var i = 0; i < scripts.length; i++) { " +
                "  var match = scripts[i].innerHTML.match(/jsToken\\s*:\\s*\"([^\"]+)\"/); " +
                "  if (match) return match[1]; " +
                "} " +
                "return ''; " +
                "})()";
        
        webView.evaluateJavascript(js, value -> {
            if (value != null && !value.equals("\"\"") && !value.equals("null")) {
                jsToken = value.replace("\"", "");
                mainHandler.post(() -> {
                    tvStatus.setText("Status: Logged In (jsToken captured)");
                    if (!ndus.isEmpty()) {
                        webView.setVisibility(View.GONE);
                        fetchTaskList();
                    }
                });
            }
        });
    }

    private String generateDpLogId() {
        // Generate a random hex string similar to TeraboxUploaderCLI
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(String.format("%02x", random.nextInt(256)));
        }
        return sb.toString().toUpperCase();
    }

    private void startRemoteUpload() {
        String url = etLink.getText().toString().trim();
        if (ndus.isEmpty()) {
            Toast.makeText(this, "Please login first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (url.isEmpty()) {
            Toast.makeText(this, "Please paste a link", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                mainHandler.post(() -> tvStatus.setText("Status: Adding task..."));
                
                // TeraBox 2025 API parameters - using 1024terabox.com domain
                // Generate dp-logid for proper request tracking
                String dpLogId = generateDpLogId();
                String apiUrl = "https://www.1024terabox.com/rest/2.0/services/cloud_dl?method=add_task"
                        + "&app_id=" + APP_ID 
                        + "&web=1" 
                        + "&channel=dubox" 
                        + "&clienttype=0"
                        + "&jsToken=" + jsToken
                        + "&dp-logid=" + dpLogId;

                // Important: source_url must be the first parameter
                // We use FormBody to send parameters in the POST body as TeraBox expects
                FormBody formBody = new FormBody.Builder()
                        .add("source_url", url)
                        .add("save_path", "/")
                        .build();

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Cookie", allCookies)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Referer", "https://www.1024terabox.com/main")
                        .addHeader("Origin", "https://www.1024terabox.com")
                        .addHeader("X-Requested-With", "XMLHttpRequest")
                        .post(formBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    String responseData = response.body() != null ? response.body().string() : "No data";
                    JSONObject json = new JSONObject(responseData);
                    int errno = json.optInt("errno", -1);

                    if (errno == 0) {
                        mainHandler.post(() -> {
                            tvStatus.setText("Status: Task Added Successfully");
                            etLink.setText("");
                            fetchTaskList();
                        });
                    } else {
                        // Detailed error reporting for 36001
                        mainHandler.post(() -> {
                            String msg = json.optString("errmsg", "Unknown error");
                            tvStatus.setText("Error " + errno + ": " + msg);
                            android.util.Log.e("TeraBox", "API Error: " + responseData);
                        });
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> tvStatus.setText("Exception: " + e.getMessage()));
            }
        });
    }

    private void fetchTaskList() {
        if (ndus.isEmpty()) return;
        executor.execute(() -> {
            try {
                String dpLogId = generateDpLogId();
                String listUrl = "https://www.1024terabox.com/rest/2.0/services/cloud_dl?method=list_task"
                        + "&app_id=" + APP_ID 
                        + "&web=1" 
                        + "&channel=dubox" 
                        + "&clienttype=0"
                        + "&jsToken=" + jsToken
                        + "&dp-logid=" + dpLogId
                        + "&need_report=1"
                        + "&num=100"
                        + "&page=1";

                Request request = new Request.Builder()
                        .url(listUrl)
                        .addHeader("Cookie", allCookies)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Referer", "https://www.terabox.com/main")
                        .get()
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String data = response.body().string();
                        JSONObject json = new JSONObject(data);
                        if (json.has("task_info")) {
                            JSONArray array = json.getJSONArray("task_info");
                            List<TaskItem> newTasks = new ArrayList<>();
                            for (int i = 0; i < array.length(); i++) {
                                JSONObject obj = array.getJSONObject(i);
                                TaskItem item = new TaskItem();
                                item.id = obj.getString("task_id");
                                item.name = obj.optString("task_name", "Unknown");
                                item.status = obj.optInt("status", -1);
                                long finished = obj.optLong("finished_size", 0);
                                long total = obj.optLong("file_size", 0);
                                if (total > 0) {
                                    item.progress = (int) ((finished * 100) / total);
                                } else if (item.status == 0) {
                                    item.progress = 100;
                                } else {
                                    item.progress = 0;
                                }
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void deleteTask(String taskId) {
        executor.execute(() -> {
            try {
                String dpLogId = generateDpLogId();
                String delUrl = "https://www.1024terabox.com/rest/2.0/services/cloud_dl?method=cancel_task"
                        + "&app_id=" + APP_ID 
                        + "&web=1" 
                        + "&channel=dubox" 
                        + "&clienttype=0"
                        + "&jsToken=" + jsToken
                        + "&dp-logid=" + dpLogId;
                
                FormBody formBody = new FormBody.Builder()
                        .add("task_ids", taskId)
                        .build();

                Request request = new Request.Builder()
                        .url(delUrl)
                        .addHeader("Cookie", allCookies)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Referer", "https://www.terabox.com/main")
                        .post(formBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        mainHandler.post(() -> {
                            Toast.makeText(MainActivity.this, "Task cancelled", Toast.LENGTH_SHORT).show();
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
            
            String statusStr;
            switch(item.status) {
                case 0: statusStr = "Success"; break;
                case 1: statusStr = "Downloading"; break;
                case 2: statusStr = "Waiting"; break;
                case 3: statusStr = "Failed"; break;
                default: statusStr = "Status: " + item.status;
            }
            
            holder.tvStatus.setText(statusStr + " (" + item.progress + "%)");
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
