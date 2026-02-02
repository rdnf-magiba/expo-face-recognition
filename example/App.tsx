import { useState, useEffect } from 'react';
import { StyleSheet, Text, View, SafeAreaView, PermissionsAndroid } from 'react-native';
import { ExpoFaceRecognitionView, FaceRecognitionResult } from 'expo-face-recognition';

export default function App() {
  const [result, setResult] = useState<FaceRecognitionResult | null>(null);
  const [hasPermission, setHasPermission] = useState(false);

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
    // @ts-ignore
    console.log(nativeEvent.spoofScore)
  };

  // if (!hasPermission) {
  //   return <View style={styles.container}><Text style={styles.headerText}>No Camera Permission</Text></View>;
  // }

  return (
    <View style={styles.container}>
      <ExpoFaceRecognitionView
        style={styles.camera}
        onFaceDetected={handleFaceDetected}
      />
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
  overlay: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    padding: 20,
    backgroundColor: 'rgba(0,0,0,0.7)',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
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
