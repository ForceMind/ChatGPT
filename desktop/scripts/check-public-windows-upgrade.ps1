$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows') { throw 'Disposable Windows CI only' }
$root = Join-Path $env:RUNNER_TEMP 'studio-public-upgrade'
$control = Join-Path $PSScriptRoot 'windows-branding-control.ps1'
$programs = Join-Path $env:LOCALAPPDATA 'Programs'
$modern = Join-Path $programs 'ChatGPT Studio'
$nested = Join-Path $modern 'ChatGPT Profiles'
$legacy = Join-Path $programs 'ChatGPT Profiles'
$data = Join-Path $env:APPDATA 'ChatGPT Profiles'
& $control -Operation Guard
foreach ($existing in @($modern, $legacy, $data)) {
    if (Test-Path -LiteralPath $existing) { throw 'Refuse pre-existing installation or data' }
}
$out = Join-Path (Get-Location) 'test-results'
New-Item -ItemType Directory -Force $out | Out-Null
function Install-Public($version, $destination, $label) {
    $setup = Join-Path $root "ChatGPT-Studio-$version-windows-x64-setup.exe"
    $result = Join-Path $out "$label-install.json"
    & $control -Operation Install -File $setup -Directory $destination -Output $result -Evidence (Join-Path $out "$label-migration.json")
    if ((Get-Content -Raw $result | ConvertFrom-Json).exitCode -ne 0) { throw "Public installer failed: $label" }
}
$rows = @()
foreach ($case in @(
    @{name='normal'; version='2026.11.10'; old=$modern; explicit=''},
    @{name='nested'; version='2026.11.9'; old=$nested; explicit=$nested}
)) {
    Install-Public $case.version $case.explicit "$($case.name)-baseline"
    $oldExe = Join-Path $case.old 'ChatGPT Studio.exe'
    if (!(Test-Path -LiteralPath $oldExe)) { throw 'Baseline executable missing from its expected installation directory' }
    $baseVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($oldExe).ProductVersion
    if ($baseVersion -notmatch ('^' + [regex]::Escape($case.version) + '($|\.0$)')) { throw 'Wrong baseline binary' }
    New-Item -ItemType Directory -Force $data | Out-Null
    $marker = Join-Path $data 'studio-owned-upgrade-data.txt'
    Set-Content -LiteralPath $marker -Value "synthetic data for $($case.name)"
    $before = (Get-FileHash -LiteralPath $marker).Hash
    $pin = Join-Path $env:APPDATA "Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar\Studio-Owned-$($case.name).lnk"
    & $control -Operation Pin -Directory $case.old -Executable 'ChatGPT Studio.exe' -File $pin
    Install-Public '2026.11.11' '' "$($case.name)-upgrade"
    $report = Join-Path $out "$($case.name)-inspection.json"
    & $control -Operation Inspect -Directory $modern -RequireUpdater -Output $report
    & $control -Operation CheckPin -Directory $modern -File $pin
    $inspected = Get-Content -Raw $report | ConvertFrom-Json
    if ($inspected.productVersion -notmatch '^2026\.11\.11($|\.0$)') { throw 'Wrong installed version' }
    if (Test-Path -LiteralPath $nested) { throw 'Nested legacy directory remains' }
    if ((Get-FileHash -LiteralPath $marker).Hash -ne $before) { throw 'Installer changed synthetic data' }
    $rows += @{name=$case.name; from=$baseVersion; to=$inspected.productVersion; inspection=$inspected; dataBytesUnchanged=$true; pinnedShortcutVerified=$true}
    & $control -Operation Uninstall -Directory $modern
    if ((Get-FileHash -LiteralPath $marker).Hash -ne $before) { throw 'Uninstall changed synthetic data' }
    Remove-Item -LiteralPath $marker
    if (Test-Path -LiteralPath $pin) { Remove-Item -LiteralPath $pin }
}
@{complete=$true; realAccountsUsed=$false; applicationLaunched=$false; cases=$rows} |
    ConvertTo-Json -Depth 10 | Set-Content (Join-Path $out 'public-windows-upgrade.json')
