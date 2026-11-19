package com.bydwatch.companion.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.update.AppUpdateChecker;
import com.bydwatch.companion.update.AppUpdateInfo;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates in-app GitHub release update checks, download progress dialogs, and APK install intents.
 */
public final class AppUpdateUiHelper {

    public static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    public interface Host {
        void onUpdateCheckStarted(boolean manual);

        void onUpdateCheckFinished(
                AppUpdateInfo latestInfo,
                boolean isNewer,
                String errorMessage,
                boolean manual);

        void openExternalUrl(String url);

        void showToast(String message, boolean isError);

        void onRequestInstallPermission(Intent settingsIntent);
    }

    public interface ApkUriResolver {
        Uri getUriForFile(Context context, String authority, File file);
    }

    private final AppCompatActivity activity;
    private final ConfigStore configStore;
    private final AppUpdateChecker checker;
    private final Host host;
    private ApkUriResolver apkUriResolver = FileProvider::getUriForFile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "byd-app-update");
        t.setDaemon(true);
        return t;
    });

    private Future<?> activeTask;
    private AlertDialog activeUpdateDialog;
    private AlertDialog activePermissionDialog;
    private File pendingInstallApkFile;
    private boolean checkingNow;

    public AppUpdateUiHelper(
            @NonNull AppCompatActivity activity,
            ConfigStore configStore,
            @NonNull AppUpdateChecker checker,
            @NonNull Host host) {
        this.activity = activity;
        this.configStore = configStore;
        this.checker = checker;
        this.host = host;
    }

    public void maybeAutoCheckForUpdates() {
        long now = System.currentTimeMillis();
        long lastCheck = configStore != null ? configStore.getLastUpdateCheckMs() : 0L;
        if (lastCheck > 0L && (now - lastCheck) < AUTO_CHECK_INTERVAL_MS) {
            return;
        }
        checkForUpdates(false);
    }

    public void checkForUpdates(boolean manual) {
        if (checkingNow) {
            return;
        }
        checkingNow = true;
        host.onUpdateCheckStarted(manual);
        activeTask = executor.submit(() -> {
            try {
                AppUpdateInfo info = checker.fetchLatestRelease();
                if (configStore != null) {
                    configStore.setLastUpdateCheckMs(System.currentTimeMillis());
                }
                boolean isNewer = AppUpdateChecker.isNewerVersion(
                        info.versionName, BuildConfig.VERSION_NAME);
                mainHandler.post(() -> {
                    checkingNow = false;
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    host.onUpdateCheckFinished(info, isNewer, null, manual);
                    if (manual) {
                        if (isNewer) {
                            showUpdateDialog(info);
                        } else {
                            host.showToast(
                                    activity.getString(
                                            R.string.update_status_up_to_date,
                                            BuildConfig.VERSION_NAME),
                                    false);
                        }
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null && !e.getMessage().trim().isEmpty()
                        ? e.getMessage().trim()
                        : e.getClass().getSimpleName();
                mainHandler.post(() -> {
                    checkingNow = false;
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    host.onUpdateCheckFinished(null, false, msg, manual);
                    if (manual) {
                        host.showToast(
                                activity.getString(R.string.update_status_failed_fmt, msg),
                                true);
                    }
                });
            }
        });
    }

    public void showUpdateDialog(@NonNull AppUpdateInfo info) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) {
            activeUpdateDialog.dismiss();
        }

        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null);
        TextView tvVersion = content.findViewById(R.id.tvUpdateDialogVersion);
        TextView tvSize = content.findViewById(R.id.tvUpdateDialogSize);
        TextView tvNotes = content.findViewById(R.id.tvUpdateReleaseNotes);
        View layoutProgress = content.findViewById(R.id.layoutUpdateProgress);
        ProgressBar pbDownload = content.findViewById(R.id.pbUpdateDownload);
        TextView tvProgressText = content.findViewById(R.id.tvUpdateProgressText);
        TextView tvError = content.findViewById(R.id.tvUpdateDialogError);
        Button btnLater = content.findViewById(R.id.btnUpdateLater);
        Button btnGithub = content.findViewById(R.id.btnUpdateGithub);
        Button btnDownload = content.findViewById(R.id.btnUpdateDownload);

        tvVersion.setText(activity.getString(
                R.string.update_dialog_version_fmt,
                BuildConfig.VERSION_NAME,
                info.versionName));

        if (info.apkSizeBytes > 0L) {
            tvSize.setVisibility(View.VISIBLE);
            tvSize.setText(activity.getString(
                    R.string.update_dialog_size_fmt,
                    Formatter.formatShortFileSize(activity, info.apkSizeBytes)));
        } else {
            tvSize.setVisibility(View.GONE);
        }

        tvNotes.setText(info.releaseNotes.isEmpty()
                ? activity.getString(R.string.update_dialog_notes_empty)
                : formatReleaseNotes(info.releaseNotes));

        final AtomicBoolean downloading = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final File[] readyApk = new File[1];

        if (!info.hasApkAsset()) {
            btnDownload.setText(R.string.update_btn_open_release);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(content)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setOnDismissListener(d -> {
            cancelled.set(true);
            if (activeUpdateDialog == dialog) {
                activeUpdateDialog = null;
            }
        });
        activeUpdateDialog = dialog;

        btnLater.setOnClickListener(v -> {
            cancelled.set(true);
            dialog.dismiss();
        });

        btnGithub.setOnClickListener(v -> host.openExternalUrl(info.htmlUrl));

        btnDownload.setOnClickListener(v -> {
            if (!info.hasApkAsset()) {
                host.openExternalUrl(info.htmlUrl);
                dialog.dismiss();
                return;
            }
            if (readyApk[0] != null && readyApk[0].exists()) {
                launchApkInstall(readyApk[0]);
                return;
            }
            if (downloading.get()) {
                cancelled.set(true);
                btnDownload.setEnabled(false);
                return;
            }

            downloading.set(true);
            cancelled.set(false);
            tvError.setVisibility(View.GONE);
            layoutProgress.setVisibility(View.VISIBLE);
            pbDownload.setIndeterminate(info.apkSizeBytes <= 0L);
            pbDownload.setProgress(0);
            tvProgressText.setText(activity.getString(R.string.update_dialog_downloading_bytes_fmt,
                    Formatter.formatShortFileSize(activity, 0)));
            btnDownload.setText(R.string.update_btn_cancel);

            activeTask = executor.submit(() -> {
                try {
                    File downloaded = checker.downloadApk(
                            activity,
                            info,
                            (read, total) -> mainHandler.post(() -> {
                                if (!dialog.isShowing() || cancelled.get()) return;
                                String readStr = Formatter.formatShortFileSize(activity, read);
                                if (total > 0L) {
                                    int pct = (int) Math.min(100L, (read * 100L) / total);
                                    pbDownload.setIndeterminate(false);
                                    pbDownload.setProgress(pct);
                                    String totalStr = Formatter.formatShortFileSize(activity, total);
                                    tvProgressText.setText(activity.getString(
                                            R.string.update_dialog_downloading_pct_fmt,
                                            pct,
                                            readStr,
                                            totalStr));
                                } else {
                                    pbDownload.setIndeterminate(true);
                                    tvProgressText.setText(activity.getString(
                                            R.string.update_dialog_downloading_bytes_fmt,
                                            readStr));
                                }
                            }),
                            cancelled);

                    mainHandler.post(() -> {
                        downloading.set(false);
                        if (activity.isFinishing() || activity.isDestroyed() || cancelled.get()) {
                            return;
                        }
                        readyApk[0] = downloaded;
                        if (dialog.isShowing()) {
                            pbDownload.setIndeterminate(false);
                            pbDownload.setProgress(100);
                            tvProgressText.setText(R.string.update_dialog_ready_to_install);
                            btnDownload.setEnabled(true);
                            btnDownload.setText(R.string.update_btn_install_now);
                        }
                        launchApkInstall(downloaded);
                    });
                } catch (Exception e) {
                    boolean wasCancelled = cancelled.get()
                            || e instanceof AppUpdateChecker.DownloadCancelledException;
                    String msg = e.getMessage() != null && !e.getMessage().trim().isEmpty()
                            ? e.getMessage().trim()
                            : e.getClass().getSimpleName();
                    mainHandler.post(() -> {
                        downloading.set(false);
                        if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) {
                            return;
                        }
                        btnDownload.setEnabled(true);
                        btnDownload.setText(R.string.update_btn_download_install);
                        if (wasCancelled) {
                            layoutProgress.setVisibility(View.GONE);
                        } else {
                            tvError.setVisibility(View.VISIBLE);
                            tvError.setText(msg);
                        }
                    });
                }
            });
        });

        dialog.show();
    }

    public void launchApkInstall(@NonNull File apkFile) {
        if (!apkFile.exists()) {
            host.showToast(
                    activity.getString(R.string.update_install_failed_fmt, "APK file missing"),
                    true);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstallApkFile = apkFile;
            showInstallPermissionDialog();
            return;
        }
        pendingInstallApkFile = null;
        try {
            Uri apkUri = apkUriResolver.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(installIntent);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            host.showToast(
                    activity.getString(R.string.update_install_failed_fmt, msg),
                    true);
        }
    }

    public void onInstallPermissionResult() {
        File pending = pendingInstallApkFile;
        if (pending == null || !pending.exists()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls()) {
            launchApkInstall(pending);
        }
    }

    private void showInstallPermissionDialog() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (activePermissionDialog != null && activePermissionDialog.isShowing()) {
            activePermissionDialog.dismiss();
        }
        activePermissionDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_perm_title)
                .setMessage(R.string.update_perm_message)
                .setNegativeButton(R.string.update_btn_later, null)
                .setPositiveButton(R.string.update_perm_open_settings, (d, w) -> {
                    Intent settingsIntent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    host.onRequestInstallPermission(settingsIntent);
                })
                .show();
    }

    public void destroy() {
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        if (activeUpdateDialog != null && activeUpdateDialog.isShowing()) {
            activeUpdateDialog.dismiss();
        }
        activeUpdateDialog = null;
        if (activePermissionDialog != null && activePermissionDialog.isShowing()) {
            activePermissionDialog.dismiss();
        }
        activePermissionDialog = null;
        executor.shutdownNow();
    }

    @VisibleForTesting
    public void awaitActiveTaskForTesting() throws Exception {
        Future<?> task = activeTask;
        if (task != null && !task.isDone()) {
            task.get(5, TimeUnit.SECONDS);
        }
    }

    @VisibleForTesting
    public void setApkUriResolverForTesting(ApkUriResolver resolver) {
        this.apkUriResolver = resolver != null ? resolver : FileProvider::getUriForFile;
    }

    static String formatReleaseNotes(String rawBody) {
        if (rawBody == null) return "";
        String[] lines = rawBody.replace("\r\n", "\n").split("\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String clean = line.trim();
            if (clean.startsWith("### ")) {
                clean = clean.substring(4);
            } else if (clean.startsWith("## ")) {
                clean = clean.substring(3);
            } else if (clean.startsWith("# ")) {
                clean = clean.substring(2);
            }
            clean = clean.replace("**", "").replace("__", "").replace("`", "");
            if (clean.startsWith("- ") || clean.startsWith("* ")) {
                clean = "• " + clean.substring(2);
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(clean);
        }
        return out.toString().trim();
    }
}
