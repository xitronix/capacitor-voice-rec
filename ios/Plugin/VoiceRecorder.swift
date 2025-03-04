import Foundation
import AVFoundation
import Capacitor

@objc(VoiceRecorder)
public class VoiceRecorder: CAPPlugin {

    private var customMediaRecorder: CustomMediaRecorder? = nil
    private var audioFilePath: URL?

    @objc public func canDeviceVoiceRecord(_ call: CAPPluginCall) {
        call.resolve(ResponseGenerator.successResponse())
    }
    
    @objc public func requestAudioRecordingPermission(_ call: CAPPluginCall) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            if granted {
                call.resolve(ResponseGenerator.successResponse())
            } else {
                call.resolve(ResponseGenerator.failResponse())
            }
        }
    }
    
    @objc public func hasAudioRecordingPermission(_ call: CAPPluginCall) {
        call.resolve(ResponseGenerator.fromBoolean(doesUserGaveAudioRecordingPermission()))
    }

    @objc public func startRecording(_ call: CAPPluginCall) {
        if(!doesUserGaveAudioRecordingPermission()) {
            call.reject(Messages.MISSING_PERMISSION)
            return
        }
        
        if(customMediaRecorder != nil) {
            call.reject(Messages.ALREADY_RECORDING)
            return
        }
        
        customMediaRecorder = CustomMediaRecorder()
        if(customMediaRecorder == nil) {
            call.reject(Messages.CANNOT_RECORD_ON_THIS_PHONE)
            return
        }
        
        let directory = call.getString("directory")
        let successfullyStartedRecording = customMediaRecorder!.startRecording(directory: directory)

        if successfullyStartedRecording == false {
            call.reject(Messages.CANNOT_RECORD_ON_THIS_PHONE)
        } else {
            audioFilePath = customMediaRecorder?.getOutputFile()
            let recordData = RecordData(
                mimeType: "audio/aac",
                msDuration: -1,
                filePath: audioFilePath!.absoluteString
            )
            call.resolve(ResponseGenerator.dataResponse(recordData.toDictionary()))
        }
    }

    @objc public func stopRecording(_ call: CAPPluginCall) {
        if(customMediaRecorder == nil) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED)
            return
        }
        
        customMediaRecorder?.stopRecording()
        audioFilePath = customMediaRecorder?.getOutputFile()
        
        if(audioFilePath == nil) {
            customMediaRecorder = nil
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
        customMediaRecorder = nil
    }
    @objc public func isPause() -> Bool{
        return (customMediaRecorder?.getCurrentStatus() == .PAUSED)
    }
    
    @objc public func pauseRecording(_ call: CAPPluginCall) {
        if(customMediaRecorder == nil) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED)
        } else {
            call.resolve(ResponseGenerator.fromBoolean(customMediaRecorder?.pauseRecording() ?? false))
        }
    }
    
    @objc public func resumeRecording(_ call: CAPPluginCall) {
        if(customMediaRecorder == nil) {
            call.reject(Messages.RECORDING_HAS_NOT_STARTED)
        } else {
            call.resolve(ResponseGenerator.fromBoolean(customMediaRecorder?.resumeRecording() ?? false))
        }
    }
    
    @objc public func getCurrentStatus(_ call: CAPPluginCall) {
        if(customMediaRecorder == nil) {
            call.resolve(ResponseGenerator.statusResponse(CurrentRecordingStatus.NONE))
        } else {
            call.resolve(ResponseGenerator.statusResponse(customMediaRecorder?.getCurrentStatus() ?? CurrentRecordingStatus.NONE))
        }
    }
    
    public func doesUserGaveAudioRecordingPermission() -> Bool {
        return AVAudioSession.sharedInstance().recordPermission == AVAudioSession.RecordPermission.granted
    }
    
    public func readFileAsBase64(_ filePath: URL?) -> String? {
        if(filePath == nil) {
            return nil
        }
        
        do {
            let fileData = try Data.init(contentsOf: filePath!)
            let fileStream = fileData.base64EncodedString(options: NSData.Base64EncodingOptions.init(rawValue: 0))
            return fileStream
        } catch {}
        
        return nil
    }
    
    public func getMsDurationOfAudioFile(_ filePath: URL?) -> Int {
        if filePath == nil {
            return -1
        }
        return Int(CMTimeGetSeconds(AVURLAsset(url: filePath!).duration) * 1000)
    }
    public func getAudioFile() -> URL? {
        return audioFilePath
    }

    // public removeRecording(_ call: CAPPluginCall) {
    //      guard let file = call.getString("path") else {
    //         handleError(call, "path must be provided and must be a string.")
    //         return
    //     }

    //     let directory = call.getString("directory")
    //     guard let fileUrl = customMediaRecorder.getFileUrl(at: file, in: directory) else {
    //         call.reject(ResponseGenerator.failResponse());
    //         return
    //     }

    //     customMediaRecorder.deleteRecording(fileUrl)
    // }
}


