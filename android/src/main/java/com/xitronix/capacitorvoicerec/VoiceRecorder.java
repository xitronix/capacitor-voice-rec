package com.xitronix.capacitorvoicerec;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.IOException;

@CapacitorPlugin(
    name = "VoiceRecorder",
    permissions = { @Permission(alias = VoiceRecorder.RECORD_AUDIO_ALIAS, strings = { Manifest.permission.RECORD_AUDIO }) }
)
public class VoiceRecorder extends Plugin implements CustomMediaRecorder.OnStatusChangeListener {

    static final String RECORD_AUDIO_ALIAS = "voice recording";
    private static final String TAG = "VoiceRecorderPlugin";
    private static final String EVENT_STATE_CHANGE = "recordingStateChange";

    private CustomMediaRecorder customMediaRecorder;
    private boolean useForegroundService = false;
    // private String currentDirectory = "DOCUMENTS"; // Store directory if needed across calls

    @Override
    public void load() {
        // Initialization if needed when the plugin loads
         // customMediaRecorder = new CustomMediaRecorder(getContext()); // Instantiate here? Or per recording? Per recording seems better.
         // customMediaRecorder.setListener(this); // Set listener if instance is kept
    }

    // Callback from CustomMediaRecorder
    @Override
    public void onStatusChange(CurrentRecordingStatus status) {
        Log.d(TAG, "Notifying status change: " + status);
        notifyListeners(EVENT_STATE_CHANGE, ResponseGenerator.statusResponse(status));
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
            Log.d(TAG, "Permission already granted.");
            call.resolve(ResponseGenerator.successResponse());
        } else {
            Log.d(TAG, "Requesting permission.");
            // Use requestPermissionForAlias for consistent handling
            requestPermissionForAlias(RECORD_AUDIO_ALIAS, call, "recordAudioPermissionCallback");
        }
    }

    @PermissionCallback
    private void recordAudioPermissionCallback(PluginCall call) {
        if (doesUserGaveAudioRecordingPermission()) {
            Log.d(TAG, "Permission granted after request.");
            call.resolve(ResponseGenerator.successResponse());
        } else {
            Log.d(TAG, "Permission denied after request.");
            call.reject(Messages.MISSING_PERMISSION); // Reject if denied
        }
    }

    @PluginMethod
    public void hasAudioRecordingPermission(PluginCall call) {
        call.resolve(ResponseGenerator.fromBoolean(doesUserGaveAudioRecordingPermission()));
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
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
        // this.currentDirectory = directory; // Store if needed

        try {
             // Start foreground service if requested
            if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                 startForegroundService(call);
             }

            // Create a new recorder instance for each recording session
            customMediaRecorder = new CustomMediaRecorder(getContext());
            customMediaRecorder.setListener(this); // Set listener for status updates

            String filePathUri = customMediaRecorder.startRecording(directory);

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
             if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  stopForegroundService();
             }
            call.reject(Messages.FAILED_TO_RECORD, exp);
        }
    }

     @PluginMethod
     public void continueRecording(PluginCall call) {
         if (!doesUserGaveAudioRecordingPermission()) {
             call.reject(Messages.MISSING_PERMISSION, RECORD_AUDIO_ALIAS);
             return;
         }

         if (isMicrophoneOccupied()) {
              call.reject(Messages.MICROPHONE_BEING_USED);
              return;
         }

         // Allow continuing only if stopped (NONE state)
         if (customMediaRecorder != null && customMediaRecorder.getCurrentStatus() != CurrentRecordingStatus.NONE) {
             call.reject("Cannot continue recording, recorder is currently active or paused. Stop recording first.");
             return;
         }

          // Get previous file path and directory
         String prevFilePathUri = call.getString("filePath");
         String directory = call.getString("directory", "DOCUMENTS"); // Directory for the *new* segment
          // this.currentDirectory = directory;

          if (prevFilePathUri == null || prevFilePathUri.isEmpty()) {
             call.reject("Missing required 'filePath' for previous recording segment.");
             return;
          }

          // Validate URI format (basic)
         if (!prevFilePathUri.startsWith("file://")) {
              Log.w(TAG, "Previous file path does not start with file://, using as is: " + prevFilePathUri);
              // Consider rejecting if format is strictly expected:
              // call.reject("Invalid 'filePath' format. Expected a file URI (file://...).");
              // return;
         }


         try {
              // Start foreground service if requested (and not already running, though state check above should handle this)
              if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  startForegroundService(call);
              }

              // Create a new recorder instance (or reuse if designed differently, but new seems cleaner)
             customMediaRecorder = new CustomMediaRecorder(getContext());
             customMediaRecorder.setListener(this);

             String newSegmentPathUri = customMediaRecorder.continueRecording(prevFilePathUri, directory);

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
              if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  stopForegroundService();
             }
             call.reject("Failed to continue recording: " + e.getMessage(), e);
         } catch (Exception e) { // Catch other potential errors
              Log.e(TAG, "Unexpected error during continue recording", e);
               if (customMediaRecorder != null) {
                  customMediaRecorder = null;
              }
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
                Log.w(TAG,"Recording resulted in empty or invalid file: " + finalPath + " Duration: " + duration);
                new File(finalPath).delete();
                call.reject(Messages.EMPTY_RECORDING);
            } else {
                RecordData recordData = new RecordData(duration, "audio/aac", finalPath); // Use direct path instead of URI
                call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));
            }

        } catch (Exception exp) {
             Log.e(TAG, "Stop Recording failed", exp);
             // Attempt to stop foreground service even if stopRecorder failed
              if (useForegroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  stopForegroundService();
              }
            call.reject(Messages.FAILED_TO_FETCH_RECORDING, exp);
        } finally {
            // Clean up the recorder instance after stopping
            customMediaRecorder = null;
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
        CurrentRecordingStatus status = (customMediaRecorder != null)
            ? customMediaRecorder.getCurrentStatus()
            : CurrentRecordingStatus.NONE;
        call.resolve(ResponseGenerator.statusResponse(status));
    }

    // --- Helper Methods ---

    private boolean doesUserGaveAudioRecordingPermission() {
        return getPermissionState(RECORD_AUDIO_ALIAS) == PermissionState.GRANTED;
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
         Log.d(TAG, "Current AudioManager mode: " + mode);
         // Consider other modes as potentially problematic too, though MODE_NORMAL should be safe.
         // This check is basic and might not cover all scenarios (e.g., other apps using AudioRecord directly).
        return mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL;
        // return audioManager.getMode() != AudioManager.MODE_NORMAL; // Stricter check
    }

     // --- Foreground Service Helpers ---

     private void startForegroundService(PluginCall call) {
         Intent serviceIntent = new Intent(getContext(), ForegroundService.class);
         // Pass configuration like icon name if needed
         String smallIcon = call.getString("smallIcon"); // Make sure this matches the expected key
         if (smallIcon != null) {
              serviceIntent.putExtra(ForegroundService.EXTRA_ICON_RES_NAME, smallIcon);
              Log.d(TAG, "Starting Foreground Service with custom icon name: " + smallIcon);
         } else {
              Log.d(TAG, "Starting Foreground Service with default icon.");
         }
          // Add other extras like notification title/text if configurable
          // serviceIntent.putExtra(ForegroundService.EXTRA_NOTIFICATION_TITLE, call.getString("notificationTitle", "Recording Audio"));
          // serviceIntent.putExtra(ForegroundService.EXTRA_NOTIFICATION_TEXT, call.getString("notificationText", "Tap to return to app"));

         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             getContext().startForegroundService(serviceIntent);
         } else {
              // Older versions don't have startForegroundService, just startService
              getContext().startService(serviceIntent);
              // Note: foreground notification might behave differently pre-Oreo
         }
     }

     private void stopForegroundService() {
          Log.d(TAG, "Stopping Foreground Service.");
         Intent serviceIntent = new Intent(getContext(), ForegroundService.class);
         getContext().stopService(serviceIntent);
     }

    // Optional: Add a method to delete recordings if needed (like iOS/Web)
    // @PluginMethod
    // public void deleteRecording(PluginCall call) { ... }

}