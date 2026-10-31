#!/usr/bin/env python3
"""Run V1 instrumentation against a disposable emulator and synthetic server.

Usage: python3 tools/rustclipsync_android_integration.py SERVER APP_APK TEST_APK ADB EMULATOR_SERIAL
Only an emulator-* serial is accepted; this never installs on a physical device.
"""
import base64
import contextlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

PASSWORD = "synthetic-android-fixture"


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    if len(sys.argv) != 6 or not sys.argv[5].startswith("emulator-"):
        raise SystemExit(__doc__)
    server, app_apk, test_apk, adb = [str(Path(p).resolve()) for p in sys.argv[1:5]]
    serial = sys.argv[5]
    avd_name = subprocess.check_output([adb, "-s", serial, "emu", "avd", "name"], text=True).splitlines()[0].strip()
    if avd_name != "rcs-v1-test":
        raise SystemExit("refusing to modify an emulator other than the disposable rcs-v1-test AVD")
    with tempfile.TemporaryDirectory(prefix="rcs-android-fixture-") as directory:
        root = Path(directory)
        backend_port, tls_port = port(), port()
        env = os.environ.copy()
        env.update({"RCS_USER": "fixture", "RCS_PASSWORD": PASSWORD, "RCS_DB_KEY": "3f" * 32,
                    "RCS_DATA_DIR": str(root / "db"), "RCS_LISTEN": f"127.0.0.1:{backend_port}"})
        service = subprocess.Popen([server], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        proxy = None
        try:
            deadline = time.monotonic() + 20
            while True:
                try:
                    if urllib.request.urlopen(f"http://127.0.0.1:{backend_port}/healthz", timeout=1).status == 200:
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(.1)
            key, cert = root / "key.pem", root / "cert.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                            "-keyout", str(key), "-out", str(cert)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            der = subprocess.check_output(["openssl", "x509", "-in", str(cert), "-outform", "DER"])

            class Proxy(BaseHTTPRequestHandler):
                protocol_version = "HTTP/1.1"
                def log_message(self, *args):
                    pass
                def forward(self):
                    connection = http.client.HTTPConnection("127.0.0.1", backend_port, timeout=32)
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                        if length > 10 * 1024 * 1024 + 4096:
                            self.send_error(413)
                            return
                        headers = {k: v for k, v in self.headers.items() if k.lower() not in {"host", "connection"}}
                        connection.request(self.command, self.path, self.rfile.read(length) if length else None, headers)
                        response = connection.getresponse()
                        self.send_response(response.status)
                        for name, value in response.getheaders():
                            if name.lower() not in {"connection", "transfer-encoding", "server", "date"}:
                                self.send_header(name, value)
                        self.end_headers()
                        while chunk := response.read(65536):
                            self.wfile.write(chunk)
                    except (OSError, http.client.HTTPException):
                        with contextlib.suppress(OSError): self.send_error(502)
                    finally:
                        connection.close()
                do_GET = do_POST = do_PUT = do_DELETE = forward

            proxy = ThreadingHTTPServer(("127.0.0.1", tls_port), Proxy)
            proxy.daemon_threads = True
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.load_cert_chain(cert, key)
            proxy.socket = tls.wrap_socket(proxy.socket, server_side=True)
            threading.Thread(target=proxy.serve_forever, daemon=True).start()
            command = [adb, "-s", serial]
            for apk in (app_apk, test_apk):
                subprocess.run(command + ["install", "-r", apk], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(command + ["reverse", f"tcp:{tls_port}", f"tcp:{tls_port}"], check=True)
            ime = "org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
            subprocess.run(command + ["shell", "ime", "enable", ime], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(command + ["shell", "ime", "set", ime], check=True, stdout=subprocess.DEVNULL)
            result = subprocess.run(command + ["shell", "am", "instrument", "-w", "-r",
                "-e", "class", "org.fcitx.fcitx5.android.rustclipsync.SyncAndroidTest",
                "-e", "rcs_fixture", "1", "-e", "rcs_url", f"https://localhost:{tls_port}",
                "-e", "rcs_cert", base64.b64encode(der).decode(), "-e", "rcs_user", "fixture",
                "-e", "rcs_password", PASSWORD,
                "org.fcitx.fcitx5.android.debug.test/androidx.test.runner.AndroidJUnitRunner"],
                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            print(result.stdout)
            if result.returncode != 0 or "OK (4 tests)" not in result.stdout:
                raise SystemExit("Android instrumentation failed")
        finally:
            subprocess.run([adb, "-s", serial, "reverse", "--remove", f"tcp:{tls_port}"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if proxy:
                proxy.shutdown()
                proxy.server_close()
            service.terminate()
            try:
                service.wait(timeout=5)
            except subprocess.TimeoutExpired:
                service.kill()
                service.wait()


if __name__ == "__main__":
    main()
