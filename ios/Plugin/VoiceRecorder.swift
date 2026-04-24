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
        
        // Add audio session interruption handling for streaming
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioSessionInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        
        // Handle route changes (headphones plugged/unplugged)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioRouteChange),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
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

    @objc func listRecordingFiles(_ call: CAPPluginCall) {
        let directory = call.getString("directory")
        let dirURL = customMediaRecorder.getDirectory(directory: directory)
        let fileManager = FileManager.default

        var filesArray: [[String: Any]] = []

        // Scan main recording directory
        if let contents = try? fileManager.contentsOfDirectory(at: dirURL, includingPropertiesForKeys: [.fileSizeKey, .creationDateKey], options: .skipsHiddenFiles) {
            for fileURL in contents where fileURL.pathExtension == "aac" {
                if let info = getFileInfo(fileURL: fileURL, isSegment: false) {
                    filesArray.append(info)
                }
            }
        }

        // Scan VoiceRecorderSegments subdirectory
        let segmentsDir = dirURL.appendingPathComponent("VoiceRecorderSegments", isDirectory: true)
        if fileManager.fileExists(atPath: segmentsDir.path),
           let segmentContents = try? fileManager.contentsOfDirectory(at: segmentsDir, includingPropertiesForKeys: [.fileSizeKey, .creationDateKey], options: .skipsHiddenFiles) {
            for fileURL in segmentContents where fileURL.pathExtension == "aac" {
                if let info = getFileInfo(fileURL: fileURL, isSegment: true) {
                    filesArray.append(info)
                }
            }
        }

        call.resolve(["files": filesArray])
    }

    private func getFileInfo(fileURL: URL, isSegment: Bool) -> [String: Any]? {
        let fileManager = FileManager.default
        guard let attrs = try? fileManager.attributesOfItem(atPath: fileURL.path) else { return nil }

        let size = (attrs[.size] as? NSNumber)?.intValue ?? 0
        let createdAt = (attrs[.creationDate] as? Date)?.timeIntervalSince1970 ?? 0

        let asset = AVURLAsset(url: fileURL)
        let durationMs = Int(CMTimeGetSeconds(asset.duration) * 1000)

        return [
            "filePath": fileURL.absoluteString,
            "fileName": fileURL.lastPathComponent,
            "size": size,
            "createdAt": Int(createdAt * 1000),
            "durationMs": max(durationMs, 0),
            "isSegment": isSegment
        ]
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
    private var streamingBufferSize: UInt32 = 4096

    // Live-chunk persistence state
    private static let liveChunkRoot = "live-session-chunks"
    private static let liveChunkSampleRate: Double = 16000
    private static let liveChunkChannels: UInt32 = 1
    private var persistSessionId: String?
    private var persistSessionDir: URL?
    private var persistSeq: Int = 0
    private let persistSeqLock = NSLock()

    @objc func startAudioStream(_ call: CAPPluginCall) {
        if isStreaming {
            call.resolve(ResponseGenerator.failResponse())
            return
        }

        // Check permissions first
        let hasPermission = doesUserGaveAudioRecordingPermission()
        if !hasPermission {
            call.resolve(ResponseGenerator.failResponse())
            return
        }

        // Get options directly from call parameters
        streamingSampleRate = call.getDouble("sampleRate") ?? 48000
        streamingChannels = UInt32(call.getInt("channels") ?? 1)
        let bufferSize = UInt32(call.getInt("bufferSize") ?? 4096)
        streamingBufferSize = bufferSize

        if let sid = call.getString("persistSessionId"), !sid.isEmpty {
            guard Self.isValidSessionId(sid) else {
                print("VoiceRecorder: Invalid persistSessionId (unsafe characters)")
                call.resolve(ResponseGenerator.failResponse())
                return
            }
            persistSessionId = sid
            persistSessionDir = Self.ensureSessionDir(sid)
            persistSeq = Self.nextSeq(for: persistSessionDir!)
        } else {
            persistSessionId = nil
            persistSessionDir = nil
            persistSeq = 0
        }

        do {
            // Setup audio session for voice chat
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playAndRecord,
                                       mode: .voiceChat,
                                       options: [.defaultToSpeaker, .allowBluetoothA2DP, .mixWithOthers])
            try audioSession.setPreferredSampleRate(48000)
            try audioSession.setPreferredIOBufferDuration(0.005)
            try audioSession.setActive(true)

            try installEngineAndTap(bufferSize: bufferSize)
            isStreaming = true

            call.resolve(ResponseGenerator.successResponse())
        } catch {
            print("VoiceRecorder: Error starting audio stream: \(error)")
            call.resolve(ResponseGenerator.failResponse())
        }
    }

    private func installEngineAndTap(bufferSize: UInt32) throws {
        audioEngine = AVAudioEngine()
        inputNode = audioEngine!.inputNode

        let inputFormat = inputNode!.outputFormat(forBus: 0)
        streamingSampleRate = inputFormat.sampleRate
        streamingChannels = inputFormat.channelCount

        inputNode!.installTap(onBus: 0, bufferSize: bufferSize, format: inputFormat) { [weak self] (buffer, time) in
            self?.processAudioBuffer(buffer, time: time)
        }

        try audioEngine!.start()
    }

    private func reinstallEngineAndTap() {
        guard isStreaming else { return }
        do {
            if let inputNode = inputNode {
                inputNode.removeTap(onBus: 0)
            }
            audioEngine?.stop()
            audioEngine = nil
            inputNode = nil
            try AVAudioSession.sharedInstance().setActive(true)
            try installEngineAndTap(bufferSize: streamingBufferSize)
        } catch {
            print("VoiceRecorder: Failed to reinstall engine/tap: \(error)")
        }
    }

    private var bufferCount = 0
    private var silentBufferCount = 0

    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer, time: AVAudioTime) {
        guard let channelData = buffer.floatChannelData else {
            return
        }

        let frameLength = Int(buffer.frameLength)
        let audioData = Array(UnsafeBufferPointer(start: channelData[0], count: frameLength))

        let avgLevel = audioData.reduce(0) { $0 + abs($1) } / Float(audioData.count)
        bufferCount += 1

        if bufferCount % 100 == 0 {
            if avgLevel < 0.001 {
                silentBufferCount += 1
                if silentBufferCount > 10 {
                    print("VoiceRecorder: Extended silence detected - check microphone input")
                }
            } else {
                silentBufferCount = 0
            }
        }

        // If persistence is active, downsample + PCM16 + write BEFORE emitting to JS.
        var persistedSeq: Int? = nil
        var persistedPath: String? = nil
        var emittedSamples: [Float] = audioData
        var emittedSampleRate: Double = streamingSampleRate
        var emittedChannels: UInt32 = streamingChannels
        var downsampled = false

        if let sessionDir = persistSessionDir {
            let pcm16k = Self.downsampleToMono16k(samples: audioData, inRate: streamingSampleRate)
            persistSeqLock.lock()
            let seq = persistSeq
            persistSeq += 1
            persistSeqLock.unlock()
            do {
                let path = try Self.writeChunkAtomic(dir: sessionDir, seq: seq, pcm16: pcm16k)
                persistedSeq = seq
                persistedPath = path.path
            } catch {
                print("VoiceRecorder: Failed to persist chunk: \(error)")
            }
            emittedSamples = pcm16k.map { Float($0) / 32768.0 }
            emittedSampleRate = Self.liveChunkSampleRate
            emittedChannels = Self.liveChunkChannels
            downsampled = true
        }

        var data: [String: Any] = [
            "audioData": emittedSamples,
            "sampleRate": emittedSampleRate,
            "timestamp": Date().timeIntervalSince1970 * 1000,
            "channels": emittedChannels,
            "downsampled16kMono": downsampled
        ]
        if let seq = persistedSeq { data["seq"] = seq }
        if let path = persistedPath { data["path"] = path }

        notifyListeners("audioData", data: data)
    }

    @objc func stopAudioStream(_ call: CAPPluginCall) {
        do {
            isStreaming = false

            bufferCount = 0
            silentBufferCount = 0

            if let inputNode = inputNode {
                inputNode.removeTap(onBus: 0)
            }

            audioEngine?.stop()
            audioEngine = nil
            inputNode = nil

            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setActive(false)

            persistSessionId = nil
            persistSessionDir = nil
            persistSeq = 0

            call.resolve(ResponseGenerator.successResponse())
        } catch {
            call.resolve(ResponseGenerator.failResponse())
        }
    }

    @objc func getStreamingStatus(_ call: CAPPluginCall) {
        let result = ["status": isStreaming ? "STREAMING" : "STOPPED"]
        call.resolve(result)
    }

    // MARK: - Live-chunk plugin methods

    @objc func listLiveChunkSessions(_ call: CAPPluginCall) {
        let root = Self.liveChunkRootDir()
        var ids: [String] = []
        if let contents = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) {
            for url in contents {
                var isDir: ObjCBool = false
                if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
                    ids.append(url.lastPathComponent)
                }
            }
        }
        call.resolve(["sessions": ids])
    }

    @objc func listLiveChunks(_ call: CAPPluginCall) {
        guard let sid = call.getString("sessionId"), Self.isValidSessionId(sid) else {
            call.reject("Invalid sessionId")
            return
        }
        let dir = Self.liveChunkRootDir().appendingPathComponent(sid, isDirectory: true)
        var chunks: [[String: Any]] = []
        if let contents = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]) {
            let sorted = contents.sorted { $0.lastPathComponent < $1.lastPathComponent }
            for url in sorted {
                guard url.pathExtension == "pcm" else { continue }
                let name = url.deletingPathExtension().lastPathComponent
                guard let seq = Int(name) else { continue }
                let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
                chunks.append([
                    "seq": seq,
                    "path": url.path,
                    "size": size ?? 0
                ])
            }
        }
        call.resolve(["chunks": chunks])
    }

    @objc func readLiveChunk(_ call: CAPPluginCall) {
        guard let sid = call.getString("sessionId"), Self.isValidSessionId(sid),
              let seq = call.getInt("seq") else {
            call.reject("Invalid sessionId or seq")
            return
        }
        let fileUrl = Self.liveChunkRootDir()
            .appendingPathComponent(sid, isDirectory: true)
            .appendingPathComponent(String(format: "%06d.pcm", seq))
        guard FileManager.default.fileExists(atPath: fileUrl.path) else {
            call.reject("Chunk not found")
            return
        }
        do {
            let data = try Data(contentsOf: fileUrl)
            call.resolve([
                "data": data.base64EncodedString(),
                "size": data.count
            ])
        } catch {
            call.reject("Failed to read chunk: \(error.localizedDescription)")
        }
    }

    @objc func deleteLiveChunk(_ call: CAPPluginCall) {
        guard let sid = call.getString("sessionId"), Self.isValidSessionId(sid),
              let seq = call.getInt("seq") else {
            call.reject("Invalid sessionId or seq")
            return
        }
        let fileUrl = Self.liveChunkRootDir()
            .appendingPathComponent(sid, isDirectory: true)
            .appendingPathComponent(String(format: "%06d.pcm", seq))
        if FileManager.default.fileExists(atPath: fileUrl.path) {
            do {
                try FileManager.default.removeItem(at: fileUrl)
            } catch {
                call.resolve(ResponseGenerator.failResponse())
                return
            }
        }
        call.resolve(ResponseGenerator.successResponse())
    }

    @objc func deleteLiveChunkSession(_ call: CAPPluginCall) {
        guard let sid = call.getString("sessionId"), Self.isValidSessionId(sid) else {
            call.reject("Invalid sessionId")
            return
        }
        let dir = Self.liveChunkRootDir().appendingPathComponent(sid, isDirectory: true)
        if FileManager.default.fileExists(atPath: dir.path) {
            do {
                try FileManager.default.removeItem(at: dir)
            } catch {
                call.resolve(ResponseGenerator.failResponse())
                return
            }
        }
        call.resolve(ResponseGenerator.successResponse())
    }

    @objc func getLiveChunkSessionInfo(_ call: CAPPluginCall) {
        guard let sid = call.getString("sessionId"), Self.isValidSessionId(sid) else {
            call.reject("Invalid sessionId")
            return
        }
        let dir = Self.liveChunkRootDir().appendingPathComponent(sid, isDirectory: true)
        var totalBytes = 0
        var count = 0
        var firstSeq = -1
        var lastSeq = -1
        if let contents = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]) {
            for url in contents {
                guard url.pathExtension == "pcm" else { continue }
                let name = url.deletingPathExtension().lastPathComponent
                guard let seq = Int(name) else { continue }
                let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
                totalBytes += size ?? 0
                count += 1
                if firstSeq == -1 || seq < firstSeq { firstSeq = seq }
                if seq > lastSeq { lastSeq = seq }
            }
        }
        call.resolve([
            "totalBytes": totalBytes,
            "count": count,
            "firstSeq": firstSeq,
            "lastSeq": lastSeq,
            "dir": dir.path
        ])
    }

    @objc func assembleLiveChunksToWav(_ call: CAPPluginCall) {
        guard let sid = call.getString("sessionId"), Self.isValidSessionId(sid),
              let outputPath = call.getString("outputPath"), !outputPath.isEmpty else {
            call.reject("Invalid sessionId or outputPath")
            return
        }
        let sampleRate = UInt32(call.getInt("sampleRate") ?? Int(Self.liveChunkSampleRate))
        let channels = UInt32(call.getInt("channels") ?? Int(Self.liveChunkChannels))

        let dir = Self.liveChunkRootDir().appendingPathComponent(sid, isDirectory: true)
        guard FileManager.default.fileExists(atPath: dir.path) else {
            call.reject("Session directory not found")
            return
        }

        let fileManager = FileManager.default
        var chunkUrls: [URL] = []
        if let contents = try? fileManager.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) {
            chunkUrls = contents.filter { $0.pathExtension == "pcm" }
                                .sorted { $0.lastPathComponent < $1.lastPathComponent }
        }

        var dataBytes: UInt64 = 0
        for url in chunkUrls {
            let size = (try? fileManager.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
            dataBytes += UInt64(size ?? 0)
        }
        if dataBytes == 0 {
            call.reject("No chunks to assemble")
            return
        }

        let outURL = URL(fileURLWithPath: outputPath)
        try? fileManager.createDirectory(at: outURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fileManager.fileExists(atPath: outURL.path) {
            try? fileManager.removeItem(at: outURL)
        }
        fileManager.createFile(atPath: outURL.path, contents: nil)
        guard let out = try? FileHandle(forWritingTo: outURL) else {
            call.reject("Cannot open output for writing")
            return
        }
        defer { try? out.close() }

        out.write(Self.buildWavHeader(sampleRate: sampleRate, channels: channels, dataBytes: UInt32(min(UInt64(UInt32.max), dataBytes))))
        for url in chunkUrls {
            if let input = try? FileHandle(forReadingFrom: url) {
                while true {
                    let chunk = input.readData(ofLength: 64 * 1024)
                    if chunk.isEmpty { break }
                    out.write(chunk)
                }
                try? input.close()
            }
        }
        try? out.synchronize()

        let msDuration = (Double(dataBytes) / (Double(sampleRate) * Double(channels) * 2.0)) * 1000.0
        let finalSize = (try? fileManager.attributesOfItem(atPath: outURL.path)[.size] as? Int) ?? 0
        call.resolve([
            "filePath": outURL.path,
            "msDuration": Int(msDuration),
            "size": finalSize ?? 0
        ])
    }

    // MARK: - Audio Session Interruption Handling
    
    @objc private func handleAudioSessionInterruption(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }
        
        switch type {
        case .began:
            if isStreaming {
                audioEngine?.pause()
            }
            
        case .ended:
            if isStreaming {
                do {
                    try AVAudioSession.sharedInstance().setActive(true)
                    try audioEngine?.start()
                } catch {
                    print("VoiceRecorder: Failed to restart audio engine: \(error)")
                }
            }
            
        @unknown default:
            break
        }
    }
    
    @objc private func handleAudioRouteChange(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        switch reason {
        case .oldDeviceUnavailable, .newDeviceAvailable, .routeConfigurationChange:
            // Reinstall the tap so we continue capturing from the correct input.
            reinstallEngineAndTap()
        default:
            break
        }
    }

    // MARK: - Live-chunk helpers

    private static func isValidSessionId(_ id: String) -> Bool {
        if id.isEmpty || id.count > 128 { return false }
        for c in id {
            let isOk = c.isASCII && (c.isLetter || c.isNumber || c == "-" || c == "_")
            if !isOk { return false }
        }
        return true
    }

    private static func applicationSupportDir() -> URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        if !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        return url
    }

    private static func liveChunkRootDir() -> URL {
        let url = applicationSupportDir().appendingPathComponent(liveChunkRoot, isDirectory: true)
        if !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        return url
    }

    private static func ensureSessionDir(_ sessionId: String) -> URL {
        let url = liveChunkRootDir().appendingPathComponent(sessionId, isDirectory: true)
        if !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        return url
    }

    private static func nextSeq(for dir: URL) -> Int {
        guard let contents = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) else {
            return 0
        }
        var maxSeq = -1
        for url in contents where url.pathExtension == "pcm" {
            let name = url.deletingPathExtension().lastPathComponent
            if let s = Int(name), s > maxSeq { maxSeq = s }
        }
        return maxSeq + 1
    }

    private static func writeChunkAtomic(dir: URL, seq: Int, pcm16: [Int16]) throws -> URL {
        let filename = String(format: "%06d.pcm", seq)
        let finalUrl = dir.appendingPathComponent(filename)
        var data = Data(capacity: pcm16.count * 2)
        for s in pcm16 {
            var le = s.littleEndian
            withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
        }
        try data.write(to: finalUrl, options: [.atomic])
        return finalUrl
    }

    private static func downsampleToMono16k(samples: [Float], inRate: Double) -> [Int16] {
        if abs(inRate - liveChunkSampleRate) < 0.5 {
            return samples.map { Int16(max(-32768, min(32767, Int($0 * 32767.0)))) }
        }
        let ratio = inRate / liveChunkSampleRate
        // Integer-step decimation with box filter if clean multiple
        if ratio >= 2 && abs(ratio - ratio.rounded()) < 0.01 {
            let step = Int(ratio.rounded())
            let outLen = samples.count / step
            var out = [Int16](); out.reserveCapacity(outLen)
            var i = 0
            while i + step <= samples.count {
                var acc: Float = 0
                for k in 0..<step { acc += samples[i + k] }
                let avg = acc / Float(step)
                out.append(Int16(max(-32768, min(32767, Int(avg * 32767.0)))))
                i += step
            }
            return out
        }
        // Linear resample
        let outLen = Int(Double(samples.count) / ratio)
        var out = [Int16](); out.reserveCapacity(outLen)
        for i in 0..<outLen {
            let srcPos = Double(i) * ratio
            let srcIdx = Int(srcPos)
            let frac = srcPos - Double(srcIdx)
            let a = srcIdx < samples.count ? Double(samples[srcIdx]) : 0
            let b = srcIdx + 1 < samples.count ? Double(samples[srcIdx + 1]) : a
            let v = a + (b - a) * frac
            out.append(Int16(max(-32768, min(32767, Int(v * 32767.0)))))
        }
        return out
    }

    private static func buildWavHeader(sampleRate: UInt32, channels: UInt32, dataBytes: UInt32) -> Data {
        var header = Data()
        func writeStr(_ s: String) { header.append(contentsOf: s.utf8) }
        func writeU32(_ v: UInt32) { var le = v.littleEndian; withUnsafeBytes(of: &le) { header.append(contentsOf: $0) } }
        func writeU16(_ v: UInt16) { var le = v.littleEndian; withUnsafeBytes(of: &le) { header.append(contentsOf: $0) } }
        writeStr("RIFF")
        writeU32(36 + dataBytes)
        writeStr("WAVE")
        writeStr("fmt ")
        writeU32(16)
        writeU16(1) // PCM
        writeU16(UInt16(channels))
        writeU32(sampleRate)
        writeU32(sampleRate * channels * 2)
        writeU16(UInt16(channels * 2))
        writeU16(16)
        writeStr("data")
        writeU32(dataBytes)
        return header
    }
}


