import Foundation
import AVFoundation
import Capacitor

@objc(VoiceRecorder)
public class VoiceRecorder: CAPPlugin {
    private var customMediaRecorder = CustomMediaRecorder()
    private var audioFilePath: URL?

    override public func load() {
        customMediaRecorder.onStatusChange = { [weak self] status in
            self?.notifyListeners("recordingStateChange", data: ["status": status.rawValue])
        }
    }

    @objc func canDeviceVoiceRecord(_ call: CAPPluginCall) {
        call.resolve(ResponseGenerator.successResponse())
    }
    
    @objc func requestAudioRecordingPermission(_ call: CAPPluginCall) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            if granted {
                call.resolve(ResponseGenerator.successResponse())
            } else {
                call.resolve(ResponseGenerator.failResponse())
            }
        }
    }
    
    @objc func hasAudioRecordingPermission(_ call: CAPPluginCall) {
        call.resolve(ResponseGenerator.fromBoolean(doesUserGaveAudioRecordingPermission()))
    }

    @objc func startRecording(_ call: CAPPluginCall) {
        if(!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION)
            return
        }
        
        let directory = call.getString("directory")
        let successfullyStartedRecording = customMediaRecorder.startRecording(directory: directory)

        if successfullyStartedRecording == false {
            call.reject(Messages.CANNOT_RECORD_ON_THIS_PHONE)
            return
        }
            
        audioFilePath = customMediaRecorder.getOutputFile()
        let recordData = RecordData(
            mimeType: "audio/aac",
            msDuration: -1,
            filePath: audioFilePath!.absoluteString
        )
        call.resolve(ResponseGenerator.dataResponse(recordData.toDictionary()))
    }

    @objc func continueRecording(_ call: CAPPluginCall) {
        if(!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION)
            return
        }
        
        guard let prevFilePath = call.getString("filePath") else {
            call.reject("Missing previous recording file path")
            return
        }
        
        // Convert file URL string to URL
        let prevFileURL: URL
        if prevFilePath.hasPrefix("file://") {
            // Handle file:// URLs properly
            if let url = URL(string: prevFilePath) {
                prevFileURL = url
            } else {
                call.reject("Invalid file URL format: \(prevFilePath)")
                return
            }
        } else {
            prevFileURL = URL(fileURLWithPath: prevFilePath)
        }
        
        // Check if file exists
        if !FileManager.default.fileExists(atPath: prevFileURL.path) {
            call.reject("Previous recording file not found at path: \(prevFileURL.path)")
            return
        }
        
        // Check file is readable and has content
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: prevFileURL.path)
            let fileSize = attributes[.size] as? NSNumber ?? 0
            if fileSize.intValue <= 0 {
                call.reject("Previous recording file is empty")
                return
            }
        } catch {
            call.reject("Error checking file attributes: \(error.localizedDescription)")
            return
        }
        
        let directory = call.getString("directory")
        let successfullyStartedRecording = customMediaRecorder.continueRecording(
            fromURL: prevFileURL,
            directory: directory
        )

        if successfullyStartedRecording == false {
            call.reject(Messages.CANNOT_RECORD_ON_THIS_PHONE)
            return
        }
            
        audioFilePath = customMediaRecorder.getOutputFile()
        let recordData = RecordData(
            mimeType: "audio/aac",
            msDuration: -1,
            filePath: audioFilePath!.absoluteString
        )
        call.resolve(ResponseGenerator.dataResponse(recordData.toDictionary()))
    }

    @objc func stopRecording(_ call: CAPPluginCall) {
        customMediaRecorder.stopRecording()
        audioFilePath = customMediaRecorder.getOutputFile()
        
        if(audioFilePath == nil) {
            call.reject(Messages.FAILED_TO_FETCH_RECORDING)
            return
        }

        let recordData = RecordData(
            mimeType: "audio/aac",
            msDuration: getMsDurationOfAudioFile(audioFilePath),
            filePath: audioFilePath!.absoluteString
        )

        if recordData.filePath == nil || recordData.msDuration < 0 {
            call.reject(Messages.EMPTY_RECORDING)
        } else {
            call.resolve(ResponseGenerator.dataResponse(recordData.toDictionary()))
        }
    }

    @objc func pauseRecording(_ call: CAPPluginCall) {
        let paused = customMediaRecorder.pauseRecording()
        call.resolve(ResponseGenerator.fromBoolean(paused))
    }

    @objc func resumeRecording(_ call: CAPPluginCall) {
        let resumed = customMediaRecorder.resumeRecording()
        call.resolve(ResponseGenerator.fromBoolean(resumed))
    }

    @objc func getCurrentStatus(_ call: CAPPluginCall) {
        let status = customMediaRecorder.getCurrentStatus()
        call.resolve(ResponseGenerator.statusResponse(status))
    }

    /**
     * Get information about a recording file without having to continue/stop it
     * This allows apps to directly access recording information even if the microphone is busy
     */
    @objc func getRecordingInfo(_ call: CAPPluginCall) {
        guard let filePath = call.getString("filePath") else {
            call.reject("Missing file path")
            return
        }
        
        let (exists, fileURL, durationMs, hasSegments) = customMediaRecorder.getRecordingInfo(filePath: filePath)
        
        if !exists || fileURL == nil {
            call.reject("Recording file not found or invalid")
            return
        }
        
        let recordData = RecordData(
            mimeType: "audio/aac",
            msDuration: durationMs,
            filePath: fileURL!.absoluteString
        )
        
        var response = recordData.toDictionary()
        response["hasSegments"] = hasSegments
        
        call.resolve(ResponseGenerator.dataResponse(response))
    }
    
    /**
     * Finalize a recording by merging any temporary segments without continuing/stopping it
     * This allows apps to access and finalize recordings even if the microphone is busy
     */
    @objc func finalizeRecording(_ call: CAPPluginCall) {
        guard let filePath = call.getString("filePath") else {
            call.reject("Missing file path")
            return
        }
        
        let (success, fileURL, durationMs) = customMediaRecorder.finalizeRecording(filePath: filePath)
        
        if !success || fileURL == nil {
            call.reject("Failed to finalize recording")
            return
        }
        
        let recordData = RecordData(
            mimeType: "audio/aac",
            msDuration: durationMs,
            filePath: fileURL!.absoluteString
        )
        
        call.resolve(ResponseGenerator.dataResponse(recordData.toDictionary()))
    }

    private func doesUserGaveAudioRecordingPermission() -> Bool {
        return AVAudioSession.sharedInstance().recordPermission == AVAudioSession.RecordPermission.granted
    }
    
    private func getMsDurationOfAudioFile(_ filePath: URL?) -> Int {
        if filePath == nil {
            return -1
        }
        return Int(CMTimeGetSeconds(AVURLAsset(url: filePath!).duration) * 1000)
    }
    
    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?
    private var isStreaming = false
    private var streamingSampleRate: Double = 44100
    private var streamingChannels: UInt32 = 1

    @objc func startAudioStream(_ call: CAPPluginCall) {
        print("VoiceRecorder: startAudioStream called")
        if isStreaming {
            print("VoiceRecorder: Already streaming, returning failure")
            call.resolve(ResponseGenerator.failResponse())
            return
        }

        // Check permissions first
        let hasPermission = doesUserGaveAudioRecordingPermission()
        print("VoiceRecorder: Audio recording permission granted: \(hasPermission)")
        if !hasPermission {
            print("VoiceRecorder: Audio recording permission denied")
            call.resolve(ResponseGenerator.failResponse())
            return
        }

        // Get options directly from call parameters
        streamingSampleRate = call.getDouble("sampleRate") ?? 44100
        streamingChannels = UInt32(call.getInt("channels") ?? 1)
        let bufferSize = UInt32(call.getInt("bufferSize") ?? 4096)

        do {
            // Setup audio session
            let audioSession = AVAudioSession.sharedInstance()
            print("VoiceRecorder: Setting up audio session for streaming")
            try audioSession.setCategory(.record, mode: .measurement, options: [])
            try audioSession.setActive(true)
            print("VoiceRecorder: Audio session activated successfully")

            // Create audio engine
            audioEngine = AVAudioEngine()
            inputNode = audioEngine!.inputNode
            print("VoiceRecorder: Audio engine created")

            // Configure input format
            let inputFormat = inputNode!.outputFormat(forBus: 0)
            let recordingFormat = AVAudioFormat(
                standardFormatWithSampleRate: streamingSampleRate,
                channels: streamingChannels
            )!
            print("VoiceRecorder: Input format configured - sampleRate: \(streamingSampleRate), channels: \(streamingChannels)")

            // Install tap on input node
            inputNode!.installTap(onBus: 0, bufferSize: bufferSize, format: inputFormat) { [weak self] (buffer, time) in
                self?.processAudioBuffer(buffer, time: time)
            }
            print("VoiceRecorder: Audio tap installed")

            // Start audio engine
            try audioEngine!.start()
            isStreaming = true
            print("VoiceRecorder: Audio engine started successfully")

            call.resolve(ResponseGenerator.successResponse())
        } catch {
            print("VoiceRecorder: Error starting audio stream: \(error)")
            call.resolve(ResponseGenerator.failResponse())
        }
    }

    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer, time: AVAudioTime) {
        guard let channelData = buffer.floatChannelData else { 
            print("VoiceRecorder: No channel data in buffer")
            return 
        }
        
        let frameLength = Int(buffer.frameLength)
        let audioData = Array(UnsafeBufferPointer(start: channelData[0], count: frameLength))

        // Debug: Log audio data occasionally
        if Int.random(in: 1...100) == 1 { // ~1% of the time
            let avgLevel = audioData.reduce(0, +) / Float(audioData.count)
            print("VoiceRecorder: Processing \(frameLength) samples, avg level: \(avgLevel)")
        }

        // Send data to JavaScript
        let data: [String: Any] = [
            "audioData": audioData,
            "sampleRate": streamingSampleRate,
            "timestamp": Date().timeIntervalSince1970 * 1000, // milliseconds
            "channels": streamingChannels
        ]

        notifyListeners("audioData", data: data)
    }

    @objc func stopAudioStream(_ call: CAPPluginCall) {
        do {
            isStreaming = false
            
            if let inputNode = inputNode {
                inputNode.removeTap(onBus: 0)
            }
            
            audioEngine?.stop()
            audioEngine = nil
            inputNode = nil

            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setActive(false)

            call.resolve(ResponseGenerator.successResponse())
        } catch {
            call.resolve(ResponseGenerator.failResponse())
        }
    }

    @objc func getStreamingStatus(_ call: CAPPluginCall) {
        let result = ["status": isStreaming ? "STREAMING" : "STOPPED"]
        call.resolve(result)
    }
}


