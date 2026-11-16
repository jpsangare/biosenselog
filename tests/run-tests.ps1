<#
.SYNOPSIS
  Runs the BioSense Log test suites from any terminal (no IDE needed).

.DESCRIPTION
  Suites:
    unit         Measurement algorithms (pulse, HRV, breathing, tremor, steps).      No device.
    integration  Database upgrades, ViewModel, exports - developer AND store builds. No device.
    security     Permissions, exported components, backup rules - both builds.       No device.
    lint         Android static analysis (includes lint's security checks).          No device.
    local        unit + integration + security + lint (default).                     No device.
    ui           The real app driven on an emulator or phone.                        Device needed.
    all          local + ui.
    coverage     Code coverage of the local tests (JaCoCo), with a per-file summary. No device.

.EXAMPLE
  .\tests\run-tests.ps1
  .\tests\run-tests.ps1 integration
  .\tests\run-tests.ps1 all
#>
param(
    [ValidateSet('unit', 'integration', 'security', 'lint', 'local', 'ui', 'all', 'coverage')]
    [string]$Suite = 'local'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradle = if ($IsLinux -or $IsMacOS) { Join-Path $root 'gradlew' } else { Join-Path $root 'gradlew.bat' }
$results = [ordered]@{}

function Invoke-Step([string]$name, [string[]]$gradleArgs, [string]$report) {
    Write-Host ""
    Write-Host "=== $name ===" -ForegroundColor Cyan
    Push-Location $root
    try {
        & $gradle @gradleArgs --console=plain
        $ok = ($LASTEXITCODE -eq 0)
    } finally { Pop-Location }
    $results[$name] = [pscustomobject]@{ Ok = $ok; Report = $report }
}

function Find-Adb {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    $props = Join-Path $root 'local.properties'
    if (-not $sdk -and (Test-Path $props)) {
        $line = Select-String -Path $props -Pattern '^sdk\.dir=(.*)$' | Select-Object -First 1
        if ($line) { $sdk = $line.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':' }
    }
    if (-not $sdk -and $env:LOCALAPPDATA) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $exe = if ($IsLinux -or $IsMacOS) { 'adb' } else { 'adb.exe' }
    $adb = Join-Path $sdk "platform-tools/$exe"
    if (Test-Path $adb) { return $adb }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}

$unitFilter = @('--tests', 'com.example.sensor.*', '--tests', 'com.example.ExampleUnitTest')
$integrationFilter = @('--tests', 'com.example.data.*', '--tests', 'com.example.ui.*')
$securityFilter = @('--tests', 'com.example.security.*')
$debugReport = 'app/build/reports/tests/testDebugUnitTest/index.html'
$releaseReport = 'app/build/reports/tests/testReleaseUnitTest/index.html'

$runUnit = $Suite -in 'unit'
$runIntegration = $Suite -in 'integration'
$runSecurity = $Suite -in 'security'
$runLocal = $Suite -in 'local', 'all'
$runLint = $Suite -in 'lint', 'local', 'all'
$runUi = $Suite -in 'ui', 'all'

if ($runUnit) {
    Invoke-Step 'Unit tests' (@('testDebugUnitTest') + $unitFilter) $debugReport
}
if ($runIntegration) {
    Invoke-Step 'Integration tests (developer + store builds)' (@('testDebugUnitTest', 'testReleaseUnitTest', '--continue') + $integrationFilter) "$debugReport  +  $releaseReport"
}
if ($runSecurity) {
    Invoke-Step 'Security tests (developer + store builds)' (@('testDebugUnitTest', 'testReleaseUnitTest', '--continue') + $securityFilter) "$debugReport  +  $releaseReport"
}
if ($runLocal) {
    Invoke-Step 'Unit + integration + security tests (developer + store builds)' @('testDebugUnitTest', 'testReleaseUnitTest', '--continue') "$debugReport  +  $releaseReport"
}
if ($runLint) {
    Invoke-Step 'Lint (static analysis)' @('lintDebug') 'app/build/reports/lint-results-debug.html'
}
if ($runUi) {
    $adb = Find-Adb
    $skipUiMessage = $false
    $devices =if ($adb) { @((& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] }) } else { @() }
    $device = $devices | Select-Object -First 1
    if ($devices.Count -gt 1 -and -not $env:ANDROID_SERIAL) {
        # UI tests erase the app's data on the device: never pick one implicitly.
        Write-Host ""
        Write-Host "=== UI tests ===" -ForegroundColor Cyan
        Write-Host "Several devices are connected: $($devices -join ', ')" -ForegroundColor Yellow
        Write-Host "UI tests ERASE BioSense Log's measurements on the device they run on. Choose one, e.g.:" -ForegroundColor Yellow
        $suggested = ($devices | Where-Object { $_ -like 'emulator-*' } | Select-Object -First 1)
        if (-not $suggested) { $suggested = $devices[0] }
        Write-Host "  `$env:ANDROID_SERIAL = '$suggested'; .\tests\run-tests.ps1 ui" -ForegroundColor Yellow
        $results['UI tests (device)'] = [pscustomobject]@{ Ok = $false; Report = 'not run: choose a device with ANDROID_SERIAL' }
        $device = $null
        $skipUiMessage = $true
    }
    if ($skipUiMessage) {
        # already reported above
    } elseif (-not $device) {
        Write-Host ""
        Write-Host "=== UI tests ===" -ForegroundColor Cyan
        Write-Host "No emulator or phone connected. Start an emulator (Android Studio > Device Manager)" -ForegroundColor Yellow
        Write-Host "or plug in a phone with USB debugging enabled, then run: .\tests\run-tests.ps1 ui" -ForegroundColor Yellow
        $results['UI tests (device)'] = [pscustomobject]@{ Ok = $false; Report = 'not run: no device' }
    } else {
        Invoke-Step 'UI tests (device)' @('connectedDebugAndroidTest') 'app/build/reports/androidTests/connected/debug/index.html'
    }
}

if ($Suite -eq 'coverage') {
    Invoke-Step 'Coverage (local tests)' @('createDebugUnitTestCoverageReport') 'app/build/reports/coverage/test/debug/index.html'
    $xmlPath = Join-Path $root 'app/build/reports/coverage/test/debug/report.xml'
    if (Test-Path $xmlPath) {
        $settings = New-Object System.Xml.XmlReaderSettings
        $settings.DtdProcessing = 'Ignore'
        $reader = [System.Xml.XmlReader]::Create($xmlPath, $settings)
        $report = New-Object System.Xml.XmlDocument
        $report.Load($reader); $reader.Close()
        function Percent($node, $type) {
            $c = $node.counter | Where-Object { $_.type -eq $type }
            if (-not $c) { return 'n/a' }
            $total = [int]$c.missed + [int]$c.covered
            if ($total -eq 0) { return 'n/a' }
            return ('{0:N1}%' -f (100.0 * [int]$c.covered / $total))
        }
        Write-Host ""
        Write-Host ("Overall: lines {0}, branches {1}" -f (Percent $report.report 'LINE'), (Percent $report.report 'BRANCH')) -ForegroundColor Cyan
        foreach ($pkg in $report.report.package) {
            foreach ($file in $pkg.sourcefile) {
                Write-Host ("  {0,-45} lines {1,7}   branches {2,7}" -f "$($pkg.name)/$($file.name)", (Percent $file 'LINE'), (Percent $file 'BRANCH'))
            }
        }
    }
}

Write-Host ""
Write-Host "=== Summary ===" -ForegroundColor Cyan
$failed = $false
foreach ($entry in $results.GetEnumerator()) {
    $status = if ($entry.Value.Ok) { 'PASS' } else { 'FAIL' }
    $color = if ($entry.Value.Ok) { 'Green' } else { 'Red' }
    Write-Host ("{0,-4}  {1}" -f $status, $entry.Key) -ForegroundColor $color
    Write-Host "      report: $($entry.Value.Report)"
    if (-not $entry.Value.Ok) { $failed = $true }
}
if ($failed) { exit 1 } else { exit 0 }
