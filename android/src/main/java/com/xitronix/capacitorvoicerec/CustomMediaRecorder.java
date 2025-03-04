package com.xitronix.capacitorvoicerec;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import android.os.Environment;
import android.util.Log;

import java.util.UUID;

public class CustomMediaRecorder {

    private final Context context;
    private MediaRecorder mediaRecorder;
    private File outputFile;
    private CurrentRecordingStatus currentRecordingStatus = CurrentRecordingStatus.NONE;

    public CustomMediaRecorder(Context context, String directory) throws IOException {
        this.context = context;
        generateMediaRecorder(directory);
    }

    private void generateMediaRecorder(String directory) throws IOException {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(96000);
        mediaRecorder.setAudioSamplingRate(44100);

        outputFile = getFileObject("voice_record_" + UUID.randomUUID().toString() + ".aac", directory);
        
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
        mediaRecorder.prepare();
    }

    public File getDirectory(String directory) {
        switch (directory) {
            case "TEMPORARY":
            case "CACHE":
                return this.context.getCacheDir();
            default:
                return context.getFilesDir(); // Persistent storage
        }

    }

    public File getFileObject(String path, String directory) {

        File androidDirectory = this.getDirectory(directory);
        File outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC); // Persistent storage
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs();
        }
        outputFile = new File(outputDir, "voice_recording_" + UUID.randomUUID().toString() + ".aac");


        if (androidDirectory == null) {
            return null;
        } else {
            if (!androidDirectory.exists()) {
                androidDirectory.mkdir();
            }
        }

        return new File(androidDirectory, path);
    }

    public void startRecording() {
        mediaRecorder.start();
        currentRecordingStatus = CurrentRecordingStatus.RECORDING;
        return getOutputFilePath();
    }

    public void stopRecording() {
        mediaRecorder.stop();
        mediaRecorder.release();
        currentRecordingStatus = CurrentRecordingStatus.NONE;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public String getOutputFilePath() {
        Log.w("ABS_PATH", "getOutputFilePath" + outputFile.getAbsolutePath() );
        return outputFile != null ? outputFile.getAbsolutePath() : null;
    }

    public boolean pauseRecording() throws NotSupportedOsVersion {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw new NotSupportedOsVersion();
        }

        if (currentRecordingStatus == CurrentRecordingStatus.RECORDING) {
            mediaRecorder.pause();
            currentRecordingStatus = CurrentRecordingStatus.PAUSED;
            return true;
        } else {
            return false;
        }
    }

    public boolean resumeRecording() throws NotSupportedOsVersion {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw new NotSupportedOsVersion();
        }

        if (currentRecordingStatus == CurrentRecordingStatus.PAUSED) {
            mediaRecorder.resume();
            currentRecordingStatus = CurrentRecordingStatus.RECORDING;
            return true;
        } else {
            return false;
        }
    }

    public CurrentRecordingStatus getCurrentStatus() {
        return currentRecordingStatus;
    }

    public boolean deleteOutputFile() {
        return outputFile.delete();
    }

    public static boolean canPhoneCreateMediaRecorder(Context context) {
        return true;
    }

    private static boolean canPhoneCreateMediaRecorderWhileHavingPermission(Context context) {
        CustomMediaRecorder tempMediaRecorder = null;
        try {
            tempMediaRecorder = new CustomMediaRecorder(context, "CACHE");
            tempMediaRecorder.startRecording();
            tempMediaRecorder.stopRecording();
            return true;
        } catch (Exception exp) {
            return exp.getMessage().startsWith("stop failed");
        } finally {
            if (tempMediaRecorder != null) tempMediaRecorder.deleteOutputFile();
        }
    }

    
//
//    /**
//     * Checks the the given permission is granted or not
//     * @return Returns true if the app is running on Android 30 or newer or if the permission is already granted
//     * or false if it is denied.
//     */
//    private boolean isStoragePermissionGranted() {
//        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || getPermissionState(PUBLIC_STORAGE) == PermissionState.GRANTED;
//    }
}
