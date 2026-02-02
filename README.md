# @rdnf-magiba/expo-face-recognition

A powerful Face Recognition library for Expo and React Native, utilizing **ML Kit** for face detection and **TensorFlow Lite** for face recognition/embeddings.

This module provides a camera view that detects faces in real-time and returns their embeddings, allowing you to build Face Attendance, Secure Login, or User Verification systems.

## Features

- 🚀 **Real-time Face Detection**: Uses Google's ML Kit for lightning-fast face bounds detection.
- 🧠 **On-device Recognition**: Generates 512-d embeddings using FaceNet/MobileFaceNet TFLite models.
- 📸 **Live Camera Preview**: Native Android camera view integration.
- ⚡ **Performance**: Optimized for smooth 30fps detection on modern mobile devices.
- 🤖 **Platform**: Android Only (iOS support coming soon).

## Installation

```bash
npm install @rdnf-magiba/expo-face-recognition
```

## Configuration

### Android Manual Setup

This library currently does not include an Expo Config Plugin. You may need to manually configure permissions in your `android/app/src/main/AndroidManifest.xml` if not using standard Expo permissions.

**Permissions**

Ensure your app requests Camera permissions:

**Android `AndroidManifest.xml`**
```xml
<uses-permission android:name="android.permission.CAMERA" />
```

## Usage

Import the `ExpoFaceRecognitionView` and use it in your component.

```tsx
import { ExpoFaceRecognitionView } from '@rdnf-magiba/expo-face-recognition';
import { StyleSheet, View, Text } from 'react-native';

export default function FaceAuthScreen() {
  
  const handleFacesDetected = (event: any) => {
    // nativeEvent contains:
    // - faces: Array of face objects (bounds, etc.)
    // - recognition: Embedding data (if enabled)
    console.log("Detected Faces:", event.nativeEvent);
  };

  return (
    <View style={styles.container}>
      <ExpoFaceRecognitionView 
        style={styles.camera}
        onFaceDetected={handleFacesDetected}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  camera: {
    flex: 1,
    width: '100%',
  },
});
```

## API Reference

### `ExpoFaceRecognitionView`

A React component that renders the camera view and performs face recognition.

| Prop | Type | Description |
|------|------|-------------|
| `onFaceDetected` | `function` | Callback invoked when a face is detected. Returns face bounds and verification data. |
| `style` | `ViewStyle` | Styles for the camera view container. |

## Contributing

Contributions are welcome!

1. Clone the repo
2. Install dependencies: `npm install`
3. Run the example app: `npm run open:android` or `npm run open:ios`

## License

MIT
