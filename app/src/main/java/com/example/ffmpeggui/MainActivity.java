package com.example.ffmpeggui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Session;
import com.arthenica.ffmpegkit.Statistics;

import com.example.ffmpeggui.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Main Activity for FFmpeg GUI
 *
 * Uses ffmpeg-kit-android-full-lts library.
 * See: https://github.com/arthenica/ffmpeg-kit
 *
 * Key API methods used:
 *  - FFmpegKit.executeAsync()                       : run ffmpeg command in background
 *  - FFmpegKitConfig.enableLogCallback()            : receive log lines
 *  - FFmpegKitConfig.enableStatisticsCallback()     : receive progress stats
 *  - FFprobeKit.getMediaInformationAsync()          : probe media info
 *  - ReturnCode.isSuccess()                         : check if command succeeded
 *  - FFmpegKit.cancel(sessionId)                    : cancel running session
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private Uri selectedFileUri = null;
    private String selectedFilePath = null;
    private String outputDir = null;

    private static final int PERMISSION_REQUEST_CODE = 100;
    private long currentSessionId = -1;

    // File picker launcher
    private final ActivityResultLauncher<String[]> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                selectedFileUri = uri;
                getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                selectedFilePath = copyUriToCache(uri);
                String name = getFileNameFromUri(uri);
                binding.tvSelectedFile.setText("Selected: " + name);
                binding.btnProbe.setEnabled(true);
                appendLog("[INFO] File selected: " + name);
                appendLog("[INFO] Cached at: " + selectedFilePath);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Set up output directory
        File moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (moviesDir == null) moviesDir = getFilesDir();
        moviesDir.mkdirs();
        outputDir = moviesDir.getAbsolutePath();
        binding.tvOutputDir.setText("Output: " + outputDir);

        // Set up FFmpeg-Kit log callback
        // Docs: https://github.com/arthenica/ffmpeg-kit#52-log-output
        FFmpegKitConfig.enableLogCallback(log -> {
            String message = log.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                runOnUiThread(() -> appendLog(message.trim()));
            }
        });

        // Set up FFmpeg-Kit statistics callback
        // Docs: https://github.com/arthenica/ffmpeg-kit#53-statistics
        FFmpegKitConfig.enableStatisticsCallback(stats ->
            runOnUiThread(() -> updateProgress(stats)));

        setupButtons();
        requestPermissions();
        appendLog("[INFO] FFmpeg GUI ready.");
        appendLog("[INFO] Library: ffmpeg-kit-android-full-lts:6.0-2");
    }

    private void setupButtons() {
        binding.btnPickFile.setOnClickListener(v ->
            filePickerLauncher.launch(new String[]{"video/*", "audio/*"}));

        binding.btnProbe.setOnClickListener(v -> probeMediaInfo());
        binding.btnConvertMp4.setOnClickListener(v -> convertToMp4());
        binding.btnExtractAudio.setOnClickListener(v -> extractAudio());
        binding.btnCompress.setOnClickListener(v -> compressVideo());
        binding.btnTrim.setOnClickListener(v -> trimVideo());
        binding.btnRunCustom.setOnClickListener(v -> runCustomCommand());
        binding.btnCancel.setOnClickListener(v -> cancelOperation());
        binding.btnClearLog.setOnClickListener(v -> binding.tvLog.setText(""));

        binding.btnProbe.setEnabled(false);
        binding.btnCancel.setEnabled(false);
    }

    // -------------------------------------------------------------------------
    // FFmpeg Operations
    // -------------------------------------------------------------------------

    /**
     * Probe media information using FFprobeKit.
     * Docs: https://github.com/arthenica/ffmpeg-kit#7-using-ffprobe-in-ffmpeg-kit
     */
    private void probeMediaInfo() {
        if (selectedFilePath == null) { toast("Please select a file first"); return; }
        appendLog("\n[PROBE] Getting media information...");
        setOperationRunning(true);

        FFprobeKit.getMediaInformationAsync(selectedFilePath, session -> {
            com.arthenica.ffmpegkit.MediaInformation info =
                ((com.arthenica.ffmpegkit.MediaInformationSession) session).getMediaInformation();
            runOnUiThread(() -> {
                setOperationRunning(false);
                if (info != null) {
                    appendLog("[PROBE] Format: " + info.getFormat());
                    appendLog("[PROBE] Duration: " + info.getDuration() + "s");
                    appendLog("[PROBE] Bitrate: " + info.getBitrate() + " bps");
                    appendLog("[PROBE] Size: " + info.getSize() + " bytes");
                    if (info.getStreams() != null) {
                        for (com.arthenica.ffmpegkit.StreamInformation s : info.getStreams()) {
                            appendLog("[PROBE] Stream #" + s.getIndex()
                                + " codec=" + s.getCodec()
                                + " type=" + s.getType());
                        }
                    }
                } else {
                    appendLog("[PROBE] Failed. Log: " + session.getLogsAsString());
                }
            });
        });
    }

    /**
     * Convert to MP4 using H.264 + AAC.
     * FFmpeg docs: https://ffmpeg.org/ffmpeg-codecs.html
     */
    private void convertToMp4() {
        if (!checkFileSelected()) return;
        String output = makeOutputPath("converted", "mp4");
        String cmd = String.format(
            "-i \"%s\" -c:v libx264 -preset fast -c:a aac -movflags +faststart \"%s\"",
            selectedFilePath, output);
        appendLog("\n[CONVERT] Converting to MP4...");
        appendLog("[CMD] ffmpeg " + cmd);
        executeFFmpeg(cmd, output);
    }

    /**
     * Extract audio as MP3.
     */
    private void extractAudio() {
        if (!checkFileSelected()) return;
        String output = makeOutputPath("audio", "mp3");
        String cmd = String.format(
            "-i \"%s\" -vn -acodec libmp3lame -q:a 2 \"%s\"",
            selectedFilePath, output);
        appendLog("\n[AUDIO] Extracting audio as MP3...");
        appendLog("[CMD] ffmpeg " + cmd);
        executeFFmpeg(cmd, output);
    }

    /**
     * Compress video to 720p.
     */
    private void compressVideo() {
        if (!checkFileSelected()) return;
        String output = makeOutputPath("compressed", "mp4");
        String cmd = String.format(
            "-i \"%s\" -vf scale=-2:720 -c:v libx264 -crf 28 -preset fast -c:a copy \"%s\"",
            selectedFilePath, output);
        appendLog("\n[COMPRESS] Compressing to 720p...");
        appendLog("[CMD] ffmpeg " + cmd);
        executeFFmpeg(cmd, output);
    }

    /**
     * Trim video between start and end seconds.
     */
    private void trimVideo() {
        if (!checkFileSelected()) return;
        String startStr = binding.etTrimStart.getText().toString().trim();
        String endStr = binding.etTrimEnd.getText().toString().trim();
        if (startStr.isEmpty()) startStr = "0";
        if (endStr.isEmpty()) endStr = "30";
        String output = makeOutputPath("trimmed", "mp4");
        String cmd = String.format(
            "-ss %s -i \"%s\" -to %s -c copy \"%s\"",
            startStr, selectedFilePath, endStr, output);
        appendLog("\n[TRIM] Trimming: " + startStr + "s to " + endStr + "s");
        appendLog("[CMD] ffmpeg " + cmd);
        executeFFmpeg(cmd, output);
    }

    /**
     * Run a custom FFmpeg command.
     */
    private void runCustomCommand() {
        String cmd = binding.etCustomCommand.getText().toString().trim();
        if (cmd.isEmpty()) { toast("Enter a custom FFmpeg command"); return; }
        if (selectedFilePath != null) {
            cmd = cmd.replace("{input}", "\"" + selectedFilePath + "\"");
        }
        cmd = cmd.replace("{output_dir}", outputDir);
        appendLog("\n[CUSTOM] Running custom command...");
        appendLog("[CMD] ffmpeg " + cmd);
        executeFFmpeg(cmd, null);
    }

    // -------------------------------------------------------------------------
    // Core FFmpeg Execution
    // -------------------------------------------------------------------------

    /**
     * Execute FFmpeg asynchronously using FFmpegKit.executeAsync().
     * Docs: https://github.com/arthenica/ffmpeg-kit#3-execute-synchronous-and-asynchronous
     */
    private void executeFFmpeg(String command, String expectedOutput) {
        setOperationRunning(true);
        binding.progressBar.setProgress(0);

        Session session = FFmpegKit.executeAsync(command, completedSession -> {
            ReturnCode returnCode = completedSession.getReturnCode();
            runOnUiThread(() -> {
                setOperationRunning(false);
                if (ReturnCode.isSuccess(returnCode)) {
                    String msg = expectedOutput != null
                        ? "[SUCCESS] Done! Output: " + expectedOutput
                        : "[SUCCESS] Command completed.";
                    appendLog(msg);
                    binding.progressBar.setProgress(100);
                    toast("Success! Check output folder.");
                } else if (ReturnCode.isCancel(returnCode)) {
                    appendLog("[CANCELLED] Operation was cancelled.");
                } else {
                    appendLog("[ERROR] FFmpeg failed. Return code: " + returnCode);
                    toast("FFmpeg error. Check log.");
                }
            });
        });
        currentSessionId = session.getSessionId();
    }

    /**
     * Cancel current FFmpeg session.
     * Docs: https://github.com/arthenica/ffmpeg-kit#4-cancel-execution
     */
    private void cancelOperation() {
        if (currentSessionId != -1) {
            FFmpegKit.cancel(currentSessionId);
            appendLog("[INFO] Cancellation requested for session " + currentSessionId);
        } else {
            FFmpegKit.cancel();
            appendLog("[INFO] Cancellation requested.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void updateProgress(Statistics stats) {
        if (stats != null) {
            long time = stats.getTime();
            long size = stats.getSize();
            double bitrate = stats.getBitrate();
            double speed = stats.getSpeed();
            String info = String.format(Locale.US,
                "[STATS] time=%dms size=%dKB bitrate=%.1fkbps speed=%.2fx",
                time, size / 1024, bitrate, speed);
            int progress = Math.min((int)(size / 1024 / 10), 95);
            binding.progressBar.setProgress(progress);
            binding.tvStats.setText(info);
        }
    }

    private String copyUriToCache(Uri uri) {
        try {
            String fileName = getFileNameFromUri(uri);
            File cacheFile = new File(getCacheDir(), fileName);
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }
            return cacheFile.getAbsolutePath();
        } catch (Exception e) {
            appendLog("[ERROR] Failed to copy file: " + e.getMessage());
            return null;
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String name = "unknown_file";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    private String makeOutputPath(String prefix, String extension) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return outputDir + "/" + prefix + "_" + timestamp + "." + extension;
    }

    private boolean checkFileSelected() {
        if (selectedFilePath == null) {
            toast("Please select a file first");
            return false;
        }
        return true;
    }

    private void setOperationRunning(boolean running) {
        binding.btnCancel.setEnabled(running);
        binding.btnConvertMp4.setEnabled(!running);
        binding.btnExtractAudio.setEnabled(!running);
        binding.btnCompress.setEnabled(!running);
        binding.btnTrim.setEnabled(!running);
        binding.btnRunCustom.setEnabled(!running);
        binding.btnPickFile.setEnabled(!running);
        if (!running) currentSessionId = -1;
    }

    private void appendLog(String text) {
        String current = binding.tvLog.getText().toString();
        binding.tvLog.setText(current + "\n" + text);
        binding.scrollLog.post(() -> binding.scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int r : grantResults)
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            appendLog(allGranted ? "[INFO] Permissions granted." : "[WARN] Some permissions denied.");
        }
    }
}