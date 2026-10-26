$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'Isolated Windows CI only' }
$output = Join-Path (Get-Location) 'release/windows'
$info = Get-Content (Join-Path $output 'windows-build-info.json') -Raw | ConvertFrom-Json
$smoke = Get-Content (Join-Path $output 'windows-smoke.json') -Raw | ConvertFrom-Json
$data = Join-Path $env:APPDATA 'ChatGPT Profiles'
$stateFile = Join-Path $data 'state.json'
$before = Get-Content $stateFile -Raw | ConvertFrom-Json
# The preceding installer test refused any pre-existing data and created this exact account.
if (!$smoke.accountDataRetained -or $before.activeProfileId -ne $smoke.accountId) { throw 'CI-owned state receipt mismatch' }
$root = Join-Path $env:RUNNER_TEMP ('studio-upgrade-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$install = Join-Path $root 'installed'
$old = Join-Path $root 'alpha18.exe'
$process = $null
$result = @{ sourceCommit = $env:GITHUB_SHA; version = $info.version; scope = 'Published alpha.18 NSIS -> candidate using shipping updater modules in disposable CI; no real ChatGPT accounts'; installed = $false; identityRetained = $false; launch = $false }
try {
    Invoke-WebRequest 'https://github.com/ForceMind/ChatGPT/releases/download/v3.0.0-alpha.18/ChatGPT-Profiles-3.0.0-alpha.18-windows-x64-setup.exe' -OutFile $old
    if ((Get-Item $old).Length -ne 130970936 -or (Get-FileHash $old -Algorithm SHA256).Hash.ToLowerInvariant() -ne '60d5ee520b2bc758f251eecd190c08385ab4a562f0c0a789a828fd8643768db6') { throw 'Pinned original installer mismatch' }
    $setup = Start-Process $old -ArgumentList @('/S', "/D=$install") -PassThru
    if (!$setup.WaitForExit(120000) -or $setup.ExitCode -ne 0) { throw 'Baseline installation failed' }
    $exe = Join-Path $install 'ChatGPT Profiles.exe'
    if ([Diagnostics.FileVersionInfo]::GetVersionInfo($exe).ProductVersion -ne '3.0.0-alpha.18') { throw 'Installed baseline version mismatch' }
    $candidate = @($info.artifacts | Where-Object { $_.file.EndsWith('-setup.exe') })
    if ($candidate.Count -ne 1) { throw 'Candidate installer ambiguous' }
    $config = @{ root = $root; installed = $exe; archive = (Join-Path $output 'win-unpacked/resources/app.asar'); installer = (Join-Path $output $candidate[0].file); feed = (Join-Path $output 'alpha.yml'); sourceCommit = $env:GITHUB_SHA }
    $configFile = Join-Path $root 'config.json'
    $config | ConvertTo-Json | Set-Content $configFile -Encoding utf8NoBOM
    & 'node_modules/electron/dist/electron.exe' 'scripts/windows-packaged-update-probe.cjs' $configFile
    if ($LASTEXITCODE -ne 0) { throw 'Shipping updater probe failed' }
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    do {
        Start-Sleep -Milliseconds 500
        $version = if (Test-Path $exe) { [Diagnostics.FileVersionInfo]::GetVersionInfo($exe).ProductVersion } else { '' }
    } until ($version -eq $info.version -or [DateTime]::UtcNow -gt $deadline)
    if ($version -ne $info.version) { throw 'Native NSIS upgrade did not install the candidate' }
    $result.installed = $true
    if ((Get-FileHash (Join-Path $install 'resources/app.asar')).Hash -ne (Get-FileHash (Join-Path $output 'win-unpacked/resources/app.asar')).Hash) { throw 'Installed shipping ASAR differs' }
    $result.prepared = Get-Content (Join-Path $root 'prepared.json') -Raw | ConvertFrom-Json
    $after = Get-Content $stateFile -Raw | ConvertFrom-Json
    if ($after.activeProfileId -ne $before.activeProfileId -or ($after.tabs.id -join ',') -ne ($before.tabs.id -join ',')) { throw 'Update changed account or tab identities' }
    $result.identityRetained = $true
    $process = Start-Process $exe -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do { Start-Sleep -Milliseconds 250; $process.Refresh() } until ($process.HasExited -or $process.MainWindowHandle -ne 0 -or [DateTime]::UtcNow -gt $deadline)
    if ($process.HasExited -or $process.MainWindowHandle -eq 0) { throw 'Upgraded app failed to open' }
    $result.launch = $true
    if (!$process.CloseMainWindow() -or !$process.WaitForExit(20000)) { throw 'Upgraded app did not quit normally' }
    $after = Get-Content $stateFile -Raw | ConvertFrom-Json
    if ($after.activeProfileId -ne $before.activeProfileId -or ($after.tabs.id -join ',') -ne ($before.tabs.id -join ',')) { throw 'Relaunch changed account or tab identities' }
} finally {
    if ($process -and !$process.HasExited) { $process.Kill($true) }
    $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $output 'windows-upgrade.json') -Encoding utf8
    $uninstaller = Get-ChildItem $install -Filter 'Uninstall*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($uninstaller) { $remove = Start-Process $uninstaller.FullName -ArgumentList '/S' -PassThru; [void]$remove.WaitForExit(120000) }
    # Only the uniquely created CI directory is removed; account files remain available for evidence.
    Remove-Item $root -Recurse -Force
}
if (!$result.installed -or !$result.identityRetained -or !$result.launch) { throw 'Upgrade incomplete' }
$result | ConvertTo-Json -Depth 8
