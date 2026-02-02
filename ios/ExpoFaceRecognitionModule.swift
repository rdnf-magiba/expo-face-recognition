import ExpoModulesCore

public class ExpoFaceRecognitionModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoFaceRecognition")

    AsyncFunction("processFace") { (imageUri: String) -> [String: Any] in
      return [
        "success": false,
        "error": "Not available on iOS"
      ]
    }
  }
}
