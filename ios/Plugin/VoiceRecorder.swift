import Foundation
import AVFoundation
import Capacitor

@objc(VoiceRecorder)
public class VoiceRecorder: CAPPlugin {
    private let customMediaRecorder = CustomMediaRecorder()
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
            customMediaRecorder = nil
            call.reject(Messages.CANNOT_RECORD_ON_THIS_PHONE)
        } else {
            audioFilePath = customMediaRecorder.getOutputFile()
            let recordData = RecordData(
                mimeType: "audio/aac",
                msDuration: -1,
                filePath: audioFilePath!.absoluteString
            )
            call.resolve(ResponseGenerator.dataResponse(recordData.toDictionary()))
        }
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


