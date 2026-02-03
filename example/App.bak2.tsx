import { useState, useEffect } from 'react';
import { StyleSheet, Text, View, PermissionsAndroid, LayoutRectangle, Button, TextInput, Alert, TouchableOpacity } from 'react-native';
import { ExpoFaceRecognitionView, FaceRecognitionResult, processFace } from 'expo-face-recognition';
import * as ImagePicker from 'expo-image-picker';

type RegisteredUser = {
  name: string;
  embedding: number[];
};

export default function App() {
  const [result, setResult] = useState<FaceRecognitionResult | null>(null);
  const [hasPermission, setHasPermission] = useState(false);
  const [viewLayout, setViewLayout] = useState<LayoutRectangle | null>(null);
  const [isLiveMode, setIsLiveMode] = useState(true);

  // User Management
  const [users, setUsers] = useState<RegisteredUser[]>([]);
  const [registerName, setRegisterName] = useState("");
  const [isRegistering, setIsRegistering] = useState(false);
  const [recognizedName, setRecognizedName] = useState<string | null>(null);

  // Constants
  const MATCH_THRESHOLD = 1.0;

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

  const calculateDistance = (emb1: number[], emb2: number[]) => {
    let sum = 0;
    for (let i = 0; i < emb1.length; i++) {
      const diff = emb1[i] - emb2[i];
      sum += diff * diff;
    }
    return Math.sqrt(sum);
  };

  const findBestMatch = (embedding: number[]) => {
    let minDist = Number.MAX_VALUE;
    let bestName = null;

    for (const user of users) {
      const dist = calculateDistance(embedding, user.embedding);
      if (dist < minDist) {
        minDist = dist;
        bestName = user.name;
      }
    }
    return minDist < MATCH_THRESHOLD ? bestName : "Unknown";
  };

  const handleFaceDetected = ({ nativeEvent }: { nativeEvent: FaceRecognitionResult }) => {
    if (isLiveMode) {
      processResult(nativeEvent);
    }
  };

  const processResult = (res: FaceRecognitionResult) => {
    setResult(res);
    if (res.success && res.isLive && res.embedding) {
      const name = findBestMatch(res.embedding);
      setRecognizedName(name);
    } else {
      setRecognizedName(null);
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
      setResult(null);
      setRecognizedName(null);
      try {
        const uri = pickerResult.assets[0].uri;
        console.log("Processing image:", uri);
        const processingResult = await processFace(uri);
        processResult(processingResult);
      } catch (e) {
        console.error("Error processing face:", e);
        setIsLiveMode(true);
      }
    }
  };

  const resumeCamera = () => {
    setIsLiveMode(true);
    setResult(null);
    setRecognizedName(null);
    setIsRegistering(false);
  };

  const handleRegister = () => {
    if (!registerName.trim()) {
      Alert.alert("Error", "Please enter a name");
      return;
    }
    if (!result || !result.success || !('embedding' in result) || !result.embedding) {
      Alert.alert("Error", "No face embedding to register. Make sure a live face is detected.");
      return;
    }

    const newUser: RegisteredUser = {
      name: registerName,
      embedding: result.embedding
    };
    setUsers([...users, newUser]);
    setRegisterName("");
    setIsRegistering(false);
    Alert.alert("Success", `User ${newUser.name} registered!`);
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
      {isLiveMode && result && result.success && 'rect' in result && viewLayout && (
        <View
          style={[
            styles.faceBox,
            {
              left: result.rect.x * viewLayout.width,
              top: result.rect.y * viewLayout.height,
              width: result.rect.width * viewLayout.width,
              height: result.rect.height * viewLayout.height,
              borderColor: result.isLive ? (recognizedName && recognizedName !== "Unknown" ? '#00ff00' : '#ffff00') : '#ff0000',
            },
          ]}
        >
          <Text style={{
            color: '#fff',
            backgroundColor: result.isLive ? (recognizedName && recognizedName !== "Unknown" ? 'green' : '#aa0') : 'red',
            position: 'absolute',
            top: -30,
            left: 0,
            paddingHorizontal: 8,
            paddingVertical: 4,
            borderRadius: 4,
            fontWeight: 'bold'
          }}>
            {result.isLive ? (recognizedName || "Unknown") : 'SPOOF'}
          </Text>
        </View>
      )}

      <View style={styles.overlay}>
        <View style={styles.buttonRow}>
          <Button title="Pick Image" onPress={pickImage} />
          {!isLiveMode && <Button title="Resume Camera" onPress={resumeCamera} color="green" />}
          <Button title={isRegistering ? "Cancel" : "Register User"} onPress={() => setIsRegistering(!isRegistering)} color={isRegistering ? "red" : "#2196F3"} />
        </View>

        {isRegistering && (
          <View style={styles.registerBox}>
            <TextInput
              style={styles.input}
              placeholder="Enter Name"
              value={registerName}
              onChangeText={setRegisterName}
              placeholderTextColor="#888"
            />
            <Button title="Save Face" onPress={handleRegister} />
          </View>
        )}

        <Text style={styles.headerText}>
          {isLiveMode ? "Real-Time Recognition" : "Static Image Result"}
        </Text>

        {result && (
          <View style={styles.resultBox}>
            <Text style={styles.label}>Match: <Text style={{ fontWeight: 'bold', color: recognizedName && recognizedName !== "Unknown" ? 'green' : 'black' }}>{recognizedName || "None"}</Text></Text>

            {'isLive' in result && result.isLive && <Text style={styles.success}>Live: YES</Text>}
            {'isLive' in result && !result.isLive && <Text style={styles.error}>Live: NO (SPOOF)</Text>}

            <Text style={styles.label}>Registered Users: {users.length}</Text>

            {'duration' in result && (
              <View style={styles.timings}>
                <Text style={[styles.timingText, { fontWeight: 'bold' }]}>Speed: {result.duration.total}ms</Text>
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
  registerBox: {
    backgroundColor: '#fff',
    padding: 10,
    borderRadius: 8,
    marginBottom: 10,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10
  },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#ccc',
    padding: 8,
    borderRadius: 4,
    color: '#000'
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
    color: '#333'
  },
  timings: {
    marginTop: 5,
    paddingTop: 5,
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
