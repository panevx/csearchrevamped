import Foundation

enum Settings {
    private static let defaults = UserDefaults.standard

    static let languages: [(code: String, name: String)] = [
        ("en", "English"), ("es", "Spanish"), ("fr", "French"), ("de", "German"),
        ("it", "Italian"), ("pt", "Portuguese"), ("nl", "Dutch"), ("pl", "Polish"),
        ("bg", "Bulgarian"), ("ru", "Russian"), ("uk", "Ukrainian"), ("tr", "Turkish"),
        ("el", "Greek"), ("ar", "Arabic"), ("hi", "Hindi"), ("zh-CN", "Chinese (Simplified)"),
        ("zh-TW", "Chinese (Traditional)"), ("ja", "Japanese"), ("ko", "Korean"),
        ("vi", "Vietnamese"), ("th", "Thai"), ("id", "Indonesian"),
    ]

    static var translateTarget: String {
        get {
            if let saved = defaults.string(forKey: "translateTarget") { return saved }
            let system = Locale.current.language.languageCode?.identifier ?? "en"
            return languages.contains { $0.code == system } ? system : "en"
        }
        set { defaults.set(newValue, forKey: "translateTarget") }
    }

    /// false = listen to the Mac's own audio output, true = microphone.
    static var useMicrophone: Bool {
        get { defaults.bool(forKey: "useMicrophone") }
        set { defaults.set(newValue, forKey: "useMicrophone") }
    }

    static var auddToken: String? {
        get {
            let token = defaults.string(forKey: "auddToken")?.trimmingCharacters(in: .whitespacesAndNewlines)
            return (token?.isEmpty ?? true) ? nil : token
        }
        set { defaults.set(newValue, forKey: "auddToken") }
    }
}
