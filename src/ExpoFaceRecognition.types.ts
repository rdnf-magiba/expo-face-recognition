export type FaceRect = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type FaceRecognitionDuration = {
  detection: number;
  spoof: number;
  embedding: number;
};

export type FaceRecognitionLiveResult = {
  success: true;
  isLive: true;
  isStable: boolean;
  isStraight: boolean;
  yaw: number;
  roll: number;
  embedding: number[];
  rect: FaceRect;
  spoofScore: number;
  duration: FaceRecognitionDuration;
};

export type FaceRecognitionSpoofResult = {
  success: true;
  isLive: false;
  isStable: boolean;
  isStraight: boolean;
  yaw: number;
  roll: number;
  spoofScore: number;
  rect: FaceRect;
  duration: FaceRecognitionDuration;
};

export type FaceRecognitionFailureResult = {
  success: false;
  error: string;
};

export type FaceRecognitionResult =
  | FaceRecognitionLiveResult
  | FaceRecognitionSpoofResult
  | FaceRecognitionFailureResult;

export enum ModelLoadingStatus {
  LOADING = "LOADING",
  LOADED = "LOADED",
  FAILED = "FAILED",
}