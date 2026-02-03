import ExpoFaceRecognitionModule from './ExpoFaceRecognitionModule';
import { FaceRecognitionResult } from './ExpoFaceRecognition.types';

export async function processFace(imageUri: string): Promise<FaceRecognitionResult> {
    return await ExpoFaceRecognitionModule.processFace(imageUri);
}

export { default } from './ExpoFaceRecognitionModule';
export * from './ExpoFaceRecognition.types';
export { default as ExpoFaceRecognitionView } from './ExpoFaceRecognitionView';

