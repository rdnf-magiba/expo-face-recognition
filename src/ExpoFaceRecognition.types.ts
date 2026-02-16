export type FaceRect = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type FaceRecognitionDuration = {
  detection: number;
  spoof: number;
  glass?: number;
  embedding?: number;
};

export type FaceRecognitionWearingGlassesResult = {
  success: true;
  isLive: true;
  isWearingGlasses: true;
  rect: FaceRect;
  duration: FaceRecognitionDuration;
};

export type FaceRecognitionNotWearingGlassesResult = {
  success: true;
  isLive: true;
  isWearingGlasses: false;
  embedding: number[];
  rect: FaceRect;
  duration: FaceRecognitionDuration;
};

export type FaceRecognitionSpoofResult = {
  success: true;
  isLive: false;
  isWearingGlasses?: boolean;
  rect: FaceRect;
  duration: FaceRecognitionDuration;
};

export type FaceRecognitionFailureResult = {
  success: false;
  error: string;
};

export type FaceRecognitionResult =
  | FaceRecognitionWearingGlassesResult
  | FaceRecognitionNotWearingGlassesResult
  | FaceRecognitionSpoofResult
  | FaceRecognitionFailureResult;

export enum ModelLoadingStatus {
  LOADING = "LOADING",
  LOADED = "LOADED",
  FAILED = "FAILED",
}