import { useState, useEffect } from 'react';
import { StyleSheet, Text, View, PermissionsAndroid, LayoutRectangle, Button, TouchableOpacity } from 'react-native';
import { ExpoFaceRecognitionView, FaceRecognitionResult, processFace } from 'expo-face-recognition';
import * as ImagePicker from 'expo-image-picker';

export default function App() {
  const [result, setResult] = useState<FaceRecognitionResult | null>(null);
  const [hasPermission, setHasPermission] = useState(false);
  const [viewLayout, setViewLayout] = useState<LayoutRectangle | null>(null);
  const [isLiveMode, setIsLiveMode] = useState(true);

  useEffect(() => {
    (async () => {
      const granted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.CAMERA,
        {
          title: "Camera Permission",
          message: "App needs access to your camera ",
          buttonNeutral: "Ask Me Later",
          buttonNegative: "Cancel",
          buttonPositive: "OK"
        }
      );
      if (granted === PermissionsAndroid.RESULTS.GRANTED) {
        setHasPermission(true);
      } else {
        console.log("Camera permission denied");
      }
    })();
  }, []);

  const handleFaceDetected = ({ nativeEvent }: { nativeEvent: FaceRecognitionResult }) => {
    if (isLiveMode) {
      setResult(nativeEvent);
    }
  };

  const pickImage = async () => {
    const pickerResult = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ImagePicker.MediaTypeOptions.Images,
      allowsEditing: false,
      quality: 1,
    });

    if (!pickerResult.canceled && pickerResult.assets && pickerResult.assets.length > 0) {
      setIsLiveMode(false);
      setResult(null); // Clear previous result
      try {
        const uri = pickerResult.assets[0].uri;
        console.log("Processing image:", uri);
        const processingResult = await processFace(uri);
        console.log("Result:", processingResult);
        setResult(processingResult);
      } catch (e) {
        console.error("Error processing face:", e);
        setIsLiveMode(true);
      }
    }
  };

  const resumeCamera = () => {
    setIsLiveMode(true);
    setResult(null);
  };

  if (!hasPermission) {
    return <View style={styles.container}><Text style={styles.headerText}>No Camera Permission</Text></View>;
  }

  return (
    <View style={styles.container}>
      <ExpoFaceRecognitionView
        style={styles.camera}
        onFaceDetected={handleFaceDetected}
        onLayout={(event) => setViewLayout(event.nativeEvent.layout)}
      />

      {/* Face Overlay - Only show in Live Mode or if we want to visualize rect relative to camera (which we can't for static image easily without image view) */}
      {isLiveMode && result && result.success && 'rect' in result && viewLayout && (
        <View
          style={[
            styles.faceBox,
            {
              left: result.rect.x * viewLayout.width,
              top: result.rect.y * viewLayout.height,
              width: result.rect.width * viewLayout.width,
              height: result.rect.height * viewLayout.height,
              borderColor: result.isLive ? '#00ff00' : '#ff0000',
            },
          ]}
        >
          <Text style={{
            color: result.isLive ? '#00ff00' : '#ff0000',
            backgroundColor: 'rgba(0,0,0,0.5)',
            position: 'absolute',
            top: -25,
            left: 0,
            paddingHorizontal: 5
          }}>
            {result.isLive ? 'LIVE' : 'SPOOF'} ({'spoofScore' in result ? result.spoofScore.toFixed(2) : ''})
          </Text>
        </View>
      )}

      <View style={styles.overlay}>
        <View style={styles.buttonRow}>
          <Button title="Pick Image" onPress={pickImage} />
          {!isLiveMode && <Button title="Resume Camera" onPress={resumeCamera} color="green" />}
        </View>

        <Text style={styles.headerText}>
          {isLiveMode ? "Real-Time Recognition" : "Static Image Result"}
        </Text>

        {result && (
          <View style={styles.resultBox}>
            <Text style={styles.label}>Success: {result.success ? "Yes" : "No"}</Text>
            {'isLive' in result && <Text style={result.isLive ? styles.success : styles.error}>Live: {result.isLive ? "YES" : "NO"}</Text>}
            {'spoofScore' in result && <Text style={styles.label}>Spoof Score: {result.spoofScore?.toFixed(3)}</Text>}
            {'embedding' in result && <Text style={styles.label}>Embedding Size: {result.embedding?.length}</Text>}
            {'duration' in result && (
              <View style={styles.timings}>
                <Text style={styles.timingText}>Detection: {result.duration.detection}ms</Text>
                <Text style={styles.timingText}>Spoof: {result.duration.spoof}ms</Text>
                <Text style={styles.timingText}>Embedding: {result.duration.embedding}ms</Text>
                <Text style={[styles.timingText, { fontWeight: 'bold' }]}>Total: {result.duration.total}ms</Text>
              </View>
            )}
            {'error' in result && <Text style={styles.error}>{result.error}</Text>}
          </View>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  camera: {
    flex: 1,
  },
  faceBox: {
    position: 'absolute',
    borderWidth: 3,
    borderRadius: 8,
    zIndex: 10,
  },
  overlay: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    padding: 20,
    backgroundColor: 'rgba(0,0,0,0.85)',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    zIndex: 20,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    marginBottom: 15,
  },
  headerText: {
    color: '#fff',
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 10,
    textAlign: 'center',
  },
  resultBox: {
    padding: 10,
    backgroundColor: '#fff',
    borderRadius: 10,
  },
  label: {
    fontSize: 16,
    marginBottom: 5,
  },
  timings: {
    marginTop: 10,
    paddingTop: 10,
    borderTopWidth: 1,
    borderTopColor: '#eee',
  },
  timingText: {
    fontSize: 14,
    color: '#555',
    marginBottom: 2,
  },
  success: {
    color: 'green',
    fontSize: 16,
    fontWeight: 'bold',
  },
  error: {
    color: 'red',
    fontSize: 16,
    fontWeight: 'bold',
  }
});
