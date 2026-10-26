# Builds XstreamRemote.apk with the plain SDK tools - no Gradle, no dependencies.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$bt = if ($env:ANDROID_BUILD_TOOLS) { $env:ANDROID_BUILD_TOOLS } else { 'C:\cmdline-tools\build-tools\34.0.0' }
$androidJar = if ($env:ANDROID_JAR) { $env:ANDROID_JAR } else { 'C:\cmdline-tools\platforms\android-34\android.jar' }
$versionCode = 1
$versionName = '1.0'
$aapt2 = Join-Path $bt 'aapt2.exe'
$d8 = Join-Path $bt 'd8.bat'
$zipalign = Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'

foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner, $androidJar)) {
    if (!(Test-Path $tool)) { throw "missing build tool: $tool" }
}

Write-Host '== icons ==' -ForegroundColor Cyan
python tools\fetch_icons.py
if ($LASTEXITCODE -ne 0) { throw 'icon fetch failed' }

Write-Host '== web ui ==' -ForegroundColor Cyan
python tools\make_ui.py
if ($LASTEXITCODE -ne 0) { throw 'ui build failed' }

Write-Host '== clean ==' -ForegroundColor Cyan
Remove-Item -Recurse -Force build\gen, build\classes, build\dex -ErrorAction SilentlyContinue
Remove-Item -Force build\res.zip, build\base.apk, build\unsigned.apk, build\aligned.apk -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build | Out-Null

Write-Host '== resources ==' -ForegroundColor Cyan
& $aapt2 compile --dir res -o build\res.zip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

& $aapt2 link -o build\base.apk `
    -I $androidJar `
    --manifest AndroidManifest.xml `
    --java build\gen `
    --min-sdk-version 24 `
    --target-sdk-version 34 `
    --version-code $versionCode `
    --version-name $versionName `
    build\res.zip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '== java ==' -ForegroundColor Cyan
$sources = @()
$sources += (Get-ChildItem -Recurse -Filter *.java build\gen | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem -Recurse -Filter *.java java | ForEach-Object { $_.FullName })
New-Item -ItemType Directory -Force build\classes | Out-Null
& javac -encoding UTF-8 --release 11 -parameters -classpath $androidJar -d build\classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '== dex ==' -ForegroundColor Cyan
New-Item -ItemType Directory -Force build\dex | Out-Null
$classFiles = Get-ChildItem -Recurse -Filter *.class build\classes | ForEach-Object { $_.FullName }
& $d8 --lib $androidJar --min-api 24 --output build\dex $classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '== package ==' -ForegroundColor Cyan
Copy-Item build\base.apk build\unsigned.apk -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $root 'build\unsigned.apk'), 'Update')
$existing = $zip.GetEntry('classes.dex')
if ($existing) { $existing.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip,
    (Join-Path $root 'build\dex\classes.dex'),
    'classes.dex',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

# aapt2 -A writes Windows separators on this host, so add assets explicitly.
$assetMap = @(
    'assets\ui\index.html|assets/ui/index.html'
)
if ((Test-Path (Join-Path $root 'assets\client.pem')) -and
    (Test-Path (Join-Path $root 'assets\client.key'))) {
    $assetMap += 'assets\client.pem|assets/client.pem'
    $assetMap += 'assets\client.key|assets/client.key'
} else {
    Write-Warning 'No paired client.pem/client.key in assets; the APK will build but cannot connect (v1).'
}
foreach ($entry in $assetMap) {
    $pair = $entry.Split('|')
    $old = $zip.GetEntry($pair[1])
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip,
        (Join-Path $root $pair[0]),
        $pair[1],
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
}
$zip.Dispose()

& $zipalign -f -p 4 build\unsigned.apk build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '== sign ==' -ForegroundColor Cyan
$storePass = $env:XSTREAM_STORE_PASS
if (-not $storePass) {
    throw 'Set XSTREAM_STORE_PASS before building (and optionally XSTREAM_KEYSTORE / XSTREAM_KEY_ALIAS).'
}
$keyAlias = if ($env:XSTREAM_KEY_ALIAS) { $env:XSTREAM_KEY_ALIAS } else { 'airtel' }
$keystore = if ($env:XSTREAM_KEYSTORE) { $env:XSTREAM_KEYSTORE } else { Join-Path $root 'keystore.jks' }
if (!(Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -alias $keyAlias -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass $storePass -keypass $storePass `
        -dname 'CN=Xstream Remote, O=Local, C=IN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$out = Join-Path $root "XstreamRemote-$versionName.apk"
& $apksigner sign --ks $keystore --ks-key-alias $keyAlias `
    --ks-pass "pass:$storePass" --key-pass "pass:$storePass" `
    --out $out build\aligned.apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& $apksigner verify --print-certs $out
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

Copy-Item $out (Join-Path (Split-Path $root -Parent) 'XstreamRemote.apk') -Force

$size = [math]::Round((Get-Item $out).Length / 1KB, 1)
Write-Host ''
Write-Host "built: $out ($size KB)" -ForegroundColor Green
