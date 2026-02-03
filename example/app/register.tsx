import { useState } from 'react';
import { StyleSheet, Text, View, TextInput, Button, Alert, Image, ScrollView } from 'react-native';
import { FaceRecognitionResult, processFace } from 'expo-face-recognition';
import * as ImagePicker from 'expo-image-picker';
import { useRouter } from 'expo-router';
import { useUser } from '../components/UserContext';

export default function RegisterScreen() {
    const router = useRouter();
    const { addUser } = useUser();

    const [name, setName] = useState("");
    const [imageUri, setImageUri] = useState<string | null>(null);
    const [currentResult, setCurrentResult] = useState<FaceRecognitionResult | null>(null);
    const [isProcessing, setIsProcessing] = useState(false);

    const takePhoto = async () => {
        try {
            const result = await ImagePicker.launchCameraAsync({
                mediaTypes: 'images',
                allowsEditing: false,
                quality: 1,
            });

            if (!result.canceled && result.assets && result.assets.length > 0) {
                const uri = result.assets[0].uri;
                setImageUri(uri);
                processImage(uri);
            }
        } catch (e) {
            Alert.alert("Camera Error", "Could not launch camera: " + e);
        }
    };

    const processImage = async (uri: string) => {
        setIsProcessing(true);
        setCurrentResult(null);
        try {
            const res = await processFace(uri);
            setCurrentResult(res);
            if (!res.success) {
                Alert.alert("Processing Failed", res.error || "Unknown error");
            } else if (!res.isLive) {
                Alert.alert("Spoof Detected", "This face seems to be a spoof!");
            }
        } catch (e) {
            console.error(e);
            Alert.alert("Error", "Face processing failed");
        } finally {
            setIsProcessing(false);
        }
    };

    const handleRegister = () => {
        if (!name.trim()) {
            Alert.alert("Input Error", "Please enter a name.");
            return;
        }
        if (!currentResult || !currentResult.success || !currentResult.isLive || !('embedding' in currentResult) || !currentResult.embedding) {
            Alert.alert("Error", "No valid live face to register.");
            return;
        }

        addUser(name, currentResult.embedding);
        Alert.alert("Success", "User registered successfully!", [
            { text: "OK", onPress: () => router.back() }
        ]);
    };

    return (
        <ScrollView contentContainerStyle={styles.container}>
            <Text style={styles.title}>Register User</Text>

            <View style={styles.imageContainer}>
                {imageUri ? (
                    <Image source={{ uri: imageUri }} style={styles.image} />
                ) : (
                    <View style={styles.placeholder}>
                        <Text style={styles.placeholderText}>No Photo Taken</Text>
                    </View>
                )}
            </View>

            <Button title="Take Photo" onPress={takePhoto} disabled={isProcessing} />

            {isProcessing && <Text style={styles.status}>Processing...</Text>}

            {!isProcessing && currentResult && (
                <View style={styles.resultBox}>
                    {currentResult.success && 'isLive' in currentResult && (
                        <Text>Live: {currentResult.isLive ? "YES" : "NO"}</Text>
                    )}
                    {'spoofScore' in currentResult && <Text>Spoof Score: {currentResult.spoofScore?.toFixed(2)}</Text>}
                    {'embedding' in currentResult && <Text style={{ color: 'green', fontWeight: 'bold' }}>Embedding Ready!</Text>}
                </View>
            )}

            <TextInput
                style={styles.input}
                placeholder="Enter User Name"
                placeholderTextColor="#999"
                value={name}
                onChangeText={setName}
            />

            <View style={styles.row}>
                <Button title="Cancel" onPress={() => router.back()} color="red" />
                <Button
                    title="Save User"
                    onPress={handleRegister}
                    disabled={isProcessing || !currentResult?.success || !('isLive' in currentResult) || !currentResult.isLive}
                />
            </View>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: { flexGrow: 1, backgroundColor: '#fff', padding: 20, alignItems: 'center' },
    title: { fontSize: 22, fontWeight: 'bold', marginBottom: 20, color: '#000' },
    imageContainer: { width: 200, height: 200, marginBottom: 15, borderWidth: 1, borderColor: '#ccc', justifyContent: 'center', alignItems: 'center', backgroundColor: '#eee' },
    image: { width: '100%', height: '100%' },
    placeholder: { justifyContent: 'center', alignItems: 'center' },
    placeholderText: { color: '#888' },
    status: { marginVertical: 10, fontStyle: 'italic' },
    resultBox: { marginVertical: 10, padding: 10, backgroundColor: '#f0f0f0', borderRadius: 5, width: '100%' },
    input: { width: '100%', borderWidth: 1, borderColor: '#ccc', borderRadius: 8, padding: 12, fontSize: 16, marginVertical: 15, color: '#000' },
    row: { flexDirection: 'row', justifyContent: 'space-around', width: '100%' }
});
