import os, sys, time, urllib.request, urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DL = os.path.join(ROOT, "dl")
os.makedirs(DL, exist_ok=True)

TARGETS = [
    ("build-tools", "https://dl.google.com/android/repository/build-tools_r34-windows.zip"),
    ("platform34", "https://dl.google.com/android/repository/platform-34-ext7_r03.zip"),
    ("jdk", "https://corretto.aws/downloads/latest/amazon-corretto-17-x64-windows-jdk.zip"),
    ("commons-compress", "https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.27.1/commons-compress-1.27.1.jar"),
    ("commons-io", "https://repo1.maven.org/maven2/commons-io/commons-io/2.17.0/commons-io-2.17.0.jar"),
    ("xz", "https://repo1.maven.org/maven2/org/tukaani/xz/1.10/xz-1.10.jar"),
    ("junrar", "https://repo1.maven.org/maven2/com/github/junrar/junrar/7.5.5/junrar-7.5.5.jar"),
    ("slf4j-api", "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"),
    ("slf4j-nop", "https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar"),
]

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}


def human(n):
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return "%.1f%s" % (n, u)
        n /= 1024.0
    return "%.1fTB" % n


def download(name, url, tries=4):
    ext = ".jar" if url.endswith(".jar") else ".zip"
    dest = os.path.join(DL, name + ext)
    for attempt in range(1, tries + 1):
        have = os.path.getsize(dest) if os.path.exists(dest) else 0
        hdrs = dict(UA)
        if have:
            hdrs["Range"] = "bytes=%d-" % have
        try:
            req = urllib.request.Request(url, headers=hdrs)
            r = urllib.request.urlopen(req, timeout=60)
            total = int(r.headers.get("Content-Length") or 0)
            mode = "ab" if (have and r.status == 206) else "wb"
            if mode == "wb":
                have, total = 0, total
            print("[%s] start %s total=%s attempt=%d" % (name, human(have), human(total) if total else "?", attempt), flush=True)
            t0 = time.time()
            last = 0
            with open(dest, mode) as f:
                while True:
                    chunk = r.read(262144)
                    if not chunk:
                        break
                    f.write(chunk)
                    have += len(chunk)
                    if have - last > 8 * 1024 * 1024:
                        last = have
                        sp = have / max(0.001, time.time() - t0)
                        print("  [%s] %s  %s/s" % (name, human(have), human(sp)), flush=True)
            sz = os.path.getsize(dest)
            if total and sz < total:
                print("[%s] incomplete %d/%d, retry" % (name, sz, total), flush=True)
                continue
            print("[%s] DONE %s" % (name, human(sz)), flush=True)
            return True
        except Exception as e:
            print("[%s] ERR %s: %s" % (name, type(e).__name__, str(e)[:150]), flush=True)
            time.sleep(2)
    print("[%s] FAILED" % name, flush=True)
    return False


ok = True
for name, url in TARGETS:
    ok = download(name, url) and ok
print("ALL_OK" if ok else "SOME_FAILED", flush=True)
