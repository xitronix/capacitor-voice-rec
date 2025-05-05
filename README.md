<p align="center">
  <img src="https://user-images.githubusercontent.com/236501/85893648-1c92e880-b7a8-11ea-926d-95355b8175c7.png" width="128" height="128" />
</p>
<h3 align="center">Capacitor Voice Recorder</h3>
<p align="center"><strong><code>xitronix/cap-voice-rec</code></strong></p>
<p align="center">Capacitor plugin for simple voice recording (For Capacitor 6)</p>

<p align="center">
  <img src="https://img.shields.io/maintenance/yes/2024" />
  <a href="https://www.npmjs.com/package/cap-voice-rec"><img src="https://img.shields.io/npm/l/cap-voice-rec" /></a>
<br>
  <a href="https://www.npmjs.com/package/cap-voice-rec"><img src="https://img.shields.io/npm/dw/cap-voice-rec" /></a>
  <a href="https://www.npmjs.com/package/cap-voice-rec"><img src="https://img.shields.io/npm/v/cap-voice-rec" /></a>
</p>

## Maintainers

| Maintainer            | GitHub |
| ----------------------| -------|
| Yaraslau Stsetskevich | [xironix](https://github.com/xitronix) |

Implementation is based on [tchvu3 implementation](https://github.com/tchvu3/capacitor-voice-recorder)
## Installation

```
npm install --save cap-voice-rec
npx cap sync
```

#### ios note
cap-voice-rec
Make sure to include the ```NSMicrophoneUsageDescription```
key, and a corresponding purpose string in your app's Info.plist

## Configuration

No configuration required for this plugin.

## Supported methods

| Name | Android | iOS | Web |
| :------------------------------ | :------ | :-- | :-- |
| canDeviceVoiceRecord | ✅ | ✅ | ✅ |
requestAudioRecordingPermission | ✅ | ✅ | ✅ |
| hasAudioRecordingPermission | ✅ | ✅ | ✅ |
| startRecording | ✅ | ✅ | ✅ |
| stopRecording | ✅ | ✅ | ✅ |
| pauseRecording | ✅ | ✅ | ✅ |
| resumeRecording | ✅ | ✅ | ✅ |
| getCurrentStatus | ✅ | ✅ | ✅ |

## Explanation

* canDeviceVoiceRecord - on mobile this function will always return a promise that resolves to `{ value: true }`,
  while in a browser it will be resolved to `{ value: true }` / `{ value: false }` based on the browser's ability to record.
  note that this method does not take into account the permission status,
  only if the browser itself is capable of recording at all.

---

* requestAudioRecordingPermission - if the permission has already been provided then the promise will resolve with `{ value: true }`,
  otherwise the promise will resolve to `{ value: true }` / `{ value: false }` based on the answer of the user to the request.

---

* hasAudioRecordingPermission - will resolve to `{ value: true }` / `{ value: false }` based on the status of the permission.
  please note that the web implementation of this plugin uses the Permissions API under the hood which is not widespread as of now.
  as a result, if the status of the permission cannot be checked the promise will reject with `COULD_NOT_QUERY_PERMISSION_STATUS`.
  in that case you have no choice but to use the `requestAudioRecordingPermission` function straight away or `startRecording` and capture any exception that is thrown.

---
* startRecording - if the app lacks the required permission then the promise will reject with the message `MISSING_PERMISSION`.
  if the current device cannot voice record at all (for example, due to old browser) then the promise will reject with `DEVICE_CANNOT_VOICE_RECORD`.
  if there's a recording already running then the promise will reject with `ALREADY_RECORDING`,
  and if other apps are using the microphone then the promise will reject
  with `MICROPHONE_BEING_USED`. in a case of unknown error the promise will reject with `FAILED_TO_RECORD`.

---

* stopRecording - will stop the recording that has been previously started. if the function ` has not been called beforehand
  the promise will reject with `RECORDING_HAS_NOT_STARTED`.
  if the recording has been stopped immediately after it has been started the promise will reject with `EMPTY_RECORDING`.
  in a case of unknown error the promise will reject with `FAILED_TO_FETCH_RECORDING`.
  in case of success, you will get the recording in base-64, the duration of the
  recording in milliseconds, and the mime type.

---

* pauseRecording - will pause an ongoing recording. note that if the recording has not started yet the promise
  will reject with `RECORDING_HAS_NOT_STARTED`. in case of success the promise will resolve to `{ value: true }` if the pause
  was successful or `{ value: false }` if the recording is already paused.
  note that on certain mobile os versions this function is not supported.
  in these cases the function will reject with `NOT_SUPPORTED_OS_VERSION` and your only viable options is to stop the recording instead.

---

* resumeRecording - will resume a paused recording. note that if the recording has not started yet the promise
  will reject with `RECORDING_HAS_NOT_STARTED`. in case of success the promise will resolve to `{ value: true }` if the resume
  was successful or `{ value: false }` if the recording is already running.
  note that on certain mobile os versions this function is not supported.
  in these cases the function will reject with `NOT_SUPPORTED_OS_VERSION` and your only viable options is to stop the recording instead

---

* getCurrentStatus - will let you know the current status of the current recording (if there is any at all).
  will resolve with one of the following values: `{ status: "NONE" }` if the plugin is idle and waiting to start a new recording.
  `{ status: "RECORDING" }` if the plugin is in the middle of recording and `{ status: "PAUSED" }` if the recording is paused right now.

## Usage

```

// only 'VoiceRecorder' is mandatory, the rest is for typing
import { VoiceRecorder, VoiceRecorderPlugin, RecordingData, GenericResponse, CurrentRecordingStatus } from 'cap-voice-rec';

// will print true / false based on the ability of the current device (or web browser) to record audio
VoiceRecorder.canDeviceVoiceRecord().then((result: GenericResponse) => console.log(result.value))

/**
* will prompt the user to give the required permission, after that
* the function will print true / false based on the user response
*/
VoiceRecorder.requestAudioRecordingPermission().then((result: GenericResponse) => console.log(result.value))

/**
* will print true / false based on the status of the recording permission.
* the promise will reject with "COULD_NOT_QUERY_PERMISSION_STATUS"
* if the current device cannot query the current status of the recording permission
*/
VoiceRecorder.hasAudioRecordingPermission.then((result: GenericResponse) => console.log(result.value))

/**
* In case of success the promise will resolve to { value: true }
* in case of an error the promise will reject with one of the following messages:
* "MISSING_PERMISSION", "ALREADY_RECORDING", "MICROPHONE_BEING_USED", "DEVICE_CANNOT_VOICE_RECORD", or "FAILED_TO_RECORD"
*/
VoiceRecorder.startRecording()
.then((result: GenericResponse) => console.log(result.value))
.catch(error => console.log(error))

/**
* In case of success the promise will resolve to:
* {"value": { msDuration: number, mimeType: string, path: string }},
* the file will be in one of several possible formats (more on that later).
* in case of an error the promise will reject with one of the following messages:
* "RECORDING_HAS_NOT_STARTED" or "FAILED_TO_FETCH_RECORDING"
*/
VoiceRecorder.stopRecording()
.then((result: RecordingData) => console.log(result.value))
.catch(error => console.log(error))

/**
* will pause an ongoing recording. note that if the recording has not started yet the promise
* will reject with `RECORDING_HAS_NOT_STARTED`. in case of success the promise will resolve to `{ value: true }` if the pause
* was successful or `{ value: false }` if the recording is already paused.
* if the current mobile os does not support this method the promise will reject with `NOT_SUPPORTED_OS_VERSION`
*/
VoiceRecorder.pauseRecording()
.then((result: GenericResponse) => console.log(result.value))
.catch(error => console.log(error))

/**
* will resume a paused recording. note that if the recording has not started yet the promise
* will reject with `RECORDING_HAS_NOT_STARTED`. in case of success the promise will resolve to `{ value: true }` if the resume
* was successful or `{ value: false }` if the recording is already running.
* if the current mobile os does not support this method the promise will reject with `NOT_SUPPORTED_OS_VERSION`
*/
VoiceRecorder.resumeRecording()
.then((result: GenericResponse) => console.log(result.value))
.catch(error => console.log(error))

/**
* Will return the current status of the plugin.
* in this example one of these possible values will be printed: "NONE" / "RECORDING" / "PAUSED"
*/
VoiceRecorder.getCurrentStatus()
.then((result: CurrentRecordingStatus) => console.log(result.status))
.catch(error => console.log(error))

## Handling Recordings: `continueRecording` and `finalizeRecording`

The Voice Recorder plugin provides two specialized methods for managing audio recordings across app sessions and device states:

- **continueRecording**: Resumes recording from a previous file when the app has been closed or restarted
- **finalizeRecording**: Merges any temporary segments without needing to continue or stop recording

### Method Details

#### `continueRecording(options: { filePath: string; directory?: string })`

**Purpose**: 
This method allows you to continue recording from a previous session, even if the app was closed or restarted. It's particularly useful for implementing robust recording experiences that can survive app termination or crashes.

**Parameters**:
- `filePath`: Path to the previous recording
- `directory` (optional): Directory to store the new segment (defaults to "DOCUMENTS")

**Returns**: 
- A Promise with `RecordingData` containing:
  - `filePath`: Path to the original file
  - `mimeType`: MIME type of the recording (usually "audio/aac")
  - `msDuration`: Duration in milliseconds

**Platform-specific behavior**:
- **Android**: Creates a new segment file that will be merged with the original file later. If existing segments are found, finalizes them first before starting a new segment.
- **iOS**: Continues the recording from the previous session, handling any segments that need to be appended.
- **Web**: Loads the existing recording (if found) and starts a new recording session that merges with the previous data.

**Example**:
```typescript
import { VoiceRecorder } from 'capacitor-voice-recorder';

async function continueExistingRecording(filePath: string) {
  try {
    // Get recording information to check if it exists
    const recordingInfo = await VoiceRecorder.getRecordingInfo({ filePath });
    
    if (recordingInfo.value.exists) {
      // Continue recording from the existing file
      const result = await VoiceRecorder.continueRecording({
        filePath,
        directory: 'DOCUMENTS'
      });
      
      console.log(`Continuing recording: ${result.value.filePath}`);
      return result.value.filePath;
    } else {
      console.error('Recording not found');
      return null;
    }
  } catch (error) {
    console.error('Error continuing recording:', error);
    return null;
  }
}
```

#### `finalizeRecording(options: { filePath: string })`

**Purpose**: 
This method merges any temporary recording segments without requiring microphone access. It's useful for cleaning up recordings after app crashes or when resuming from background state.

**Parameters**:
- `filePath`: Path to the original recording file

**Returns**: 
- A Promise with `RecordingData` containing:
  - `filePath`: Path to the finalized file
  - `mimeType`: MIME type of the recording (usually "audio/aac")
  - `msDuration`: Duration in milliseconds

**Platform-specific behavior**:
- **Android**: Checks for existing segments associated with the file and merges them with the original file.
- **iOS**: Finalizes any pending segments and creates a complete audio file.
- **Web**: Ensures the recording is properly saved in IndexedDB.

**Example**:
```typescript
import { VoiceRecorder } from 'capacitor-voice-recorder';

async function finalizeExistingRecording(filePath: string) {
  try {
    // Check if the recording has segments that need to be finalized
    const recordingInfo = await VoiceRecorder.getRecordingInfo({ filePath });
    
    if (recordingInfo.value.hasSegments) {
      console.log('Recording has segments, finalizing...');
      const result = await VoiceRecorder.finalizeRecording({ filePath });
      
      console.log(`Finalized recording: ${result.value.filePath}`);
      console.log(`Duration: ${result.value.msDuration}ms`);
      return result.value.filePath;
    } else {
      console.log('No segments to finalize');
      return filePath;
    }
  } catch (error) {
    console.error('Error finalizing recording:', error);
    return null;
  }
}
```

### Best Practices for Recording Across App Lifecycles

#### 1. Save Recording Information

Always store the path to your current recording in persistent storage:

```typescript
// When starting a new recording
async function startAndSaveRecording() {
  const result = await VoiceRecorder.startRecording();
  
  // Save path to localStorage or another persistence mechanism
  localStorage.setItem('currentRecordingPath', result.value.filePath);
}
```

#### 2. Handle App Restarts

When your app restarts, check for an existing recording:

```typescript
async function checkForExistingRecording() {
  const savedPath = localStorage.getItem('currentRecordingPath');
  
  if (savedPath) {
    try {
      const info = await VoiceRecorder.getRecordingInfo({ filePath: savedPath });
      
      if (info.value.exists) {
        // Recording exists, you can either continue it or finalize it
        return savedPath;
      }
    } catch (error) {
      console.error('Error checking recording:', error);
    }
  }
  
  return null;
}
```

#### 3. Implement Proper Cleanup

After successfully finalizing a recording:

```typescript
async function cleanupAfterRecording(filePath: string) {
  try {
    await finalizeExistingRecording(filePath);
    
    // Clear the saved path
    localStorage.removeItem('currentRecordingPath');
  } catch (error) {
    console.error('Error cleaning up recording:', error);
  }
}
```

#### 4. Handle App Closure and Memory Pressure Situations

The `continueRecording` and `finalizeRecording` methods are primarily designed for scenarios where:
- The app was completely closed (not just backgrounded)
- The app was terminated by the OS due to memory pressure
- The app crashed during recording
- The device was restarted

Here's how to handle these scenarios:

```typescript
import { App } from '@capacitor/app';

// When app starts, check for interrupted recordings
document.addEventListener('deviceready', async () => {
  const savedPath = localStorage.getItem('currentRecordingPath');
  if (savedPath) {
    try {
      // Check if the recording exists and has segments
      const info = await VoiceRecorder.getRecordingInfo({ filePath: savedPath });
      
      if (info.value.exists) {
        // Ask the user if they want to continue the interrupted recording
        const shouldContinue = await askUserToContinueRecording();
        
        if (shouldContinue) {
          // Continue the recording from where it left off
          await VoiceRecorder.continueRecording({
            filePath: savedPath
          });
        } else {
          // Just finalize any segments that might exist
          await VoiceRecorder.finalizeRecording({
            filePath: savedPath
          });
          // Clear the path since we're done with this recording
          localStorage.removeItem('currentRecordingPath');
        }
      }
    } catch (error) {
      console.error('Error handling recording recovery:', error);
    }
  }
});

// For regular backgrounding/foregrounding
App.addListener('appStateChange', ({ isActive }) => {
  const currentRecordingPath = localStorage.getItem('currentRecordingPath');
  
  if (!isActive && currentRecordingPath) {
    // App going to background - pause recording
    VoiceRecorder.pauseRecording();
  } else if (isActive && currentRecordingPath) {
    // App coming to foreground - check status and resume if needed
    VoiceRecorder.getCurrentStatus().then(status => {
      if (status.status === 'PAUSED') {
        VoiceRecorder.resumeRecording();
      }
    });
  }
});
```

Remember that `continueRecording` is especially useful after complete app closure. When the app is merely backgrounded (not terminated), you usually just need to pause/resume recording rather than using these specialized methods.

### Important Considerations

1. **Platform differences**:
   - Android uses a segment-based approach for continuing recordings
   - iOS handles continuations differently but provides consistent results
   - Web implementation uses IndexedDB for storage and has slightly different behavior

2. **File paths**:
   - Always use the file paths returned by the plugin methods
   - Do not construct file paths manually as they differ across platforms

3. **Error handling**:
   - Always implement proper error handling for recording operations
   - Check recording status before continuing or finalizing

4. **App lifecycle**:
   - Consider implementing handlers for app lifecycle events to ensure robust recording
   - Use `finalizeRecording` when your app has been terminated if possible
   - Use `continueRecording` when resuming a recording after app closure or crash

## Format and Mime type

The plugin will return the recording in one of several possible formats.
the format is dependent on the os / web browser that the user uses.
on android and ios the mime type will be `audio/aac`, while on chrome and firefox it
will be `audio/webm;codecs=opus` and on safari it will be `audio/mp4`.
note that these 3 browsers has been tested on. the plugin should still work on
other browsers, as there is a list of mime types that the plugin checks against the
user's browser.

Note that this fact might cause unexpected behavior in case you'll try to play recordings
between several devices or browsers - as they not all support the same set of audio formats.
it is recommended to convert the recordings to a format that all your target devices supports.
as this plugin focuses on the recording aspect, it does not provide any conversion between formats.

## Playback

To play the recorded file you can use plain javascript:

```
const base64Sound = '...' // from plugin
const mimeType = '...'  // from plugin
const audioRef = new Audio(`data:${mimeType};base64,${base64Sound}`)
audioRef.oncanplaythrough = () => audioRef.play()
audioRef.load()
```

