# -*- coding: utf-8 -*-
"""调 grsai 文生图，落盘。用法: python gen_logo.py <out.png> <prompt-file>"""
import json
import os
import sys
import time
import urllib.request

BASE = "https://grsai.dakka.com.cn"
KEY_PATH = r"D:\大肥鱼的文件夹\.grsai-key"


def key():
    if os.environ.get("GRSAI_API_KEY"):
        return os.environ["GRSAI_API_KEY"].strip()
    return open(KEY_PATH, encoding="utf-8").read().strip()


def post(url, payload, timeout=180):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": "Bearer " + key(), "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def main():
    out = sys.argv[1]
    prompt = open(sys.argv[2], encoding="utf-8").read().strip()
    model = sys.argv[3] if len(sys.argv) > 3 else "gpt-image-2.5"

    print("generating:", os.path.basename(out), flush=True)
    t0 = time.time()
    text = post(BASE + "/v1/draw/completions",
                {"model": model, "prompt": prompt, "aspectRatio": "1:1"})

    objs = []
    for line in text.replace("\r", "").split("\n"):
        line = line.strip()
        if line.startswith("data:"):
            try:
                objs.append(json.loads(line[5:].strip()))
            except Exception:
                pass
    if not objs:
        print("no data:", text[:300], flush=True)
        sys.exit(1)

    last = objs[-1]
    if last.get("status") == "failed":
        print("failed:", last.get("failure_reason"), last.get("error"), flush=True)
        sys.exit(1)
    url = None
    res = last.get("results")
    if res and isinstance(res, list):
        url = res[0].get("url")
    if not url:
        url = last.get("url")
    if not url:
        print("no url:", json.dumps(last, ensure_ascii=False)[:300], flush=True)
        sys.exit(1)

    os.makedirs(os.path.dirname(out), exist_ok=True)
    urllib.request.urlretrieve(url, out)
    print("saved %s  %.1fs  %.0f KB" % (out, time.time() - t0, os.path.getsize(out) / 1024.0), flush=True)


if __name__ == "__main__":
    main()
