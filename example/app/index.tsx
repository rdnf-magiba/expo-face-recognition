import { useState, useEffect } from 'react';
import { StyleSheet, Text, View, PermissionsAndroid, LayoutRectangle, Button, TouchableOpacity } from 'react-native';
import { ExpoFaceRecognitionView, FaceRecognitionResult, processFace } from 'expo-face-recognition';
import * as ImagePicker from 'expo-image-picker';
import { useRouter } from 'expo-router';
import { useUser } from '../components/UserContext';

export default function recognitionScreen() {
    const router = useRouter();
    const { identifyUser, users } = useUser();

    const [result, setResult] = useState<FaceRecognitionResult | null>(null);
    const [hasPermission, setHasPermission] = useState(false);
    const [viewLayout, setViewLayout] = useState<LayoutRectangle | null>(null);
    const [isLiveMode, setIsLiveMode] = useState(true);
    const [isGPUEnabled, setIsGPUEnabled] = useState(false); // Default CPU
    const [recognizedName, setRecognizedName] = useState<string | null>(null);

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
            }
        })();
    }, []);

    const processResult = async (res: FaceRecognitionResult) => {
        setResult(res);
        if (res.success && res.isLive && res.embedding) {
            const match = await identifyUser(res.embedding);
            if (match) {
                setRecognizedName(`${match.name} (${match.score.toFixed(2)})`);
            } else {
                setRecognizedName(null);
            }
        } else {
            setRecognizedName(null);
        }
    };

    const handleFaceDetected = ({ nativeEvent }: { nativeEvent: FaceRecognitionResult }) => {
        if (isLiveMode) {
            processResult(nativeEvent);
        }
    };

    const resumeCamera = () => {
        setIsLiveMode(true);
        setResult(null);
        setRecognizedName(null);
    };

    if (!hasPermission) return <View style={styles.container}><Text style={styles.headerText}>No Camera Permission</Text></View>;

    return (
        <View style={styles.container}>
            <ExpoFaceRecognitionView
                style={styles.camera}
                onFaceDetected={handleFaceDetected}
                isGPUEnabled={isGPUEnabled}
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
                            borderColor: result.isLive ? (recognizedName ? '#00ff00' : '#ffff00') : '#ff0000',
                        },
                    ]}
                >
                    <Text style={{
                        color: '#fff',
                        backgroundColor: result.isLive ? (recognizedName ? 'green' : '#aa0') : 'red',
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
                    {!isLiveMode && <Button title="Resume Camera" onPress={resumeCamera} color="green" />}
                    <Button title="Register User" onPress={() => router.push('/register')} color="#2196F3" />
                    <Button title={isGPUEnabled ? "GPU ON" : "GPU OFF"} onPress={() => setIsGPUEnabled(!isGPUEnabled)} color={isGPUEnabled ? "purple" : "gray"} />
                </View>

                {isLiveMode && <Text style={styles.headerText}>Real-Time Recognition ({users.length} users)</Text>}
                {!isLiveMode && <Text style={styles.headerText}>Static Image Analysis</Text>}

                {result && (
                    <View style={styles.resultBox}>
                        <Text style={styles.label}>Match: <Text style={{ fontWeight: 'bold', color: recognizedName ? 'green' : 'black' }}>{recognizedName || "None"}</Text></Text>
                        {'isLive' in result && <Text style={result.isLive ? styles.success : styles.error}>Live: {result.isLive ? "YES" : "NO"}</Text>}
                        {'spoofScore' in result && <Text style={styles.label}>Spoof Score: {result.spoofScore?.toFixed(3)}</Text>}
                        {'duration' in result && (
                            <>
                                <Text style={styles.timingText}>Detection: {result.duration.detection}ms</Text>
                                <Text style={styles.timingText}>Embedding: {result.duration.embedding}ms</Text>
                                <Text style={styles.timingText}>Spoof: {result.duration.spoof}ms</Text>
                                <Text style={styles.timingText}>Total: {result.duration.detection + result.duration.embedding + result.duration.spoof}ms</Text>
                            </>
                        )}
                        {'error' in result && <Text style={styles.error}>{result.error}</Text>}
                    </View>
                )}
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: '#000' },
    camera: { flex: 1 },
    faceBox: { position: 'absolute', borderWidth: 3, borderRadius: 8, zIndex: 10 },
    overlay: { position: 'absolute', bottom: 0, left: 0, right: 0, padding: 20, backgroundColor: 'rgba(0,0,0,0.85)', borderTopLeftRadius: 20, borderTopRightRadius: 20, zIndex: 20 },
    buttonRow: { flexDirection: 'row', justifyContent: 'space-around', marginBottom: 15 },
    headerText: { color: '#fff', fontSize: 20, fontWeight: 'bold', marginBottom: 10, textAlign: 'center' },
    resultBox: { padding: 10, backgroundColor: '#fff', borderRadius: 10 },
    label: { fontSize: 16, marginBottom: 5, color: '#333' },
    timingText: { fontSize: 14, color: '#555', marginTop: 5 },
    success: { color: 'green', fontSize: 16, fontWeight: 'bold' },
    error: { color: 'red', fontSize: 16, fontWeight: 'bold' }
});
