import { WebPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

import { VoiceRecorderImpl } from './VoiceRecorderImpl';
import type { 
  CurrentRecordingStatus, 
  GenericResponse, 
  RecordingData, 
  RecordingInfoData,
  VoiceRecorderPlugin,
  AudioStreamOptions,
  AudioDataEvent,
  StreamingStatus
} from './definitions';

export class VoiceRecorderWeb extends WebPlugin implements VoiceRecorderPlugin {
  private voiceRecorderInstance = new VoiceRecorderImpl();
  private currentStatus: CurrentRecordingStatus = { status: 'NONE' };

  private audioContext?: AudioContext;
  private mediaStream?: MediaStream;
  private audioWorkletNode?: AudioWorkletNode;
  private isStreaming = false;
  private streamingOptions?: AudioStreamOptions;

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
    eventName: 'recordingStateChange' | 'audioData',
    listenerFunc: ((status: CurrentRecordingStatus) => void) | ((event: AudioDataEvent) => void),
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
  
  /**
   * Start streaming audio data in real-time for voice chat applications
   */
  public async startAudioStream(options: AudioStreamOptions = {}): Promise<GenericResponse> {
    try {
      if (this.isStreaming) {
        return { value: false };
      }

      // Set default options
      this.streamingOptions = {
        sampleRate: options.sampleRate || 44100,
        channels: options.channels || 1,
        bufferSize: options.bufferSize || 4096
      };

      // Request microphone access
      const constraints = {
        audio: {
          sampleRate: this.streamingOptions.sampleRate,
          channelCount: this.streamingOptions.channels,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      };

      this.mediaStream = await navigator.mediaDevices.getUserMedia(constraints);
      
      // Create audio context
      this.audioContext = new AudioContext({ 
        sampleRate: this.streamingOptions.sampleRate 
      });
      
      // Load AudioWorklet processor
      try {
        await this.audioContext.audioWorklet.addModule('/assets/audio-stream-processor.js');
      } catch (error) {
        console.warn('AudioWorklet not available, falling back to ScriptProcessor');
        return this.startAudioStreamFallback();
      }
      
      // Create audio worklet node
      const source = this.audioContext.createMediaStreamSource(this.mediaStream);
      this.audioWorkletNode = new AudioWorkletNode(this.audioContext, 'audio-stream-processor', {
        processorOptions: {
          bufferSize: this.streamingOptions.bufferSize,
          sampleRate: this.streamingOptions.sampleRate,
          channels: this.streamingOptions.channels
        }
      });

      // Listen for audio data from worklet
      this.audioWorkletNode.port.onmessage = (event) => {
        const { audioData, timestamp, sampleRate, channels } = event.data;
        this.notifyListeners('audioData', {
          audioData: new Float32Array(audioData),
          sampleRate: sampleRate || this.streamingOptions?.sampleRate || 44100,
          timestamp,
          channels: channels || this.streamingOptions?.channels || 1
        } as AudioDataEvent);
      };

      // Connect the audio graph
      source.connect(this.audioWorkletNode);
      
      this.isStreaming = true;
      return { value: true };
      
    } catch (error) {
      console.error('Failed to start audio stream:', error);
      await this.cleanupStreaming();
      return { value: false };
    }
  }

  /**
   * Fallback implementation using ScriptProcessor for older browsers
   */
  private async startAudioStreamFallback(): Promise<GenericResponse> {
    try {
      const source = this.audioContext!.createMediaStreamSource(this.mediaStream!);
      const processor = this.audioContext!.createScriptProcessor(
        this.streamingOptions!.bufferSize, 
        this.streamingOptions!.channels, 
        this.streamingOptions!.channels
      );

      processor.onaudioprocess = (event) => {
        const inputBuffer = event.inputBuffer;
        const audioData = inputBuffer.getChannelData(0);
        
        this.notifyListeners('audioData', {
          audioData: new Float32Array(audioData),
          sampleRate: this.streamingOptions!.sampleRate!,
          timestamp: Date.now(),
          channels: this.streamingOptions!.channels!
        } as AudioDataEvent);
      };

      source.connect(processor);
      processor.connect(this.audioContext!.destination);
      
      this.isStreaming = true;
      return { value: true };
      
    } catch (error) {
      console.error('Fallback audio streaming failed:', error);
      await this.cleanupStreaming();
      return { value: false };
    }
  }

  /**
   * Stop streaming audio data
   */
  public async stopAudioStream(): Promise<GenericResponse> {
    try {
      if (!this.isStreaming) {
        return { value: false };
      }

      await this.cleanupStreaming();
      return { value: true };
      
    } catch (error) {
      console.error('Failed to stop audio stream:', error);
      return { value: false };
    }
  }

  /**
   * Get current streaming status
   */
  public async getStreamingStatus(): Promise<StreamingStatus> {
    return { status: this.isStreaming ? 'STREAMING' : 'STOPPED' };
  }

  /**
   * Clean up streaming resources
   */
  private async cleanupStreaming(): Promise<void> {
    this.isStreaming = false;

    if (this.audioWorkletNode) {
      this.audioWorkletNode.disconnect();
      this.audioWorkletNode = undefined;
    }

    if (this.audioContext && this.audioContext.state !== 'closed') {
      await this.audioContext.close();
      this.audioContext = undefined;
    }

    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(track => track.stop());
      this.mediaStream = undefined;
    }

    this.streamingOptions = undefined;
  }
}
