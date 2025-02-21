import Foundation

struct RecordData {
    
    public let mimeType: String
    public let msDuration: Int
    public let filePath: String?
    
    public func toDictionary() -> Dictionary<String, Any> {
        return [
            "msDuration": msDuration,
            "mimeType": mimeType,
            "filePath": filePath,
        ]
    }
    
}
