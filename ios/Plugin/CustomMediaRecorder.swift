import Foundation
import AVFoundation
import UIKit

class CustomMediaRecorder:NSObject {
    private var recordingSession: AVAudioSession!
    private var audioRecorder: AVAudioRecorder!
    private var audioFilePath: URL!
    private var originalRecordingSessionCategory: AVAudioSession.Category!
    private var status = CurrentRecordingStatus.NONE
    
    // Add property to track audio session reset attempts
    private var isResettingSession = false
    
    // Add property to track if recorder has been stopped
    private var isStopped = false
    
    // Timer for audio session availability checks
    private var audioCheckTimer: Timer?
    
    // Proximity sensor tracking
    private var isMonitoringProximitySensor = false
    
    private let settings = [
        AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
        AVSampleRateKey: 44100,
        AVNumberOfChannelsKey: 1,
        AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
    ]
    
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    
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
        // Reset stopped flag when starting recording
        isStopped = false
        
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
        
        // Add observer for app becoming active - useful for VOIP call scenarios
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleAppDidBecomeActive),
                                             name: UIApplication.didBecomeActiveNotification,
                                             object: nil)
        
        do {
            recordingSession = AVAudioSession.sharedInstance()
            originalRecordingSessionCategory = recordingSession.category
            
            // Configure audio session for recording with background support (one comprehensive configuration)
            try recordingSession.setCategory(.playAndRecord, 
                                          mode: .default,
                                          options: [.allowBluetooth, .duckOthers, .defaultToSpeaker, 
                                                   .mixWithOthers, .allowAirPlay, .allowBluetoothA2DP])
            try recordingSession.setActive(true, options: .notifyOthersOnDeactivation)
            
            if #available(iOS 14.5, *) {
                try recordingSession.setPrefersNoInterruptionsFromSystemAlerts(true)
            }
            
            // Set audio session priority to high
            try recordingSession.setPreferredIOBufferDuration(0.005)
            
            // Enable background audio task
            enableBackgroundAudio()
            
            audioFilePath = getFileUrl(
                at: "\(UUID().uuidString).aac",
                in: directory
            )
            
            // Check if microphone is available
            guard recordingSession.isInputAvailable else {
                print("❌ Microphone not available")
                cleanup()
                return false
            }
            
            audioRecorder = try AVAudioRecorder(url: audioFilePath, settings: settings)
            audioRecorder.delegate = self
            
            if !audioRecorder.record() {
                print("❌ Failed to start recording")
                cleanup()
                return false
            }
            
            status = CurrentRecordingStatus.RECORDING
            print("📱 Recording started successfully")
            return true
        } catch {
            print("❌ Failed to start recording: \(error.localizedDescription)")
            cleanup()
            return false
        }
    }
    
    // Enable background audio mode
    private func enableBackgroundAudio() {
        // Tell system we need to continue working in background
        let sharedApp = UIApplication.shared
        if sharedApp.responds(to: #selector(UIApplication.beginBackgroundTask(expirationHandler:))) {
            print("📱 Enabling background audio tasks")
            
            // End any existing background task
            if backgroundTask != .invalid {
                sharedApp.endBackgroundTask(backgroundTask)
                backgroundTask = .invalid
            }
            
            // Start a new background task
            backgroundTask = sharedApp.beginBackgroundTask { [weak self] in
                print("⚠️ Background task about to expire")
                // Clean up if background task is expiring
                if let self = self, self.backgroundTask != .invalid {
                    sharedApp.endBackgroundTask(self.backgroundTask)
                    self.backgroundTask = .invalid
                }
            }
            
            print("🔍 Background task started with ID: \(backgroundTask)")
        }
    }
    
    @objc func handleAppDidBecomeActive(notification: Notification) {
        print("📱 App became active")
        if status == .PAUSED {
            print("📱 App active with paused recording - checking if we should resume")
            checkAndTryResume()
        }
    }
    
    private func cleanup() {
        // First set our status to NONE to prevent any new callbacks from starting work
        status = CurrentRecordingStatus.NONE
        
        // Mark as stopped to prevent any pending async operations
        isStopped = true
        
        // Immediately mark that we're not resetting session anymore
        isResettingSession = false
        
        // Stop timers synchronously on main thread if we're not already there
        if Thread.isMainThread {
            // If on main thread, invalidate timer directly
            audioCheckTimer?.invalidate()
            audioCheckTimer = nil
        } else {
            // If not on main thread, dispatch synchronously to main thread
            DispatchQueue.main.sync {
                self.audioCheckTimer?.invalidate()
                self.audioCheckTimer = nil
            }
        }
        
        // Stop proximity monitoring
        UIDevice.current.isProximityMonitoringEnabled = false
        isMonitoringProximitySensor = false
        
        // Remove all notification observers once
        NotificationCenter.default.removeObserver(self)
        
        do {
            if let recorder = audioRecorder {
                // Check if recording is still happening
                if recorder.isRecording {
                    recorder.stop()
                }
                audioRecorder = nil
            }
            
            if let session = recordingSession {
                // Just try to reset basic audio session state
                try? session.setActive(false, options: .notifyOthersOnDeactivation)
                if let originalCategory = originalRecordingSessionCategory {
                    try? session.setCategory(originalCategory)
                }
                recordingSession = nil
            }
            
            // End background task if active
            if backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
                backgroundTask = .invalid
            }
            
            originalRecordingSessionCategory = nil
        } catch {
            print("Error during cleanup: \(error)")
        }
    }
    
    public func stopRecording() {
        print("📱 Stopping recording")
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
        print("📱 Attempting to resume recording. Current status: \(status)")
        if(status == CurrentRecordingStatus.PAUSED) {
            // Check if already resetting
            if isResettingSession {
                print("📱 Already resetting audio session, ignoring duplicate call")
                return true
            }
            
            // Use a background queue for potential blocking calls
            isResettingSession = true
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else { return }
                self.attemptSessionReset(attempt: 1)
                // Note: isResettingSession is reset to false inside attemptSessionReset
            }
            return true // Return true immediately and handle actual resumption async
        } else {
            print("⚠️ Cannot resume recording - not currently paused (status: \(status))")
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
    
    // Simplified audio session reset method to handle Telegram call interruptions
    private func attemptSessionReset(attempt: Int) {
        print("📱 Audio session reset attempt \(attempt)")
        
        // If we've tried too many times, give up
        if attempt > 2 {
            print("❌ Failed to reset audio session after 2 attempts")
            isResettingSession = false
            return
        }
        
        let session = AVAudioSession.sharedInstance()
        
        do {
            // The key error code we're seeing is 561015905 ("Session activation failed")
            // This typically happens when another app still has partial ownership of the audio session
            
            // First deactivate with options that allow other apps to release resources
            print("🔍 Reset - Deactivating audio session with notification")
            try session.setActive(false, options: [.notifyOthersOnDeactivation])
            
            // Longer delay to let Telegram fully release the audio session
            let deactivationDelay = attempt == 1 ? 0.5 : 1.0
            print("🔍 Reset - Waiting \(deactivationDelay)s for system to release audio resources")
            Thread.sleep(forTimeInterval: deactivationDelay)
            
            // Force audio session to a neutral state first
            print("🔍 Reset - Setting intermediate category")
            try session.setCategory(.ambient, mode: .default)
            
            // Another brief pause
            Thread.sleep(forTimeInterval: 0.2)
            
            // Now attempt to set our desired category
            print("🔍 Reset - Setting final category to playAndRecord")
            try session.setCategory(.playAndRecord, 
                                   mode: .default,
                                   options: [.allowBluetooth, .defaultToSpeaker, .mixWithOthers])
            
            // Activate with options to take priority
            print("🔍 Reset - Activating audio session")
            try session.setActive(true, options: [.notifyOthersOnDeactivation])
            
            if #available(iOS 14.5, *) {
                try session.setPrefersNoInterruptionsFromSystemAlerts(true)
            }
            
            print("🔍 Reset - Audio session successfully reset")
            print("🔍 Reset - Inputs available: \(session.isInputAvailable)")
            
            // Now restart the recorder
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.attemptRecorderRestart()
                self.isResettingSession = false
            }
            
        } catch {
            let nsError = error as NSError
            print("❌ Audio session reset error: \(error.localizedDescription)")
            print("🔍 Reset - Error code: \(nsError.code)")
            print("🔍 Reset - Error domain: \(nsError.domain)")
            print("🔍 Reset - Detailed error: \(nsError)")
            
            if let underlyingError = nsError.userInfo[NSUnderlyingErrorKey] as? NSError {
                print("🔍 Reset - Underlying error: \(underlyingError)")
                print("🔍 Reset - Underlying code: \(underlyingError.code)")
            }
            
            // Special handling for the common error after Telegram calls
            if nsError.code == 561015905 { // "Session activation failed"
                print("🔍 Reset - Detected Telegram session conflict - trying different approach")
                
                // Use a longer delay for subsequent attempts
                let delay = pow(2.0, Double(attempt)) * 0.5
                print("📱 Will retry with alternate approach in \(delay) seconds")
                
                DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + delay) { [weak self] in
                    guard let self = self else { return }
                    
                    // On second attempt, try a more forceful reset approach
                    if attempt == 2 {
                        // Force reset entire AVAudioSession system on last attempt
                        print("🔍 Reset - Final attempt with forced audio subsystem reset")
                        self.forceAudioSessionReset()
                    } else {
                        self.attemptSessionReset(attempt: attempt + 1)
                    }
                }
            } else {
                // For other errors, just retry normally
                let delay = 1.0
                print("📱 Will retry audio session reset in \(delay) seconds")
                
                DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + delay) { [weak self] in
                    guard let self = self else { return }
                    self.attemptSessionReset(attempt: attempt + 1)
                }
            }
        }
    }
    
    // New method for last-resort audio session reset
    private func forceAudioSessionReset() {
        print("🔍 Performing forced audio subsystem reset")
        do {
            // Get a fresh audio session
            let session = AVAudioSession.sharedInstance()
            
            // First set to ambient (lowest privilege)
            try session.setCategory(.ambient)
            try session.setActive(false, options: .notifyOthersOnDeactivation)
            Thread.sleep(forTimeInterval: 0.5)
            
            // Completely deactivate
            NotificationCenter.default.removeObserver(self, 
                                                     name: AVAudioSession.interruptionNotification, 
                                                     object: nil)
            
            // Now try to reactivate with our desired settings
            try session.setCategory(.playAndRecord, 
                                   mode: .default,
                                   options: [.allowBluetooth, .defaultToSpeaker, .mixWithOthers])
            try session.setActive(true)
            
            // Reset our observers
            NotificationCenter.default.addObserver(self,
                                                 selector: #selector(handleInterruption),
                                                 name: AVAudioSession.interruptionNotification,
                                                 object: session)
            
            print("🔍 Forced reset successful")
            
            // Try to resume
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.attemptRecorderRestart()
                self.isResettingSession = false
            }
        } catch {
            let nsError = error as NSError
            print("❌ Even forced reset failed: \(error.localizedDescription)")
            print("🔍 Force Reset - Error code: \(nsError.code)")
            print("🔍 Force Reset - Error domain: \(nsError.domain)")
            print("🔍 Force Reset - Detailed error: \(nsError)")
            
            if let underlyingError = nsError.userInfo[NSUnderlyingErrorKey] as? NSError {
                print("🔍 Force Reset - Underlying error: \(underlyingError)")
                print("🔍 Force Reset - Underlying code: \(underlyingError.code)")
            }
            
            // Print current audio session state for diagnosis
            let session = AVAudioSession.sharedInstance()
            print("🔍 Force Reset - Current audio category: \(session.category.rawValue)")
            print("🔍 Force Reset - Current audio mode: \(session.mode.rawValue)")
            print("🔍 Force Reset - Audio inputs available: \(session.isInputAvailable)")
            
            isResettingSession = false
        }
    }
    
    // New method to restart the recorder after session is reset
    private func attemptRecorderRestart() {
        // If we're already stopping or not paused anymore, abort
        if status == .NONE {
            print("⚠️ Recording has been stopped, aborting restart")
            isResettingSession = false
            return
        }
        
        guard status == .PAUSED else {
            print("⚠️ Status changed during resumption, aborting (current: \(status))")
            isResettingSession = false
            return
        }
        
        print("🔍 Restart - Checking file at path: \(audioFilePath.path)")
        let fileExists = FileManager.default.fileExists(atPath: audioFilePath.path)
        print("🔍 Restart - File exists: \(fileExists)")
        
        if !fileExists {
            print("⚠️ Audio file no longer exists, cannot resume recording")
            // Print additional information about the file and directory
            let directory = audioFilePath.deletingLastPathComponent()
            print("🔍 Restart File Error - Directory: \(directory.path)")
            
            do {
                let directoryContents = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
                print("🔍 Restart File Error - Directory contents: \(directoryContents.map { $0.lastPathComponent })")
            } catch {
                print("🔍 Restart File Error - Failed to list directory: \(error.localizedDescription)")
            }
            
            isResettingSession = false
            return
        }
        
        do {
            // First verify the session is active
            let session = AVAudioSession.sharedInstance()
            if !session.isInputAvailable {
                print("⚠️ Audio input not available, cannot restart recording")
                print("🔍 Restart Input Error - Category: \(session.category.rawValue)")
                print("🔍 Restart Input Error - Mode: \(session.mode.rawValue)")
                
                // Log route information
                let currentRoute = session.currentRoute
                let outputs = currentRoute.outputs.map { "\($0.portName) (\($0.portType.rawValue))" }.joined(separator: ", ")
                let inputs = currentRoute.inputs.map { "\($0.portName) (\($0.portType.rawValue))" }.joined(separator: ", ")
                print("🔍 Restart Input Error - Current route:")
                print("🔍 Restart Input Error - Inputs: \(inputs.isEmpty ? "None" : inputs)")
                print("🔍 Restart Input Error - Outputs: \(outputs.isEmpty ? "None" : outputs)")
                
                isResettingSession = false
                return
            }
            
            // If the recorder is in an invalid state, recreate it
            if audioRecorder == nil || audioRecorder.url != audioFilePath {
                print("🔍 Restart - Creating new audio recorder instance")
                
                // If there's an existing recorder, stop it first
                if audioRecorder != nil {
                    audioRecorder.stop()
                }
                
                // Create a new recorder
                audioRecorder = try AVAudioRecorder(url: audioFilePath, settings: settings)
                audioRecorder.delegate = self
                print("🔍 Restart - Recorder created successfully")
            } else {
                print("🔍 Restart - Using existing recorder")
            }
            
            print("🔍 Restart - Calling record() method")
            if audioRecorder.record() {
                status = CurrentRecordingStatus.RECORDING
                print("📱 Recording resumed successfully")
            } else {
                print("❌ Failed to restart recording - record() returned false")
                print("🔍 Restart Failure - Recorder state: \(audioRecorder.isRecording ? "Recording" : "Not recording")")
                
                // Try to diagnose the issue
                print("🔍 Restart Failure - File path valid: \(audioFilePath.path.isEmpty ? "No" : "Yes")")
                print("🔍 Restart Failure - Session active: \(session.isInputAvailable ? "Yes" : "No")")
                print("🔍 Restart Failure - Audio category: \(session.category.rawValue)")
                
                // Try to reinitialize the recorder as a final attempt
                do {
                    print("🔍 Restart Failure - Final attempt to recreate recorder")
                    audioRecorder = try AVAudioRecorder(url: audioFilePath, settings: settings)
                    audioRecorder.delegate = self
                    
                    if audioRecorder.record() {
                        print("🔍 Restart Failure - Final attempt succeeded")
                        status = CurrentRecordingStatus.RECORDING
                    } else {
                        print("🔍 Restart Failure - Final attempt failed")
                    }
                } catch {
                    print("🔍 Restart Failure - Final attempt exception: \(error.localizedDescription)")
                }
            }
        } catch {
            let nsError = error as NSError
            print("❌ Error restarting recorder: \(error.localizedDescription)")
            print("🔍 Restart - Error code: \((error as NSError).code)")
            print("🔍 Restart - Error domain: \((error as NSError).domain)")
            print("🔍 Restart - User info: \(nsError.userInfo)")
            
            if let underlyingError = nsError.userInfo[NSUnderlyingErrorKey] as? NSError {
                print("🔍 Restart - Underlying error: \(underlyingError)")
                print("🔍 Restart - Underlying code: \(underlyingError.code)")
            }
            
            // Try to get file attributes to help diagnose permission issues
            do {
                let attributes = try FileManager.default.attributesOfItem(atPath: audioFilePath.path)
                print("🔍 Restart - File attributes: \(attributes)")
            } catch {
                print("🔍 Restart - Could not read file attributes: \(error.localizedDescription)")
            }
            
            isResettingSession = false
        }
    }
    
    // Helper to describe audio session category options
    private func describeAudioOptions(_ options: AVAudioSession.CategoryOptions) -> String {
        var descriptions = [String]()
        
        if options.contains(.mixWithOthers) { descriptions.append("mixWithOthers") }
        if options.contains(.duckOthers) { descriptions.append("duckOthers") }
        if options.contains(.allowBluetooth) { descriptions.append("allowBluetooth") }
        if options.contains(.defaultToSpeaker) { descriptions.append("defaultToSpeaker") }
        if options.contains(.interruptSpokenAudioAndMixWithOthers) { descriptions.append("interruptSpokenAudio") }
        if options.contains(.allowBluetoothA2DP) { descriptions.append("allowBluetoothA2DP") }
        if options.contains(.allowAirPlay) { descriptions.append("allowAirPlay") }
        
        return descriptions.isEmpty ? "none" : descriptions.joined(separator: ", ")
    }

    // Improved helper to check conditions and try resuming - better handling of VOIP calls
    private func checkAndTryResume() {
        // If the recorder has been stopped, don't try to resume
        if isStopped {
            print("📱 Recorder has been stopped, not trying to resume")
            return
        }
        
        let session = AVAudioSession.sharedInstance()
        let currentRoute = session.currentRoute
        
        // The most important factor: are we PAUSED and is the microphone available?
        if status != .PAUSED {
            return
        }
        
        print("🔍 Checking if we should resume recording")
        
        // Get key details about the current audio state
        let hasNormalSpeaker = currentRoute.outputs.contains { $0.portType == .builtInSpeaker }
        let hasReceiver = currentRoute.outputs.contains { $0.portType == .builtInReceiver }
        let hasMicrophone = !currentRoute.inputs.isEmpty
        let isOtherAudioPlaying = session.isOtherAudioPlaying
        
        print("🔍 - Has normal speaker: \(hasNormalSpeaker)")
        print("🔍 - Has receiver: \(hasReceiver)") 
        print("🔍 - Has microphone: \(hasMicrophone)")
        print("🔍 - Inputs available: \(session.isInputAvailable)")
        print("🔍 - Other audio playing: \(isOtherAudioPlaying)")
        
        // ENHANCED CONDITION 1: We have a speaker but no receiver - typical of call end
        let callEndedCondition = hasNormalSpeaker && !hasReceiver
        
        // ENHANCED CONDITION 2: We have a microphone and inputs are available
        let microphoneAvailable = hasMicrophone && session.isInputAvailable
        
        // ENHANCED CONDITION 3: Check for VOIP call end specifically
        // No other audio playing is a good signal that VOIP call ended
        let voipCallEndedCondition = !isOtherAudioPlaying && microphoneAvailable
        
        // Determine if we should attempt resumption - improved for VOIP calls
        if (callEndedCondition && microphoneAvailable) || voipCallEndedCondition {
            print("📱 Call appears to have ended, attempting to resume recording")
            
            // Enable background task
            enableBackgroundAudio()
            
            // Stop checking - we're about to attempt resuming
            stopAudioSessionAvailabilityCheck()
            
            // Ensure audio session is properly configured for recording
            configureAudioSessionForRecording { success in
                if success {
                    // If audio session is successfully configured, try to resume recording
                    self.resumeRecording()
                } else {
                    print("⚠️ Failed to configure audio session for recording")
                }
            }
        } else {
            print("📱 Not resuming - conditions not favorable")
            
            // If conditions aren't right yet, continue checking periodically
            if status == .PAUSED && session.isInputAvailable {
                if audioCheckTimer == nil {
                    startAudioSessionAvailabilityCheck()
                }
            }
        }
    }
    
    // Helper method to configure audio session for recording
    private func configureAudioSessionForRecording(completion: @escaping (Bool) -> Void) {
        // First attempt a quick reset
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { 
                completion(false)
                return 
            }
            
            let session = AVAudioSession.sharedInstance()
            
            do {
                print("🔍 Configuring audio session for recording")
                
                // Deactivate first
                try session.setActive(false, options: .notifyOthersOnDeactivation)
                Thread.sleep(forTimeInterval: 0.3)
                
                // Set the category with all necessary options
                try session.setCategory(.playAndRecord, 
                                      mode: .default,
                                      options: [.allowBluetooth, .duckOthers, .defaultToSpeaker, 
                                               .mixWithOthers, .allowAirPlay, .allowBluetoothA2DP])
                
                // Reactivate
                try session.setActive(true, options: .notifyOthersOnDeactivation)
                
                if #available(iOS 14.5, *) {
                    try session.setPrefersNoInterruptionsFromSystemAlerts(true)
                }
                
                print("🔍 Audio session configured successfully")
                
                DispatchQueue.main.async {
                    completion(true)
                }
            } catch {
                let nsError = error as NSError
                print("❌ Audio session configuration failed: \(error.localizedDescription)")
                print("🔍 Error code: \(nsError.code)")
                
                // If it's a Telegram-like error, try the full reset process instead
                if nsError.code == 561015905 { // "Session activation failed"
                    print("🔍 Detected session conflict - trying full reset process")
                    DispatchQueue.main.async {
                        self.resumeRecording() // This will trigger the full reset process
                    }
                } else {
                    DispatchQueue.main.async {
                        completion(false)
                    }
                }
            }
        }
    }

    // Helper to attempt resuming recording with retries
    // This method handles the actual resumption logic with multiple attempts
    // while checkAndTryResume checks the conditions for whether we should attempt at all
    private func tryResumeRecording(attempt: Int = 1) {
        // If the recorder has been stopped, don't try to resume
        if isStopped {
            print("📱 Recorder has been stopped, aborting resume attempts")
            return
        }
        
        print("📱 Attempting to resume recording (attempt \(attempt)/3)")
        
        // First log the current state for better diagnostics
        let session = AVAudioSession.sharedInstance()
        print("🔍 Resume - Current status: \(status)")
        print("🔍 Resume - Session active: \(session.isOtherAudioPlaying ? "Other audio playing" : "No other audio")")
        print("🔍 Resume - Audio inputs available: \(session.isInputAvailable)")
        print("🔍 Resume - Can record check: \(canRecord())")
        
        if status == .PAUSED && canRecord() {
            print("🔍 Status is PAUSED and can record, trying to resume")
            let isResumed = resumeRecording()
            print("🔍 Resume attempt result: \(isResumed)")
            
            if !isResumed && attempt < 3 {
                // Use exponential backoff for retries
                let delay = pow(2.0, Double(attempt)) * 0.5  
                print("📱 Resume failed, will retry in \(delay) seconds (attempt \(attempt+1)/3)")
                
                // Before retrying, log more detailed state information to help diagnose issues
                print("🔍 Resume Retry - Current audio category: \(session.category.rawValue)")
                print("🔍 Resume Retry - Current audio mode: \(session.mode.rawValue)")
                print("🔍 Resume Retry - Recording permission: \(session.recordPermission.rawValue)")
                
                // Check the audio route
                let currentRoute = session.currentRoute
                let outputs = currentRoute.outputs.map { "\($0.portName)" }.joined(separator: ", ")
                let inputs = currentRoute.inputs.map { "\($0.portName)" }.joined(separator: ", ")
                print("🔍 Resume Retry - Current route - Inputs: \(inputs.isEmpty ? "None" : inputs), Outputs: \(outputs)")
                
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                    guard let self = self else { return }
                    self.tryResumeRecording(attempt: attempt + 1)
                }   
            } else if !isResumed {
                print("⚠️ Failed to resume recording after 3 attempts")
                print("🔍 Final Resume Failure - Recorder state: \(audioRecorder?.isRecording ?? false ? "Recording" : "Not recording")")
                print("🔍 Final Resume Failure - File exists: \(FileManager.default.fileExists(atPath: audioFilePath.path))")
                print("🔍 Final Resume Failure - Session category: \(session.category.rawValue)")
                
                // Check if session activation is the issue
                do {
                    try session.setActive(true, options: .notifyOthersOnDeactivation)
                    print("🔍 Final Resume Failure - Session activation succeeded on retry")
                } catch {
                    let nsError = error as NSError
                    print("❌ Final Resume Failure - Session activation error: \(error.localizedDescription)")
                    print("🔍 Final Resume Failure - Error code: \(nsError.code)")
                    print("🔍 Final Resume Failure - Error domain: \(nsError.domain)")
                }
            }
        } else {
            print("⚠️ Cannot try to resume - status: \(status), can record: \(canRecord())")
            
            // Log details about why we can't record
            if status != .PAUSED {
                print("🔍 Resume Invalid - Wrong status: \(status)")
            } else if !canRecord() {
                print("🔍 Resume Invalid - Cannot record:")
                print("🔍 Resume Invalid - Input available: \(session.isInputAvailable)")
                print("🔍 Resume Invalid - Record permission: \(session.recordPermission.rawValue)")
                
                // Check if we have a valid recording session
                if recordingSession == nil {
                    print("🔍 Resume Invalid - Recording session is nil")
                }
            }
        }
    }

    // Helper to get readable route change reason
    private func reasonString(for reason: AVAudioSession.RouteChangeReason) -> String {
        switch reason {
        case .newDeviceAvailable: return "New device available"
        case .oldDeviceUnavailable: return "Old device unavailable"
        case .categoryChange: return "Category changed"
        case .override: return "Route overridden"
        case .wakeFromSleep: return "Woke from sleep"
        case .noSuitableRouteForCategory: return "No suitable route for category"
        case .routeConfigurationChange: return "Route configuration changed"
        case .unknown: return "Unknown reason"
        @unknown default: return "Unknown reason (\(reason.rawValue))"
        }
    }

    // Helper method to attempt background resume
    private func attemptBackgroundResume() {
        print("📱 Attempting to resume in background")
        
        // First ensure we have a background task
        enableBackgroundAudio()
        
        // Configure audio session and then check if we can resume
        configureAudioSessionForRecording { [weak self] success in
            guard let self = self else { return }
            
            if success {
                self.checkAndTryResume()
            } else {
                print("⚠️ Failed to configure audio session for background resumption")
                // Try full reset process as fallback
                self.resumeRecording()
            }
        }
    }
}

extension CustomMediaRecorder:AVAudioRecorderDelegate {
    // Implement delegate methods for better error handling
    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        print("🔍 Recording finished - Success: \(flag)")
        if !flag {
            // Recording failed to finish successfully
            print("⚠️ Recording did not finish successfully")
        }
    }
    
    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        if let error = error {
            let nsError = error as NSError
            print("❌ Recording encode error: \(error.localizedDescription)")
            print("🔍 Error code: \(nsError.code)")
            print("🔍 Error domain: \(nsError.domain)")
        }
    }
    
    @objc func handleInterruption(notification: Notification) {
        // If we've been stopped, don't handle any more interruptions
        if isStopped {
            return
        }
        
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            print("⚠️ Interruption: Missing notification info")
            return
        }

        // Log detailed interruption info
        let session = AVAudioSession.sharedInstance()
        print("🔍 Interruption details:")
        print("🔍 - Type: \(type == .began ? "Began" : "Ended")")
        print("🔍 - Current audio category: \(session.category.rawValue)")
        print("🔍 - Audio inputs available: \(session.isInputAvailable)")
        
        // Log audio route info
        let currentRoute = session.currentRoute
        let outputs = currentRoute.outputs.map { "\($0.portName) (\($0.portType.rawValue))" }.joined(separator: ", ")
        let inputs = currentRoute.inputs.map { "\($0.portName) (\($0.portType.rawValue))" }.joined(separator: ", ")
        print("🔍 - Audio route - Inputs: \(inputs.isEmpty ? "None" : inputs), Outputs: \(outputs)")

        switch type {
        case .began:
            print("📱 Audio recording interrupted - pausing")
            let _ = pauseRecording()
            
            // For VOIP calls, we need to observe for the call ending
            // We do this by registering for additional notifications while paused
            registerVOIPCallEndObservers()
            
        case .ended:
            // Check if we should attempt to resume automatically
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                print("🔍 - shouldResume flag: \(options.contains(.shouldResume))")
                
                if options.contains(.shouldResume) {
                    print("📱 Audio interruption ended with resume flag")
                    // Log the current state of audio session 
                    print("🔍 Active: \(session.isOtherAudioPlaying ? "Other audio playing" : "No other audio")")
                    
                    // Delay slightly to let system audio services fully reset
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        self.attemptBackgroundResume()
                    }
                } else {
                    print("📱 Audio interruption ended without resume flag")
                    print("🔍 Will check conditions for resumption anyway")
                    
                    // Some VOIP apps don't provide the resume flag
                    // Still try to resume if conditions look right
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        self.checkAndTryResume()
                    }
                }
            } else {
                print("⚠️ No interruption options provided")
                
                // Even without options, try to check if we can resume
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    self.checkAndTryResume()
                }
            }
        @unknown default:
            print("⚠️ Unknown interruption type: \(type.rawValue)")
            break
        }
    }

    // Register for additional observers that might indicate VOIP call end
    private func registerVOIPCallEndObservers() {
        print("📱 Registering for VOIP call end events")
        
        // Start monitoring proximity sensor - often disabled when calls end
        startProximitySensorMonitoring()
        
        // We're already registered for route changes, which is one indicator
        // Add a periodic check to detect when audio session becomes available again
        startAudioSessionAvailabilityCheck()
    }
    
    // Start monitoring device proximity sensor to detect when phone is moved away from face
    // This can be an indicator that a call has ended
    private func startProximitySensorMonitoring() {
        guard !isMonitoringProximitySensor else { return }
        
        print("📱 Starting proximity sensor monitoring")
        isMonitoringProximitySensor = true
        
        // Enable device proximity monitoring
        UIDevice.current.isProximityMonitoringEnabled = true
        
        // Add observer for proximity state changes
        NotificationCenter.default.addObserver(self,
                                             selector: #selector(handleProximityChange),
                                             name: UIDevice.proximityStateDidChangeNotification,
                                             object: UIDevice.current)
    }
    
    private func stopProximitySensorMonitoring() {
        guard isMonitoringProximitySensor else { return }
        
        print("📱 Stopping proximity sensor monitoring")
        isMonitoringProximitySensor = false
        
        // Disable proximity monitoring to save battery
        UIDevice.current.isProximityMonitoringEnabled = false
        
        // Remove the observer
        NotificationCenter.default.removeObserver(self,
                                                name: UIDevice.proximityStateDidChangeNotification,
                                                object: UIDevice.current)
    }
    
    @objc func handleProximityChange(notification: Notification) {
        let proximityState = UIDevice.current.proximityState
        print("📱 Proximity changed: \(proximityState ? "Near" : "Far")")
        
        // When device moves away from face (proximityState becomes false)
        // This often happens when a call ends
        if !proximityState && status == .PAUSED {
            print("📱 Device moved away from face, might indicate call ended")
            
            // Wait a moment for audio system to stabilize
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                guard let self = self, self.status == .PAUSED else { return }
                
                // Check if conditions are favorable for resuming
                self.checkAndTryResume()
            }
        }
    }
    
    // Start periodic check for audio session availability
    // This helps detect when VOIP calls (especially Telegram) end
    private func startAudioSessionAvailabilityCheck() {
        // Stop any existing timer
        stopAudioSessionAvailabilityCheck()
        
        print("📱 Starting audio session availability checks")
        
        // Create and schedule timer on main thread
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            // Check every 1 second if we can resume recording
            self.audioCheckTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                
                // Only check if we're still paused
                if self.status == .PAUSED {
                    let session = AVAudioSession.sharedInstance()
                    
                    print("🔍 Periodic check - Input available: \(session.isInputAvailable)")
                    print("🔍 Periodic check - Other audio playing: \(session.isOtherAudioPlaying)")
                    
                    // If inputs are available and no other audio is playing, might be time to resume
                    if session.isInputAvailable && !session.isOtherAudioPlaying {
                        print("📱 Audio session appears available after VOIP call")
                        self.checkAndTryResume()
                    }
                } else {
                    // If we're not paused anymore, stop checking
                    self.stopAudioSessionAvailabilityCheck()
                }
            }
            
            // Make sure timer runs when app is in background
            RunLoop.main.add(self.audioCheckTimer!, forMode: .common)
        }
    }
    
    private func stopAudioSessionAvailabilityCheck() {
        // Ensure we invalidate timer on main thread
        if audioCheckTimer != nil {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.audioCheckTimer?.invalidate()
                self.audioCheckTimer = nil
            }
        }
    }
    
    @objc func handleRouteChange(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }
        
        let reasonStr = reasonString(for: reason)
        print("📱 Route change: \(reasonStr)")
        
        // Print the essential route information 
        let session = AVAudioSession.sharedInstance()
        let currentRoute = session.currentRoute
        
        // Log current route (most important info)
        let inputs = currentRoute.inputs.map { "\($0.portName)" }.joined(separator: ", ")
        let outputs = currentRoute.outputs.map { "\($0.portName)" }.joined(separator: ", ")
        print("🔍 Route - Inputs: \(inputs.isEmpty ? "None" : inputs), Outputs: \(outputs)")
        
        // Get previous route if available
        if let previousRouteRaw = userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription {
            let prevInputs = previousRouteRaw.inputs.map { "\($0.portName)" }.joined(separator: ", ")
            let prevOutputs = previousRouteRaw.outputs.map { "\($0.portName)" }.joined(separator: ", ")
            print("🔍 Route - Previous inputs: \(prevInputs.isEmpty ? "None" : prevInputs)")
            print("🔍 Route - Previous outputs: \(prevOutputs.isEmpty ? "None" : prevOutputs)")
            
            // Check if this looks like a VOIP call ending (receiver/headset -> speaker transition)
            let hadReceiver = previousRouteRaw.outputs.contains { $0.portType == .builtInReceiver }
            let hadHeadphones = previousRouteRaw.outputs.contains { 
                $0.portType == .headphones || $0.portType == .bluetoothA2DP || $0.portType == .bluetoothHFP 
            }
            let nowHasSpeaker = currentRoute.outputs.contains { $0.portType == .builtInSpeaker }
            
            if (hadReceiver || hadHeadphones) && nowHasSpeaker && status == .PAUSED {
                print("📱 Detected transition that looks like call ending")
            }
        }
        
        // Handle the route change based on current status
        switch status {
        case .RECORDING:
            // Category changes and overrides usually interrupt recording
            if reason == .categoryChange || reason == .override {
                print("📱 Pausing recording due to \(reasonStr)")
                let _ = pauseRecording()
            }
            
        case .PAUSED:
            // Any route change while paused might be the end of a call
            // Wait briefly to let system stabilize before checking
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                guard let self = self else { return }
                if self.status == .PAUSED {
                    // Check in background context
                    self.enableBackgroundAudio()
                    self.checkAndTryResume()
                }
            }
            
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
}
