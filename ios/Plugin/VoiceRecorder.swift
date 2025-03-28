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

    private func doesUserGaveAudioRecordingPermission() -> Bool {
        return AVAudioSession.sharedInstance().recordPermission == AVAudioSession.RecordPermission.granted
    }
    
    private func getMsDurationOfAudioFile(_ filePath: URL?) -> Int {
        if filePath == nil {
            return -1
        }
        return Int(CMTimeGetSeconds(AVURLAsset(url: filePath!).duration) * 1000)
    }
}


