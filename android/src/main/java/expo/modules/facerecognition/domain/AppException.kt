package expo.modules.facerecognition.domain

class AppException(val errorCode: ErrorCode) : Exception(errorCode.name)
