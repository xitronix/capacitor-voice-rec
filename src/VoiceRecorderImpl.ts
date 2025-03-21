// eslint-disable-next-line @typescript-eslint/no-explicit-any
import getBlobDuration from 'get-blob-duration';
import { openDB } from 'idb';

import type { CurrentRecordingStatus, GenericResponse, RecordingData } from './definitions';

import {
  couldNotQueryPermissionStatusError,
  deviceCannotVoiceRecordError,
  emptyRecordingError,
  failedToFetchRecordingError,
  failedToRecordError,
  failureResponse,
  missingPermissionError,
  recordingHasNotStartedError,
  successResponse,
} from './predefined-web-responses';

// these mime types will be checked one by one in order until one of them is found to be supported by the current browser
const possibleMimeTypes = ['audio/aac', 'audio/webm;codecs=opus', 'audio/mp4', 'audio/webm', 'audio/ogg;codecs=opus'];
const neverResolvingPromise = (): Promise<never> => new Promise(() => undefined);

export class VoiceRecorderImpl {
  private mediaRecorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private pendingResult: Promise<RecordingData> = neverResolvingPromise();
  private currentStream: MediaStream | null = null;
  public onStateChange?: (status: CurrentRecordingStatus) => void;

  static readonly DB_NAME = 'capacitor-voice-rec-db';
  static readonly DB_STORE_NAME = 'recordings';

  private cleanupStream(): void {
    if (this.currentStream) {
      this.currentStream.getTracks().forEach(track => {
        try {
          track.stop();
        } catch (e) {
          console.error('Error stopping track:', e);
        }
      });
      this.currentStream = null;
    }
  }

  private cleanupMediaRecorder(): void {
    if (this.mediaRecorder) {
      try {
        if (this.mediaRecorder.state !== 'inactive') {
          this.mediaRecorder.stop();
        }
      } catch (e) {
        console.error('Error stopping mediaRecorder:', e);
      }
      this.mediaRecorder = null;
    }
  }

  public static async canDeviceVoiceRecord(): Promise<GenericResponse> {
    if (navigator?.mediaDevices?.getUserMedia == null || VoiceRecorderImpl.getSupportedMimeType() == null) {
      return failureResponse();
    } else {
      return successResponse();
    }
  }

  public async startRecording(): Promise<RecordingData> {
    // First clean up any existing recording
    this.prepareInstanceForNextOperation();

    const deviceCanRecord = await VoiceRecorderImpl.canDeviceVoiceRecord();
    if (!deviceCanRecord.value) {
      throw deviceCannotVoiceRecordError();
    }
    const havingPermission = await VoiceRecorderImpl.hasAudioRecordingPermission().catch(() => successResponse());
    if (!havingPermission.value) {
      throw missingPermissionError();
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          autoGainControl: true,
          noiseSuppression: true,
          channelCount: 1,
        },
      });
      const result = this.onSuccessfullyStartedRecording(stream);
      this.notifyStateChange('RECORDING');
      return result;
    } catch (error) {
      console.error('Failed to start recording:', error);
      this.prepareInstanceForNextOperation();
      throw failedToRecordError();
    }
  }

  public async stopRecording(): Promise<RecordingData> {
    if (this.mediaRecorder == null) {
      throw recordingHasNotStartedError();
    }

    try {
      this.mediaRecorder.stop();
      this.notifyStateChange('NONE');
      return await this.pendingResult;
    } finally {
      this.prepareInstanceForNextOperation();
    }
  }

  public static async hasAudioRecordingPermission(): Promise<GenericResponse> {
    return navigator.permissions
      .query({ name: 'microphone' as PermissionName })
      .then((result) => ({ value: result.state === 'granted' }))
      .catch(() => {
        throw couldNotQueryPermissionStatusError();
      });
  }

  public static async requestAudioRecordingPermission(): Promise<GenericResponse> {
    const havingPermission = await VoiceRecorderImpl.hasAudioRecordingPermission().catch(() => failureResponse());
    if (havingPermission.value) {
      return successResponse();
    }

    return navigator.mediaDevices
      .getUserMedia({ audio: true })
      .then(() => successResponse())
      .catch(() => failureResponse());
  }

  public pauseRecording(): Promise<GenericResponse> {
    if (this.mediaRecorder?.state === 'recording') {
      this.mediaRecorder.pause();
      this.notifyStateChange('PAUSED');
      return Promise.resolve(successResponse());
    } else {
      return Promise.resolve(failureResponse());
    }
  }

  public resumeRecording(): Promise<GenericResponse> {
    if (this.mediaRecorder?.state === 'paused') {
      this.mediaRecorder.resume();
      this.notifyStateChange('RECORDING');
      return Promise.resolve(successResponse());
    } else {
      return Promise.resolve(failureResponse());
    }
  }

  public getCurrentStatus(): Promise<CurrentRecordingStatus> {
    if (this.mediaRecorder == null) {
      return Promise.resolve({ status: 'NONE' });
    } else if (this.mediaRecorder.state === 'recording') {
      return Promise.resolve({ status: 'RECORDING' });
    } else if (this.mediaRecorder.state === 'paused') {
      return Promise.resolve({ status: 'PAUSED' });
    } else {
      return Promise.resolve({ status: 'NONE' });
    }
  }

  public static getSupportedMimeType(): string | null {
    if (MediaRecorder?.isTypeSupported == null) return null;
    const foundSupportedType = possibleMimeTypes.find((type) => MediaRecorder.isTypeSupported(type));
    return foundSupportedType ?? null;
  }

  private onSuccessfullyStartedRecording(stream: MediaStream): RecordingData {
    const mimeType = VoiceRecorderImpl.getSupportedMimeType();
    if (mimeType == null) {
      this.prepareInstanceForNextOperation();
      throw failedToFetchRecordingError();
    }

    this.currentStream = stream;
    this.chunks = [];

    const fileName = `audio-${Date.now()}.webm`;
    const finalPath = `idb://${VoiceRecorderImpl.DB_NAME}/${VoiceRecorderImpl.DB_STORE_NAME}/${fileName}`;

    this.pendingResult = new Promise((resolve, reject) => {
      try {
        this.mediaRecorder = new MediaRecorder(stream, {
          mimeType,
          audioBitsPerSecond: 128000
        });
      } catch (error) {
        console.error('Failed to create MediaRecorder:', error);
        this.prepareInstanceForNextOperation();
        reject(failedToRecordError());
        return;
      }

      this.mediaRecorder.ondataavailable = (event: BlobEvent) => {
        if (event.data.size > 0) {
          this.chunks.push(event.data);
        }
      };

      this.mediaRecorder.onerror = (event) => {
        console.error('MediaRecorder error:', event);
        this.prepareInstanceForNextOperation();
        reject(failedToRecordError());
      };

      this.mediaRecorder.onstop = async () => {
        try {
          // Wait a small amount of time to ensure all chunks are collected
          await new Promise(resolve => setTimeout(resolve, 100));

          if (!this.chunks.length) {
            this.prepareInstanceForNextOperation();
            reject(emptyRecordingError());
            return;
          }

          const blobVoiceRecording = new Blob(this.chunks, { type: mimeType });
          if (blobVoiceRecording.size <= 0) {
            this.prepareInstanceForNextOperation();
            reject(emptyRecordingError());
            return;
          }

          const filePath = await saveToIndexedDB(blobVoiceRecording, fileName);
          const recordingDuration = await getBlobDuration(blobVoiceRecording);
          
          resolve({
            value: {
              mimeType,
              msDuration: recordingDuration * 1000,
              filePath
            }
          });
        } catch (error) {
          console.error('Error processing recording:', error);
          reject(failedToFetchRecordingError());
        } finally {
          this.prepareInstanceForNextOperation();
        }
      };

      // Request data more frequently (every 250ms) to ensure we don't miss chunks
      this.mediaRecorder.start(250);
    });

    return {
      value: {
        mimeType,
        msDuration: -1,
        filePath: finalPath,
      },
    };
  }

  // private static blobToBase64(blob: Blob): Promise<Base64String> {
  //   return new Promise((resolve) => {
  //     const reader = new FileReader();
  //     reader.onloadend = () => {
  //       const recordingResult = String(reader.result);
  //       const splitResult = recordingResult.split('base64,');
  //       const toResolve = splitResult.length > 1 ? splitResult[1] : recordingResult;
  //       resolve(toResolve.trim());
  //     };
  //     reader.readAsDataURL(blob);
  //   });
  // }

  private prepareInstanceForNextOperation(): void {
    this.cleanupMediaRecorder();
    this.cleanupStream();
    this.chunks = [];
    this.pendingResult = neverResolvingPromise();
  }

  private notifyStateChange(status: 'RECORDING' | 'PAUSED' | 'NONE') {
    if (this.onStateChange) {
      this.onStateChange({ status });
    }
  }
}

async function saveToIndexedDB(blob: Blob, fileName: string): Promise<string> {
  const db = await openIDB();
  await db.put(VoiceRecorderImpl.DB_STORE_NAME, blob, fileName);
  return `idb://${VoiceRecorderImpl.DB_NAME}/${VoiceRecorderImpl.DB_STORE_NAME}/${fileName}`;
}

async function openIDB() {
  const db = await openDB(VoiceRecorderImpl.DB_NAME, 1, {
    upgrade(db) {
      db.createObjectStore(VoiceRecorderImpl.DB_STORE_NAME);
    },
  });
  return db;
}
