import { requireNativeViewManager } from 'expo-modules-core';
import * as React from 'react';
import { ViewProps, View, Text } from 'react-native';
import { FaceRecognitionResult } from './ExpoFaceRecognition.types';

export type ExpoFaceRecognitionViewProps = {
    onFaceDetected?: (event: { nativeEvent: FaceRecognitionResult }) => void;
    isGPUEnabled?: boolean;
} & ViewProps;

const NativeView: React.ComponentType<ExpoFaceRecognitionViewProps> | null =
    requireNativeViewManager('ExpoFaceRecognition');

export default function ExpoFaceRecognitionView(props: ExpoFaceRecognitionViewProps) {
    if (!NativeView) {
        return (
            <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center' }}>
                <Text>ExpoFaceRecognitionView is not available on this platform</Text>
            </View>
        );
    }
    return <NativeView {...props} />;
}
