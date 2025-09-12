package com.xitronix.capacitorvoicerec;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
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

    // Static reference to the active recorder for the foreground service
    private static CustomMediaRecorder activeRecorder;

    @Override
    public void load() {
        // Initialization if needed when the plugin loads
         // customMediaRecorder = new CustomMediaRecorder(getContext()); // Instantiate here? Or per recording? Per recording seems better.
         // customMediaRecorder.setListener(this); // Set listener if instance is kept
    }

    // Callback from CustomMediaRecorder
    @Override
    public void onStatusChange(CurrentRecordingStatus status) {
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
            activeRecorder = customMediaRecorder; // Update static reference

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
            activeRecorder = null; // Clear static reference
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
        if (customMediaRecorder == null) {
            // If no recorder initialized, status is NONE
            call.resolve(ResponseGenerator.statusResponse(CurrentRecordingStatus.NONE));
        } else {
            call.resolve(ResponseGenerator.statusResponse(customMediaRecorder.getCurrentStatus()));
        }
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
        if (isStreaming) {
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

        try {
            // Configure audio session for voice chat (similar to iOS setup)
            AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                // Set audio mode for voice communication (similar to iOS .voiceChat mode)
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                Log.d(TAG, "Android: Set audio mode to MODE_IN_COMMUNICATION for voice chat");
            }
            
            // Use VOICE_COMMUNICATION source for better voice chat quality (similar to iOS .voiceChat)
            audioRecord = new android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                streamingSampleRate,
                streamingChannelConfig,
                streamingAudioFormat,
                streamingBufferSize
            );
            
            Log.d(TAG, "Android: AudioRecord created - sampleRate: " + streamingSampleRate + "Hz, channels: " + channels + ", bufferSize: " + streamingBufferSize);

            if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                call.resolve(ResponseGenerator.failResponse());
                return;
            }

            audioRecord.startRecording();
            isStreaming = true;
            
            Log.d(TAG, "Android: ✅ Audio streaming started successfully");

            // Start streaming thread
            streamingThread = new Thread(this::streamAudioData);
            streamingThread.start();

            call.resolve(ResponseGenerator.successResponse());
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio stream", e);
            call.resolve(ResponseGenerator.failResponse());
        }
    }

    private int bufferCount = 0;
    private int silentBufferCount = 0;
    
    private void streamAudioData() {
        short[] audioBuffer = new short[streamingBufferSize / 2]; // 16-bit samples
        
        while (isStreaming && audioRecord != null) {
            int samplesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
            
            if (samplesRead > 0) {
                // Convert to float array for consistency with web
                float[] floatBuffer = new float[samplesRead];
                float sum = 0;
                for (int i = 0; i < samplesRead; i++) {
                    floatBuffer[i] = audioBuffer[i] / 32768.0f; // Normalize to [-1, 1]
                    sum += Math.abs(floatBuffer[i]);
                }
                
                // Calculate audio level for monitoring (similar to iOS)
                float avgLevel = sum / samplesRead;
                bufferCount++;
                
                // Log audio levels periodically (similar to iOS logging)
                if (bufferCount % 20 == 0) { // Every 20 buffers
                    Log.d(TAG, "Android: Buffer #" + bufferCount + ", " + samplesRead + " samples, avg level: " + avgLevel + ", sampleRate: " + streamingSampleRate + "Hz");
                    
                    if (avgLevel > 0.01f) {
                        Log.d(TAG, "Android: 🎤 Good audio detected!");
                        silentBufferCount = 0;
                    } else if (avgLevel < 0.001f) {
                        silentBufferCount++;
                        Log.d(TAG, "Android: 🔇 Very low audio level detected (silent count: " + silentBufferCount + ")");
                        
                        if (silentBufferCount > 50) { // ~2.3 seconds of silence
                            Log.w(TAG, "Android: ⚠️ Extended silence detected - check microphone input");
                        }
                    }
                }

                // Send data to JavaScript - Convert float array to JSArray for proper JS compatibility
                JSObject data = new JSObject();
                
                try {
                    // Convert float[] to JSArray to ensure it's a proper JavaScript array
                    com.getcapacitor.JSArray jsAudioData = new com.getcapacitor.JSArray();
                    for (float sample : floatBuffer) {
                        jsAudioData.put(sample);
                    }
                    
                    data.put("audioData", jsAudioData);
                    data.put("sampleRate", streamingSampleRate);
                    data.put("timestamp", System.currentTimeMillis());
                    data.put("channels", streamingChannelConfig == android.media.AudioFormat.CHANNEL_IN_MONO ? 1 : 2);

                    notifyListeners("audioData", data);
                } catch (org.json.JSONException e) {
                    Log.e(TAG, "Error creating audio data JSON", e);
                    // Continue streaming even if one buffer fails
                }
            }
        }
    }

    @PluginMethod
    public void stopAudioStream(PluginCall call) {
        try {
            Log.d(TAG, "Android: Stopping audio stream");
            isStreaming = false;
            
            // Reset counters
            bufferCount = 0;
            silentBufferCount = 0;
            
            if (streamingThread != null) {
                streamingThread.interrupt();
                streamingThread = null;
            }
            
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
            
            // Reset audio mode to normal (similar to iOS cleanup)
            AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                Log.d(TAG, "Android: Reset audio mode to MODE_NORMAL");
            }
            
            Log.d(TAG, "Android: ✅ Audio stream stopped successfully");

            call.resolve(ResponseGenerator.successResponse());
        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio stream", e);
            call.resolve(ResponseGenerator.failResponse());
        }
    }

    @PluginMethod
    public void getStreamingStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("status", isStreaming ? "STREAMING" : "STOPPED");
        call.resolve(result);
    }
}