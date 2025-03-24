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
    
    public func stopRecording() {
        cleanup()
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
            tryResumeRecording()
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
