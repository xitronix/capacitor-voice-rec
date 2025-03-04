import Foundation
import AVFoundation

class CustomMediaRecorder:NSObject {
    private var recordingSession: AVAudioSession!
    private var audioRecorder: AVAudioRecorder!
    private var audioFilePath: URL!
    private var originalRecordingSessionCategory: AVAudioSession.Category!
    private var status = CurrentRecordingStatus.NONE
    
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
        NotificationCenter.default.addObserver(self,
                                                selector: #selector(handleInterruption),
                                                name: AVAudioSession.interruptionNotification,
                                                object: AVAudioSession.sharedInstance())
        do {
            recordingSession = AVAudioSession.sharedInstance()
            originalRecordingSessionCategory = recordingSession.category
            try recordingSession.setCategory(AVAudioSession.Category.playAndRecord, options: .mixWithOthers)
            try recordingSession.setActive(true)
            // TODO: set audio file path

            audioFilePath = getFileUrl(
                at: "\(UUID().uuidString).aac",
                in: directory
            )
            audioRecorder = try AVAudioRecorder(url: audioFilePath, settings: settings)
            audioRecorder.record()
            status = CurrentRecordingStatus.RECORDING
            return true
        } catch {
            return false
        }
    }
    
    public func stopRecording() {
        do {
            audioRecorder.stop()
            try recordingSession.setActive(false)
            try recordingSession.setCategory(originalRecordingSessionCategory)
            originalRecordingSessionCategory = nil
            audioRecorder = nil
            recordingSession = nil
            status = CurrentRecordingStatus.NONE
        } catch {}
    }
    
    public func getOutputFile() -> URL {
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
            do{
                try recordingSession.setActive(true)
                audioRecorder.record()
                status = CurrentRecordingStatus.RECORDING
                return true
            }catch{
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
    
}

extension CustomMediaRecorder:AVAudioRecorderDelegate {
    @objc func handleInterruption(notification: Notification) {
        print("CUSTOM RECORDER \(#function)")
           guard let userInfo = notification.userInfo,
                 let interruptionTypeRawValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
                 let interruptionType = AVAudioSession.InterruptionType(rawValue: interruptionTypeRawValue) else {
               return
           }

           switch interruptionType {
           case .began:
               let _ = pauseRecording()
           case .ended:
               guard let optionsRawValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else { return }
               let options = AVAudioSession.InterruptionOptions(rawValue: optionsRawValue)
               if options.contains(.shouldResume) {
                   let _ = resumeRecording()
               }
           @unknown default:
               break
           }
       }
}
