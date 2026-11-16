$ErrorActionPreference = 'Stop'

# aapt2/d8 对含中文的路径不稳定，先把工具链和工程镜像到纯 ASCII 的临时目录再构建
$b = $PSScriptRoot
$T = Join-Path $env:TEMP "unpack_build"

if (-not (Test-Path "$T\sdk\platform\android-34\android.jar")) {
    Write-Host "[0/6] staging toolchain -> $T"
    New-Item -ItemType Directory -Force -Path $T | Out-Null
    Copy-Item "$b\sdk" "$T\sdk" -Recurse -Force
}
New-Item -ItemType Directory -Force -Path "$T\dl", "$T\project" | Out-Null
Copy-Item "$b\dl\*.jar" "$T\dl" -Force
Copy-Item "$b\project\*" "$T\project" -Recurse -Force

$env:JAVA_HOME = "$T\sdk\jdk\jdk17.0.21_0"
$bt    = "$T\sdk\build-tools\android-14"
$ajar  = "$T\sdk\platform\android-34\android.jar"
$proj  = "$T\project"
$out   = "$T\out"
$dl    = "$T\dl"
$env:PATH = "$env:JAVA_HOME\bin;$bt;" + $env:PATH

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$out\classes", "$out\dex", "$out\jars" | Out-Null

$cpJars = @(
    "$dl\commons-compress.jar",
    "$dl\commons-io.jar",
    "$dl\commons-lang3-3.17.0.jar",
    "$dl\xz-1.9.jar",
    "$dl\junrar.jar",
    "$dl\slf4j-api-1.7.36.jar",
    "$dl\slf4j-nop-1.7.36.jar"
)

Write-Host "[1/6] aapt2 compile + link"
& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $ajar --manifest "$proj\AndroidManifest.xml" -R "$out\res.zip" --auto-add-overlay --java "$out\gen" --min-sdk-version 26 --target-sdk-version 34 --version-code 9 --version-name 1.4.3
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[2/6] javac"
$srcs = @(Get-ChildItem -Recurse -Filter *.java "$proj\src" | ForEach-Object { $_.FullName })
$srcs += @(Get-ChildItem -Recurse -Filter *.java "$out\gen" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath $ajar -classpath ($cpJars -join ';') -d "$out\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[3/6] jar + d8"
& "$env:JAVA_HOME\bin\jar.exe" cf "$out\jars\app.jar" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

& "$bt\d8.bat" --release --min-api 26 --lib $ajar --output "$out\dex" "$out\jars\app.jar" $cpJars
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/6] merge classes.dex into apk"
$py = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $py) { $py = (Get-Command python3 -ErrorAction SilentlyContinue).Source }
if (-not $py) { throw "需要 Python 3（用于把 classes.dex 塞回 apk）" }
& $py "$b\merge_dex.py" "$out\base.apk" "$out\dex\classes.dex" "$out\unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "merge failed" }

Write-Host "[5/6] zipalign"
& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] sign"
$ks = "$b\signing\unpack.keystore"
if (-not (Test-Path $ks)) {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $ks -alias unpack -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=UnPack, OU=Local, O=Local, C=CN" | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --out "$b\out\UnPack.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "[done] $b\out\UnPack.apk"
Get-Item "$b\out\UnPack.apk" | Select-Object Name, Length
