#!/usr/bin/env python3
"""Verify generated Android/Apple MediaMTX policies using synthetic video only.

Pass a pinned MediaMTX binary, a directory with {android,apple}-{restricted,open}.yml,
and this machine's own non-loopback IPv4 address. Uses test ports, never device media.
"""
import argparse
import pathlib
import re
import socket
import subprocess
import tempfile
import time


def connects(host, port):
    try:
        with socket.create_connection((host, port), timeout=1):
            return True
    except OSError:
        return False


def read_frame(url, rtsp=False):
    command = ["ffmpeg", "-nostdin", "-v", "error"]
    if rtsp:
        command += ["-rtsp_transport", "tcp"]
    command += ["-i", url, "-frames:v", "1", "-f", "null", "-"]
    return subprocess.run(command, capture_output=True, timeout=12)


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def verify(binary, source, host):
    restricted = source.stem.endswith("-restricted")
    for port in (21935, 28554, 28888, 28889, 28189, 29997, 29998, 29999):
        assert not connects("127.0.0.1", port), f"Test port {port} is already in use"
    with tempfile.TemporaryDirectory(prefix="r2c-media-access-") as directory:
        root = pathlib.Path(directory)
        config = source.read_text()
        # Keep the generated policy intact; change only ports and recording destination.
        for original, test_port in [(1935, 21935), (8554, 28554), (8888, 28888),
                                    (8889, 28889), (8189, 28189), (9997, 29997),
                                    (9998, 29998), (9999, 29999)]:
            config = config.replace(f":{original}", f":{test_port}")
        config = re.sub(r"(?m)^  recordPath:.*$", f"  recordPath: '{root}/recordings/%path/%Y-%m-%d_%H-%M-%S-%f'", config)
        config_path = root / "mediamtx.yml"
        config_path.write_text(config)
        server = publisher = None
        with (root / "server.log").open("w+") as log, (root / "publisher.log").open("w+") as publisher_log:
            try:
                server = subprocess.Popen([binary, str(config_path)], stdout=log, stderr=subprocess.STDOUT)
                for _ in range(50):
                    if server.poll() is not None:
                        raise AssertionError("Server failed to start")
                    if connects("127.0.0.1", 28554):
                        break
                    time.sleep(0.1)
                assert connects(host, 21935), "Controller RTMP listener is unreachable"
                publisher = subprocess.Popen([
                    "ffmpeg", "-nostdin", "-v", "error", "-re", "-f", "lavfi", "-i",
                    "testsrc2=size=160x90:rate=5", "-an", "-c:v", "libx264",
                    "-preset", "ultrafast", "-tune", "zerolatency", "-g", "5",
                    "-f", "flv", f"rtmp://{host}:21935/access-test",
                ], stdout=publisher_log, stderr=subprocess.STDOUT)
                time.sleep(2)
                assert publisher.poll() is None, "Controller-style publisher failed"
                for url, rtsp in [("rtsp://127.0.0.1:28554/access-test", True),
                                  ("rtmp://127.0.0.1:21935/access-test", False)]:
                    result = read_frame(url, rtsp)
                    assert result.returncode == 0, result.stderr.decode()
                assert connects(host, 28554) == (not restricted), "RTSP network binding is wrong"
                assert connects(host, 28888) == (not restricted), "HLS network binding is wrong"
                assert not connects(host, 29997), "Control API exposed to network"
                assert not connects(host, 29998), "Metrics exposed to network"
                assert not connects(host, 29999), "Profiler exposed to network"
                result = read_frame(f"rtmp://{host}:21935/access-test")
                if restricted:
                    assert result.returncode != 0, "External RTMP read unexpectedly succeeded"
                    log.flush()
                    assert "authentication failed" in (root / "server.log").read_text(), "Read failure was not an access rejection"
                    assert not connects(host, 28889), "WebRTC network listener remains exposed"
                else:
                    assert result.returncode == 0, result.stderr.decode()
                    assert read_frame(f"rtsp://{host}:28554/access-test", True).returncode == 0
                stop(publisher)
                publisher = None
                time.sleep(1)
                stop(server)
                server = None
                assert any(p.stat().st_size > 0 for p in (root / "recordings").rglob("*.mp4")), "No recording produced"
                print(f"PASS {source.stem}: network publish, local playback, recording; external read {'denied' if restricted else 'allowed'}; administration local only", flush=True)
            except Exception:
                log.flush()
                publisher_log.flush()
                print((root / "server.log").read_text()[-10000:])
                print((root / "publisher.log").read_text()[-3000:])
                raise
            finally:
                stop(publisher)
                stop(server)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary")
    parser.add_argument("configs", type=pathlib.Path)
    parser.add_argument("host", help="This machine's own non-loopback IPv4 address")
    args = parser.parse_args()
    assert not args.host.startswith("127."), "A non-loopback source is needed to test read denial"
    # Binding the address confirms it belongs to this host; never target another machine.
    with socket.socket() as local:
        local.bind((args.host, 0))
    for platform in ("android", "apple"):
        for mode in ("restricted", "open"):
            verify(args.binary, args.configs / f"{platform}-{mode}.yml", args.host)


if __name__ == "__main__":
    main()
