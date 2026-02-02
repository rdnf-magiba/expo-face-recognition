import { useState } from 'react';
import { StyleSheet, Text, View, Button, Image, ScrollView, SafeAreaView, ActivityIndicator } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import ExpoFaceRecognition, { FaceRecognitionResult } from 'expo-face-recognition';

export default function App() {
  const [image, setImage] = useState<string | null>(null);
  const [result, setResult] = useState<FaceRecognitionResult | null>(null);
  const [loading, setLoading] = useState(false);

  const pickImage = async () => {
    // No permissions request is necessary for launching the image library
    let result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      allowsEditing: false,
      quality: 1,
    });

    if (!result.canceled) {
      const uri = result.assets[0].uri;
      setImage(uri);
      processFace(uri);
    }
  };

  const processFace = async (uri: string) => {
    setLoading(true);
    setResult(null);
    try {
      console.log('Processing face for URI:', uri);
      const response = await ExpoFaceRecognition.processFace(uri);
      console.log('Result:', response);
      setResult(response);
    } catch (e: any) {
      console.error('Error processing face:', e);
      setResult({ success: false, error: e.message || 'Unknown error' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.scrollContainer}>
        <Text style={styles.header}>Face Recognition Module</Text>

        <View style={styles.buttonContainer}>
          <Button title="Pick an Image to Process" onPress={pickImage} />
        </View>

        {image && (
          <View style={styles.imageContainer}>
            <Image source={{ uri: image }} style={styles.image} />
          </View>
        )}

        {loading && <ActivityIndicator size="large" color="#0000ff" style={{ marginVertical: 20 }} />}

        {result && (
          <View style={styles.resultContainer}>
            <Text style={styles.resultTitle}>Processing Result:</Text>
            {result.success ? (
              <View>
                <Text style={styles.successText}>Success: {result.success.toString()}</Text>
                <Text style={result.isLive ? styles.liveText : styles.spoofText}>
                  Is Live: {result.isLive ? 'YES' : 'NO'}
                </Text>
                {'spoofScore' in result && (
                  <Text>Spoof Score: {result.spoofScore}</Text>
                )}
                {'embedding' in result && (
                  <Text>Embedding Length: {result.embedding.length}</Text>
                )}
              </View>
            ) : (
              <Text style={styles.errorText}>Error: {result.error}</Text>
            )}
            <Text style={styles.code}>{JSON.stringify(result, null, 2)}</Text>
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
  scrollContainer: {
    padding: 20,
    alignItems: 'center',
  },
  header: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 20,
    textAlign: 'center',
  },
  buttonContainer: {
    marginBottom: 20,
  },
  imageContainer: {
    width: '100%',
    height: 300,
    marginBottom: 20,
    backgroundColor: '#f0f0f0',
    borderRadius: 10,
    overflow: 'hidden',
  },
  image: {
    width: '100%',
    height: '100%',
    resizeMode: 'contain',
  },
  resultContainer: {
    width: '100%',
    padding: 15,
    backgroundColor: '#f8f9fa',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#e9ecef',
  },
  resultTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 10,
  },
  code: {
    fontFamily: 'monospace',
    fontSize: 12,
    marginTop: 10,
    color: '#333',
  },
  successText: {
    color: 'green',
    fontSize: 16,
    fontWeight: 'bold',
  },
  errorText: {
    color: 'red',
    fontSize: 16,
    fontWeight: 'bold',
  },
  liveText: {
    color: 'green',
    fontSize: 16,
    fontWeight: 'bold',
    marginVertical: 5,
  },
  spoofText: {
    color: 'orange',
    fontSize: 16,
    fontWeight: 'bold',
    marginVertical: 5,
  }
});
