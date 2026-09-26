#!/usr/bin/env python3
"""Exercise the actual app callback and registration from a detached worker.

Stub only C registration/log transport; actor isolation comes from app source.
Run with Swift runtime actor checks, which reproduce build 270's crash.
"""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[2]
source = (root / 'apple/App/AppleDiagnosticLog.swift').read_text()
start = source.index('private nonisolated func appleSEIDiagnosticCallback')
end = source.index('@MainActor\nfinal class AppleDiagnosticsCenter', start)
actual = source[start:end]
harness = r'''
import Foundation
nonisolated(unsafe) var registered: (@convention(c) (UnsafePointer<CChar>?) -> Void)?
func R2CFFmpegSetSEIDiscovery(_ enabled: Bool, _ callback: @escaping @convention(c) (UnsafePointer<CChar>?) -> Void) { registered = callback }
enum AppleLog {
    static func info(_ category: String, _ text: String) {
        precondition(category == "DjiSeiPayload" && text == "worker sample")
    }
}
'''
harness += actual
harness += r'''
@main struct Test {
    @MainActor static func main() async {
        AppleSEIHexDiagnostics.enabled = true
        let callback = registered!
        await Task.detached {
            "worker sample".withCString { callback($0) }
            callback(nil)
        }.value
        AppleSEIHexDiagnostics.enabled = false
        precondition(AppleSEIHexDiagnostics.preserveOriginals)
        print("SEI callback: MainActor registration, worker invocation and nil input passed")
    }
}
'''
with tempfile.TemporaryDirectory(prefix='r2c-sei-callback-') as tmp:
    src = Path(tmp) / 'Test.swift'
    exe = Path(tmp) / 'test'
    src.write_text(harness)
    subprocess.run(['xcrun', 'swiftc', '-swift-version', '6', '-parse-as-library',
                    '-module-cache-path', str(Path(tmp) / 'cache'), '-Xfrontend', '-enable-actor-data-race-checks', str(src), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
