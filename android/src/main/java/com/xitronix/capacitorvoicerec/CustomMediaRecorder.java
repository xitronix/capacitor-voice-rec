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
    private CurrentRecordingStatus currentRecordingStatus = CurrentRecordingStatus.NONE;
    private OnStatusChangeListener listener;
    
    // List to store all temporary recording segments
    private java.util.List<String> tempRecordingSegments = new java.util.ArrayList<>();
    // Original file that's being continued
    private String originalFilePath = null;
    // Shared preferences key prefix for storing segments
    private static final String PREFS_SEGMENTS_KEY_PREFIX = "voice_recorder_segments_";

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

    /**
     * Starts a completely new recording
     * This creates a fresh recording with no connection to previous recordings
     */
    public String startRecording(String directory) throws IOException {
        // Clean up any previous recordings
        cleanupAnyPreviousRecordings();
        
        // Reset recording state
        resetRecordingState();
        
        // Initialize new recorder
        initRecorder(directory);

        try {
            mediaRecorder.start();
            setStatus(CurrentRecordingStatus.RECORDING);
            Log.i(TAG, "Started new recording to: " + currentFilePath);
            return getOutputFilePathUri();
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder start failed: " + e.getMessage());
            resetRecordingState();
            throw new IOException("Failed to start recording.", e);
        }
    }

    /**
     * Clean up any previous recordings to prevent interference
     */
    private void cleanupAnyPreviousRecordings() {
        // Clear any existing segments from previous recordings
        if (originalFilePath != null) {
            clearTempSegmentsList();
            originalFilePath = null;
        }
        
        // Scan for and remove any orphaned segments in SharedPreferences
        android.content.SharedPreferences prefs = context.getSharedPreferences(
            "VoiceRecorderPrefs", android.content.Context.MODE_PRIVATE);
            
        // Get all preference keys    
        java.util.Map<String, ?> allPrefs = prefs.getAll();
        
        // Find and remove any segment lists
        for (String key : allPrefs.keySet()) {
            if (key.startsWith(PREFS_SEGMENTS_KEY_PREFIX)) {
                prefs.edit().remove(key).commit();
            }
        }
    }

    /**
     * Loads existing temporary segments for a recording file
     * @param originalFilePath Path to the original recording file
     * @return List of valid segment paths
     */
    private java.util.List<String> loadTempSegments(String originalFilePath) {
        // Create a new list to store segments
        java.util.List<String> segments = new java.util.ArrayList<>();
        
        if (originalFilePath == null || originalFilePath.isEmpty()) {
            return segments;
        }
        
        // Generate segments key from file name
        File file = new File(originalFilePath);
        String segmentsKey = PREFS_SEGMENTS_KEY_PREFIX + file.getName();
        
        // Get saved segments from SharedPreferences
        android.content.SharedPreferences prefs = context.getSharedPreferences(
            "VoiceRecorderPrefs", android.content.Context.MODE_PRIVATE);
        java.util.Set<String> savedSegments = prefs.getStringSet(segmentsKey, null);
        
        if (savedSegments == null || savedSegments.isEmpty()) {
            return segments;
        }
        
        // Filter valid segments (existing files with content)
        java.util.Set<String> validSegments = new java.util.LinkedHashSet<>();
        for (String segmentPath : savedSegments) {
            File segmentFile = new File(segmentPath);
            if (segmentFile.exists() && segmentFile.length() > 0) {
                validSegments.add(segmentPath);
            }
        }
        
        // If we filtered out invalid segments, update the saved list
        if (validSegments.size() < savedSegments.size()) {
            prefs.edit().putStringSet(segmentsKey, validSegments).apply();
        }
        
        segments.addAll(validSegments);
        return segments;
    }
    
    // Find existing temporary segments from previous recordings
    private void findExistingTempSegments(String originalFile) {
        // Clear any existing segments first to prevent duplicates
        tempRecordingSegments.clear();
        
        // Use our new loadTempSegments method to fill the list
        tempRecordingSegments.addAll(loadTempSegments(originalFile));
    }
    
    // Save the current temp segments list to SharedPreferences
    private void saveTempSegmentsList() {
        if (originalFilePath == null) return;
        
        if (tempRecordingSegments.isEmpty()) {
            return;
        }
        
        java.util.Set<String> uniqueSegmentPaths = new java.util.LinkedHashSet<>(tempRecordingSegments);
        
        if (uniqueSegmentPaths.size() < tempRecordingSegments.size()) {
            tempRecordingSegments.clear();
            tempRecordingSegments.addAll(uniqueSegmentPaths);
        }
        
        String segmentsKey = getTempSegmentsKey(originalFilePath);
        android.content.SharedPreferences prefs = context.getSharedPreferences(
            "VoiceRecorderPrefs", android.content.Context.MODE_PRIVATE);
            
        prefs.edit()
            .putStringSet(segmentsKey, uniqueSegmentPaths)
            .commit();
    }

    /**
     * Continue recording from a previous file - used when app was closed or restarted
     * This creates a new segment file that will be merged with the original later
     */
    public String continueRecording(String prevFilePathUri, String directory) throws IOException {
        if (currentRecordingStatus == CurrentRecordingStatus.RECORDING || currentRecordingStatus == CurrentRecordingStatus.PAUSED) {
            throw new IOException("Cannot continue recording, already recording or paused.");
        }

        // Normalize the file path
        String prevFilePath = normalizeFilePath(prevFilePathUri);
        
        // Basic validation of the previous file path
        if (prevFilePath == null || !isValidAudioFile(prevFilePath)) {
            throw new IOException("Previous recording file is invalid or empty: " + 
                (prevFilePath != null ? prevFilePath : "null"));
        }

        // Reset recording state but preserve originalFilePath
        resetRecordingState();
        this.originalFilePath = prevFilePath;
        
        // Get recording info to check for existing segments
        java.util.Map<String, Object> recordingInfo = getRecordingInfo(prevFilePath);
        boolean hasSegments = (boolean)recordingInfo.get("hasSegments");
        
        // If we already have segments, finalize the recording first
        if (hasSegments) {
            Log.i(TAG, "Recording has existing segments - finalizing before continuing");
            java.util.Map<String, Object> finalizeResult = finalizeRecording(prevFilePath);
            
            if ((boolean)finalizeResult.get("success")) {
                // Use the finalized recording as our new original file
                String finalizedFilePath = (String)finalizeResult.get("fileUri");
                this.originalFilePath = finalizedFilePath;
                Log.i(TAG, "Successfully finalized existing recording: " + finalizedFilePath);
            } else {
                Log.w(TAG, "Failed to finalize existing recording - continuing with original file");
            }
        } else {
            // Find existing segments if any - but there shouldn't be any at this point
            findExistingTempSegments(prevFilePath);
        }
        
        // Setup a new recording segment
        initRecorder(directory); // Initialize for the new segment

        try {
            mediaRecorder.start();
            setStatus(CurrentRecordingStatus.RECORDING);
            
            // Only add current file to segments if it's not already in the list
            if (currentFilePath != null && !tempRecordingSegments.contains(currentFilePath)) {
                tempRecordingSegments.add(currentFilePath);
                // Save segments list immediately to prevent duplicates during crashes
                saveTempSegmentsList();
                Log.i(TAG, "Added new segment to list: " + currentFilePath);
            }
            
            Log.i(TAG, "Continued recording. New segment: " + currentFilePath + 
                  ". Original file: " + originalFilePath + 
                  ". Total segments: " + tempRecordingSegments.size());
                  
            // Return the original file path directly, not as a URI
            return originalFilePath;
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder start failed during continue: " + e.getMessage());
            resetRecordingState();
            throw new IOException("Failed to continue recording.", e);
        }
    }
    
    // Clear temporary segments list from SharedPreferences
    private void clearTempSegmentsList() {
        if (originalFilePath == null) return;
        
        String segmentsKey = getTempSegmentsKey(originalFilePath);
        android.content.SharedPreferences prefs = context.getSharedPreferences(
            "VoiceRecorderPrefs", android.content.Context.MODE_PRIVATE);
            
        prefs.edit()
            .remove(segmentsKey)
            .apply();
    }
    
    // Generate a key for SharedPreferences based on file path
    private String getTempSegmentsKey(String filePath) {
        File file = new File(filePath);
        return PREFS_SEGMENTS_KEY_PREFIX + file.getName();
    }

    // Stops the recording and performs merging if necessary
    public String stopRecording() throws IOException {
        if (mediaRecorder == null || currentRecordingStatus == CurrentRecordingStatus.NONE) {
            return originalFilePath != null ? originalFilePath : currentFilePath;
        }

        String finalPath = null;
        try {
            mediaRecorder.stop();

            if (originalFilePath != null && !tempRecordingSegments.isEmpty()) {
                if (currentFilePath != null && !tempRecordingSegments.contains(currentFilePath)) {
                    tempRecordingSegments.add(currentFilePath);
                }
                
                finalPath = mergeSegments(originalFilePath, tempRecordingSegments);
                if (finalPath != null) {
                    for (String tempFile : tempRecordingSegments) {
                        new File(tempFile).delete();
                    }
                    
                    currentFilePath = finalPath;
                } else {
                    Log.e(TAG, "Failed to finalize recording with segments");
                    finalPath = originalFilePath;
                }
                
                tempRecordingSegments.clear();
                clearTempSegmentsList();
                
            } else if (currentFilePath != null) {
                finalPath = currentFilePath;
            }

        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder stop failed: " + e.getMessage());
            finalPath = originalFilePath != null ? originalFilePath : currentFilePath;
        } catch (RuntimeException e) {
            Log.e(TAG, "RuntimeException during stop/merge: " + e.getMessage());
            finalPath = originalFilePath != null ? originalFilePath : currentFilePath;
        } finally {
            resetRecordingState();
        }

        return finalPath;
    }

    /**
     * Get information about a recording file without having to continue/stop it
     * This allows checking file info directly from its path
     * 
     * @param filePath Path to the recording file
     * @return Object with three properties: exists (boolean), fileUri (string), durationMs (long), hasSegments (boolean)
     */
    public java.util.Map<String, Object> getRecordingInfo(String filePath) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        
        // Default values
        result.put("exists", false);
        result.put("fileUri", null);
        result.put("durationMs", 0L);
        result.put("hasSegments", false);
        
        // Normalize the file path
        String path = normalizeFilePath(filePath);
        if (path == null || !isValidAudioFile(path)) {
            return result;
        }
        
        // Get duration using helper method
        long durationMs = getAudioDuration(path);
        
        // Check for segments using our loadTempSegments method
        java.util.List<String> segments = loadTempSegments(path);
        boolean hasSegments = !segments.isEmpty();

        // Build result
        result.put("exists", true);
        result.put("fileUri", path); // Return direct path instead of URI
        result.put("durationMs", durationMs);
        result.put("hasSegments", hasSegments);
        
        return result;
    }
    
    /**
     * Finalize a recording by merging segments without continuing/stopping
     * 
     * @param filePath Path to original recording file
     * @return Path to the finalized file, or null if failed
     */
    public java.util.Map<String, Object> finalizeRecording(String filePath) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        
        // Default failure result
        result.put("success", false);
        result.put("fileUri", null);
        result.put("durationMs", 0L);
        
        // Normalize the file path
        String path = normalizeFilePath(filePath);
        if (path == null || !isValidAudioFile(path)) {
            return result;
        }
        
        // Get segments using our loadTempSegments method
        java.util.List<String> segments = loadTempSegments(path);
        
        // If no segments, return success with just the original file info
        if (segments.isEmpty()) {
            long durationMs = getAudioDuration(path);
            
            result.put("success", true);
            result.put("fileUri", path); // Use direct path
            result.put("durationMs", durationMs);
            return result;
        }
        
        // Merge segments
        try {
            String finalizedPath = mergeSegments(path, segments);
            
            if (finalizedPath != null) {
                // Get duration of merged file
                long durationMs = getAudioDuration(finalizedPath);
                
                // Clean up segments list in preferences
                String segmentsKey = getTempSegmentsKey(path);
                android.content.SharedPreferences prefs = context.getSharedPreferences(
                    "VoiceRecorderPrefs", android.content.Context.MODE_PRIVATE);
                prefs.edit().remove(segmentsKey).apply();
                
                // Clean up segment files
                for (String segment : segments) {
                    new File(segment).delete();
                }
                
                result.put("success", true);
                result.put("fileUri", finalizedPath); // Use direct path
                result.put("durationMs", durationMs);
                return result;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error finalizing recording: " + e.getMessage());
        }

        // Return original file in case of failure
        result.put("success", false);
        result.put("fileUri", path); // Use direct path
        result.put("durationMs", 0L);
        return result;
    }
    
    /**
     * Merges multiple audio segments into a single file
     * This is the core function for combining segmented recordings
     * 
     * @param originalFilePath Path to the original/main recording file
     * @param segments List of segment file paths to merge
     * @return Path to the final merged file
     */
    private String mergeSegments(String originalFilePath, java.util.List<String> segments) throws IOException {
        // If no segments to merge, just return the original path
        if (segments == null || segments.isEmpty()) {
            return originalFilePath;
        }
        
        // Remove duplicates
        java.util.LinkedHashSet<String> uniqueSegments = new java.util.LinkedHashSet<>(segments);
        java.util.List<String> filteredSegments = new java.util.ArrayList<>(uniqueSegments);
        
        // Check if original file exists
        File originalFile = new File(originalFilePath);
        if (!originalFile.exists()) {
            // If original file doesn't exist but we have segments, use first segment as original
            if (!filteredSegments.isEmpty()) {
                File firstSegment = new File(filteredSegments.get(0));
                if (firstSegment.exists()) {
                    copyFileInChunks(firstSegment, originalFile);
                    filteredSegments.remove(0);
                    
                    if (filteredSegments.isEmpty()) {
                        return originalFilePath;
                    }
                } else {
                    throw new IOException("Neither original file nor first segment exists");
                }
            } else {
                throw new IOException("Original file not found: " + originalFilePath);
            }
        }
        
        // If no segments left after initialization and filtering, return original
        if (filteredSegments.isEmpty()) {
            return originalFilePath;
        }
        
        // Get the directory for output based on original file location
        String outputDirIdentifier = getDirectoryIdentifierFromFile(originalFile);
        
        // Perform the actual merge
        return mergeAacFiles(originalFile, filteredSegments, outputDirIdentifier);
    }
    
    /**
     * Log details about all segments to help debug duration issues
     */
    private void logSegmentDetails(File originalFile, java.util.List<String> segments) {
        // This method is now a no-op - we don't need detailed logging
    }
    
    /**
     * Get the duration of an audio file in milliseconds
     * @param filePath Path to the audio file
     * @return Duration in milliseconds, or 0 if not available
     */
    private long getAudioDuration(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return 0;
        }
        
        try {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            String duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            retriever.release();
            
            if (duration != null) {
                return Long.parseLong(duration);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file duration: " + e.getMessage());
        }
        
        return 0;
    }

    /**
     * Merge AAC audio files - special handling for AAC format
     */
    private String mergeAacFiles(File originalFile, java.util.List<String> segments, 
                            String outputDirIdentifier) throws IOException {
        // Create a unique name for the merged file
        String finalOutputName = "final_merged_" + System.currentTimeMillis() + ".aac";
        File outputFile = getFileObject(finalOutputName, outputDirIdentifier);
        
        // Create a list of all files to process in order (filter invalid files)
        java.util.List<File> allFiles = new java.util.ArrayList<>();
        allFiles.add(originalFile);
        
        for (String segmentPath : segments) {
            File segmentFile = new File(segmentPath);
            if (segmentFile.exists() && segmentFile.length() > 0) {
                allFiles.add(segmentFile);
            }
        }
        
        // If no valid files to merge after filtering, return original
        if (allFiles.size() <= 1) {
            return originalFile.getAbsolutePath();
        }
        
        try (FileOutputStream finalOutput = new FileOutputStream(outputFile)) {
            // Extract and use header from original file, or create a default one
            byte[] aacHeader = extractAacHeader(originalFile);
            if (aacHeader == null) {
                aacHeader = createAacHeader();
            }
            
            // Write header to output file
            finalOutput.write(aacHeader);
            
            // Process each file in sequence
            for (File currentFile : allFiles) {
                try (FileInputStream fileInput = new FileInputStream(currentFile)) {
                    // Skip the header in each file (we already wrote one header)
                    fileInput.skip(aacHeader.length);
                    // Copy the audio content
                    copyStreamContentWithFrameAlignment(fileInput, finalOutput);
                } catch (Exception e) {
                    Log.e(TAG, "Error processing file " + currentFile.getName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error during merge: " + e.getMessage(), e);
            if (outputFile.exists()) {
                outputFile.delete();
            }
            throw e;
        }

        // Validate the final file to ensure it's playable
        if (!validateAudioFile(outputFile.getAbsolutePath())) {
            Log.e(TAG, "Merged file validation failed");
            outputFile.delete();
            throw new IOException("Failed to create a valid merged audio file");
        }
        
        return outputFile.getAbsolutePath();
    }
    
    /**
     * Extract the AAC header from a file
     */
    private byte[] extractAacHeader(File file) {
        byte[] header = new byte[7]; // Standard AAC ADTS header size
        
        try (FileInputStream fis = new FileInputStream(file)) {
            int headerSize = fis.read(header);
            if (headerSize == 7 && header[0] == (byte)0xFF && (header[1] & 0xF0) == 0xF0) {
                // Valid AAC ADTS header starts with 0xFFF... 
                return header;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting AAC header: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Create a basic AAC ADTS header
     */
    private byte[] createAacHeader() {
        // Create a standard AAC LC ADTS header (7 bytes)
        byte[] header = new byte[7];
        header[0] = (byte)0xFF;  // Sync word (12 bits) - first 8 bits
        header[1] = (byte)0xF1;  // Sync word (last 4 bits) + MPEG-4 + Layer 0 + No CRC
        header[2] = (byte)0x50;  // AAC LC profile + 44.1kHz
        header[3] = (byte)0x80;  // Channel config 2 (stereo) + frame length
        // Last bytes would typically contain frame length and buffer fullness
        return header;
    }
    
    /**
     * Copy stream content with attention to AAC frame boundaries
     * @return Total bytes processed
     */
    private int copyStreamContentWithFrameAlignment(FileInputStream input, FileOutputStream output) throws IOException {
        // For AAC ADTS, each frame starts with sync word 0xFFF...
        // We could implement frame-by-frame copying, but for now we'll use a simpler approach
        int totalBytes = 0;
        final int BUFFER_SIZE = 16 * 1024; // 16KB buffer
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
            
            // Periodic flush to avoid memory issues
            if (totalBytes % (BUFFER_SIZE * 4) == 0) {
                output.flush();
            }
        }
        
        output.flush();
        return totalBytes;
    }
    
    /**
     * Format duration in milliseconds to minutes:seconds format
     */
    private String formatDuration(long durationMs) {
        long minutes = durationMs / 1000 / 60;
        long seconds = (durationMs / 1000) % 60;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : seconds);
    }
    
    /**
     * Validates audio file without loading the entire file into memory
     * @param filePath Path to the audio file
     * @return true if file is a valid audio file with non-zero duration
     */
    private boolean validateAudioFile(String filePath) {
        MediaPlayer mediaPlayer = null;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            
            // Prepare with limited resources
            mediaPlayer.setOnErrorListener((mp, what, extra) -> true); // Catch errors
            
            // Only prepare enough to get duration
            mediaPlayer.prepare();
            long duration = mediaPlayer.getDuration();
            
            return duration > 0;
        } catch (Exception e) {
            Log.e(TAG, "Error validating audio file: " + e.getMessage());
            return false;
        } finally {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing MediaPlayer: " + e.getMessage());
                }
            }
        }
    }
            
    /**
     * Copy a file in chunks to avoid loading the entire file into memory
     */
    private void copyFileInChunks(File source, File destination) throws IOException {
        final int BUFFER_SIZE = 64 * 1024; // 64 KB chunks
        
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalBytesCopied = 0;
            
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytesCopied += bytesRead;
                
                // Periodically flush
                if (totalBytesCopied % (BUFFER_SIZE * 10) == 0) {
                    out.flush();
                }
            }
            
            out.flush();
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
                mediaRecorder.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error releasing mediaRecorder (already released?): " + e.getMessage());
            } catch(Exception e){
                Log.e(TAG, "Unexpected error releasing mediaRecorder: " + e.getMessage());
            }
            mediaRecorder = null;
        }
    }

    public File getOutputFile() {
        return currentFilePath != null ? new File(currentFilePath) : null;
    }

    public String getOutputFilePath() {
        return currentFilePath;
    }

    // Returns the file path as a direct path without URI scheme
    public String getOutputFilePathUri() {
        if (currentFilePath == null) {
            return null;
        }
        return currentFilePath;
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
         // Cleans up recorder first if active
         if (currentRecordingStatus != CurrentRecordingStatus.NONE) {
             try {
                 stopRecording(); // Ensure recording is stopped before deleting
             } catch(IOException e) {
                 Log.e(TAG, "Error stopping recording before delete: " + e.getMessage());
             }
             // After stopRecording, the state is already reset
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

    /**
     * Utility method to convert a potential URI string to a file path
     * @param filePathOrUri Either a direct file path or a file:// URI
     * @return Normalized file path
     */
    private String normalizeFilePath(String filePathOrUri) {
        if (filePathOrUri == null || filePathOrUri.isEmpty()) {
            return null;
        }
        
        if (filePathOrUri.startsWith("file://")) {
            Uri uri = Uri.parse(filePathOrUri);
            return uri.getPath();
        }
        
        return filePathOrUri;
    }
    
    /**
     * Validates a file path to ensure it points to a valid file
     * @param filePath Path to validate
     * @return true if valid, false otherwise
     */
    private boolean isValidAudioFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        
        File file = new File(filePath);
        return file.exists() && file.isFile() && file.length() > 0;
    }

    /**
     * Reset all recording state
     * This should be called when a recording is done or failed
     */
    private void resetRecordingState() {
        cleanupRecorder();
        setStatus(CurrentRecordingStatus.NONE);
        originalFilePath = null;
        tempRecordingSegments.clear();
    }
}