export type FaceRecognitionLiveResult = {
  success: true;
  isLive: true;
  embedding: number[];
};

export type FaceRecognitionSpoofResult = {
  success: true;
  isLive: false;
  spoofScore: number;
};

export type FaceRecognitionFailureResult = {
  success: false;
  error: string;
};

export type FaceRecognitionResult =
  | FaceRecognitionLiveResult
  | FaceRecognitionSpoofResult
  | FaceRecognitionFailureResult;