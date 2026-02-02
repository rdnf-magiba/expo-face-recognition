import { registerWebModule, NativeModule } from 'expo';

import { ExpoFaceRecognitionModuleEvents } from './ExpoFaceRecognition.types';

class ExpoFaceRecognitionModule extends NativeModule<ExpoFaceRecognitionModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(ExpoFaceRecognitionModule, 'ExpoFaceRecognitionModule');
