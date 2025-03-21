package com.xitronix.capacitorvoicerec;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Base64;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import com.getcapacitor.JSObject;

@CapacitorPlugin(
    name = "VoiceRecorder",
    permissions = { @Permission(alias = VoiceRecorder.RECORD_AUDIO_ALIAS, strings = { Manifest.permission.RECORD_AUDIO }) }
)
public class VoiceRecorder extends Plugin {

    static final String RECORD_AUDIO_ALIAS = "voice recording";
    private CustomMediaRecorder mediaRecorder;
    private boolean useForegroundService = false;
    private String directory = "DOCUMENTS";

    @PluginMethod
    public void canDeviceVoiceRecord(PluginCall call) {
        if (CustomMediaRecorder.canPhoneCreateMediaRecorder(getContext())) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            call.resolve(ResponseGenerator.failResponse());
        }
    }

    @PluginMethod
    public void requestAudioRecordingPermission(PluginCall call) {
        if (doesUserGaveAudioRecordingPermission()) {
            call.resolve(ResponseGenerator.successResponse());
        } else {
            requestPermissionForAlias(RECORD_AUDIO_ALIAS, call, "recordAudioPermissionCallback");
        }
    }

    @PermissionCallback
    private void recordAudioPermissionCallback(PluginCall call) {
        this.hasAudioRecordingPermission(call);
    }

    @PluginMethod
    public void hasAudioRecordingPermission(PluginCall call) {
        call.resolve(ResponseGenerator.fromBoolean(doesUserGaveAudioRecordingPermission()));
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (!CustomMediaRecorder.canPhoneCreateMediaRecorder(getContext())) {
            call.reject(Messages.CANNOT_RECORD_ON_THIS_PHONE);
            return;
        }

        if (!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION);
            return;
        }

        if (this.isMicrophoneOccupied()) {
            call.reject(Messages.MICROPHONE_BEING_USED);
            return;
        }

        if (mediaRecorder != null) {
            call.reject(Messages.ALREADY_RECORDING);
            return;
        }

        directory = call.getString("directory", "DOCUMENTS");

        useForegroundService = Boolean.TRUE.equals(call.getBoolean("useForegroundService", false));
        try {
            if (useForegroundService) {
                Intent serviceIntent = new Intent(getContext(), ForegroundService.class);

                String iconResName = call.getString("smallIcon");
                serviceIntent.putExtra(ForegroundService.EXTRA_ICON_RES_NAME, iconResName);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getContext().startForegroundService(serviceIntent);
                }
            }
            mediaRecorder = new CustomMediaRecorder(getContext(), directory);
            String filePath = mediaRecorder.startRecording();
            notifyRecordingStateChange(CurrentRecordingStatus.RECORDING);
            RecordData recordData = new RecordData(
                -1, // duration not available at start
                "audio/aac",
                filePath
            );
            call.resolve(ResponseGenerator.dataResponse(recordData.toJSObject()));
        } catch (Exception exp) {
            call.reject(Messages.FAILED_TO_RECORD, exp);
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            if (mediaRecorder == null) {
                call.reject(Messages.NOT_RECORDING);
                return;
            }

            String recordedFilePath = mediaRecorder.getOutputFilePath();
            mediaRecorder.stopRecording();
            notifyRecordingStateChange(CurrentRecordingStatus.NONE);

            if (useForegroundService) {
                getContext().stopService(new Intent(getContext(), ForegroundService.class));
            }

            File recordedFile = new File(recordedFilePath);
            if (!recordedFile.exists()) {
                call.reject(Messages.FILE_DOES_NOT_EXIST);
                return;
            }

            String path = recordedFile.getAbsolutePath();
            RecordData recordData = new RecordData(
                getMsDurationOfAudioFile(path),
                "audio/aac",
                path
            );

            mediaRecorder = null;
            call.resolve(recordData.toJSObject());
        } catch (Exception exception) {
            call.reject(Messages.RECORDING_FAILED, exception);
        }
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        if (mediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }
        try {
            mediaRecorder.pauseRecording();
            notifyRecordingStateChange(CurrentRecordingStatus.PAUSED);
            call.resolve(ResponseGenerator.successResponse());
        } catch (NotSupportedOsVersion exception) {
            call.reject(Messages.NOT_SUPPORTED_OS_VERSION);
        }
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        if (mediaRecorder == null) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED);
            return;
        }
        try {
            mediaRecorder.resumeRecording();
            notifyRecordingStateChange(CurrentRecordingStatus.RECORDING);
            call.resolve(ResponseGenerator.successResponse());
        } catch (NotSupportedOsVersion exception) {
            call.reject(Messages.NOT_SUPPORTED_OS_VERSION);
        }
    }

    @PluginMethod
    public void getCurrentStatus(PluginCall call) {
        if (mediaRecorder == null) {
            call.resolve(ResponseGenerator.statusResponse(CurrentRecordingStatus.NONE));
        } else {
            call.resolve(ResponseGenerator.statusResponse(mediaRecorder.getCurrentStatus()));
        }
    }

    private boolean doesUserGaveAudioRecordingPermission() {
        return getPermissionState(VoiceRecorder.RECORD_AUDIO_ALIAS).equals(PermissionState.GRANTED);
    }

    private int getMsDurationOfAudioFile(String recordedFilePath) {
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(recordedFilePath);
            mediaPlayer.prepare();
            return mediaPlayer.getDuration();
        } catch (Exception ignore) {
            return -1;
        }
    }

    private boolean isMicrophoneOccupied() {
        AudioManager audioManager = (AudioManager) this.getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return true;
        return audioManager.getMode() != AudioManager.MODE_NORMAL;
    }

    private void notifyRecordingStateChange(CurrentRecordingStatus status) {
        JSObject ret = new JSObject();
        ret.put("status", status.toString());
        notifyListeners("recordingStateChange", ret);
    }

    // @PluginMethod
    // public void deleteFile(PluginCall call) {
    //     String file = call.getString("path");
    //     String directory = getDirectoryParameter(call);
    //     if (isPublicDirectory(directory) && !isStoragePermissionGranted()) {
    //         requestAllPermissions(call, "permissionCallback");
    //     } else {
    //         try {
    //             boolean deleted = implementation.deleteFile(file, directory);
    //             if (!deleted) {
    //                 call.reject("Unable to delete file");
    //             } else {
    //                 call.resolve();
    //             }
    //         } catch (FileNotFoundException ex) {
    //             call.reject(ex.getMessage());
    //         }
    //     }
    // }
}
