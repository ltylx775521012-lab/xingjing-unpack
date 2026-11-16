import sys, zipfile, shutil, os

apk, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(apk, out)
# aapt2 产出的 apk 是 zip：直接追加 classes.dex
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    names = z.namelist()
    if "classes.dex" in names:
        raise SystemExit("classes.dex already present")
    z.write(dex, "classes.dex")
with zipfile.ZipFile(out) as z:
    bad = z.testzip()
    if bad:
        raise SystemExit("corrupt: " + bad)
    print("entries:", len(z.namelist()), "dex_size:", z.getinfo("classes.dex").file_size)
