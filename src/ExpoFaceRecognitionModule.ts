import { NativeModule, requireNativeModule } from 'expo';
import { FaceRecognitionResult } from './ExpoFaceRecognition.types';

declare class ExpoFaceRecognitionModule extends NativeModule {
  processFace(imageUri: string): Promise<FaceRecognitionResult>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoFaceRecognitionModule>('ExpoFaceRecognition');
