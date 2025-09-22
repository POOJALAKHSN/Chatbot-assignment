\
param([string]$mvnArgs = "spring-boot:run")
$found = $false
try { $null = & mvn -v 2>$null; $found = $true } catch { $found = $false }
$cwd = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $cwd
if (-not $found) {
    Write-Host "Maven not found. Downloading Maven to .maven..."
    $mavenDir = Join-Path $cwd ".maven"
    if (-not (Test-Path $mavenDir)) { New-Item -ItemType Directory -Path $mavenDir | Out-Null }
    $mavenVersion = "3.9.6"
    $zipName = "apache-maven-$mavenVersion-bin.zip"
    $zipPath = Join-Path $mavenDir $zipName
    $extractDir = Join-Path $mavenDir "apache-maven-$mavenVersion"
    if (-not (Test-Path $extractDir)) {
        Write-Host "Downloading Maven..."
        Invoke-WebRequest -Uri "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/$zipName" -OutFile $zipPath
        Write-Host "Extracting Maven..."
        Expand-Archive -LiteralPath $zipPath -DestinationPath $mavenDir
    }
    $mvnCmd = Join-Path $extractDir "bin\mvn.cmd"
    & $mvnCmd $mvnArgs
} else {
    mvn $mvnArgs
}
