package com.example.teraboxremote;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Button btnLogin, btnStart, btnPause, btnResume;
    private EditText etLink;
    private ProgressBar pbProgress;
    private TextView tvStatus, tvProgressStatus;

    private String ndus = "";
    private String currentTaskId = "";
    private boolean isPaused = false;
    private final OkHttpClient client = new OkHttpClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview_login);
        btnLogin = findViewById(R.id.btn_login);
        btnStart = findViewById(R.id.btn_start);
        btnPause = findViewById(R.id.btn_pause);
        btnResume = findViewById(R.id.btn_resume);
        etLink = findViewById(R.id.et_link);
        pbProgress = findViewById(R.id.pb_progress);
        tvStatus = findViewById(R.id.tv_status);
        tvProgressStatus = findViewById(R.id.tv_progress_status);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        // Omijanie CORS w WebView (uproszczone dla celów edukacyjnych/demonstracyjnych)
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                checkCookies(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkCookies(url);
            }
        });

        btnLogin.setOnClickListener(v -> {
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl("https://www.terabox.com/main");
        });

        btnStart.setOnClickListener(v -> startRemoteUpload());
        btnPause.setOnClickListener(v -> pauseTask());
        btnResume.setOnClickListener(v -> resumeTask());
    }

    private void checkCookies(String url) {
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null && cookies.contains("ndus=")) {
            String[] parts = cookies.split(";");
            for (String part : parts) {
                if (part.trim().startsWith("ndus=")) {
                    ndus = part.trim().substring(5);
                    tvStatus.setText("Status: Logged In (ndus found)");
                    webView.setVisibility(View.GONE);
                    break;
                }
            }
        }
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
                // Endpoint dla dodawania zadania offline download
                String apiUrl = "https://www.1024terabox.com/rest/2.0/cloud_dl/add_task?app_id=250528";
                FormBody formBody = new FormBody.Builder()
                        .add("save_path", "/")
                        .add("source_url", url)
                        .build();

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Cookie", "ndus=" + ndus)
                        .post(formBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseData = response.body().string();
                        JSONObject json = new JSONObject(responseData);
                        if (json.has("task_id")) {
                            currentTaskId = json.getString("task_id");
                            mainHandler.post(() -> tvStatus.setText("Status: Task Started ID: " + currentTaskId));
                            startPollingStatus();
                        } else {
                            mainHandler.post(() -> Toast.makeText(MainActivity.this, "Failed to start task", Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void startPollingStatus() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                while (!currentTaskId.isEmpty() && !isPaused) {
                    try {
                        String statusUrl = "https://www.1024terabox.com/rest/2.0/cloud_dl/query_task?app_id=250528&task_ids=" + currentTaskId;
                        Request request = new Request.Builder()
                                .url(statusUrl)
                                .addHeader("Cookie", "ndus=" + ndus)
                                .get()
                                .build();

                        try (Response response = client.newCall(request).execute()) {
                            if (response.isSuccessful() && response.body() != null) {
                                String responseData = response.body().string();
                                JSONObject json = new JSONObject(responseData);
                                // Logika parsowania statusu i postępu
                                // TeraBox zwraca listę zadań w "task_info"
                                if (json.has("task_info")) {
                                    JSONObject task = json.getJSONArray("task_info").getJSONObject(0);
                                    int status = task.getInt("status"); // 0: success, 1: downloading, 2: waiting, etc.
                                    long finished = task.optLong("finished_size", 0);
                                    long total = task.optLong("file_size", 1);
                                    int progress = (int) ((finished * 100) / total);

                                    mainHandler.post(() -> {
                                        pbProgress.setProgress(progress);
                                        tvProgressStatus.setText("Progress: " + progress + "%");
                                        if (status == 0) {
                                            tvStatus.setText("Status: Completed");
                                            currentTaskId = "";
                                        }
                                    });
                                }
                            }
                        }
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        e.printStackTrace();
                        break;
                    }
                }
            }
        });
    }

    private void pauseTask() {
        isPaused = true;
        tvStatus.setText("Status: Paused");
    }

    private void resumeTask() {
        if (isPaused && !currentTaskId.isEmpty()) {
            isPaused = false;
            tvStatus.setText("Status: Resuming...");
            startPollingStatus();
        }
    }
}
