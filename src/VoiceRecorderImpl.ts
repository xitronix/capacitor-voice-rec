// eslint-disable-next-line @typescript-eslint/no-explicit-any
import getBlobDuration from 'get-blob-duration';
import { openDB } from 'idb';

import type { CurrentRecordingStatus, GenericResponse, RecordingData } from './definitions';

import {
  alreadyRecordingError,
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

  static readonly DB_NAME = 'capacitor-voice-rec-db';
  static readonly DB_STORE_NAME = 'recordings';

  public static async canDeviceVoiceRecord(): Promise<GenericResponse> {
    if (navigator?.mediaDevices?.getUserMedia == null || VoiceRecorderImpl.getSupportedMimeType() == null) {
      return failureResponse();
    } else {
      return successResponse();
    }
  }

  public async startRecording(): Promise<GenericResponse> {
    if (this.mediaRecorder != null) {
      throw alreadyRecordingError();
    }
    const deviceCanRecord = await VoiceRecorderImpl.canDeviceVoiceRecord();
    if (!deviceCanRecord.value) {
      throw deviceCannotVoiceRecordError();
    }
    const havingPermission = await VoiceRecorderImpl.hasAudioRecordingPermission().catch(() => successResponse());
    if (!havingPermission.value) {
      throw missingPermissionError();
    }

    return navigator.mediaDevices
      .getUserMedia({ audio: true })
      .then(this.onSuccessfullyStartedRecording.bind(this))
      .catch(this.onFailedToStartRecording.bind(this));
  }

  public async stopRecording(): Promise<RecordingData> {
    if (this.mediaRecorder == null) {
      throw recordingHasNotStartedError();
    }
    try {
      this.mediaRecorder.stop();
      this.mediaRecorder.stream.getTracks().forEach((track) => track.stop());
      return this.pendingResult;
    } catch (ignore) {
      throw failedToFetchRecordingError();
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
    if (this.mediaRecorder == null) {
      throw recordingHasNotStartedError();
    } else if (this.mediaRecorder.state === 'recording') {
      this.mediaRecorder.pause();
      return Promise.resolve(successResponse());
    } else {
      return Promise.resolve(failureResponse());
    }
  }

  public resumeRecording(): Promise<GenericResponse> {
    if (this.mediaRecorder == null) {
      throw recordingHasNotStartedError();
    } else if (this.mediaRecorder.state === 'paused') {
      this.mediaRecorder.resume();
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

  private onSuccessfullyStartedRecording(stream: MediaStream): GenericResponse {
    this.pendingResult = new Promise((resolve, reject) => {
      this.mediaRecorder = new MediaRecorder(stream);
      this.mediaRecorder.onerror = () => {
        this.prepareInstanceForNextOperation();
        reject(failedToRecordError());
      };
      this.mediaRecorder.onstop = async () => {
        const mimeType = VoiceRecorderImpl.getSupportedMimeType();
        if (mimeType == null) {
          this.prepareInstanceForNextOperation();
          reject(failedToFetchRecordingError());
          return;
        }
        const blobVoiceRecording = new Blob(this.chunks, { type: mimeType });
        if (blobVoiceRecording.size <= 0) {
          this.prepareInstanceForNextOperation();
          reject(emptyRecordingError());
          return;
        }
        // TODO: return uri
        // const recordDataBase64 = await VoiceRecorderImpl.blobToBase64(blobVoiceRecording);
        // todo save blob to filesystem
        const recordingDuration = await getBlobDuration(blobVoiceRecording);
        this.prepareInstanceForNextOperation();
        // TODO: Handle path on WEB
        const filePath = await saveToIndexedDB(blobVoiceRecording);

        resolve({ value: { mimeType, msDuration: recordingDuration * 1000, filePath } });
      };
      this.mediaRecorder.ondataavailable = (event: BlobEvent) => this.chunks.push(event.data);
      this.mediaRecorder.start();
    });
    return successResponse();
  }

  private onFailedToStartRecording(): GenericResponse {
    this.prepareInstanceForNextOperation();
    throw failedToRecordError();
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
    if (this.mediaRecorder != null && this.mediaRecorder.state === 'recording') {
      try {
        this.mediaRecorder.stop();
        // eslint-disable-next-line no-empty
      } catch (ignore) {}
    }
    this.pendingResult = neverResolvingPromise();
    this.mediaRecorder = null;
    this.chunks = [];
  }
}

async function saveToIndexedDB(
  blob: Blob,
  // mimeType: string
): Promise<string> {
  const db = await openIDB();

  const key: string = `audio-${Date.now()}.webm`;

  await db.put(VoiceRecorderImpl.DB_STORE_NAME, blob, key);

  // Return full IDB path format: idb://database/collection/id
  return `idb://${VoiceRecorderImpl.DB_NAME}/${VoiceRecorderImpl.DB_STORE_NAME}/${key}`;
}

async function openIDB() {
  const db = await openDB(VoiceRecorderImpl.DB_NAME, 1, {
    upgrade(db) {
      db.createObjectStore(VoiceRecorderImpl.DB_STORE_NAME);
    },
  });
  return db;
}
