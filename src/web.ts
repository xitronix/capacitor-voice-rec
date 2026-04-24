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
  StreamingStatus,
  ListRecordingFilesResult,
  ListLiveChunkSessionsResult,
  ListLiveChunksResult,
  ReadLiveChunkResult,
  LiveChunkSessionInfo,
  ActiveRecordingSessionResult,
  RecoverableRecordingSessionsResult,
} from './definitions';

// IndexedDB-backed live-chunk storage for web parity. Keyed by (sessionId, seq).
// Native platforms own the authoritative implementation; web is best-effort for
// development and debugging.
const LIVE_DB_NAME = 'CapacitorVoiceRecLiveChunks';
const LIVE_STORE = 'chunks';

async function openLiveChunkDb(): Promise<IDBDatabase> {
  // Dynamically import `idb` only when needed to keep non-streaming web usage slim.
  const { openDB } = await import('idb');
  return (await openDB(LIVE_DB_NAME, 1, {
    upgrade(db) {
      const store = db.createObjectStore(LIVE_STORE, { keyPath: ['sessionId', 'seq'] });
      store.createIndex('bySession', 'sessionId');
    },
  })) as unknown as IDBDatabase;
}

function base64FromArrayBuffer(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + chunkSize)) as unknown as number[]);
  }
  return btoa(binary);
}

export class VoiceRecorderWeb extends WebPlugin implements VoiceRecorderPlugin {
  private voiceRecorderInstance = new VoiceRecorderImpl();
  private currentStatus: CurrentRecordingStatus = { status: 'NONE' };

  private audioContext?: AudioContext;
  private mediaStream?: MediaStream;
  private audioWorkletNode?: AudioWorkletNode;
  private isStreaming = false;
  private streamingOptions?: AudioStreamOptions;
  private persistSessionId?: string;
  private streamingSeq = 0;

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

  public async getActiveRecordingSession(): Promise<ActiveRecordingSessionResult> {
    return { value: null };
  }

  public async listRecoverableRecordingSessions(): Promise<RecoverableRecordingSessionsResult> {
    return { sessions: [] };
  }

  public async getRecordingInfo(options: { filePath: string }): Promise<RecordingInfoData> {
    return this.voiceRecorderInstance.getRecordingInfo(options.filePath);
  }

  public async finalizeRecording(options: { filePath: string }): Promise<RecordingData> {
    return this.voiceRecorderInstance.finalizeRecording(options.filePath);
  }

  public async startAudioStream(options: AudioStreamOptions = {}): Promise<GenericResponse> {
    try {
      if (this.isStreaming) {
        return { value: false };
      }

      this.streamingOptions = {
        sampleRate: options.sampleRate || 44100,
        channels: options.channels || 1,
        bufferSize: options.bufferSize || 4096,
      };
      this.persistSessionId = options.persistSessionId;
      this.streamingSeq = 0;

      const constraints = {
        audio: {
          sampleRate: this.streamingOptions.sampleRate,
          channelCount: this.streamingOptions.channels,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      };

      this.mediaStream = await navigator.mediaDevices.getUserMedia(constraints);

      this.audioContext = new AudioContext({ sampleRate: this.streamingOptions.sampleRate });

      try {
        await this.audioContext.audioWorklet.addModule('/assets/audio-stream-processor.js');
      } catch (error) {
        console.warn('AudioWorklet not available, falling back to ScriptProcessor');
        return this.startAudioStreamFallback();
      }

      const source = this.audioContext.createMediaStreamSource(this.mediaStream);
      this.audioWorkletNode = new AudioWorkletNode(this.audioContext, 'audio-stream-processor', {
        processorOptions: {
          bufferSize: this.streamingOptions.bufferSize,
          sampleRate: this.streamingOptions.sampleRate,
          channels: this.streamingOptions.channels,
        },
      });

      this.audioWorkletNode.port.onmessage = async (event) => {
        const { audioData, timestamp, sampleRate, channels } = event.data;
        const seq = this.streamingSeq++;
        let path: string | undefined;
        if (this.persistSessionId) {
          path = await this.persistWebChunk(this.persistSessionId, seq, audioData as Float32Array);
        }
        this.notifyListeners('audioData', {
          audioData: new Float32Array(audioData),
          sampleRate: sampleRate || this.streamingOptions?.sampleRate || 44100,
          timestamp,
          channels: channels || this.streamingOptions?.channels || 1,
          seq,
          path,
          downsampled16kMono: false,
        } as AudioDataEvent);
      };

      source.connect(this.audioWorkletNode);

      this.isStreaming = true;
      return { value: true };
    } catch (error) {
      console.error('Failed to start audio stream:', error);
      await this.cleanupStreaming();
      return { value: false };
    }
  }

  private async startAudioStreamFallback(): Promise<GenericResponse> {
    try {
      const source = this.audioContext!.createMediaStreamSource(this.mediaStream!);
      const processor = this.audioContext!.createScriptProcessor(
        this.streamingOptions!.bufferSize,
        this.streamingOptions!.channels,
        this.streamingOptions!.channels,
      );

      processor.onaudioprocess = async (event) => {
        const inputBuffer = event.inputBuffer;
        const audioData = inputBuffer.getChannelData(0);
        const seq = this.streamingSeq++;
        let path: string | undefined;
        if (this.persistSessionId) {
          path = await this.persistWebChunk(this.persistSessionId, seq, audioData);
        }

        this.notifyListeners('audioData', {
          audioData: new Float32Array(audioData),
          sampleRate: this.streamingOptions!.sampleRate!,
          timestamp: Date.now(),
          channels: this.streamingOptions!.channels!,
          seq,
          path,
          downsampled16kMono: false,
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

  public async getStreamingStatus(): Promise<StreamingStatus> {
    return { status: this.isStreaming ? 'STREAMING' : 'STOPPED' };
  }

  public async listRecordingFiles(): Promise<ListRecordingFilesResult> {
    return { files: [] };
  }

  // --- Live-chunk persistence (IndexedDB-backed on web) ---

  public async listLiveChunkSessions(): Promise<ListLiveChunkSessionsResult> {
    try {
      const db = await openLiveChunkDb();
      const tx = (db as any).transaction(LIVE_STORE, 'readonly');
      const ids = new Set<string>();
      let cursor = await tx.store.openCursor();
      while (cursor) {
        const key = cursor.key as [string, number];
        ids.add(key[0]);
        cursor = await cursor.continue();
      }
      return { sessions: Array.from(ids) };
    } catch (e) {
      console.warn('listLiveChunkSessions failed on web', e);
      return { sessions: [] };
    }
  }

  public async listLiveChunks(options: { sessionId: string }): Promise<ListLiveChunksResult> {
    try {
      const db = await openLiveChunkDb();
      const tx = (db as any).transaction(LIVE_STORE, 'readonly');
      const chunks: { seq: number; path: string; size: number }[] = [];
      let cursor = await tx.store.openCursor();
      while (cursor) {
        const [sid, seq] = cursor.key as [string, number];
        if (sid === options.sessionId) {
          const value = cursor.value as { bytes: ArrayBuffer };
          chunks.push({ seq, path: `idb://${sid}/${seq}`, size: value.bytes.byteLength });
        }
        cursor = await cursor.continue();
      }
      chunks.sort((a, b) => a.seq - b.seq);
      return { chunks };
    } catch (e) {
      console.warn('listLiveChunks failed on web', e);
      return { chunks: [] };
    }
  }

  public async readLiveChunk(options: { sessionId: string; seq: number }): Promise<ReadLiveChunkResult> {
    const db = await openLiveChunkDb();
    const value = await (db as any).get(LIVE_STORE, [options.sessionId, options.seq]);
    if (!value) {
      throw new Error(`Chunk not found: ${options.sessionId}/${options.seq}`);
    }
    const bytes: ArrayBuffer = value.bytes;
    return { data: base64FromArrayBuffer(bytes), size: bytes.byteLength };
  }

  public async deleteLiveChunk(options: { sessionId: string; seq: number }): Promise<GenericResponse> {
    try {
      const db = await openLiveChunkDb();
      await (db as any).delete(LIVE_STORE, [options.sessionId, options.seq]);
      return { value: true };
    } catch (e) {
      console.warn('deleteLiveChunk failed on web', e);
      return { value: false };
    }
  }

  public async deleteLiveChunkSession(options: { sessionId: string }): Promise<GenericResponse> {
    try {
      const db = await openLiveChunkDb();
      const tx = (db as any).transaction(LIVE_STORE, 'readwrite');
      let cursor = await tx.store.openCursor();
      while (cursor) {
        const [sid] = cursor.key as [string, number];
        if (sid === options.sessionId) {
          await cursor.delete();
        }
        cursor = await cursor.continue();
      }
      await tx.done;
      return { value: true };
    } catch (e) {
      console.warn('deleteLiveChunkSession failed on web', e);
      return { value: false };
    }
  }

  public async getLiveChunkSessionInfo(options: { sessionId: string }): Promise<LiveChunkSessionInfo> {
    const list = await this.listLiveChunks(options);
    const totalBytes = list.chunks.reduce((acc, c) => acc + c.size, 0);
    const count = list.chunks.length;
    const firstSeq = count > 0 ? list.chunks[0].seq : -1;
    const lastSeq = count > 0 ? list.chunks[count - 1].seq : -1;
    return { totalBytes, count, firstSeq, lastSeq, dir: `idb://${options.sessionId}` };
  }

  public async assembleLiveChunksToWav(options: {
    sessionId: string;
    outputPath: string;
    sampleRate?: number;
    channels?: number;
  }): Promise<{ filePath: string; msDuration: number; size: number }> {
    const sampleRate = options.sampleRate ?? 16000;
    const channels = options.channels ?? 1;
    const list = await this.listLiveChunks({ sessionId: options.sessionId });
    let totalBytes = 0;
    const parts: ArrayBuffer[] = [];
    for (const c of list.chunks) {
      const { data } = await this.readLiveChunk({ sessionId: options.sessionId, seq: c.seq });
      const bin = atob(data);
      const buf = new ArrayBuffer(bin.length);
      const view = new Uint8Array(buf);
      for (let i = 0; i < bin.length; i++) view[i] = bin.charCodeAt(i);
      parts.push(buf);
      totalBytes += buf.byteLength;
    }
    const wav = new ArrayBuffer(44 + totalBytes);
    const dv = new DataView(wav);
    const writeStr = (off: number, s: string) => {
      for (let i = 0; i < s.length; i++) dv.setUint8(off + i, s.charCodeAt(i));
    };
    writeStr(0, 'RIFF');
    dv.setUint32(4, 36 + totalBytes, true);
    writeStr(8, 'WAVE');
    writeStr(12, 'fmt ');
    dv.setUint32(16, 16, true);
    dv.setUint16(20, 1, true);
    dv.setUint16(22, channels, true);
    dv.setUint32(24, sampleRate, true);
    dv.setUint32(28, sampleRate * channels * 2, true);
    dv.setUint16(32, channels * 2, true);
    dv.setUint16(34, 16, true);
    writeStr(36, 'data');
    dv.setUint32(40, totalBytes, true);
    let off = 44;
    const out = new Uint8Array(wav);
    for (const p of parts) {
      out.set(new Uint8Array(p), off);
      off += p.byteLength;
    }
    const msDuration = Math.round((totalBytes / (sampleRate * channels * 2)) * 1000);
    return { filePath: options.outputPath, msDuration, size: wav.byteLength };
  }

  private async persistWebChunk(sessionId: string, seq: number, samples: Float32Array): Promise<string> {
    const pcm = new ArrayBuffer(samples.length * 2);
    const view = new DataView(pcm);
    for (let i = 0; i < samples.length; i++) {
      let s = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }
    const db = await openLiveChunkDb();
    await (db as any).put(LIVE_STORE, { sessionId, seq, bytes: pcm });
    return `idb://${sessionId}/${seq}`;
  }

  private async cleanupStreaming(): Promise<void> {
    this.isStreaming = false;
    this.persistSessionId = undefined;
    this.streamingSeq = 0;

    if (this.audioWorkletNode) {
      this.audioWorkletNode.disconnect();
      this.audioWorkletNode = undefined;
    }

    if (this.audioContext && this.audioContext.state !== 'closed') {
      await this.audioContext.close();
      this.audioContext = undefined;
    }

    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach((track) => track.stop());
      this.mediaStream = undefined;
    }

    this.streamingOptions = undefined;
  }
}
