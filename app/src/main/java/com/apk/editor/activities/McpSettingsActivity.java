package com.apk.editor.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

import com.apk.editor.R;
import com.apk.editor.mcp.McpServer;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

import in.sunilpaulmathew.sCommon.CommonUtils.sCommonUtils;

/*
 * MCP settings and live activity log.
 */
public class McpSettingsActivity extends BaseActivity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private MaterialTextView mStatus, mEndpoints, mLogView;
    private TextInputEditText mPortInput;
    private SwitchMaterial mEnableSwitch;
    private boolean mInternalChange;

    private final Runnable mRefreshLogs = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            refreshLogs();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcp_settings, R.id.layout_root);

        AppCompatImageButton back = findViewById(R.id.back_button);
        mStatus = findViewById(R.id.mcp_status);
        mEndpoints = findViewById(R.id.mcp_endpoints);
        mLogView = findViewById(R.id.mcp_logs);
        mPortInput = findViewById(R.id.mcp_port);
        mEnableSwitch = findViewById(R.id.mcp_enable);
        MaterialButton applyPort = findViewById(R.id.apply_port);
        MaterialButton copyUrl = findViewById(R.id.copy_mcp_url);
        MaterialButton clearLogs = findViewById(R.id.clear_logs);

        back.setOnClickListener(v -> finish());
        mPortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        mPortInput.setText(String.valueOf(getSavedPort()));

        mEnableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mInternalChange) return;
            if (isChecked) {
                startServerFromUi();
            } else {
                McpServer.get(this).stop();
                sCommonUtils.saveBoolean("mcpServerEnabled", false, this);
            }
            refreshStatus();
            refreshLogs();
        });

        applyPort.setOnClickListener(v -> {
            int port = parsePort();
            sCommonUtils.saveInt("mcpServerPort", port, this);
            mPortInput.setText(String.valueOf(port));
            if (McpServer.get(this).isRunning()) {
                McpServer.get(this).start(port);
            }
            refreshStatus();
        });

        copyUrl.setOnClickListener(v -> {
            copyToClipboard(getString(R.string.mcp_streamable_http), getMcpUrl());
            sCommonUtils.toast(getString(R.string.mcp_url_copied), this).show();
        });

        clearLogs.setOnClickListener(v -> {
            McpServer.clearLogs();
            refreshLogs();
        });

        refreshStatus();
        refreshLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mRefreshLogs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRefreshLogs);
    }

    private int getSavedPort() {
        return McpServer.normalizePort(sCommonUtils.getInt("mcpServerPort", McpServer.DEFAULT_PORT, this));
    }

    private int parsePort() {
        try {
            return McpServer.normalizePort(Integer.parseInt(String.valueOf(mPortInput.getText()).trim()));
        } catch (Exception ignored) {
            return McpServer.DEFAULT_PORT;
        }
    }

    private void startServerFromUi() {
        int port = parsePort();
        sCommonUtils.saveInt("mcpServerPort", port, this);
        sCommonUtils.saveBoolean("mcpServerEnabled", true, this);
        McpServer.get(this).start(port);
        if (!McpServer.get(this).isRunning()) {
            sCommonUtils.toast(getString(R.string.mcp_server_failed), this).show();
        }
    }

    private void refreshStatus() {
        boolean running = McpServer.get(this).isRunning();
        mInternalChange = true;
        mEnableSwitch.setChecked(running);
        mInternalChange = false;
        mStatus.setText(running
                ? getString(R.string.mcp_server_running_port, McpServer.get(this).getPort())
                : getString(R.string.mcp_server_stopped));
        refreshEndpoints();
    }

    private void refreshEndpoints() {
        int port = McpServer.get(this).isRunning() ? McpServer.get(this).getPort() : parsePort();
        String base = "http://127.0.0.1:" + port;
        mEndpoints.setText(getString(R.string.mcp_same_phone_endpoint,
                base + "/mcp",
                base + "/sse",
                base + "/health"));
    }

    private String getMcpUrl() {
        int port = McpServer.get(this).isRunning() ? McpServer.get(this).getPort() : parsePort();
        return "http://127.0.0.1:" + port + "/mcp";
    }

    private void copyToClipboard(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        }
    }

    private void refreshLogs() {
        List<String> logs = McpServer.getLogs();
        if (logs.isEmpty()) {
            mLogView.setText(R.string.mcp_logs_empty);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (String log : logs) {
            builder.append(log).append('\n');
        }
        mLogView.setText(builder.toString());
    }
}
