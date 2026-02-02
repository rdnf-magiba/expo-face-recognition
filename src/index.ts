// Reexport the native module. On web, it will be resolved to ExpoFaceRecognitionModule.web.ts
// and on native platforms to ExpoFaceRecognitionModule.ts
export { default } from './ExpoFaceRecognitionModule';
export * from './ExpoFaceRecognition.types';
