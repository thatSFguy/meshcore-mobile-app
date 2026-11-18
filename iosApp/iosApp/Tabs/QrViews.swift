// QR codes in and out, on Apple's own frameworks only — CoreImage draws
// them and VisionKit reads them, so there is no QR dependency (Android
// carries a library for this; iOS has it in the OS).

import SwiftUI
import CoreImage.CIFilterBuiltins
import VisionKit

enum QrCode {
    /// A crisp QR image for [text], or nil if it does not fit in one.
    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let out = filter.outputImage?
            .transformed(by: CGAffineTransform(scaleX: 10, y: 10)) else { return nil }
        guard let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// "My contact": a code someone else scans to add this radio.
struct MyContactSheet: View {
    @EnvironmentObject var store: MeshCoreStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let link = store.myContactLink, let image = QrCode.image(link) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 280)
                        .padding()
                        .background(.white, in: RoundedRectangle(cornerRadius: 12))
                    Text(store.selfName).font(.headline)
                    Text("Anyone who scans this can add you and message you. It carries your name and public key, nothing secret.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    ShareLink(item: link) { Label("Share link", systemImage: "square.and.arrow.up") }
                } else {
                    Text("Connect to a radio to share its contact.")
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding()
            .navigationTitle("My contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

/// Scan a contact, channel or settings code. The text goes to the same
/// confirmation as an opened link — nothing is applied from a scan alone.
struct QrScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onCode: (String) -> Void

    private var canScan: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    var body: some View {
        NavigationStack {
            Group {
                if canScan {
                    QrScannerView { text in
                        dismiss()
                        // The confirmation is a sheet presented from the tab
                        // shell; presenting it while this one is still
                        // animating away is silently dropped by UIKit.
                        Task { @MainActor in
                            try? await Task.sleep(nanoseconds: 600_000_000)
                            onCode(text)
                        }
                    }
                    .ignoresSafeArea(edges: .bottom)
                } else {
                    ContentUnavailableView(
                        "Camera unavailable",
                        systemImage: "camera",
                        description: Text("Allow camera access in Settings, or paste the link instead.")
                    )
                }
            }
            .navigationTitle("Scan a code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

private struct QrScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighlightingEnabled: true
        )
        vc.delegate = context.coordinator
        try? vc.startScanning()
        return vc
    }

    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: DataScannerViewController, coordinator: Coordinator) {
        vc.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        private var delivered = false

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            // First readable code wins; the scanner keeps reporting the
            // same code every frame and one confirmation is enough.
            guard !delivered else { return }
            for item in addedItems {
                if case .barcode(let code) = item, let text = code.payloadStringValue {
                    delivered = true
                    onCode(text)
                    return
                }
            }
        }
    }
}
