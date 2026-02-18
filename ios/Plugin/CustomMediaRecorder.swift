import Foundation
import AVFoundation

class CustomMediaRecorder:NSObject {
    private var recordingSession: AVAudioSession!
    private var audioRecorder: AVAudioRecorder!
    private var audioFilePath: URL!
    private var originalRecordingSessionCategory: AVAudioSession.Category!
    private var currentTempRecordingPath: URL?

    private var _status = CurrentRecordingStatus.NONE
    var onStatusChange: ((CurrentRecordingStatus) -> Void)?
    
    private var status: CurrentRecordingStatus {
        get { return _status }
        set {
            _status = newValue
            onStatusChange?(newValue)
        }
    }
    
    private let settings = [
        AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
        AVSampleRateKey: 44100,
        AVNumberOfChannelsKey: 1,
        AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
    ]
    
    // Property to keep track of primary file
    private var originalFileURL: URL?
    // Storage for temporary recording segments that need to be merged
    private var tempRecordingSegments: [URL] = []

   /**
     * Get the directory URL corresponding to the JS string
     */
    public func getDirectory(directory: String? = nil) -> URL {
        let fileManager = FileManager.default
        
        switch directory {
        case "TEMPORARY":
            return fileManager.temporaryDirectory
        case "CACHE":
            return fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first!
        default:
            return fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        }
    }

    /**
     * Get the URL for a given file, ensuring proper path handling.
     */
    public func getFileUrl(at fileName: String, in directory: String?) -> URL {
        let dirUrl = getDirectory(directory: directory)
        return dirUrl.appendingPathComponent(fileName)
    }

    /**
     * Set up notification observers for audio session events
     */
    private func setupNotificationObservers() {
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleInterruption),
                                             name: AVAudioSession.interruptionNotification,
                                             object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleSecondaryAudio),
                                             name: AVAudioSession.silenceSecondaryAudioHintNotification,
                                             object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleMediaServicesReset),
                                             name: AVAudioSession.mediaServicesWereResetNotification,
                                             object: AVAudioSession.sharedInstance())
    }
    
    /**
     * Configure the audio session for recording
     * Returns true if configuration succeeded, false otherwise
     */
    private func setupAudioSession() -> Bool {
        do {
            recordingSession = AVAudioSession.sharedInstance()
            originalRecordingSessionCategory = recordingSession.category
            
            // Configure for highest priority recording
            try recordingSession.setCategory(.playAndRecord,
                                           mode: .default,
                                           options: [.allowBluetooth, .duckOthers, .defaultToSpeaker, .mixWithOthers])
            try recordingSession.setActive(true, options: .notifyOthersOnDeactivation)
            // Check if microphone is available
            guard recordingSession.isInputAvailable else {
                cleanup()
                return false
            }
            
            return true
        } catch {
            print("Error setting up audio session: \(error.localizedDescription)")
            cleanup()
            return false
        }
    }

    public func startRecording(directory: String?) -> Bool {
        // Set up notification observers
        setupNotificationObservers()
        
        // Configure audio session
        if !setupAudioSession() {
            return false
        }
        
        do {
            // Create new file for the original recording
            audioFilePath = getFileUrl(
                at: "\(UUID().uuidString).aac",
                in: directory
            )
            
            // Store as the original file URL
            originalFileURL = audioFilePath
            
            // Reset segments list
            tempRecordingSegments = []
            
            audioRecorder = try AVAudioRecorder(url: audioFilePath, settings: settings)
            audioRecorder.delegate = self
            
            if !audioRecorder.record() {
                cleanup()
                return false
            }
            
            status = CurrentRecordingStatus.RECORDING
            return true
        } catch {
            cleanup()
            return false
        }
    }
    
    public func continueRecording(fromURL prevFileURL: URL, directory: String?) -> Bool {
        // First ensure we're not already recording
        if status == CurrentRecordingStatus.RECORDING || status == CurrentRecordingStatus.PAUSED {
            return false
        }
        
        // Store the original file URL - this is what we'll always return
        originalFileURL = prevFileURL
        
        // Look for any existing temporary files from previous continuations
        findExistingTempSegments(forOriginalFile: prevFileURL)
        
        // Setup notification observers
        setupNotificationObservers()
            
        // Setup recording session
        if !setupAudioSession() {
            return false
        }
        
        // CHANGE: Use the more persistent location for temp segments
        let tempRecordingPath = getTempSegmentURL()
        currentTempRecordingPath = tempRecordingPath
        
        do {
            // Set up recorder to record to the temporary file
            audioRecorder = try AVAudioRecorder(url: tempRecordingPath, settings: settings)
            audioRecorder.delegate = self
            audioRecorder.isMeteringEnabled = true
            
            if audioRecorder.prepareToRecord() {
                audioRecorder.record(forDuration: 14400) // 4 hours max
                status = CurrentRecordingStatus.RECORDING
                self.onStatusChange?(status)
                
                // Add this temp file to our list to track it
                tempRecordingSegments.append(tempRecordingPath)
                
                // Save segments list for recovery after app restart
                saveTempSegmentsList()
                
                return true
            } else {
                cleanup()
                return false
            }
        } catch {
            print("Error setting up recorder for continued recording: \(error)")
            cleanup()
            return false
        }
    }
    
    // Find any existing temporary segments from previous recordings
    private func findExistingTempSegments(forOriginalFile originalFile: URL) {
        tempRecordingSegments = []
        
        // Try to load temp segments from UserDefaults
        if let userDefaultsKey = getTempSegmentsKey(forFile: originalFile),
           let savedSegmentsPaths = UserDefaults.standard.array(forKey: userDefaultsKey) as? [String] {
            
            for segmentPath in savedSegmentsPaths {
                let segmentURL = URL(fileURLWithPath: segmentPath)
                
                // Only add if file exists
                if FileManager.default.fileExists(atPath: segmentPath) {
                    tempRecordingSegments.append(segmentURL)
                    print("Found existing temp segment: \(segmentPath)")
                }
            }
        }
    }
    
    // Save the current temp segments list to UserDefaults
    private func saveTempSegmentsList() {
        guard let originalFile = originalFileURL else { return }
        
        let segmentPaths = tempRecordingSegments.map { $0.path }
        if let userDefaultsKey = getTempSegmentsKey(forFile: originalFile) {
            UserDefaults.standard.set(segmentPaths, forKey: userDefaultsKey)
            UserDefaults.standard.synchronize()
            print("Saved \(segmentPaths.count) temp segments for recovery")
        }
    }
    
    // Generate a consistent key for UserDefaults based on file path
    private func getTempSegmentsKey(forFile fileURL: URL) -> String? {
        // Use the filename as the key basis
        let filename = fileURL.lastPathComponent
        return "voice_recorder_segments_\(filename)"
    }
    
    // Clear temp segments list from UserDefaults
    private func clearTempSegmentsList() {
        guard let originalFile = originalFileURL,
              let userDefaultsKey = getTempSegmentsKey(forFile: originalFile) else { return }
        
        UserDefaults.standard.removeObject(forKey: userDefaultsKey)
        UserDefaults.standard.synchronize()
    }
    
    public func stopRecording() {
        if audioRecorder != nil {
            // Get the current temp recording path
            if let currentTemp = currentTempRecordingPath {
                // Add to segments if not already there
                if !tempRecordingSegments.contains(currentTemp) {
                    tempRecordingSegments.append(currentTemp)
                }
            }
            
            // Stop the recording
            audioRecorder.stop()
            
            // If we have an original file and segments to merge
            if let originalFile = originalFileURL, !tempRecordingSegments.isEmpty {
                // Create a semaphore to wait for the merge to complete
                let semaphore = DispatchSemaphore(value: 0)
                
                print("Merging \(tempRecordingSegments.count) segments into \(originalFile.path)")
                
                // If the original file doesn't exist, copy the first segment to its location
                if !FileManager.default.fileExists(atPath: originalFile.path) {
                    do {
                        if let firstSegment = tempRecordingSegments.first {
                            try FileManager.default.copyItem(at: firstSegment, to: originalFile)
                            print("Created original file from first segment")
                            
                            // Remove the first segment from the list
                            tempRecordingSegments.removeFirst()
                        }
                    } catch {
                        print("Error creating original file: \(error)")
                    }
                }
                
                // If we still have segments to merge
                if !tempRecordingSegments.isEmpty {
                    mergeSegmentsWithFile(originalFile: originalFile, segments: tempRecordingSegments) { success in
                        if success {
                            print("Successfully merged all segments into original file")
                        } else {
                            print("Failed to merge some segments")
                        }
                        
                        // Remove temp files regardless of merge result
                        self.cleanupTempFiles()
                        
                        // Signal completion
                        semaphore.signal()
                    }
                    
                    // Wait for merge: raw concat ~100ms, re-encode fallback ~70s
                    let timeout = DispatchTime.now() + 180.0
                    if semaphore.wait(timeout: timeout) == .timedOut {
                        print("Warning: Audio file merge timed out")
                    }
                } else {
                    // No segments to merge, just clean up
                    cleanupTempFiles()
                }
                
                // Clear the segments list from UserDefaults
                clearTempSegmentsList()
            }
            
            // Clean up resources
            cleanup()
        }
    }
    
    // Clean up any temporary files
    private func cleanupTempFiles() {
        for tempFile in tempRecordingSegments {
            do {
                if FileManager.default.fileExists(atPath: tempFile.path) {
                    try FileManager.default.removeItem(at: tempFile)
                    print("Removed temp file: \(tempFile.path)")
                }
            } catch {
                print("Error removing temp file: \(error)")
            }
        }
        tempRecordingSegments = []
    }
    
    // Merge multiple segments with the original file
    private func mergeSegmentsWithFile(originalFile: URL, segments: [URL], completion: @escaping (Bool) -> Void) {
        if segments.isEmpty {
            completion(true)
            return
        }

        // Strategy 1: Raw ADTS byte concatenation (milliseconds, no AVFoundation)
        if mergeSegmentsRawConcat(originalFile: originalFile, segments: segments) {
            completion(true)
            return
        }

        // Strategy 2: Re-encode fallback (handles non-ADTS files)
        mergeSegmentsReencode(originalFile: originalFile, segments: segments, completion: completion)
    }

    /// Fast merge: raw ADTS byte concatenation (no re-encoding, no AVFoundation).
    /// ADTS frames are self-contained — files can be concatenated byte-for-byte.
    /// Returns true on success, false if files are not ADTS format.
    private func mergeSegmentsRawConcat(originalFile: URL, segments: [URL]) -> Bool {
        let allFiles = [originalFile] + segments.filter { FileManager.default.fileExists(atPath: $0.path) }

        // 1. Verify ALL files are ADTS format (sync word 0xFFF in first 2 bytes)
        for fileURL in allFiles {
            guard let handle = try? FileHandle(forReadingFrom: fileURL) else { return false }
            let header = handle.readData(ofLength: 2)
            handle.closeFile()
            guard header.count >= 2,
                  header[0] == 0xFF,
                  (header[1] & 0xF0) == 0xF0 else { return false }
        }

        // 2. Calculate expected output size for validation
        var expectedSize: UInt64 = 0
        for fileURL in allFiles {
            guard let attrs = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
                  let size = attrs[.size] as? UInt64 else { return false }
            expectedSize += size
        }

        // 3. Create temp output file in same directory (for atomic rename)
        let tempURL = originalFile.deletingLastPathComponent()
            .appendingPathComponent("merged_raw_\(Date().timeIntervalSince1970).aac")
        guard FileManager.default.createFile(atPath: tempURL.path, contents: nil) else { return false }

        // 4. Concatenate all files byte-for-byte using streaming I/O (16KB buffer)
        do {
            let outputHandle = try FileHandle(forWritingTo: tempURL)
            defer { outputHandle.closeFile() }

            for fileURL in allFiles {
                let inputHandle = try FileHandle(forReadingFrom: fileURL)
                defer { inputHandle.closeFile() }
                while true {
                    let chunk = inputHandle.readData(ofLength: 16 * 1024)
                    if chunk.isEmpty { break }
                    outputHandle.write(chunk)
                }
            }
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            return false
        }

        // 5. Validate output size matches expected
        guard let outAttrs = try? FileManager.default.attributesOfItem(atPath: tempURL.path),
              let outSize = outAttrs[.size] as? UInt64,
              outSize == expectedSize else {
            try? FileManager.default.removeItem(at: tempURL)
            return false
        }

        // 6. Atomic replace: remove original, move merged file to original path
        do {
            try FileManager.default.removeItem(at: originalFile)
            try FileManager.default.moveItem(at: tempURL, to: originalFile)
            return true
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            return false
        }
    }

    /// Re-encode merge using AVAssetExportSession (AppleM4A preset)
    private func mergeSegmentsReencode(originalFile: URL, segments: [URL], completion: @escaping (Bool) -> Void) {
        let composition = AVMutableComposition()
        guard let compositionTrack = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            completion(false)
            return
        }

        var currentPosition = CMTime.zero

        do {
            // Add original file
            if FileManager.default.fileExists(atPath: originalFile.path),
               let attributes = try? FileManager.default.attributesOfItem(atPath: originalFile.path),
               let fileSize = attributes[.size] as? UInt64, fileSize > 0 {
                let originalAsset = AVURLAsset(url: originalFile)
                if let originalTrack = originalAsset.tracks(withMediaType: .audio).first {
                    try compositionTrack.insertTimeRange(
                        CMTimeRange(start: .zero, duration: originalAsset.duration),
                        of: originalTrack, at: currentPosition)
                    currentPosition = CMTimeAdd(currentPosition, originalAsset.duration)
                }
            }

            // Add segments
            for segmentURL in segments where FileManager.default.fileExists(atPath: segmentURL.path) {
                let segmentAsset = AVURLAsset(url: segmentURL)
                if let segmentTrack = segmentAsset.tracks(withMediaType: .audio).first {
                    try compositionTrack.insertTimeRange(
                        CMTimeRange(start: .zero, duration: segmentAsset.duration),
                        of: segmentTrack, at: currentPosition)
                    currentPosition = CMTimeAdd(currentPosition, segmentAsset.duration)
                }
            }

            let totalDuration = CMTimeGetSeconds(currentPosition)

            guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
                completion(false)
                return
            }

            let tempExportURL = URL(fileURLWithPath: NSTemporaryDirectory())
                .appendingPathComponent("merged_enc_\(Date().timeIntervalSince1970).m4a")

            exporter.outputURL = tempExportURL
            exporter.outputFileType = .m4a

            let exportGroup = DispatchGroup()
            exportGroup.enter()

            exporter.exportAsynchronously {
                defer { exportGroup.leave() }

                if exporter.status == .completed {
                    do {
                        if FileManager.default.fileExists(atPath: originalFile.path) {
                            try FileManager.default.removeItem(at: originalFile)
                        }
                        try FileManager.default.moveItem(at: tempExportURL, to: originalFile)
                    } catch {
                        completion(false)
                        return
                    }
                    completion(true)
                } else {
                    completion(false)
                }
            }

            let timeoutSeconds = max(60.0, totalDuration * 1.0)
            let waitResult = exportGroup.wait(timeout: .now() + timeoutSeconds)
            if waitResult == .timedOut {
                exporter.cancelExport()
                completion(false)
            }
        } catch {
            completion(false)
        }
    }
    
    private func cleanup() {
        do {
            if let recorder = audioRecorder {
                recorder.stop()
                audioRecorder = nil
            }
            if let session = recordingSession {
                try session.setActive(false)
                try session.setCategory(originalRecordingSessionCategory ?? .playAndRecord)
                recordingSession = nil
            }
            originalRecordingSessionCategory = nil
            status = CurrentRecordingStatus.NONE
            currentTempRecordingPath = nil
        } catch {
            print("Error during cleanup: \(error)")
        }
    }
    
    public func getOutputFile() -> URL? {
        // Always return the original file path
        if let originalFile = originalFileURL {
            return originalFile
        }
        return audioFilePath
    }
    
    public func pauseRecording() -> Bool {
        if(status == CurrentRecordingStatus.RECORDING) {
            audioRecorder.pause()
            status = CurrentRecordingStatus.PAUSED
            return true
        } else {
            return false
        }
    }
    
    public func resumeRecording() -> Bool {
        if(status == CurrentRecordingStatus.PAUSED) {
            do {
                try recordingSession.setActive(true, options: .notifyOthersOnDeactivation)
                audioRecorder.record() // It will continue from where it was paused
                status = CurrentRecordingStatus.RECORDING
                return true
            } catch {
                print("Failed to resume recording: \(error)")
                return false
            }
        } else {
            return false
        }
    }
    
    public func getCurrentStatus() -> CurrentRecordingStatus {
        return status
    }
 
    public func removeRecording(fileUrl: URL) {
        do {
            // Also clean up any temporary segments
            if let userDefaultsKey = getTempSegmentsKey(forFile: fileUrl) {
                if let savedSegmentsPaths = UserDefaults.standard.array(forKey: userDefaultsKey) as? [String] {
                    for segmentPath in savedSegmentsPaths {
                        if FileManager.default.fileExists(atPath: segmentPath) {
                            try FileManager.default.removeItem(atPath: segmentPath)
                            print("Removed segment file: \(segmentPath)")
                        }
                    }
                }
                UserDefaults.standard.removeObject(forKey: userDefaultsKey)
            }
            
            // Remove the main file
            if FileManager.default.fileExists(atPath: fileUrl.path) {
                try FileManager.default.removeItem(atPath: fileUrl.path)
            }
        } catch let error {
            print("Error while removing file: \(error.localizedDescription)")
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    private func setupRecordingSession() -> Bool {
        // Set up notification observers
        setupNotificationObservers()
        
        // Configure audio session
        return setupAudioSession()
    }

    private func getDocumentsDirectory(_ directory: String?) -> URL {
        return getDirectory(directory: directory)
    }

    /**
     * Get information about an existing recording file without having to continue and stop it
     * This allows accessing a recording file directly from its path
     * @param filePath: The path to the recording file
     * @return: A tuple containing (file exists, file URL, duration in ms, has temp segments that need merging)
     */
    public func getRecordingInfo(filePath: String) -> (exists: Bool, fileURL: URL?, durationMs: Int, hasSegments: Bool) {
        // Create URL from file path
        let fileURL: URL
        if filePath.hasPrefix("file://") {
            // Handle file:// URLs properly
            guard let url = URL(string: filePath) else {
                return (false, nil, 0, false)
            }
            fileURL = url
        } else {
            fileURL = URL(fileURLWithPath: filePath)
        }
        
        // Check if file exists
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return (false, nil, 0, false)
        }
        
        // Get duration
        let durationMs = Int(CMTimeGetSeconds(AVURLAsset(url: fileURL).duration) * 1000)
        
        // Check for temporary segments
        let userDefaultsKey = getTempSegmentsKey(forFile: fileURL)
        var hasSegments = false
        if let key = userDefaultsKey,
           let savedSegmentsPaths = UserDefaults.standard.array(forKey: key) as? [String],
           !savedSegmentsPaths.isEmpty {
            
            // Check if any segments actually exist
            for segmentPath in savedSegmentsPaths {
                if FileManager.default.fileExists(atPath: segmentPath) {
                    hasSegments = true
                    break
                }
            }
        }
        
        return (true, fileURL, durationMs, hasSegments)
    }
    
    /**
     * Finalize a recording by merging any temporary segments with the main file
     * This allows finalizing a recording without continuing and stopping it
     * @param filePath: The path to the recording file
     * @return: A tuple containing (success, fileURL, durationMs)
     */
    public func finalizeRecording(filePath: String) -> (success: Bool, fileURL: URL?, durationMs: Int) {
        // Parse the file URL
        let fileURL: URL
        if filePath.hasPrefix("file://") {
            guard let url = URL(string: filePath) else {
                return (false, nil, 0)
            }
            fileURL = url
        } else {
            fileURL = URL(fileURLWithPath: filePath)
        }
        
        // Check if file exists
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return (false, nil, 0)
        }
        
        // Set as original file
        originalFileURL = fileURL
        
        // Find temporary segments
        findExistingTempSegments(forOriginalFile: fileURL)
        
        // If no segments, just return file info with single duration calculation
        if tempRecordingSegments.isEmpty {
            let durationMs = Int(CMTimeGetSeconds(AVURLAsset(url: fileURL).duration) * 1000)
            return (true, fileURL, durationMs)
        }
        
        // Create a semaphore to wait for the merge
        let semaphore = DispatchSemaphore(value: 0)
        var mergingSucceeded = false
        var finalDurationMs = 0
        
        // Perform merge directly (not on a background thread, to ensure completion)
        mergeSegmentsWithFile(originalFile: fileURL, segments: tempRecordingSegments) { success in
            mergingSucceeded = success
            
            // Calculate duration once after merge is complete
            if success {
                finalDurationMs = Int(CMTimeGetSeconds(AVURLAsset(url: fileURL).duration) * 1000)
            }
            
            // Clean up temp files
            self.cleanupTempFiles()
            self.clearTempSegmentsList()
            
            // Signal completion
            semaphore.signal()
        }
        
        // Wait for merge: raw concat ~100ms, re-encode fallback ~70s
        let timeout = DispatchTime.now() + 180.0
        let timeoutOccurred = semaphore.wait(timeout: timeout) == .timedOut
        
        if timeoutOccurred {
            return (false, fileURL, 0)
        }
        
        return (mergingSucceeded, fileURL, finalDurationMs)
    }

    // Update the function that gets a temporary file name to use a more persistent directory
    private func getTempSegmentURL() -> URL {
        // CHANGE: Instead of using the temporary directory, use a subdirectory in Documents
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let tempSegmentsDir = documentsDir.appendingPathComponent("VoiceRecorderSegments", isDirectory: true)
        
        // Create the directory if it doesn't exist
        if !FileManager.default.fileExists(atPath: tempSegmentsDir.path) {
            try? FileManager.default.createDirectory(at: tempSegmentsDir, withIntermediateDirectories: true)
        }
        
        return tempSegmentsDir.appendingPathComponent("temp_segment_\(Date().timeIntervalSince1970).aac")
    }
}

extension CustomMediaRecorder:AVAudioRecorderDelegate {
    @objc func handleInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            let _ = pauseRecording()
        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    tryResumeRecording()
                }
            }
        @unknown default:
            break
        }
    }

    @objc func handleSecondaryAudio(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt,
              let type = AVAudioSession.SilenceSecondaryAudioHintType(rawValue: typeValue) else {
            return
        }
        
        switch type {
        case .begin:
            let _ = pauseRecording()
        case .end:
            tryResumeRecording()
        @unknown default:
            break
        }
    }

    @objc func handleMediaServicesReset(notification: Notification) {
        tryResumeRecording()
    }

    // Helper to check if we can record
    private func canRecord() -> Bool {
        guard let session = recordingSession else { return false }
        return session.isInputAvailable && session.recordPermission == .granted
    }

    // Helper to attempt resuming recording
    private func tryResumeRecording(attempt: Int = 1) {
        if status == .PAUSED && canRecord() {
            let isResumed = resumeRecording()
            if attempt < 3 && !isResumed {
                let delay = pow(2.0, Double(attempt)) * 0.5
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                    self.tryResumeRecording(attempt: attempt + 1)
                }
            }
        }
    }
}

