package com.xitronix.capacitorvoicerec;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.app.ActivityManager;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

@CapacitorPlugin(
    name = "VoiceRecorder",
    permissions = { @Permission(alias = VoiceRecorder.RECORD_AUDIO_ALIAS, strings = { Manifest.permission.RECORD_AUDIO }) }
)
public class VoiceRecorder extends Plugin implements CustomMediaRecorder.OnStatusChangeListener {

    static final String RECORD_AUDIO_ALIAS = "voice recording";
    private static final String TAG = "VoiceRecorderPlugin";
    private static final String EVENT_STATE_CHANGE = "recordingStateChange";
    private static final String PREFS_NAME = "VoiceRecorderPrefs";
    private static final String ACTIVE_RECORDING_SESSION_KEY = "active_recording_session";

    private CustomMediaRecorder customMediaRecorder;
    private boolean useForegroundService = false;
    private String activeSessionId;
    private long activeSessionStartedAtMs = 0;
    private String activeRecordingFilePath;
    private String activeRecordingDirectory;
    private AudioFocusRequest audioFocusRequest;
    private boolean recorderPausedForFocusLoss = false;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = this::handleAudioFocusChange;
    // Whether the currently running foreground service was started for the streaming
    // path (true) or the file-recording path (false). Governs the stop-action behavior.
    private boolean foregroundServiceForStreaming = false;
    // private String currentDirectory = "DOCUMENTS"; // Store directory if needed across calls

    // Static reference to the active recorder for the foreground service
    private static CustomMediaRecorder activeRecorder;
    // Static reference to the active plugin instance so the foreground service can
    // stop streaming when the user dismisses the notification.
    private static VoiceRecorder activePlugin;

    // --- Live-chunk persistence (native-owned storage) ---
    // Target on-disk format for persisted chunks: 16-bit little-endian PCM at 16 kHz mono.
    // Matches what the server expects over the WebSocket, so WAV assembly is a header prepend.
    private static final int LIVE_CHUNK_SAMPLE_RATE = 16000;
    private static final int LIVE_CHUNK_CHANNELS = 1;
    private static final int LIVE_CHUNK_BYTES_PER_SAMPLE = 2;
    private static final String LIVE_CHUNK_ROOT = "live-session-chunks";

    private String persistSessionId;
    private File persistSessionDir;
    private AtomicInteger persistSeq = new AtomicInteger(0);

    @Override
    public void load() {
        activePlugin = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (isStreaming || audioRecord != null) {
            stopStreamingInternal();
        }
        if (customMediaRecorder != null && customMediaRecorder.getCurrentStatus() != CurrentRecordingStatus.NONE) {
            persistActiveRecordingSession("INTERRUPTED", "pluginDestroyed");
            try {
                customMediaRecorder.stopRecording();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop recorder during plugin destroy", e);
            }
        }
        if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForegroundService();
        }
        customMediaRecorder = null;
        activeRecorder = null;
        if (activePlugin == this) {
            activePlugin = null;
        }
        super.handleOnDestroy();
    }

    /**
     * Called from {@link ForegroundService} when the user dismisses the notification
     * while a streaming session is active. Stops capture and releases resources.
     */
    static void onForegroundServiceStopRequestedForStreaming() {
        VoiceRecorder plugin = activePlugin;
        if (plugin == null) return;
        plugin.stopStreamingInternal();
    }

    // Callback from CustomMediaRecorder
    @Override
    public void onStatusChange(CurrentRecordingStatus status) {
        if (status != CurrentRecordingStatus.NONE) {
            persistActiveRecordingSession(status.name(), null);
        }
        notifyListeners(EVENT_STATE_CHANGE, ResponseGenerator.statusResponse(status));
    }

    @Override
    public void onRecordingError(String reason) {
        persistActiveRecordingSession("INTERRUPTED", reason);
        JSObject data = new JSObject();
        data.put("status", "INTERRUPTED");
        data.put("reason", reason);
        notifyListeners(EVENT_STATE_CHANGE, data);
    }


    @PluginMethod
    public void canDeviceVoiceRecord(PluginCall call) {
        // Check if MediaRecorder can be instantiated. Requires RECORD_AUDIO permission.
        // If permission isn't granted yet, this might return false positives/negatives.
        // A more reliable check happens implicitly during startRecording.
        if (!doesUserGaveAudioRecordingPermission()) {
            // Can't reliably check without permission, assume yes for now, let startRecording handle permission.
             call.resolve(ResponseGenerator.successResponse());
            // Or request permission here first? Depends on desired UX.
            // requestAudioRecordingPermission(call); // Example if you want to force permission check first
        } else {
             // With permission, we can try a more concrete check
            if (CustomMediaRecorder.canPhoneCreateMediaRecorder(getContext())) {
                call.resolve(ResponseGenerator.successResponse());
            } else {
                call.resolve(ResponseGenerator.failResponse());
            }
        }
    }

    @PluginMethod
    public void requestAudioRecordingPermission(PluginCall call) {
        if (doesUserGaveAudioRecordingPermission()) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            // Use requestPermissionForAlias for consistent handling
            requestPermissionForAlias(RECORD_AUDIO_ALIAS, call, "recordAudioPermissionCallback");
        }
    }

    @PermissionCallback
    private void recordAudioPermissionCallback(PluginCall call) {
        if (doesUserGaveAudioRecordingPermission()) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            call.reject(Messages.MISSING_PERMISSION); // Reject if denied
        }
    }

    @PluginMethod
    public void hasAudioRecordingPermission(PluginCall call) {
        call.resolve(ResponseGenerator.fromBoolean(doesUserGaveAudioRecordingPermission()));
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (isStreaming) {
            call.reject("Audio streaming is already active");
            return;
        }

        if (!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION, RECORD_AUDIO_ALIAS); // Indicate which permission is missing
            return;
        }

         // Check if microphone is available (basic check)
         if (isMicrophoneOccupied()) {
             call.reject(Messages.MICROPHONE_BEING_USED);
             return;
         }

        // Prevent starting if already recording/paused
        if (customMediaRecorder != null && customMediaRecorder.getCurrentStatus() != CurrentRecordingStatus.NONE) {
            call.reject(Messages.ALREADY_RECORDING);
            return;
        }

        // Get options
        String directory = call.getString("directory", "DOCUMENTS");
        useForegroundService = Boolean.TRUE.equals(call.getBoolean("useForegroundService", false));
        activeSessionId = java.util.UUID.randomUUID().toString();
        activeSessionStartedAtMs = nowMs();
        activeRecordingDirectory = directory;
        // this.currentDirectory = directory; // Store if needed

        try {
             if (!requestAudioFocusForRecording()) {
                 call.reject(Messages.MICROPHONE_BEING_USED);
                 return;
             }

             // Start foreground service if requested
            if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                 startForegroundService(call);
             }

            // Create a new recorder instance for each recording session
            customMediaRecorder = new CustomMediaRecorder(getContext());
            customMediaRecorder.setListener(this); // Set listener for status updates
            activeRecorder = customMediaRecorder; // Update static reference

            String filePathUri = customMediaRecorder.startRecording(directory);
            activeRecordingFilePath = filePathUri;
            persistActiveRecordingSession(CurrentRecordingStatus.RECORDING.name(), null);

             // Initial response - duration is unknown (-1)
            RecordData recordData = new RecordData(
                -1,
                "audio/aac", // Matches the encoder format
                filePathUri
            );
            call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));

        } catch (Exception exp) {
            Log.e(TAG, "Start Recording failed", exp);
             // Ensure cleanup if start fails
             if (customMediaRecorder != null) {
                 customMediaRecorder.deleteOutputFile(); // Delete potentially corrupted file
                 customMediaRecorder = null;
             }
             abandonAudioFocus();
             if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  stopForegroundService();
             }
            call.reject(Messages.FAILED_TO_RECORD, exp);
        }
    }

     @PluginMethod
     public void continueRecording(PluginCall call) {
         if (isStreaming) {
             call.reject("Audio streaming is already active");
             return;
         }

         if (!doesUserGaveAudioRecordingPermission()) {
             call.reject(Messages.MISSING_PERMISSION);
             return;
         }

         // First, check if there's already an active recording from a foreground service
         boolean foregroundServiceActive = isForegroundServiceActive();
         
         if (foregroundServiceActive) {
             // Force stop the foreground service
             stopForegroundService();
             
             // Wait a moment for service to properly stop
             try {
                 Thread.sleep(500);
             } catch (InterruptedException e) {
                 // Ignore
             }
         }

         if (customMediaRecorder != null && customMediaRecorder.getCurrentStatus() != CurrentRecordingStatus.NONE) {
             call.reject(Messages.ALREADY_RECORDING);
             return;
         }

         // Continue with normal continue recording logic...
         // Get previous file path and directory
         String prevFilePathUri = call.getString("filePath");
         String directory = call.getString("directory", "DOCUMENTS"); // Directory for the *new* segment
         useForegroundService = Boolean.TRUE.equals(call.getBoolean("useForegroundService", useForegroundService));
         activeSessionId = java.util.UUID.randomUUID().toString();
         activeSessionStartedAtMs = nowMs();
         activeRecordingDirectory = directory;
          // this.currentDirectory = directory;

          if (prevFilePathUri == null || prevFilePathUri.isEmpty()) {
             call.reject("Missing required 'filePath' for previous recording segment.");
             return;
          }

          // Validate URI format (basic)
         if (!prevFilePathUri.startsWith("file://")) {
              // Consider rejecting if format is strictly expected:
              // call.reject("Invalid 'filePath' format. Expected a file URI (file://...).");
              // return;
         }


         try {
              if (!requestAudioFocusForRecording()) {
                  call.reject(Messages.MICROPHONE_BEING_USED);
                  return;
              }

              // Start foreground service if requested (and not already running, though state check above should handle this)
              if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  startForegroundService(call);
              }

              // Create a new recorder instance (or reuse if designed differently, but new seems cleaner)
             customMediaRecorder = new CustomMediaRecorder(getContext());
             customMediaRecorder.setListener(this);

             String newSegmentPathUri = customMediaRecorder.continueRecording(prevFilePathUri, directory);
             activeRecordingFilePath = newSegmentPathUri;
             activeRecorder = customMediaRecorder;
             persistActiveRecordingSession(CurrentRecordingStatus.RECORDING.name(), null);

              // Return info about the *new* segment being recorded
             RecordData recordData = new RecordData(
                 -1, // Duration of the new segment is unknown at start
                 "audio/aac",
                 newSegmentPathUri
             );
             call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));

         } catch (IOException e) {
             Log.e(TAG, "Continue Recording failed", e);
              if (customMediaRecorder != null) {
                 // Don't delete the *previous* file on continue failure, but clean up the new instance
                 customMediaRecorder = null;
             }
             abandonAudioFocus();
              if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  stopForegroundService();
             }
             call.reject("Failed to continue recording: " + e.getMessage(), e);
         } catch (Exception e) { // Catch other potential errors
              Log.e(TAG, "Unexpected error during continue recording", e);
               if (customMediaRecorder != null) {
                  customMediaRecorder = null;
              }
               abandonAudioFocus();
               if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                   stopForegroundService();
               }
              call.reject("An unexpected error occurred while continuing recording.", e);
         }
     }


    @PluginMethod
    public void stopRecording(PluginCall call) {
        if (customMediaRecorder == null || customMediaRecorder.getCurrentStatus() == CurrentRecordingStatus.NONE) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }

        try {
            String finalFilePathUri = customMediaRecorder.stopRecording(); // This handles merging internally

            if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                stopForegroundService();
            }

            if (finalFilePathUri == null) {
                call.reject(Messages.FAILED_TO_FETCH_RECORDING, "Final file path was null after stopping.");
                return;
            }

            // Remove file:// prefix if present
            String finalPath = finalFilePathUri;
            if (finalFilePathUri.startsWith("file://")) {
                finalPath = Uri.parse(finalFilePathUri).getPath();
            }

            if (finalPath == null) {
                call.reject(Messages.FAILED_TO_FETCH_RECORDING, "Could not extract path from final URI.");
                return;
            }

            long duration = getMsDurationOfAudioFile(finalPath);

            if (duration <= 0) {
                new File(finalPath).delete();
                call.reject(Messages.EMPTY_RECORDING);
            } else {
                RecordData recordData = new RecordData(duration, "audio/aac", finalPath); // Use direct path instead of URI
                call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));
                clearActiveRecordingSession();
            }

        } catch (Exception exp) {
             Log.e(TAG, "Stop Recording failed", exp);
             persistActiveRecordingSession("INTERRUPTED", "stopFailed");
             // Attempt to stop foreground service even if stopRecorder failed
              if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  stopForegroundService();
              }
            call.reject(Messages.FAILED_TO_FETCH_RECORDING, exp);
        } finally {
            // Clean up the recorder instance after stopping
            customMediaRecorder = null;
            activeRecorder = null; // Clear static reference
            recorderPausedForFocusLoss = false;
            abandonAudioFocus();
        }
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        if (customMediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }
        try {
            boolean paused = customMediaRecorder.pauseRecording();
            if (paused) {
                persistActiveRecordingSession(CurrentRecordingStatus.PAUSED.name(), null);
            }
            call.resolve(ResponseGenerator.fromBoolean(paused));
        } catch (NotSupportedOsVersion exception) {
            call.reject(Messages.NOT_SUPPORTED_OS_VERSION);
        } catch (Exception e) {
             Log.e(TAG, "Pause Recording failed", e);
             call.reject("Failed to pause recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        if (customMediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }
        try {
             // Check if microphone is still available before resuming
            if (isMicrophoneOccupied()) {
                call.reject(Messages.MICROPHONE_BEING_USED);
                return;
            }
            boolean resumed = customMediaRecorder.resumeRecording();
            if (resumed) {
                persistActiveRecordingSession(CurrentRecordingStatus.RECORDING.name(), null);
            }
            call.resolve(ResponseGenerator.fromBoolean(resumed));
        } catch (NotSupportedOsVersion exception) {
            call.reject(Messages.NOT_SUPPORTED_OS_VERSION);
        } catch (Exception e) {
            Log.e(TAG, "Resume Recording failed", e);
            call.reject("Failed to resume recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getCurrentStatus(PluginCall call) {
        if (customMediaRecorder == null) {
            // If no recorder initialized, status is NONE
            call.resolve(ResponseGenerator.statusResponse(CurrentRecordingStatus.NONE));
        } else {
            call.resolve(ResponseGenerator.statusResponse(customMediaRecorder.getCurrentStatus()));
        }
    }

    @PluginMethod
    public void getActiveRecordingSession(PluginCall call) {
        JSObject session = buildActiveRecordingSession(null, null);
        if (session == null) {
            session = loadPersistedRecordingSession();
        }
        JSObject response = new JSObject();
        response.put("value", session);
        call.resolve(response);
    }

    @PluginMethod
    public void listRecoverableRecordingSessions(PluginCall call) {
        JSArray sessions = new JSArray();
        JSObject session = loadPersistedRecordingSession();
        if (session != null && recoverySessionHasAudio(session)) {
            sessions.put(session);
        }
        JSObject response = new JSObject();
        response.put("sessions", sessions);
        call.resolve(response);
    }

    /**
     * Get information about a recording file without having to continue/stop it
     * This allows apps to directly access recording information even if the microphone is busy
     */
    @PluginMethod
    public void getRecordingInfo(PluginCall call) {
        String filePath = call.getString("filePath");
        if (filePath == null || filePath.isEmpty()) {
            call.reject("Missing required 'filePath' parameter");
            return;
        }
        
        // Create a temporary instance to check file info (doesn't affect active recording)
        CustomMediaRecorder infoChecker = new CustomMediaRecorder(getContext());
        java.util.Map<String, Object> info = infoChecker.getRecordingInfo(filePath);
        
        if (!(boolean)info.get("exists")) {
            call.reject("Recording file not found or invalid");
            return;
        }
        
        // Build response
        RecordData recordData = new RecordData(
            ((Number)info.get("durationMs")).longValue(),
            "audio/aac",
            (String)info.get("fileUri")
        );
        
        JSObject response = recordData.toJSObject();
        response.put("hasSegments", info.get("hasSegments"));
        
        call.resolve(ResponseGenerator.dataResponse(response));
    }
    
    /**
     * Finalize a recording by merging any temporary segments without continuing/stopping it
     * This allows apps to access and finalize recordings even if the microphone is busy
     */
    @PluginMethod
    public void finalizeRecording(PluginCall call) {
        String filePath = call.getString("filePath");
        if (filePath == null || filePath.isEmpty()) {
            call.reject("Missing required 'filePath' parameter");
            return;
        }
        
        // Create a temporary instance for finalization (doesn't affect active recording)
        CustomMediaRecorder finalizer = new CustomMediaRecorder(getContext());
        java.util.Map<String, Object> result = finalizer.finalizeRecording(filePath);
        
        if (!(boolean)result.get("success")) {
            call.reject("Failed to finalize recording");
            return;
        }
        
        // Build response
        RecordData recordData = new RecordData(
            ((Number)result.get("durationMs")).longValue(),
            "audio/aac",
            (String)result.get("fileUri")
        );
        
        call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));
        clearActiveRecordingSession();
    }

    @PluginMethod
    public void listRecordingFiles(PluginCall call) {
        String directory = call.getString("directory", "DOCUMENTS");
        CustomMediaRecorder scanner = new CustomMediaRecorder(getContext());
        File dir = scanner.getDirectory(directory);

        JSArray filesArray = new JSArray();

        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".aac")) {
                        JSObject fileInfo = buildFileInfo(file, false);
                        if (fileInfo != null) {
                            filesArray.put(fileInfo);
                        }
                    }
                }
            }

            // Scan VoiceRecorderSegments subdirectory
            File segmentsDir = new File(dir, "VoiceRecorderSegments");
            if (segmentsDir.exists() && segmentsDir.isDirectory()) {
                File[] segmentFiles = segmentsDir.listFiles();
                if (segmentFiles != null) {
                    for (File file : segmentFiles) {
                        if (file.isFile() && file.getName().endsWith(".aac")) {
                            JSObject fileInfo = buildFileInfo(file, true);
                            if (fileInfo != null) {
                                filesArray.put(fileInfo);
                            }
                        }
                    }
                }
            }
        }

        JSObject result = new JSObject();
        result.put("files", filesArray);
        call.resolve(result);
    }

    private JSObject buildFileInfo(File file, boolean isSegment) {
        JSObject info = new JSObject();
        info.put("filePath", file.getAbsolutePath());
        info.put("fileName", file.getName());
        info.put("size", file.length());
        info.put("createdAt", file.lastModified());
        info.put("isSegment", isSegment);

        long durationMs = -1;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                durationMs = Long.parseLong(durationStr);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get duration for: " + file.getName(), e);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        info.put("durationMs", Math.max(durationMs, 0));

        return info;
    }

    // --- Helper Methods ---

    private boolean doesUserGaveAudioRecordingPermission() {
        return getPermissionState(RECORD_AUDIO_ALIAS) == PermissionState.GRANTED;
    }

    private long nowMs() {
        return System.currentTimeMillis();
    }

    private JSObject buildActiveRecordingSession(String statusOverride, String interruptionReason) {
        String filePath = activeRecordingFilePath;
        if (filePath == null && customMediaRecorder != null) {
            filePath = customMediaRecorder.getOutputFilePathUri();
        }
        if (filePath == null) return null;

        String status = statusOverride;
        if (status == null) {
            status = customMediaRecorder != null ? customMediaRecorder.getCurrentStatus().name() : "INTERRUPTED";
        }

        JSObject data = new JSObject();
        data.put("sessionId", activeSessionId != null ? activeSessionId : new File(filePath).getName());
        data.put("filePath", filePath);
        data.put("status", status);
        data.put("startedAt", activeSessionStartedAtMs > 0 ? activeSessionStartedAtMs : nowMs());
        data.put("updatedAt", nowMs());
        data.put("platform", "android");
        data.put("hasSegments", hasRecoverableSegments(filePath));
        if (activeRecordingDirectory != null) {
            data.put("directory", activeRecordingDirectory);
        }
        if (interruptionReason != null) {
            data.put("interruptionReason", interruptionReason);
        }
        return data;
    }

    private void persistActiveRecordingSession(String statusOverride, String interruptionReason) {
        JSObject data = buildActiveRecordingSession(statusOverride, interruptionReason);
        if (data == null) return;
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTIVE_RECORDING_SESSION_KEY, data.toString())
            .apply();
    }

    private void clearActiveRecordingSession() {
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(ACTIVE_RECORDING_SESSION_KEY)
            .apply();
        activeSessionId = null;
        activeSessionStartedAtMs = 0;
        activeRecordingFilePath = null;
        activeRecordingDirectory = null;
    }

    private JSObject loadPersistedRecordingSession() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(ACTIVE_RECORDING_SESSION_KEY, null);
        if (raw == null) return null;
        try {
            org.json.JSONObject parsed = new org.json.JSONObject(raw);
            JSObject data = new JSObject();
            java.util.Iterator<String> keys = parsed.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                data.put(key, parsed.get(key));
            }
            return data;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse active recording session", e);
            return null;
        }
    }

    private boolean recoverySessionHasAudio(JSObject session) {
        String filePath = session.getString("filePath");
        if (filePath == null) return false;
        File file = new File(filePath.startsWith("file://") ? Uri.parse(filePath).getPath() : filePath);
        return file.exists() || session.optBoolean("hasSegments", false);
    }

    private boolean hasRecoverableSegments(String filePath) {
        if (filePath == null) return false;
        File file = new File(filePath.startsWith("file://") ? Uri.parse(filePath).getPath() : filePath);
        String key = "voice_recorder_segments_" + file.getName();
        java.util.Set<String> savedSegments = getContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(key, null);
        if (savedSegments == null) return false;
        for (String segment : savedSegments) {
            File segmentFile = new File(segment);
            if (segmentFile.exists() && segmentFile.length() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean requestAudioFocusForRecording() {
        AudioManager manager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return false;

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build();
            result = manager.requestAudioFocus(audioFocusRequest);
        } else {
            result = manager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        AudioManager manager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            manager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            manager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    private void handleAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (recorderPausedForFocusLoss && customMediaRecorder != null) {
                try {
                    if (customMediaRecorder.resumeRecording()) {
                        recorderPausedForFocusLoss = false;
                        persistActiveRecordingSession(CurrentRecordingStatus.RECORDING.name(), null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to resume after audio focus gain", e);
                }
            }
            return;
        }

        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (customMediaRecorder != null &&
                customMediaRecorder.getCurrentStatus() == CurrentRecordingStatus.RECORDING) {
                try {
                    if (customMediaRecorder.pauseRecording()) {
                        recorderPausedForFocusLoss = true;
                        persistActiveRecordingSession("INTERRUPTED", "audioFocusLoss");
                    }
                } catch (Exception e) {
                    persistActiveRecordingSession("INTERRUPTED", "audioFocusLoss");
                    Log.e(TAG, "Failed to pause after audio focus loss", e);
                }
            }

            if (isStreaming) {
                Log.i(TAG, "Ignoring audio focus loss during live streaming; AudioRecord remains authoritative");
            }
        }
    }

     private long getMsDurationOfAudioFile(String filePath) {
         if (filePath == null) return -1;

         MediaPlayer mediaPlayer = new MediaPlayer();
         long duration = -1;
         try {
             mediaPlayer.setDataSource(filePath);
             mediaPlayer.prepare();
             duration = mediaPlayer.getDuration();
         } catch (IOException | IllegalStateException e) {
             Log.e(TAG, "Failed to get duration for file: " + filePath, e);
             return -1; // Return -1 on error
         } finally {
             mediaPlayer.release(); // Release the MediaPlayer resources
         }
         // Add a check for zero duration as well, treat as invalid
         return duration > 0 ? duration : -1;
     }


    private boolean isMicrophoneOccupied() {
        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return true; // Assume occupied if service not available

        // Check audio mode and if recording is active via AudioRecord (more complex)
        // A simple check is the audio mode. MODE_IN_COMMUNICATION often means mic is active.
         int mode = audioManager.getMode();
         // Consider other modes as potentially problematic too, though MODE_NORMAL should be safe.
         // This check is basic and might not cover all scenarios (e.g., other apps using AudioRecord directly).
        return mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL;
        // return audioManager.getMode() != AudioManager.MODE_NORMAL; // Stricter check
    }

     // --- Foreground Service Helpers ---

     private void startForegroundService(PluginCall call) {
         // If there is already a service running, stop it first
         if (ForegroundService.isServiceRunning()) {
             ForegroundService.stopService();
             
             // Wait a moment for service to properly stop
             try {
                 Thread.sleep(300);
             } catch (InterruptedException e) {
                 // Ignore
             }
         }
         
         Intent serviceIntent = new Intent(getContext(), ForegroundService.class);
         // Pass configuration like icon name if needed
         String smallIcon = call.getString("smallIcon");
         if (smallIcon != null) {
              serviceIntent.putExtra(ForegroundService.EXTRA_ICON_RES_NAME, smallIcon);
         }
         
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             getContext().startForegroundService(serviceIntent);
         } else {
              // Older versions don't have startForegroundService, just startService
              getContext().startService(serviceIntent);
         }
     }

     private void stopForegroundService() {
         // Use the static method to ensure we only stop the service if it's running
         ForegroundService.stopService();
         
         // Wait a moment to ensure service is stopped
         try {
             Thread.sleep(200);
         } catch (InterruptedException e) {
             // Ignore
         }
         
         // For additional safety, also try the old method
         Intent serviceIntent = new Intent(getContext(), ForegroundService.class);
         getContext().stopService(serviceIntent);
     }

    // Helper method to check if foreground service is active
    private boolean isForegroundServiceActive() {
        return ForegroundService.isServiceRunning();
    }

    // Getter for the active recorder
    public static CustomMediaRecorder getActiveRecorder() {
        return activeRecorder;
    }

    private android.media.AudioRecord audioRecord;
    private Thread streamingThread;
    private boolean isStreaming = false;
    private int streamingSampleRate = 48000; // Match iOS/Web default for WebRTC compatibility
    private int streamingChannelConfig = android.media.AudioFormat.CHANNEL_IN_MONO;
    private int streamingAudioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT;
    private int streamingBufferSize;

    @PluginMethod
    public void startAudioStream(PluginCall call) {
        String sessionId = call.getString("persistSessionId");
        if (isStreaming) {
            if (persistSessionId != null && persistSessionId.equals(sessionId)) {
                call.resolve(ResponseGenerator.successResponse());
            } else {
                call.resolve(ResponseGenerator.failResponse());
            }
            return;
        }

        if (customMediaRecorder != null && customMediaRecorder.getCurrentStatus() != CurrentRecordingStatus.NONE) {
            call.resolve(ResponseGenerator.failResponse());
            return;
        }

        // Check permissions first
        if (getPermissionState(RECORD_AUDIO_ALIAS) != PermissionState.GRANTED) {
            call.resolve(ResponseGenerator.failResponse());
            return;
        }

        // Get options directly from call parameters
        streamingSampleRate = call.getInt("sampleRate", 48000); // Default to 48kHz for WebRTC compatibility
        int channels = call.getInt("channels", 1);
        int requestedBufferSize = call.getInt("bufferSize", 4096);

        streamingChannelConfig = channels == 1 ?
            android.media.AudioFormat.CHANNEL_IN_MONO :
            android.media.AudioFormat.CHANNEL_IN_STEREO;

        streamingBufferSize = Math.max(
            requestedBufferSize * 2, // Convert to bytes (16-bit samples)
            android.media.AudioRecord.getMinBufferSize(
                streamingSampleRate,
                streamingChannelConfig,
                streamingAudioFormat
            )
        );

        boolean requestFgs = Boolean.TRUE.equals(call.getBoolean("useForegroundService", false));
        try {
            if (sessionId != null && !sessionId.isEmpty()) {
                if (!isValidSessionId(sessionId)) {
                    Log.e(TAG, "Invalid persistSessionId (unsafe characters)");
                    call.resolve(ResponseGenerator.failResponse());
                    return;
                }
                persistSessionId = sessionId;
                persistSessionDir = ensureSessionDir(sessionId);
                persistSeq.set(nextSeqForDir(persistSessionDir));
            } else {
                persistSessionId = null;
                persistSessionDir = null;
                persistSeq.set(0);
            }

            // Configure audio session for voice chat
            AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            }

            // Use VOICE_COMMUNICATION source for better voice chat quality
            audioRecord = new android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                streamingSampleRate,
                streamingChannelConfig,
                streamingAudioFormat,
                streamingBufferSize
            );

            if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                call.resolve(ResponseGenerator.failResponse());
                return;
            }

            if (requestFgs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                useForegroundService = true;
                foregroundServiceForStreaming = true;
                startForegroundService(call);
            }

            audioRecord.startRecording();
            isStreaming = true;

            // Start streaming thread
            streamingThread = new Thread(this::streamAudioData);
            streamingThread.start();

            call.resolve(ResponseGenerator.successResponse());
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio stream", e);
            abandonAudioFocus();
            if (foregroundServiceForStreaming && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                stopForegroundService();
                foregroundServiceForStreaming = false;
            }
            call.resolve(ResponseGenerator.failResponse());
        }
    }

    private int bufferCount = 0;
    private int silentBufferCount = 0;

    private void streamAudioData() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] audioBuffer = new short[streamingBufferSize / 2]; // 16-bit samples

        while (isStreaming && audioRecord != null) {
            int samplesRead;
            try {
                samplesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
            } catch (Exception e) {
                Log.e(TAG, "audioRecord.read failed", e);
                JSObject data = new JSObject();
                data.put("status", "INTERRUPTED");
                data.put("reason", "audioRecordReadException");
                notifyListeners(EVENT_STATE_CHANGE, data);
                break;
            }

            if (samplesRead < 0) {
                Log.e(TAG, "AudioRecord.read returned error: " + samplesRead);
                JSObject data = new JSObject();
                data.put("status", "INTERRUPTED");
                data.put("reason", "audioRecordReadError:" + samplesRead);
                notifyListeners(EVENT_STATE_CHANGE, data);
                break;
            }

            if (samplesRead > 0) {
                // Convert to float array for consistency with web
                float[] floatBuffer = new float[samplesRead];
                float sum = 0;
                for (int i = 0; i < samplesRead; i++) {
                    floatBuffer[i] = audioBuffer[i] / 32768.0f; // Normalize to [-1, 1]
                    sum += Math.abs(floatBuffer[i]);
                }

                float avgLevel = sum / samplesRead;
                bufferCount++;

                if (bufferCount % 100 == 0) {
                    if (avgLevel < 0.001f) {
                        silentBufferCount++;
                        if (silentBufferCount > 10) {
                            Log.w(TAG, "Android: Extended silence detected - check microphone input");
                        }
                    } else {
                        silentBufferCount = 0;
                    }
                }

                // If persistence is enabled, downsample to 16 kHz mono and write to disk
                // BEFORE emitting the JS event. This guarantees the chunk is durable
                // even if the JS bridge is unhealthy.
                Integer persistedSeq = null;
                String persistedPath = null;
                short[] pcm16kMono = null;
                if (persistSessionId != null && persistSessionDir != null) {
                    pcm16kMono = downsampleToMono16k(audioBuffer, samplesRead, streamingSampleRate);
                    try {
                        int seq = persistSeq.getAndIncrement();
                        File chunkFile = writeChunkAtomic(persistSessionDir, seq, pcm16kMono);
                        persistedSeq = seq;
                        persistedPath = chunkFile.getAbsolutePath();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to persist chunk", e);
                    }
                }

                // Emit to JS
                JSObject data = new JSObject();
                try {
                    com.getcapacitor.JSArray jsAudioData = new com.getcapacitor.JSArray();
                    if (pcm16kMono != null) {
                        // Emit the already-downsampled (normalized) samples so JS doesn't re-do work
                        for (short s : pcm16kMono) {
                            jsAudioData.put(s / 32768.0f);
                        }
                        data.put("sampleRate", LIVE_CHUNK_SAMPLE_RATE);
                        data.put("channels", LIVE_CHUNK_CHANNELS);
                        data.put("downsampled16kMono", true);
                    } else {
                        for (float sample : floatBuffer) {
                            jsAudioData.put(sample);
                        }
                        data.put("sampleRate", streamingSampleRate);
                        data.put("channels", streamingChannelConfig == android.media.AudioFormat.CHANNEL_IN_MONO ? 1 : 2);
                        data.put("downsampled16kMono", false);
                    }

                    data.put("audioData", jsAudioData);
                    data.put("timestamp", System.currentTimeMillis());
                    if (persistedSeq != null) {
                        data.put("seq", persistedSeq.intValue());
                        data.put("path", persistedPath);
                    }

                    notifyListeners("audioData", data);
                } catch (org.json.JSONException e) {
                    Log.e(TAG, "Error creating audio data JSON", e);
                }
            }
        }
        isStreaming = false;
        stopStreamingInternal();
    }

    // --- Chunk persistence helpers ---

    private static boolean isValidSessionId(String id) {
        if (id == null || id.isEmpty() || id.length() > 128) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                         (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private File liveChunkRootDir() {
        File root = new File(getContext().getFilesDir(), LIVE_CHUNK_ROOT);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    private File ensureSessionDir(String sessionId) {
        File dir = new File(liveChunkRootDir(), sessionId);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Scan existing chunk files in dir and return max(seq) + 1 so a restarted stream resumes cleanly. */
    private static int nextSeqForDir(File dir) {
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int maxSeq = -1;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".pcm")) continue;
            try {
                int seq = Integer.parseInt(name.substring(0, name.length() - 4));
                if (seq > maxSeq) maxSeq = seq;
            } catch (NumberFormatException ignored) {
            }
        }
        return maxSeq + 1;
    }

    private static String chunkFileName(int seq) {
        return String.format(Locale.US, "%06d.pcm", seq);
    }

    private static File writeChunkAtomic(File dir, int seq, short[] samples) throws IOException {
        File tmp = new File(dir, chunkFileName(seq) + ".tmp");
        File fin = new File(dir, chunkFileName(seq));
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) buf.putShort(s);
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(buf.array());
            fos.getFD().sync();
        } finally {
            try { fos.close(); } catch (IOException ignored) {}
        }
        if (!tmp.renameTo(fin)) {
            tmp.delete();
            throw new IOException("Failed to rename chunk tmp -> final");
        }
        return fin;
    }

    /**
     * Downsample 16-bit PCM mono at {@code inRate} to 16 kHz mono using simple integer-step
     * decimation/interpolation. Good enough for voice; we are not doing phase-accurate resampling.
     * For 48 kHz -> 16 kHz this is an exact 3:1 decimation with a 3-tap box filter.
     */
    private static short[] downsampleToMono16k(short[] samples, int length, int inRate) {
        if (inRate == LIVE_CHUNK_SAMPLE_RATE) {
            if (length == samples.length) return samples;
            return Arrays.copyOf(samples, length);
        }
        // Integer step (3:1, 2:1, etc.) when inRate is a clean multiple
        if (inRate % LIVE_CHUNK_SAMPLE_RATE == 0) {
            int step = inRate / LIVE_CHUNK_SAMPLE_RATE;
            int outLen = length / step;
            short[] out = new short[outLen];
            for (int i = 0; i < outLen; i++) {
                int acc = 0;
                int base = i * step;
                for (int k = 0; k < step && base + k < length; k++) {
                    acc += samples[base + k];
                }
                out[i] = (short) Math.max(-32768, Math.min(32767, acc / step));
            }
            return out;
        }
        // Fallback linear resampling
        double ratio = (double) inRate / LIVE_CHUNK_SAMPLE_RATE;
        int outLen = (int) Math.floor(length / ratio);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double srcPos = i * ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;
            short a = samples[srcIdx];
            short b = srcIdx + 1 < length ? samples[srcIdx + 1] : a;
            out[i] = (short) (a + (b - a) * frac);
        }
        return out;
    }

    @PluginMethod
    public void stopAudioStream(PluginCall call) {
        try {
            stopStreamingInternal();
            call.resolve(ResponseGenerator.successResponse());
        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio stream", e);
            call.resolve(ResponseGenerator.failResponse());
        }
    }

    private synchronized void stopStreamingInternal() {
        isStreaming = false;
        bufferCount = 0;
        silentBufferCount = 0;

        if (streamingThread != null) {
            streamingThread.interrupt();
            streamingThread = null;
        }

        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }

        abandonAudioFocus();

        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        if (foregroundServiceForStreaming && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForegroundService();
            foregroundServiceForStreaming = false;
        }

        persistSessionId = null;
        persistSessionDir = null;
        persistSeq.set(0);
    }

    @PluginMethod
    public void getStreamingStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("status", isStreaming ? "STREAMING" : "STOPPED");
        call.resolve(result);
    }

    // --- Live-chunk plugin methods ---

    @PluginMethod
    public void listLiveChunkSessions(PluginCall call) {
        JSArray arr = new JSArray();
        File root = liveChunkRootDir();
        File[] dirs = root.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory()) arr.put(d.getName());
            }
        }
        JSObject res = new JSObject();
        res.put("sessions", arr);
        call.resolve(res);
    }

    @PluginMethod
    public void listLiveChunks(PluginCall call) {
        String sessionId = call.getString("sessionId");
        if (!isValidSessionId(sessionId)) {
            call.reject("Invalid sessionId");
            return;
        }
        File dir = new File(liveChunkRootDir(), sessionId);
        JSArray arr = new JSArray();
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                for (File f : files) {
                    String name = f.getName();
                    if (!name.endsWith(".pcm")) continue;
                    try {
                        int seq = Integer.parseInt(name.substring(0, name.length() - 4));
                        JSObject info = new JSObject();
                        info.put("seq", seq);
                        info.put("path", f.getAbsolutePath());
                        info.put("size", f.length());
                        arr.put(info);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        JSObject res = new JSObject();
        res.put("chunks", arr);
        call.resolve(res);
    }

    @PluginMethod
    public void readLiveChunk(PluginCall call) {
        String sessionId = call.getString("sessionId");
        Integer seq = call.getInt("seq");
        if (!isValidSessionId(sessionId) || seq == null) {
            call.reject("Invalid sessionId or seq");
            return;
        }
        File f = new File(new File(liveChunkRootDir(), sessionId), chunkFileName(seq));
        if (!f.exists()) {
            call.reject("Chunk not found");
            return;
        }
        try {
            byte[] bytes = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            try {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = fis.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            } finally {
                try { fis.close(); } catch (IOException ignored) {}
            }
            String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            JSObject res = new JSObject();
            res.put("data", b64);
            res.put("size", bytes.length);
            call.resolve(res);
        } catch (IOException e) {
            call.reject("Failed to read chunk: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deleteLiveChunk(PluginCall call) {
        String sessionId = call.getString("sessionId");
        Integer seq = call.getInt("seq");
        if (!isValidSessionId(sessionId) || seq == null) {
            call.reject("Invalid sessionId or seq");
            return;
        }
        File f = new File(new File(liveChunkRootDir(), sessionId), chunkFileName(seq));
        boolean ok = !f.exists() || f.delete();
        call.resolve(ResponseGenerator.fromBoolean(ok));
    }

    @PluginMethod
    public void deleteLiveChunkSession(PluginCall call) {
        String sessionId = call.getString("sessionId");
        if (!isValidSessionId(sessionId)) {
            call.reject("Invalid sessionId");
            return;
        }
        File dir = new File(liveChunkRootDir(), sessionId);
        boolean ok = deleteRecursively(dir);
        call.resolve(ResponseGenerator.fromBoolean(ok));
    }

    @PluginMethod
    public void getLiveChunkSessionInfo(PluginCall call) {
        String sessionId = call.getString("sessionId");
        if (!isValidSessionId(sessionId)) {
            call.reject("Invalid sessionId");
            return;
        }
        File dir = new File(liveChunkRootDir(), sessionId);
        long totalBytes = 0;
        int count = 0;
        int firstSeq = -1;
        int lastSeq = -1;
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (!name.endsWith(".pcm")) continue;
                    try {
                        int seq = Integer.parseInt(name.substring(0, name.length() - 4));
                        totalBytes += f.length();
                        count++;
                        if (firstSeq == -1 || seq < firstSeq) firstSeq = seq;
                        if (seq > lastSeq) lastSeq = seq;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        JSObject res = new JSObject();
        res.put("totalBytes", totalBytes);
        res.put("count", count);
        res.put("firstSeq", firstSeq);
        res.put("lastSeq", lastSeq);
        res.put("dir", dir.getAbsolutePath());
        call.resolve(res);
    }

    @PluginMethod
    public void assembleLiveChunksToWav(PluginCall call) {
        String sessionId = call.getString("sessionId");
        String outputPath = call.getString("outputPath");
        int sampleRate = call.getInt("sampleRate", LIVE_CHUNK_SAMPLE_RATE);
        int channels = call.getInt("channels", LIVE_CHUNK_CHANNELS);
        if (!isValidSessionId(sessionId) || outputPath == null || outputPath.isEmpty()) {
            call.reject("Invalid sessionId or outputPath");
            return;
        }
        File dir = new File(liveChunkRootDir(), sessionId);
        if (!dir.exists()) {
            call.reject("Session directory not found");
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        long dataBytes = 0;
        for (File f : files) {
            if (f.getName().endsWith(".pcm")) dataBytes += f.length();
        }
        if (dataBytes == 0) {
            call.reject("No chunks to assemble");
            return;
        }

        File outFile = new File(outputPath);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try {
            RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
            try {
                // 44-byte WAV header
                byte[] header = buildWavHeader(sampleRate, channels, dataBytes);
                raf.write(header);
                byte[] buffer = new byte[64 * 1024];
                for (File f : files) {
                    if (!f.getName().endsWith(".pcm")) continue;
                    FileInputStream fis = new FileInputStream(f);
                    try {
                        int n;
                        while ((n = fis.read(buffer)) > 0) {
                            raf.write(buffer, 0, n);
                        }
                    } finally {
                        try { fis.close(); } catch (IOException ignored) {}
                    }
                }
                raf.getFD().sync();
            } finally {
                try { raf.close(); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            call.reject("Failed to assemble WAV: " + e.getMessage());
            return;
        }

        long msDuration = (dataBytes * 1000L) / ((long) sampleRate * channels * LIVE_CHUNK_BYTES_PER_SAMPLE);
        JSObject res = new JSObject();
        res.put("filePath", outFile.getAbsolutePath());
        res.put("msDuration", msDuration);
        res.put("size", outFile.length());
        call.resolve(res);
    }

    private static byte[] buildWavHeader(int sampleRate, int channels, long dataBytes) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        h.putInt((int) (36 + dataBytes));
        h.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        h.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        h.putInt(16); // fmt chunk size
        h.putShort((short) 1); // PCM format
        h.putShort((short) channels);
        h.putInt(sampleRate);
        h.putInt(sampleRate * channels * LIVE_CHUNK_BYTES_PER_SAMPLE);
        h.putShort((short) (channels * LIVE_CHUNK_BYTES_PER_SAMPLE));
        h.putShort((short) 16);
        h.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        h.putInt((int) dataBytes);
        return h.array();
    }

    private static boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursively(k);
            }
        }
        return f.delete();
    }
}