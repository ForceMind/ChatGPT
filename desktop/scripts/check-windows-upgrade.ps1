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
    if (!(Test-Path $exe) -or [Diagnostics.FileVersionInfo]::GetVersionInfo($exe).ProductName -ne 'ChatGPT Studio') { throw 'Installed baseline identity mismatch' }
    $baselineHash = (Get-FileHash (Join-Path $install 'resources/app.asar')).Hash
    $candidate = @($info.artifacts | Where-Object { $_.file.EndsWith('-setup.exe') })
    if ($candidate.Count -ne 1) { throw 'Candidate installer ambiguous' }
    $config = @{ root = $root; installed = $exe; archive = (Join-Path $output 'win-unpacked/resources/app.asar'); installer = (Join-Path $output $candidate[0].file); feed = (Join-Path $output 'alpha.yml'); sourceCommit = $env:GITHUB_SHA }
    $configFile = Join-Path $root 'config.json'
    $config | ConvertTo-Json | Set-Content $configFile -Encoding utf8NoBOM
    $probeOut = Join-Path $root 'probe-out.log'
    $probeErr = Join-Path $root 'probe-error.log'
    $runtime = (Resolve-Path 'node_modules/electron/dist/electron.exe').Path
    $probeScript = (Resolve-Path 'scripts/windows-packaged-update-probe.cjs').Path
    # electron.exe is a GUI process. Do not use the shell's stale LASTEXITCODE,
    # or cleanup can uninstall the baseline while its verifier is still reading it.
    $probe = Start-Process $runtime -ArgumentList @(('"' + $probeScript + '"'), ('"' + $configFile + '"')) -RedirectStandardOutput $probeOut -RedirectStandardError $probeErr -PassThru
    try {
        if (!$probe.WaitForExit(120000)) { $probe.Kill($true); throw 'Shipping updater probe timed out' }
        if ($probe.ExitCode -ne 0) { throw "Shipping updater probe failed: $($probe.ExitCode)" }
    } finally {
        if (Test-Path $probeOut) { Get-Content $probeOut }
        if (Test-Path $probeErr) { Get-Content $probeErr }
    }
    $expectedHash = (Get-FileHash (Join-Path $output 'win-unpacked/resources/app.asar')).Hash
    if ($baselineHash -eq $expectedHash) { throw 'Baseline and candidate must differ' }
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    do {
        Start-Sleep -Milliseconds 500
        try { $installedHash = (Get-FileHash (Join-Path $install 'resources/app.asar')).Hash } catch { $installedHash = '' }
    } until ($installedHash -eq $expectedHash -or [DateTime]::UtcNow -gt $deadline)
    if ($installedHash -ne $expectedHash) { throw 'Native NSIS upgrade did not install the exact candidate ASAR' }
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
} catch {
    $result.error = $_.Exception.Message
    throw
} finally {
    if ($process -and !$process.HasExited) { $process.Kill($true) }
    $uninstaller = Get-ChildItem $install -Filter 'Uninstall*.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    try {
        if ($uninstaller) { $remove = Start-Process $uninstaller.FullName -ArgumentList '/S' -PassThru; [void]$remove.WaitForExit(120000) }
        # NSIS can return before its temporary uninstaller releases its working directory.
        $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            try { Remove-Item $root -Recurse -Force; break } catch { Start-Sleep -Milliseconds 500 }
        } until ([DateTime]::UtcNow -gt $cleanupDeadline)
        $result.temporaryCleanup = !(Test-Path $root)
    } catch { $result.cleanupError = $_.Exception.Message }
    $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $output 'windows-upgrade.json') -Encoding utf8
}
if (!$result.installed -or !$result.identityRetained -or !$result.launch) { throw 'Upgrade incomplete' }
$result | ConvertTo-Json -Depth 8
