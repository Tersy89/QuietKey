[CmdletBinding()]
param(
    [ValidateSet('build', 'protocol', 'devices', 'install', 'device-tests')]
    [string]$Action = 'build',
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
$quietProject = Split-Path $PSScriptRoot -Parent
$quietLocalJdk = Get-ChildItem (Join-Path $quietProject '.local-tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($quietLocalJdk) { $env:JAVA_HOME = $quietLocalJdk.FullName }
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to a JDK 17 installation. See TESTING.md.'
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$quietAdb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$quietDeviceArgs = @()
if ($Serial) { $quietDeviceArgs = @('-s', $Serial) }
Push-Location $quietProject
try {
    switch ($Action) {
        'build' { & .\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest --console=plain }
        'protocol' {
            & "$env:JAVA_HOME\bin\java.exe" '-Dfile.encoding=UTF-8' app/src/main/java/app/quietkey/Protocol.java
            if ($LASTEXITCODE -ne 0) { throw 'Protocol checks failed.' }
            & "$env:JAVA_HOME\bin\java.exe" '-Dfile.encoding=UTF-8' tools/CheckSyntax.java
        }
        'devices' { & $quietAdb devices -l }
        'install' {
            $quietApk = 'app\build\outputs\apk\debug\app-debug.apk'
            if (-not (Test-Path $quietApk)) { throw 'Build first: .\tools\dev.ps1 build' }
            & $quietAdb @quietDeviceArgs install -r $quietApk
        }
        'device-tests' {
            # Gradle connected tests uninstall the app afterward; install updates and run directly
            # so a phone's existing identity and vault are preserved.
            & .\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
            if ($LASTEXITCODE -ne 0) { throw 'Instrumentation APK build failed.' }
            & $quietAdb @quietDeviceArgs install -r app\build\outputs\apk\debug\app-debug.apk
            if ($LASTEXITCODE -ne 0) { throw 'App update failed. Do not uninstall to work around a signing-key mismatch.' }
            & $quietAdb @quietDeviceArgs install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
            if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
            $quietTestOutput = & $quietAdb @quietDeviceArgs shell am instrument -w -r app.quietkey.debug.test/androidx.test.runner.AndroidJUnitRunner
            $quietTestOutput | Write-Output
            if ($LASTEXITCODE -ne 0 -or (($quietTestOutput -join "`n") -notmatch 'OK \(\d+ tests?\)')) { throw 'Device checks did not report success.' }
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "QuietKey $Action failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
