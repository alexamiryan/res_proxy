import ResProxyCore
import SwiftUI
import UniformTypeIdentifiers

/// Export / import an encrypted backup (shared/FORMAT.md), compatible with the Android app and the Chrome extension.
/// Arranged like android/.../ui/BackupSection.kt: explanation, Export and Import buttons, each opening a sheet.
struct BackupView: View {
    @EnvironmentObject var model: AppModel
    var startWithImport = false

    @State private var showExport = false
    @State private var showImport = false
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                Text("Copy this setup to another phone or browser: accounts, VPN profiles, Mullvad account, API keys and settings, encrypted with a passphrase you choose. Move the file with Share (AirDrop, a messenger, Files, email to yourself), then Import on the other side. Each install registers its own Mullvad device unless you share this one when exporting.")
                    .font(.footnote).foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    Button { showExport = true } label: { Label("Export", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }
                        .disabled(!model.hasAccount)
                    Button { showImport = true } label: { Label("Import", systemImage: "square.and.arrow.down").frame(maxWidth: .infinity) }
                }
                .buttonStyle(.bordered).controlSize(.large)
                if let m = message { Text(m).font(.footnote).foregroundStyle(Color.accentColor) }
            }
        }
        .navigationTitle("Backup & transfer")
        .sheet(isPresented: $showExport) { ExportSheet { message = $0 } }
        .sheet(isPresented: $showImport) { ImportSheet { message = $0 } }
        .onAppear { if startWithImport && !model.hasAccount { showImport = true } }
    }
}

private struct ExportSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let done: (String) -> Void
    @State private var pass = ""
    @State private var repeatPass = ""
    @State private var shareDevice = false
    @State private var text: String?
    @State private var file: URL?
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Passphrase (8+ characters)", text: $pass)
                    SecureField("Repeat passphrase", text: $repeatPass)
                } footer: { Text("Contains your proxy passwords and VPN key. You'll need this passphrase to import it.") }
                if let d = model.mullvad?.device {
                    Section {
                        Toggle("Share Mullvad device “\(d.name)”", isOn: $shareDevice)
                    } footer: {
                        Text(shareDevice ? "The other side uses this device instead of taking another of your 5 Mullvad slots. Never connect both at the same time: they'd keep taking the VPN from each other."
                                         : "Off: the other side registers its own Mullvad device.")
                    }
                }
                Section {
                    Button { if build() { saving = true } } label: { Label("Save file", systemImage: "folder") }
                    if let f = file, text != nil {
                        ShareLink(item: f) { Label("Share", systemImage: "square.and.arrow.up") }
                    } else {
                        Button { _ = build() } label: { Label("Share", systemImage: "square.and.arrow.up") }
                    }
                    Button {
                        if build(), let t = text { UIPasteboard.general.string = t; done("Backup copied"); dismiss() }
                    } label: { Label("Copy", systemImage: "doc.on.doc") }
                }
                .disabled(pass.count < 8 || pass != repeatPass)
                if let e = error { Section { Text(e).foregroundStyle(Brand.down) } }
            }
            .navigationTitle("Export backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .onChange(of: pass) { _ in text = nil }
            .onChange(of: shareDevice) { _ in text = nil }
            .fileExporter(isPresented: $saving, document: TextFile(text: text ?? ""), contentType: .plainText, defaultFilename: "resproxy-backup.txt") { r in
                if case .success = r { done("Backup saved"); dismiss() }
            }
        }
    }

    /// Encrypts once per passphrase/share choice.
    func build() -> Bool {
        if text != nil { return true }
        do {
            let t = try model.exportBackup(passphrase: pass, shareDevice: shareDevice)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("resproxy-backup.txt")
            try t.write(to: url, atomically: true, encoding: .utf8)
            text = t; file = url; error = nil
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }
}

private struct ImportSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let done: (String) -> Void
    @State private var text = ""
    @State private var pass = ""
    @State private var picking = false
    @State private var confirm = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button { text = UIPasteboard.general.string ?? ""; check() } label: { Label("Paste", systemImage: "doc.on.clipboard") }
                    Button { picking = true } label: { Label("Choose file", systemImage: "folder") }
                    if !text.isEmpty && Backup.looksLikeBackup(text) {
                        Label("ResProxy backup loaded", systemImage: "checkmark.circle.fill").foregroundStyle(Brand.connected)
                    }
                } footer: { Text("Choose the backup file, or paste backup text you copied.") }
                if Backup.looksLikeBackup(text) {
                    Section {
                        SecureField("Passphrase", text: $pass).submitLabel(.go).onSubmit(start)
                        Button(busy ? "Unlocking…" : "Unlock") { start() }.disabled(busy || pass.isEmpty)
                    } header: { Text("Backup passphrase") }
                }
                if let e = error { Section { Text(e).foregroundStyle(Brand.down) } }
            }
            .navigationTitle("Import backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.plainText, .text, .data]) { r in
                if case .success(let url) = r {
                    let ok = url.startAccessingSecurityScopedResource()
                    defer { if ok { url.stopAccessingSecurityScopedResource() } }
                    text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
                    check()
                }
            }
            .alert("Replace current setup?", isPresented: $confirm) {
                Button("Import", role: .destructive) { Task { await run() } }
                Button("Cancel", role: .cancel) {}
            } message: { Text("This replaces all accounts and settings here and disconnects.") }
        }
    }

    func check() {
        error = text.isEmpty ? "Nothing to import" : Backup.looksLikeBackup(text) ? nil : "That isn't a ResProxy backup"
    }

    func start() {
        guard !pass.isEmpty else { return }
        if model.hasAccount { confirm = true } else { Task { await run() } }
    }

    func run() async {
        busy = true; error = nil
        defer { busy = false }
        do {
            try await model.importBackup(text, passphrase: pass)
            done("Backup imported — tap Connect to start")
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct TextFile: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = configuration.file.regularFileContents.map { String(decoding: $0, as: UTF8.self) } ?? ""
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}
