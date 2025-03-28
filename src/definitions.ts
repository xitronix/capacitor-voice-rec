import type { PluginListenerHandle } from '@capacitor/core';

export type Base64String = string;

export interface RecordingData {
  value: {
    msDuration: number;
    mimeType: string;
    filePath: string;
  };
}

export interface GenericResponse {
  value: boolean;
}

export interface CurrentRecordingStatus {
  status: 'RECORDING' | 'PAUSED' | 'NONE';
}

export interface VoiceRecorderPlugin {
  addListener(
    eventName: 'recordingStateChange',
    listenerFunc: (status: CurrentRecordingStatus) => void,
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
}
