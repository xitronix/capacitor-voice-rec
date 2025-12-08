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
  status: 'RECORDING' | 'PAUSED' | 'NONE';
}

export interface AudioStreamOptions {
  sampleRate?: number; // Default: 44100
  channels?: number; // Default: 1 (mono)
  bufferSize?: number; // Default: 4096
}

export interface AudioDataEvent {
  audioData: Float32Array;
  sampleRate: number;
  timestamp: number;
  channels: number;
}

export interface StreamingStatus {
  status: 'STREAMING' | 'STOPPED';
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
}
