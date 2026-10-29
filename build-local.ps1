param([string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug', 'lintDebug'), [switch]$UseFixedUdp, [string]$ToolchainPath = $env:PEYGIR_TOOLCHAIN)
$ErrorActionPreference = 'Stop'
if (-not $ToolchainPath) {
    $candidates = @((Join-Path $PSScriptRoot '../Farda/.toolchain'), 'D:/Pooya/w/All/Farda/.toolchain', 'C:/Users/psh2610/Desktop/Farda/.toolchain')
    $ToolchainPath = $candidates | Where-Object { (Test-Path -LiteralPath "$_/jdk") -and (Test-Path -LiteralPath "$_/sdk/build-tools/35.0.0/aapt2.exe") } | Select-Object -First 1
}
if (-not $ToolchainPath -or -not (Test-Path -LiteralPath "$ToolchainPath/jdk")) { throw 'Set -ToolchainPath or PEYGIR_TOOLCHAIN to the directory containing jdk, sdk and gradle.' }
$toolchainPath = (Resolve-Path -LiteralPath $ToolchainPath).Path
$env:JAVA_HOME = (Get-ChildItem -LiteralPath "$toolchainPath/jdk" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$toolchainPath/sdk"
$env:ANDROID_USER_HOME = "$PSScriptRoot/.signing/android-user"
$env:GRADLE_USER_HOME = "$toolchainPath/gradle-user"
$env:PEYGIR_ROBOLECTRIC_SDKS = "$toolchainPath/robolectric-sdks"
$env:PEYGIR_GOOGLE_MAVEN = 'https://redirector.gvt1.com/edgedl/android/maven2/'
$javaExecutablePath = "$env:JAVA_HOME/bin/java.exe"
if ($UseFixedUdp) {
    $agentBuildPath = Join-Path $PSScriptRoot '.local-build'
    New-Item -ItemType Directory -Force "$agentBuildPath/classes" | Out-Null
    & "$env:JAVA_HOME/bin/javac.exe" -cp "$toolchainPath/gradle/gradle-8.11.1/lib/asm-9.7.jar" -d "$agentBuildPath/classes" "$PSScriptRoot/scripts/local-build/GradleUdpAgent.java" "$PSScriptRoot/scripts/local-build/LocalUdpPort.java"
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile the optional local UDP workaround.' }
    & python "$PSScriptRoot/scripts/local-build/package_agent.py" --asm "$toolchainPath/gradle/gradle-8.11.1/lib/asm-9.7.jar"
    if ($LASTEXITCODE -ne 0) { throw 'Could not package the optional local UDP workaround.' }
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -javaagent:$agentBuildPath/gradle-udp-agent.jar=$agentBuildPath/gradle-udp-helper.jar".Trim()
}
Push-Location $PSScriptRoot
try {
    & $javaExecutablePath '-Xmx64m' '-Djava.net.preferIPv4Stack=true' '-classpath' "$toolchainPath/gradle/gradle-8.11.1/lib/gradle-gradle-cli-main-8.11.1.jar" org.gradle.launcher.GradleMain @Tasks "-Pandroid.aapt2FromMavenOverride=$env:ANDROID_HOME/build-tools/35.0.0/aapt2.exe" '-Dorg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true' '-Dorg.gradle.internal.instrumentation.agent=false' --no-watch-fs --console=plain --no-daemon --max-workers=4
    exit $LASTEXITCODE
} finally { Pop-Location }
