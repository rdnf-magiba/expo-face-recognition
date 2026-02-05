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
  embedding: number[];
  rect: FaceRect;
  spoofScore: number;
  duration: FaceRecognitionDuration;
};

export type FaceRecognitionSpoofResult = {
  success: true;
  isLive: false;
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