import type { PluginListenerHandle } from '@capacitor/core';

export type Base64String = string;

export interface RecordingData {
  value: {
    msDuration: number;
    mimeType: string;
    filePath: string;
  };
}

export interface RecordingInfoData {
  value: {
    msDuration: number;
    mimeType: string;
    filePath: string;
    hasSegments: boolean;
  };
}

export interface GenericResponse {
  value: boolean;
}

export interface CurrentRecordingStatus {
  status: 'RECORDING' | 'PAUSED' | 'NONE' | 'INTERRUPTED';
  reason?: string;
}

export interface RecordingSessionInfo {
  sessionId: string;
  filePath: string;
  status: 'RECORDING' | 'PAUSED' | 'NONE' | 'INTERRUPTED';
  startedAt: number;
  updatedAt: number;
  platform: 'ios' | 'android' | 'web';
  hasSegments?: boolean;
  directory?: string;
  interruptionReason?: string;
}

export interface ActiveRecordingSessionResult {
  value: RecordingSessionInfo | null;
}

export interface RecoverableRecordingSessionsResult {
  sessions: RecordingSessionInfo[];
}

export interface AudioStreamOptions {
  sampleRate?: number; // Default: 48000 on native, 44100 on web
  channels?: number; // Default: 1 (mono)
  bufferSize?: number; // Default: 4096

  /**
   * Android-only: keep the app alive while streaming by running a foreground service
   * with a persistent notification. Required for long-running offline sessions that
   * must survive backgrounding / screen-lock.
   */
  useForegroundService?: boolean;

  /**
   * When set, the native plugin writes every captured PCM chunk to disk (16-bit LE
   * mono PCM at 16 kHz) in a per-session directory, atomically (tmp + rename),
   * before notifying JS via `audioData`. The `seq` and `path` of each chunk are
   * included on the event payload. This persists through JS bridge / WebView crashes.
   *
   * The value is used verbatim as the session directory name and must be filesystem-safe.
   */
  persistSessionId?: string;
}

export interface AudioDataEvent {
  audioData: Float32Array;
  sampleRate: number;
  timestamp: number;
  channels: number;

  /** Monotonic, zero-based sequence number within a session. Present when `persistSessionId` is set. */
  seq?: number;

  /** Absolute native path to the persisted chunk file. Present when `persistSessionId` is set. */
  path?: string;

  /**
   * When the native plugin performed downsampling to 16 kHz 16-bit PCM for persistence,
   * this flag is true and `audioData` is already in that format (normalized Float32 for
   * compatibility with existing JS consumers). JS should skip its own downsample path.
   */
  downsampled16kMono?: boolean;
}

export interface StreamingStatus {
  status: 'STREAMING' | 'STOPPED';
}

export interface RecordingFileInfo {
  filePath: string;
  fileName: string;
  size: number;
  createdAt: number;
  durationMs: number;
  isSegment?: boolean;
}

export interface ListRecordingFilesResult {
  files: RecordingFileInfo[];
}

export interface LiveChunkInfo {
  seq: number;
  path: string;
  size: number;
}

export interface ListLiveChunkSessionsResult {
  sessions: string[];
}

export interface ListLiveChunksResult {
  chunks: LiveChunkInfo[];
}

export interface ReadLiveChunkResult {
  /** Base64-encoded PCM bytes for the requested chunk. */
  data: Base64String;
  size: number;
}

export interface LiveChunkSessionInfo {
  totalBytes: number;
  count: number;
  firstSeq: number;
  lastSeq: number;
  /** Absolute native path to the session directory. */
  dir: string;
}

export interface VoiceRecorderPlugin {
  // Existing recording methods (DO NOT MODIFY - used by VoiceRecorderWrapper)
  addListener(
    eventName: 'recordingStateChange' | 'audioData',
    listenerFunc: ((status: CurrentRecordingStatus) => void) | ((event: AudioDataEvent) => void),
  ): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;

  canDeviceVoiceRecord(): Promise<GenericResponse>;

  requestAudioRecordingPermission(): Promise<GenericResponse>;

  hasAudioRecordingPermission(): Promise<GenericResponse>;

  startRecording(options?: { directory?: string; useForegroundService?: boolean }): Promise<RecordingData>;
  
  continueRecording(options: {
    filePath: string;
    smallIcon?: string;
    useForegroundService?: boolean;
    directory?: string;
  }): Promise<RecordingData>;

  stopRecording(): Promise<RecordingData>;

  pauseRecording(): Promise<GenericResponse>;

  resumeRecording(): Promise<GenericResponse>;

  getCurrentStatus(): Promise<CurrentRecordingStatus>;

  /**
   * Returns the native-owned active/recoverable recording session, if one exists.
   * This is the bridge-recovery source of truth after a WebView reload.
   */
  getActiveRecordingSession(): Promise<ActiveRecordingSessionResult>;

  /**
   * Lists native sessions with durable audio or segments that can be saved/resumed.
   */
  listRecoverableRecordingSessions(): Promise<RecoverableRecordingSessionsResult>;
  
  /**
   * Get information about a recording file without having to continue/stop it
   * This allows accessing a recording file even when the microphone is busy
   * @param options.filePath The path to the recording file
   */
  getRecordingInfo(options: {
    filePath: string;
  }): Promise<RecordingInfoData>;
  
  /**
   * Finalize a recording by merging any temporary segments without continuing/stopping it
   * This allows finalizing a recording even when the microphone is busy
   * @param options.filePath The path to the recording file
   */
  finalizeRecording(options: {
    filePath: string;
  }): Promise<RecordingData>;

  /**
   * Start streaming audio data in real-time for voice chat applications
   * @param options Audio streaming configuration
   */
  startAudioStream(options?: AudioStreamOptions): Promise<GenericResponse>;

  /**
   * Stop streaming audio data
   */
  stopAudioStream(): Promise<GenericResponse>;

  /**
   * Get current streaming status
   */
  getStreamingStatus(): Promise<StreamingStatus>;

  /**
   * List all recording files on disk for orphan detection
   */
  listRecordingFiles(options?: { directory?: string }): Promise<ListRecordingFilesResult>;

  // --- Live-session chunk persistence (native-owned, survives bridge/webview crashes) ---

  /**
   * List all live-chunk session directories currently present on disk. Used on app
   * boot to detect orphaned or recoverable sessions.
   */
  listLiveChunkSessions(): Promise<ListLiveChunkSessionsResult>;

  /**
   * List all chunks for a given live session, sorted by sequence number ascending.
   * Ignores any `.tmp` or otherwise partial files.
   */
  listLiveChunks(options: { sessionId: string }): Promise<ListLiveChunksResult>;

  /**
   * Read a single chunk's bytes as base64.
   */
  readLiveChunk(options: { sessionId: string; seq: number }): Promise<ReadLiveChunkResult>;

  /**
   * Delete a single chunk by sequence number (called after a successful WS ACK).
   */
  deleteLiveChunk(options: { sessionId: string; seq: number }): Promise<GenericResponse>;

  /**
   * Delete an entire session directory (used after successful finalization or dismiss).
   */
  deleteLiveChunkSession(options: { sessionId: string }): Promise<GenericResponse>;

  /**
   * Lightweight totals for a session (bytes, chunk count, seq range). Used by UI to
   * show progress and by the manager to check disk usage.
   */
  getLiveChunkSessionInfo(options: { sessionId: string }): Promise<LiveChunkSessionInfo>;

  /**
   * Assemble all chunks for a session into a single WAV file at the given output path.
   * Runs entirely in native code to avoid loading the full recording into JS memory.
   * Returns the absolute path of the WAV and its duration in ms.
   */
  assembleLiveChunksToWav(options: {
    sessionId: string;
    outputPath: string;
    sampleRate?: number; // default 16000
    channels?: number;   // default 1
  }): Promise<{ filePath: string; msDuration: number; size: number }>;
}
