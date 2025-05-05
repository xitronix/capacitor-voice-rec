// eslint-disable-next-line @typescript-eslint/no-explicit-any
import getBlobDuration from 'get-blob-duration';
import { openDB } from 'idb';

import type { CurrentRecordingStatus, GenericResponse, RecordingData, RecordingInfoData } from './definitions';

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
  private saveInterval: ReturnType<typeof setInterval> | null = null;

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

  public async startRecording(options?: { directory?: string }): Promise<RecordingData> {
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
      
      // Use any directory option passed from the options parameter
      // It's not used in the web implementation but we need to reference it to fix the TS error
      const directory = options?.directory;
      console.log(`Recording to directory (web): ${directory || 'default'}`);
      
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

  /**
   * Get information about a recording file without requiring microphone access
   * @param filePath Path to the recording file
   * @returns Information about the recording including duration and if it has segments
   */
  public async getRecordingInfo(filePath: string): Promise<RecordingInfoData> {
    try {
      // Parse the file path to get the filename
      const pathComponents = filePath.replace('idb://', '').split('/');
      const fileName = pathComponents[pathComponents.length - 1];
      
      // Open IndexedDB and check if the file exists
      const db = await openIDB();
      const existingRecording = await db.get(VoiceRecorderImpl.DB_STORE_NAME, fileName);
      
      // If the recording doesn't exist, return an error
      if (!existingRecording || !(existingRecording instanceof Blob) || existingRecording.size === 0) {
        console.warn(`Recording not found or invalid in IndexedDB: ${fileName}`);
        return {
          value: {
            filePath,
            mimeType: '',
            msDuration: 0,
            hasSegments: false
          }
        };
      }
      
      // Get the duration of the recording
      let duration = 0;
      try {
        duration = await getBlobDuration(existingRecording) * 1000;
      } catch (error) {
        console.warn('Could not determine duration of existing recording:', error);
      }
      
      // Get the mime type of the recording
      const mimeType = existingRecording.type || VoiceRecorderImpl.getSupportedMimeType() || '';
      
      // Web implementation doesn't have segments concept, but we'll return a value
      // to be consistent with the native implementation
      return {
        value: {
          filePath,
          mimeType,
          msDuration: duration,
          hasSegments: false
        }
      };
    } catch (error) {
      console.error('Error getting recording info:', error);
      throw failedToFetchRecordingError();
    }
  }

  /**
   * Finalize a recording by ensuring it's properly saved in IndexedDB
   * Web implementation doesn't have segments concept, so this just checks
   * if the recording exists and returns its info
   * @param filePath Path to the recording file
   * @returns Information about the finalized recording
   */
  public async finalizeRecording(filePath: string): Promise<RecordingData> {
    try {
      // In web implementation, we don't need to merge segments as we continually
      // save the entire recording to IndexedDB. So we just get the recording info.
      const recordingInfo = await this.getRecordingInfo(filePath);
      
      return {
        value: {
          filePath: recordingInfo.value.filePath,
          mimeType: recordingInfo.value.mimeType,
          msDuration: recordingInfo.value.msDuration
        }
      };
    } catch (error) {
      console.error('Error finalizing recording:', error);
      throw failedToFetchRecordingError();
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

  public async continueRecording(filePath: string): Promise<RecordingData> {
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
      // Check if the filepath exists in IndexedDB first
      const pathComponents = filePath.replace('idb://', '').split('/');
      const fileName = pathComponents[pathComponents.length - 1];
      
      const db = await openIDB();
      const existingRecording = await db.get(VoiceRecorderImpl.DB_STORE_NAME, fileName);
      
      // Track existing recording duration if available
      let existingDuration = 0;
      
      // If the recording doesn't exist or is invalid, we'll start a fresh recording
      // with the same file name to maintain continuity
      if (!existingRecording || !(existingRecording instanceof Blob) || existingRecording.size === 0) {
        console.warn(`Recording not found or invalid in IndexedDB: ${fileName}. Starting a fresh recording.`);
        // We'll start a fresh recording but use the same filename
        this.chunks = [];
      } else {
        console.log(`Found existing recording in IndexedDB: ${fileName}, size: ${existingRecording.size} bytes`);
        
        // Try to calculate the duration of the existing recording
        try {
          existingDuration = await getBlobDuration(existingRecording) * 1000;
          console.log(`Existing recording duration: ${existingDuration}ms`);
        } catch (error) {
          console.warn('Could not determine duration of existing recording:', error);
        }
        
        // Store existing recording for later merging
        this.chunks = [existingRecording];
      }
      
      // Start new recording session
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          autoGainControl: true,
          noiseSuppression: true,
          channelCount: 1,
        },
      });
      
      // Use same filename to ensure it gets merged with the previous recording
      const result = this.onSuccessfullyStartedRecording(stream, fileName);
      
      // Add the duration information to the result
      if (existingDuration > 0) {
        result.value.msDuration = existingDuration;
      }
      
      this.notifyStateChange('RECORDING');
      return result;
    } catch (error) {
      console.error('Failed to continue recording:', error);
      this.prepareInstanceForNextOperation();
      throw failedToRecordError();
    }
  }

  private onSuccessfullyStartedRecording(stream: MediaStream, existingFileName?: string): RecordingData {
    const mimeType = VoiceRecorderImpl.getSupportedMimeType();
    if (mimeType == null) {
      this.prepareInstanceForNextOperation();
      throw failedToFetchRecordingError();
    }

    this.currentStream = stream;
    if (!existingFileName) {
      this.chunks = [];
    }

    const fileName = existingFileName || `audio-${Date.now()}.webm`;
    const finalPath = `idb://${VoiceRecorderImpl.DB_NAME}/${VoiceRecorderImpl.DB_STORE_NAME}/${fileName}`;

    // Set up periodic saving of chunks to IndexedDB (every 3 seconds)
    this.saveInterval = setInterval(async () => {
      if (this.chunks.length > 0 && this.mediaRecorder?.state !== 'inactive') {
        try {
          const tempBlob = new Blob(this.chunks, { type: mimeType });
          await saveToIndexedDB(tempBlob, fileName);
          console.log('Saved interim recording to IndexedDB');
        } catch (e) {
          console.error('Failed to save interim recording:', e);
        }
      }
    }, 3000);

    this.pendingResult = new Promise((resolve, reject) => {
      try {
        this.mediaRecorder = new MediaRecorder(stream, {
          mimeType,
          audioBitsPerSecond: 128000
        });
      } catch (error) {
        if (this.saveInterval) clearInterval(this.saveInterval);
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
        if (this.saveInterval) clearInterval(this.saveInterval);
        console.error('MediaRecorder error:', event);
        this.prepareInstanceForNextOperation();
        reject(failedToRecordError());
      };

      this.mediaRecorder.onstop = async () => {
        if (this.saveInterval) clearInterval(this.saveInterval); // Clear the interval when recording stops
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
    // Clear any save interval if it exists
    if (this.saveInterval !== null) {
      clearInterval(this.saveInterval);
      this.saveInterval = null;
    }
    
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
  
  // Store the file
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
