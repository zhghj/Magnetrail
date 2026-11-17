$ErrorActionPreference = 'Stop'
$localJdk = Join-Path $PSScriptRoot '.toolchain\jdk-17.0.21+9'
if (Test-Path $localJdk) {
    # Prefer the local portable toolchain (not committed).
    $env:JAVA_HOME = $localJdk
} elseif (-not $env:JAVA_HOME) {
    Write-Error "JDK not found: add .toolchain/jdk-17.0.21+9 or set JAVA_HOME (JDK 17 required)."
}
$gradlew = Join-Path $PSScriptRoot 'gradlew.bat'
& $gradlew @args
exit $LASTEXITCODE
