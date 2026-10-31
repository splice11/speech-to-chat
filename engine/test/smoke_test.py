#!/usr/bin/env python3
"""Engine smoke test, run by CI on every platform the way the mod uses the engine:
starts whisper-server with --exit-on-stdin-close and stdin held open, transcribes
engine/test/speech.wav, checks the words, then closes stdin (as if the game died)
and requires the engine to exit by itself.

  smoke_test.py <whisper-server> <model.bin> [--gpu] [--allow-start-failure]
"""
import json, os, socket, subprocess, sys, tempfile, time, urllib.request, uuid
from pathlib import Path

exe, model = sys.argv[1], sys.argv[2]
gpu = "--gpu" in sys.argv
watchdog = "--no-watchdog" not in sys.argv
allow_start_failure = "--allow-start-failure" in sys.argv
wav = Path(__file__).with_name("speech.wav").read_bytes()

with socket.socket() as s:
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
args = [exe, "--host", "127.0.0.1", "--port", str(port), "-m", model, "-l", "en", "-t", str(max(1, min(4, (os.cpu_count() or 2) // 2)))]
if watchdog:
    args.append("--exit-on-stdin-close")
if not gpu:
    args.append("-ng")
# log to a file like the mod does: an unread pipe fills up (4 KB on Windows) and blocks the engine
log_path = Path(tempfile.gettempdir()) / f"smoke-engine-{port}.log"
log = open(log_path, "wb")
proc = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
base = f"http://127.0.0.1:{port}"

deadline = time.time() + 120
while time.time() < deadline:
    if proc.poll() is not None:
        out = log_path.read_text(errors="replace")[-1500:]
        if allow_start_failure:
            print(f"engine could not start (exit {proc.returncode}); allowed here, the mod falls back to CPU\n{out}")
            sys.exit(0)
        sys.exit(f"engine exited during startup with {proc.returncode}\n{out}")
    try:
        urllib.request.urlopen(base + "/health", timeout=2)
        break
    except Exception:
        time.sleep(0.3)
else:
    sys.exit("engine never became healthy")

b = uuid.uuid4().hex
body = b""
for k, v in {"response_format": "json", "language": "en", "temperature": "0.0"}.items():
    body += f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
body += f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"s.wav\"\r\nContent-Type: audio/wav\r\n\r\n".encode()
body += wav + f"\r\n--{b}--\r\n".encode()
t0 = time.time()
req = urllib.request.Request(base + "/inference", body, {"Content-Type": "multipart/form-data; boundary=" + b})
try:
    text = json.loads(urllib.request.urlopen(req, timeout=600).read())["text"]
except Exception as e:
    proc.kill()
    sys.exit(f"inference failed after {time.time() - t0:.0f}s: {e!r}\n--- engine log ---\n"
             + log_path.read_text(errors="replace")[-4000:])
print(f"transcribed in {time.time() - t0:.1f}s: {text.strip()}")
words = text.lower()
missing = [w for w in ("diamonds", "village", "creeper", "behind") if w not in words]
if missing:
    proc.kill()
    sys.exit(f"transcription is missing {missing}")

if not watchdog:
    proc.kill()
    sys.exit(0)
proc.stdin.close()  # what happens when the game process dies
try:
    proc.wait(timeout=10)
    print("engine exited by itself after its parent pipe closed: ok")
except subprocess.TimeoutExpired:
    proc.kill()
    sys.exit("engine kept running after stdin closed")
