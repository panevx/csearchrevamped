import AppKit
import Vision

enum ImageActions {
    // MARK: Google Lens

    /// Uploads the image to Google Lens and opens the results in the default browser.
    /// Falls back to a self-submitting local HTML form if the direct upload fails.
    static func searchWithGoogleLens(_ image: CGImage) async {
        let jpeg = jpegData(downscale(image, maxDimension: 1600))
        do {
            let resultURL = try await LensUploader.upload(jpeg: jpeg)
            openInBrowser(resultURL)
        } catch {
            openLensFormFallback(jpeg: jpeg)
        }
    }

    private static func openLensFormFallback(jpeg: Data) {
        let ts = Int(Date().timeIntervalSince1970 * 1000)
        let html = """
        <!doctype html><html><head><meta charset="utf-8"><title>Searching with Google Lens…</title></head>
        <body style="font-family:-apple-system;background:#111;color:#eee;display:grid;place-items:center;height:100vh">
        <p>Uploading to Google Lens…</p>
        <form id="f" method="POST" enctype="multipart/form-data"
              action="https://lens.google.com/v3/upload?hl=en&re=df&ep=gsbubb&st=\(ts)">
          <input type="file" name="encoded_image" id="i" hidden>
        </form>
        <script>
          const b = atob("\(jpeg.base64EncodedString())");
          const bytes = new Uint8Array(b.length);
          for (let k = 0; k < b.length; k++) bytes[k] = b.charCodeAt(k);
          const dt = new DataTransfer();
          dt.items.add(new File([bytes], "image.jpg", { type: "image/jpeg" }));
          document.getElementById("i").files = dt.files;
          document.getElementById("f").submit();
        </script></body></html>
        """
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("csearch-lens-\(ts).html")
        do {
            try html.write(to: url, atomically: true, encoding: .utf8)
            openInBrowser(url)
        } catch {
            NSSound.beep()
        }
    }

    // MARK: Text

    static func recognizeText(_ image: CGImage) async throws -> String {
        try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.automaticallyDetectsLanguage = true
            try VNImageRequestHandler(cgImage: image).perform([request])
            let observations = request.results ?? []
            // Vision's origin is bottom-left: read top-to-bottom, then left-to-right.
            let sorted = observations.sorted {
                let dy = $0.boundingBox.midY - $1.boundingBox.midY
                if abs(dy) > min($0.boundingBox.height, $1.boundingBox.height) / 2 { return dy > 0 }
                return $0.boundingBox.minX < $1.boundingBox.minX
            }
            return sorted.compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
        }.value
    }

    static func openGoogleTranslate(text: String, target: String) {
        let clipped = String(text.prefix(4500))
        var components = URLComponents(string: "https://translate.google.com/")!
        components.queryItems = [
            URLQueryItem(name: "sl", value: "auto"),
            URLQueryItem(name: "tl", value: target),
            URLQueryItem(name: "text", value: clipped),
            URLQueryItem(name: "op", value: "translate"),
        ]
        if let url = components.url { openInBrowser(url) }
    }

    // MARK: Clipboard

    static func copy(text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    static func copy(image: CGImage) {
        let nsImage = NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
        NSPasteboard.general.clearContents()
        NSPasteboard.general.writeObjects([nsImage])
    }

    // MARK: Helpers

    static func openInBrowser(_ url: URL) {
        if url.isFileURL, let browser = NSWorkspace.shared.urlForApplication(toOpen: URL(string: "https://example.com")!) {
            NSWorkspace.shared.open([url], withApplicationAt: browser, configuration: NSWorkspace.OpenConfiguration())
        } else {
            NSWorkspace.shared.open(url)
        }
    }

    static func downscale(_ image: CGImage, maxDimension: Int) -> CGImage {
        let longest = max(image.width, image.height)
        guard longest > maxDimension else { return image }
        let scale = Double(maxDimension) / Double(longest)
        let width = max(1, Int(Double(image.width) * scale))
        let height = max(1, Int(Double(image.height) * scale))
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return image }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return ctx.makeImage() ?? image
    }

    static func jpegData(_ image: CGImage) -> Data {
        NSBitmapImageRep(cgImage: image).representation(using: .jpeg, properties: [.compressionFactor: 0.9]) ?? Data()
    }
}

/// POSTs an image to lens.google.com and returns the results URL from the redirect.
private final class LensUploader: NSObject, URLSessionTaskDelegate {
    static func upload(jpeg: Data) async throws -> URL {
        let ts = Int(Date().timeIntervalSince1970 * 1000)
        var request = URLRequest(url: URL(string: "https://lens.google.com/v3/upload?hl=en&re=df&ep=gsbubb&st=\(ts)")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        var form = MultipartForm()
        form.addFile(name: "encoded_image", filename: "image.jpg", mimeType: "image/jpeg", data: jpeg)
        request.setValue(form.contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = form.finalize()

        let session = URLSession(configuration: .ephemeral, delegate: LensUploader(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (300..<400).contains(http.statusCode),
              let location = http.value(forHTTPHeaderField: "Location"),
              let url = URL(string: location, relativeTo: request.url)?.absoluteURL else {
            throw AppError("Unexpected response from Google Lens.")
        }
        return url
    }

    // Don't follow the redirect — we want to open it in the user's browser instead.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        nil
    }
}

struct MultipartForm {
    let boundary = "CSearch-\(UUID().uuidString)"
    private var body = Data()

    var contentType: String { "multipart/form-data; boundary=\(boundary)" }

    mutating func addField(name: String, value: String) {
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".data(using: .utf8)!)
    }

    mutating func addFile(name: String, filename: String, mimeType: String, data: Data) {
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\nContent-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n".data(using: .utf8)!)
    }

    func finalize() -> Data {
        body + "--\(boundary)--\r\n".data(using: .utf8)!
    }
}
