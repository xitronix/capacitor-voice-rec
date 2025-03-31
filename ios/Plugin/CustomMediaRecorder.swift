import Foundation
import AVFoundation

class CustomMediaRecorder:NSObject {
    private var recordingSession: AVAudioSession!
    private var audioRecorder: AVAudioRecorder!
    private var audioFilePath: URL!
    private var originalRecordingSessionCategory: AVAudioSession.Category!

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
    
    // Property to keep track of previous file that needs to be merged
    private var previousFileURL: URL?
    private var previousFileDuration: Double = 0
    
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

    public func startRecording(directory: String?) -> Bool {
        // Set up all possible interruption observers
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleInterruption),
                                             name: AVAudioSession.interruptionNotification,
                                             object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleRouteChange),
                                             name: AVAudioSession.routeChangeNotification,
                                             object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleSecondaryAudio),
                                             name: AVAudioSession.silenceSecondaryAudioHintNotification,
                                             object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleMediaServicesReset),
                                             name: AVAudioSession.mediaServicesWereResetNotification,
                                             object: AVAudioSession.sharedInstance())
        
        do {
            recordingSession = AVAudioSession.sharedInstance()
            originalRecordingSessionCategory = recordingSession.category
            
            // Configure for highest priority recording
            try recordingSession.setCategory(.playAndRecord,
                                           mode: .default,
                                           options: [.allowBluetooth, .duckOthers, .defaultToSpeaker, .mixWithOthers])
            try recordingSession.setActive(true, options: .notifyOthersOnDeactivation)
            if #available(iOS 14.5, *) {
                try recordingSession.setPrefersNoInterruptionsFromSystemAlerts(true)
            }
            
            // Set audio session priority to high
            try AVAudioSession.sharedInstance().setPreferredIOBufferDuration(0.005)
            
            audioFilePath = getFileUrl(
                at: "\(UUID().uuidString).aac",
                in: directory
            )
            
            // Check if microphone is available
            guard recordingSession.isInputAvailable else {
                cleanup()
                return false
            }
            
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
        
        // Store the previous file URL for later merging
        self.previousFileURL = prevFileURL
        
        // Calculate and store the previous file duration
        let prevAsset = AVURLAsset(url: prevFileURL)
        self.previousFileDuration = CMTimeGetSeconds(prevAsset.duration)
        
        // Setup recording session
        if !setupRecordingSession() {
            return false
        }
        
        // Create a new recording file path for the continuation
        let audioFileName = "recording_continued_\(Date().timeIntervalSince1970).m4a"
        let documentsDirectory = getDocumentsDirectory(directory)
        audioFilePath = documentsDirectory.appendingPathComponent(audioFileName)
        
        // Setup recorder for a new recording
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100.0,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]
        
        do {
            audioRecorder = try AVAudioRecorder(url: audioFilePath!, settings: settings)
            audioRecorder.delegate = self
            audioRecorder.isMeteringEnabled = true
            
            if audioRecorder.prepareToRecord() {
                audioRecorder.record(forDuration: 14400) // 4 hours max
                status = CurrentRecordingStatus.RECORDING
                self.onStatusChange?(status)
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
    
    public func stopRecording() {
        if audioRecorder != nil {
            audioRecorder.stop()
            
            // If we have a previous file to merge
            if let prevURL = previousFileURL, let currentURL = audioFilePath {
                // Create a semaphore to wait for the merge to complete
                let semaphore = DispatchSemaphore(value: 0)
                
                mergeAudioFiles(firstFileURL: prevURL, secondFileURL: currentURL) { [weak self] mergedFileURL in
                    if let mergedURL = mergedFileURL {
                        // Replace our current audio file path with the merged one
                        self?.audioFilePath = mergedURL
                        print("Successfully merged audio files to: \(mergedURL.path)")
                    } else {
                        print("Failed to merge audio files")
                    }
                    // Signal completion
                    semaphore.signal()
                }
                
                // Wait for a reasonable amount of time for the merge to complete
                // This ensures getOutputFile() returns the correct file path
                let timeout = DispatchTime.now() + 10.0 // 10 second timeout
                if semaphore.wait(timeout: timeout) == .timedOut {
                    print("Warning: Audio file merge timed out")
                }
            }
            
            // Clean up resources
            cleanup()
        }
    }
    
    // Add a method to merge audio files
    private func mergeAudioFiles(firstFileURL: URL, secondFileURL: URL, completion: @escaping (URL?) -> Void) {
        let composition = AVMutableComposition()
        
        // Get audio from the first file
        guard let firstAsset = try? AVURLAsset(url: firstFileURL) else {
            print("Could not load first asset")
            completion(nil)
            return
        }
        
        // Get audio from the second file
        guard let secondAsset = try? AVURLAsset(url: secondFileURL) else {
            print("Could not load second asset")
            completion(nil)
            return
        }
        
        // Create a composition track for audio
        guard let compositionTrack = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            print("Could not create composition track")
            completion(nil)
            return
        }
        
        // Add the first file's audio
        do {
            guard let firstTrack = firstAsset.tracks(withMediaType: .audio).first else {
                print("No audio track in first file")
                completion(nil)
                return
            }
            
            let timeRange = CMTimeRange(
                start: CMTime.zero,
                duration: firstAsset.duration
            )
            
            try compositionTrack.insertTimeRange(timeRange, of: firstTrack, at: CMTime.zero)
            
            // Add the second file's audio
            guard let secondTrack = secondAsset.tracks(withMediaType: .audio).first else {
                print("No audio track in second file")
                completion(nil)
                return
            }
            
            let secondTimeRange = CMTimeRange(
                start: CMTime.zero,
                duration: secondAsset.duration
            )
            
            try compositionTrack.insertTimeRange(
                secondTimeRange,
                of: secondTrack,
                at: firstAsset.duration
            )
            
            // Create a temporary file to export to
            let tempDirectoryURL = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            let outputURL = tempDirectoryURL.appendingPathComponent("merged_recording_\(Date().timeIntervalSince1970).m4a")
            
            // Setup exporter
            guard let exporter = AVAssetExportSession(
                asset: composition,
                presetName: AVAssetExportPresetAppleM4A
            ) else {
                print("Could not create export session")
                completion(nil)
                return
            }
            
            exporter.outputURL = outputURL
            exporter.outputFileType = .m4a
            
            // Export the file
            exporter.exportAsynchronously {
                switch exporter.status {
                case .completed:
                    completion(outputURL)
                default:
                    print("Export failed: \(exporter.error?.localizedDescription ?? "Unknown error")")
                    completion(nil)
                }
            }
        } catch {
            print("Error merging files: \(error)")
            completion(nil)
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
        } catch {
            print("Error during cleanup: \(error)")
        }
    }
    
    public func getOutputFile() -> URL? {
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
        // Set up all possible interruption observers
        NotificationCenter.default.addObserver(self,
                                            selector: #selector(handleInterruption),
                                            name: AVAudioSession.interruptionNotification,
                                            object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self,
                                            selector: #selector(handleRouteChange),
                                            name: AVAudioSession.routeChangeNotification,
                                            object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self,
                                            selector: #selector(handleSecondaryAudio),
                                            name: AVAudioSession.silenceSecondaryAudioHintNotification,
                                            object: AVAudioSession.sharedInstance())
        NotificationCenter.default.addObserver(self,
                                            selector: #selector(handleMediaServicesReset),
                                            name: AVAudioSession.mediaServicesWereResetNotification,
                                            object: AVAudioSession.sharedInstance())
        
        do {
            recordingSession = AVAudioSession.sharedInstance()
            originalRecordingSessionCategory = recordingSession.category
            
            // Configure for highest priority recording
            try recordingSession.setCategory(.playAndRecord,
                                        mode: .default,
                                        options: [.allowBluetooth, .duckOthers, .defaultToSpeaker, .mixWithOthers])
            try recordingSession.setActive(true, options: .notifyOthersOnDeactivation)
            if #available(iOS 14.5, *) {
                try recordingSession.setPrefersNoInterruptionsFromSystemAlerts(true)
            }
            
            // Set audio session priority to high
            try AVAudioSession.sharedInstance().setPreferredIOBufferDuration(0.005)
            
            // Check if microphone is available
            guard recordingSession.isInputAvailable else {
                cleanup()
                return false
            }
            
            return true
        } catch {
            cleanup()
            return false
        }
    }

    private func getDocumentsDirectory(_ directory: String?) -> URL {
        return getDirectory(directory: directory)
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

    @objc func handleRouteChange(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        switch reason {
        case .override, .oldDeviceUnavailable:
            let _ = pauseRecording()
        default:
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
