import { WebPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

import { VoiceRecorderImpl } from './VoiceRecorderImpl';
import type { 
  CurrentRecordingStatus, 
  GenericResponse, 
  RecordingData, 
  RecordingInfoData,
  VoiceRecorderPlugin 
} from './definitions';

export class VoiceRecorderWeb extends WebPlugin implements VoiceRecorderPlugin {
  private voiceRecorderInstance = new VoiceRecorderImpl();
  private currentStatus: CurrentRecordingStatus = { status: 'NONE' };

  constructor() {
    super();
    this.setupStateChangeListeners();
  }

  private setupStateChangeListeners() {
    this.voiceRecorderInstance.onStateChange = (status: CurrentRecordingStatus) => {
      this.currentStatus = status;
      this.notifyListeners('recordingStateChange', status);
    };
  }

  public async addListener(
    eventName: 'recordingStateChange',
    listenerFunc: (status: CurrentRecordingStatus) => void,
  ): Promise<PluginListenerHandle> {
    return super.addListener(eventName, listenerFunc);
  }

  public canDeviceVoiceRecord(): Promise<GenericResponse> {
    return VoiceRecorderImpl.canDeviceVoiceRecord();
  }

  public hasAudioRecordingPermission(): Promise<GenericResponse> {
    return VoiceRecorderImpl.hasAudioRecordingPermission();
  }

  public requestAudioRecordingPermission(): Promise<GenericResponse> {
    return VoiceRecorderImpl.requestAudioRecordingPermission();
  }

  public startRecording(options?: { directory?: string; useForegroundService?: boolean }): Promise<RecordingData> {
    return this.voiceRecorderInstance.startRecording(options);
  }

  public continueRecording(options: { filePath: string; directory?: string }): Promise<RecordingData> {
    try {
      return this.voiceRecorderInstance.continueRecording(options.filePath);
    } catch (error) {
      console.error('Error continuing recording:', error);
      throw error;
    }
  }

  public stopRecording(): Promise<RecordingData> {
    return this.voiceRecorderInstance.stopRecording();
  }

  public pauseRecording(): Promise<GenericResponse> {
    return this.voiceRecorderInstance.pauseRecording();
  }

  public async resumeRecording(): Promise<GenericResponse> {
    return this.voiceRecorderInstance.resumeRecording();
  }

  public getCurrentStatus(): Promise<CurrentRecordingStatus> {
    return Promise.resolve(this.currentStatus);
  }
  
  /**
   * Get information about a recording file without having to continue/stop it
   * This method is a web implementation fallback
   */
  public async getRecordingInfo(options: { filePath: string }): Promise<RecordingInfoData> {
    return this.voiceRecorderInstance.getRecordingInfo(options.filePath);
  }
  
  /**
   * Finalize a recording by merging any temporary segments without continuing/stopping it
   * This method is a web implementation fallback
   */
  public async finalizeRecording(options: { filePath: string }): Promise<RecordingData> {
    return this.voiceRecorderInstance.finalizeRecording(options.filePath);
  }
}
