import { useState, useEffect } from 'react';
import { StyleSheet, Text, View, PermissionsAndroid, LayoutRectangle } from 'react-native';
import { ExpoFaceRecognitionView, FaceRecognitionResult } from 'expo-face-recognition';

export default function App() {
  const [result, setResult] = useState<FaceRecognitionResult | null>(null);
  const [hasPermission, setHasPermission] = useState(false);
  const [viewLayout, setViewLayout] = useState<LayoutRectangle | null>(null);

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
    setResult(nativeEvent);
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

      {/* Face Overlay */}
      {result && result.success && 'rect' in result && viewLayout && (
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
        <Text style={styles.headerText}>Real-Time Face Recognition</Text>
        {result && (
          <View style={styles.resultBox}>
            <Text style={styles.label}>Success: {result.success ? "Yes" : "No"}</Text>
            {'isLive' in result && <Text style={result.isLive ? styles.success : styles.error}>Live: {result.isLive ? "YES" : "NO"}</Text>}
            {'spoofScore' in result && <Text style={styles.label}>Spoof Score: {result.spoofScore?.toFixed(3)}</Text>}
            {'embedding' in result && <Text style={styles.label}>Embedding Size: {result.embedding?.length}</Text>}
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
    backgroundColor: 'rgba(0,0,0,0.7)',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    zIndex: 20,
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
