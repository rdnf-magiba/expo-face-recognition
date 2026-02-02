import { NativeModule, requireNativeModule } from 'expo';

import { ExpoFaceRecognitionModuleEvents } from './ExpoFaceRecognition.types';

declare class ExpoFaceRecognitionModule extends NativeModule<ExpoFaceRecognitionModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoFaceRecognitionModule>('ExpoFaceRecognition');
