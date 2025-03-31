package com.xitronix.capacitorvoicerec;

import android.content.Context;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class CustomMediaRecorder {

    private static final String TAG = "CustomMediaRecorder";
    private final Context context;
    private MediaRecorder mediaRecorder;
    private String currentFilePath = null; // Path to the current recording segment
    private String previousFilePath = null; // Path to the previous segment (if continuing)
    private CurrentRecordingStatus currentRecordingStatus = CurrentRecordingStatus.NONE;
    private OnStatusChangeListener listener;

    // Interface for status change callbacks
    public interface OnStatusChangeListener {
        void onStatusChange(CurrentRecordingStatus status);
    }

    public CustomMediaRecorder(Context context) {
        this.context = context;
    }

    public void setListener(OnStatusChangeListener listener) {
        this.listener = listener;
    }

    private void setStatus(CurrentRecordingStatus newStatus) {
        if (currentRecordingStatus != newStatus) {
            currentRecordingStatus = newStatus;
            if (listener != null) {
                listener.onStatusChange(newStatus);
            }
            Log.d(TAG, "Status changed to: " + newStatus);
        }
    }

    // Initializes a new MediaRecorder instance for a new segment
    private void initRecorder(String directory) throws IOException {
        cleanupRecorder(); // Clean up any previous instance first

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(96000); // Adjust as needed
        mediaRecorder.setAudioSamplingRate(44100);   // Standard CD quality

        // Generate a unique filename for the new segment
        String fileName = "voice_record_" + UUID.randomUUID().toString() + ".aac";
        File outputFile = getFileObject(fileName, directory);
        if (outputFile == null) {
            throw new IOException("Could not create output file in directory: " + directory);
        }
        currentFilePath = outputFile.getAbsolutePath();

        Log.d(TAG, "Recording segment to: " + currentFilePath);
        mediaRecorder.setOutputFile(currentFilePath);

        try {
             mediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder prepare() failed: " + e.getMessage());
            cleanupRecorder(); // Clean up if prepare fails
            throw e; // Re-throw the exception
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder prepare() failed (IllegalState): " + e.getMessage());
            cleanupRecorder();
            throw new IOException("MediaRecorder prepare failed due to illegal state.", e);
        }
    }

    // Gets the appropriate directory based on the provided string identifier
    public File getDirectory(String directoryIdentifier) {
        File baseDir;
        if (directoryIdentifier == null) {
             directoryIdentifier = "DOCUMENTS"; // Default
        }
        switch (directoryIdentifier.toUpperCase()) {
            case "TEMPORARY":
            case "CACHE":
                baseDir = this.context.getCacheDir();
                break;
            case "DOCUMENTS":
            default:
                // Using getFilesDir() for app-specific persistent storage
                 baseDir = context.getFilesDir();
                // Alternatively, for external (but still app-specific) storage:
                // baseDir = context.getExternalFilesDir(null);
                break;
        }
        // Ensure the directory exists
        if (baseDir != null && !baseDir.exists()) {
            if (!baseDir.mkdirs()) {
                 Log.w(TAG, "Could not create directory: " + baseDir.getAbsolutePath());
                 // Fallback to internal cache dir if creation fails
                 return this.context.getCacheDir();
            }
        }
        return baseDir;
    }

    // Creates a File object within the specified directory
    public File getFileObject(String fileName, String directoryIdentifier) {
        File directory = getDirectory(directoryIdentifier);
        if (directory == null) {
            Log.e(TAG, "Failed to get directory: " + directoryIdentifier);
            return null;
        }
        return new File(directory, fileName);
    }

    // Starts a completely new recording
    public String startRecording(String directory) throws IOException {
        previousFilePath = null; // Ensure this is reset for a fresh start
        initRecorder(directory); // Initialize for the first segment

        try {
            mediaRecorder.start();
            setStatus(CurrentRecordingStatus.RECORDING);
            Log.i(TAG, "Started new recording to: " + currentFilePath);
            return getOutputFilePathUri(); // Return the path of the first segment
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder start failed: " + e.getMessage());
            cleanupRecorder(); // Clean up on failure
            setStatus(CurrentRecordingStatus.NONE);
            throw new IOException("Failed to start recording.", e);
        }
    }

    // Continues recording from a previous file
    public String continueRecording(String prevFilePathUri, String directory) throws IOException {
         if (currentRecordingStatus == CurrentRecordingStatus.RECORDING || currentRecordingStatus == CurrentRecordingStatus.PAUSED) {
            throw new IOException("Cannot continue recording, already recording or paused.");
         }

         // Convert URI string back to File path if needed
         String prevFilePath;
         if (prevFilePathUri != null && prevFilePathUri.startsWith("file://")) {
            Uri uri = Uri.parse(prevFilePathUri);
            prevFilePath = uri.getPath();
         } else {
            prevFilePath = prevFilePathUri; // Assume it's already a direct path
         }


         // Basic validation of the previous file path
         if (prevFilePath == null || prevFilePath.isEmpty()) {
             throw new IOException("Previous file path is null or empty.");
         }
         File prevFile = new File(prevFilePath);
         if (!prevFile.exists() || !prevFile.isFile() || prevFile.length() == 0) {
             throw new IOException("Previous recording file is invalid or empty: " + prevFilePath);
         }

        this.previousFilePath = prevFilePath; // Store the path to merge later
        initRecorder(directory); // Initialize for the new segment

        try {
            mediaRecorder.start();
            setStatus(CurrentRecordingStatus.RECORDING);
            Log.i(TAG, "Continued recording. New segment: " + currentFilePath + ". Previous segment: " + previousFilePath);
            // Return the path of the *new* segment. Merging happens on stop.
            return getOutputFilePathUri();
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder start failed during continue: " + e.getMessage());
            cleanupRecorder();
            setStatus(CurrentRecordingStatus.NONE);
            previousFilePath = null; // Reset previous path on failure
            throw new IOException("Failed to continue recording.", e);
        }
    }

    // Stops the recording and performs merging if necessary
    public String stopRecording() throws IOException {
        if (mediaRecorder == null || currentRecordingStatus == CurrentRecordingStatus.NONE) {
            Log.w(TAG, "Stop called but recorder is not active.");
            // Return currentFilePath if it exists, otherwise null
            return currentFilePath != null ? getOutputFilePathUri() : null;
        }

        String finalPath = null;
        try {
            // Stop recording the current segment
            mediaRecorder.stop();
            Log.d(TAG, "Stopped recording segment: " + currentFilePath);

             // Check if we need to merge
            if (previousFilePath != null && currentFilePath != null) {
                Log.i(TAG, "Merging files: " + previousFilePath + " and " + currentFilePath);
                // Determine the directory for the merged file (use the directory of the last segment)
                 File currentFile = new File(currentFilePath);
                 String outputDirIdentifier = getDirectoryIdentifierFromFile(currentFile); // Helper needed or pass directory

                finalPath = mergeAudioFiles(previousFilePath, currentFilePath, outputDirIdentifier);
                Log.i(TAG, "Merging complete. Final file: " + finalPath);

                // Clean up temporary segment files after successful merge
                boolean prevDeleted = new File(previousFilePath).delete();
                boolean currentDeleted = new File(currentFilePath).delete();
                 Log.d(TAG, "Deleted previous segment (" + previousFilePath + "): " + prevDeleted);
                 Log.d(TAG, "Deleted current segment (" + currentFilePath + "): " + currentDeleted);


                currentFilePath = finalPath; // Update currentFilePath to the merged file path
                previousFilePath = null; // Reset previous file path
            } else {
                 // No merging needed, the current file is the final file
                 finalPath = currentFilePath;
            }

        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder stop failed: " + e.getMessage());
            // Even if stop fails, try to return the path of the segment recorded so far
             finalPath = currentFilePath;
             // Don't throw immediately, let cleanup happen, but log error
        } catch (RuntimeException e) { // Catch other potential runtime issues during stop
             Log.e(TAG, "RuntimeException during stop/merge: " + e.getMessage());
             finalPath = currentFilePath; // Attempt to return what we have
        } finally {
            cleanupRecorder(); // Release recorder resources
            setStatus(CurrentRecordingStatus.NONE); // Set status regardless of merge success
        }

        // Return the File URI of the final (potentially merged) file
         return finalPath != null ? Uri.fromFile(new File(finalPath)).toString() : null;
    }

    // Merging of two files by concatenating streams.
    private String mergeAudioFiles(String path1, String path2, String outputDirectoryIdentifier) throws IOException {
        File file1 = new File(path1);
        File file2 = new File(path2);

        if (!file1.exists() || !file2.exists()) {
            throw new IOException("One or both files to merge do not exist.");
        }

        // Create a unique name for the merged file
        String mergedFileName = "voice_record_merged_" + UUID.randomUUID().toString() + ".aac";
        File mergedFile = getFileObject(mergedFileName, outputDirectoryIdentifier);
        if (mergedFile == null) {
            throw new IOException("Could not create merged output file.");
        }

        try (FileInputStream fis1 = new FileInputStream(file1);
             FileInputStream fis2 = new FileInputStream(file2);
             FileOutputStream fos = new FileOutputStream(mergedFile)) {
            
            // Skip AAC header in first file (typically 7 bytes for ADTS header)
            byte[] header = new byte[7];
            int headerSize = fis1.read(header);
            if (headerSize != 7) {
                throw new IOException("Invalid AAC file format (header)");
            }
            
            // Write the header only once
            fos.write(header);

            // Copy rest of first file
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis1.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }

            // For second file, skip the header
            fis2.skip(7); // Skip ADTS header of second file
            
            // Copy rest of second file
            while ((bytesRead = fis2.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            
            fos.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error merging files: " + e.getMessage());
            if (mergedFile.exists()) {
                mergedFile.delete();
            }
            throw e;
        }

        // Verify the merged file
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(mergedFile.getAbsolutePath());
            mediaPlayer.prepare();
            long duration = mediaPlayer.getDuration();
            mediaPlayer.release();
            
            if (duration <= 0) {
                mergedFile.delete();
                throw new IOException("Merged file validation failed - invalid duration");
            }
            
            // Clean up original files after successful merge
            file1.delete();
            file2.delete();
            
            return mergedFile.getAbsolutePath();
        } catch (Exception e) {
            mergedFile.delete();
            throw new IOException("Failed to validate merged file: " + e.getMessage());
        }
    }

     // Helper to guess directory identifier (this is basic, might need refinement)
    private String getDirectoryIdentifierFromFile(File file) {
         if (file == null) return "DOCUMENTS";
         String parentPath = file.getParent();
         if (parentPath == null) return "DOCUMENTS";

         if (parentPath.equals(context.getCacheDir().getAbsolutePath())) {
             return "CACHE";
         } else if (parentPath.equals(context.getFilesDir().getAbsolutePath())) {
             return "DOCUMENTS";
         }
         // Add checks for external dirs if you use them
         return "DOCUMENTS"; // Default fallback
     }


    // Releases the MediaRecorder instance
    private void cleanupRecorder() {
        if (mediaRecorder != null) {
            try {
                 // Check state before stopping/releasing if possible, though stop() handles some states
                 // Avoid calling stop() if it's already stopped or not initialized.
                 // However, calling release() is generally safe.
                mediaRecorder.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error releasing mediaRecorder (already released?): " + e.getMessage());
            } catch(Exception e){ // Catch any other unexpected exceptions during release
                 Log.e(TAG, "Unexpected error releasing mediaRecorder: " + e.getMessage());
            }
             mediaRecorder = null; // Set to null after release
             Log.d(TAG, "MediaRecorder released.");
        }
    }

    public File getOutputFile() {
        return currentFilePath != null ? new File(currentFilePath) : null;
    }

    public String getOutputFilePath() {
        return currentFilePath;
    }

    // Returns the file path as a "file://" URI string
    public String getOutputFilePathUri() {
        if (currentFilePath == null) {
            return null;
        }
        File file = new File(currentFilePath);
        return Uri.fromFile(file).toString();
    }


    public boolean pauseRecording() throws NotSupportedOsVersion {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw new NotSupportedOsVersion("Pause/Resume requires Android API level 24+");
        }
        if (mediaRecorder != null && currentRecordingStatus == CurrentRecordingStatus.RECORDING) {
            try {
                mediaRecorder.pause();
                setStatus(CurrentRecordingStatus.PAUSED);
                Log.i(TAG, "Recording paused.");
                return true;
            } catch (IllegalStateException e) {
                 Log.e(TAG, "Failed to pause recording: " + e.getMessage());
                 // Optionally attempt to stop/cleanup if pause fails critically?
                 return false;
            }
        } else {
            Log.w(TAG, "Cannot pause: Recorder not active or not recording.");
            return false;
        }
    }

    public boolean resumeRecording() throws NotSupportedOsVersion {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw new NotSupportedOsVersion("Pause/Resume requires Android API level 24+");
        }
        if (mediaRecorder != null && currentRecordingStatus == CurrentRecordingStatus.PAUSED) {
             try {
                mediaRecorder.resume();
                setStatus(CurrentRecordingStatus.RECORDING);
                Log.i(TAG, "Recording resumed.");
                return true;
             } catch (IllegalStateException e) {
                 Log.e(TAG, "Failed to resume recording: " + e.getMessage());
                 // Optionally attempt to stop/cleanup?
                 return false;
            }
        } else {
            Log.w(TAG, "Cannot resume: Recorder not active or not paused.");
            return false;
        }
    }

    public CurrentRecordingStatus getCurrentStatus() {
        return currentRecordingStatus;
    }

     // Deletes the *final* output file if it exists.
    public boolean deleteOutputFile() {
         // Cleans up recorder first if active, potentially stopping it.
         // Consider if this is the desired behavior. Maybe only delete if not recording?
         if (currentRecordingStatus != CurrentRecordingStatus.NONE) {
             try {
                 stopRecording(); // Ensure recording is stopped before deleting
             } catch(IOException e) {
                 Log.e(TAG, "Error stopping recording before delete: " + e.getMessage());
                 // Proceed with delete attempt anyway?
             }
         }

        File fileToDelete = getOutputFile();
        if (fileToDelete != null && fileToDelete.exists()) {
             boolean deleted = fileToDelete.delete();
             if (deleted) {
                 Log.i(TAG, "Deleted output file: " + currentFilePath);
                 currentFilePath = null; // Clear path after deletion
             } else {
                  Log.w(TAG, "Failed to delete output file: " + currentFilePath);
             }
             return deleted;
        }
        return false; // File didn't exist or path was null
    }


    public static boolean canPhoneCreateMediaRecorder(Context context) {
        // Basic check, might need refinement (e.g., check specific codecs if needed)
        MediaRecorder testRecorder = null;
        boolean canRecord = true;
        try {
            testRecorder = new MediaRecorder();
            testRecorder.setAudioSource(MediaRecorder.AudioSource.MIC); // Need permission for this check
            testRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            testRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
             // Don't need prepare/start/stop/output file for just capability check
        } catch (Exception e) {
             Log.w(TAG, "Cannot create MediaRecorder instance: " + e.getMessage());
             canRecord = false;
        } finally {
            if (testRecorder != null) {
                try {
                    testRecorder.release();
                } catch (Exception e) { /* ignore */ }
            }
        }
        return canRecord;
    }
}