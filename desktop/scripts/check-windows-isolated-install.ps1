$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:GITHUB_REPOSITORY -ne 'ForceMind/ChatGPT' -or !$env:RUNNER_TEMP) {
    throw 'Use a disposable Windows CI runner.'
}
$output = Join-Path (Get-Location).Path 'release/windows'
$info = Get-Content "$output/windows-build-info.json" -Raw | ConvertFrom-Json
if ($info.sourceCommit -ne $env:GITHUB_SHA) { throw 'Candidate was built from a different commit.' }
$installer, $portable = @($info.artifacts | Where-Object { $_.file -match '(\.exe|\.zip)$' })
if (!$installer.file.EndsWith('-setup.exe') -or !$portable.file.EndsWith('.zip')) { throw 'Expected the EXE and ZIP.' }
foreach ($item in @($installer, $portable)) {
    if ((Get-Item "$output/$($item.file)").Length -ne $item.bytes -or
        (Get-FileHash "$output/$($item.file)" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256) {
        throw 'Package differs from the build manifest.'
    }
}
$launcher = 'Start-Isolated.cmd'
$config = 'resources/app-update-isolated.yml'
$zip = [IO.Compression.ZipFile]::OpenRead("$output/$($portable.file)")
try {
    if (@($zip.Entries | Where-Object { $_.Name -eq $launcher -or $_.Name -eq 'app-update-isolated.yml' }).Count) {
        throw 'The portable ZIP contains the isolated entry point or its configuration.'
    }
} finally { $zip.Dispose() }

$root = Join-Path $env:RUNNER_TEMP ('studio-isolated-install-' + [guid]::NewGuid().ToString('N'))
$destination = Join-Path $root 'ChatGPT Studio'
New-Item -ItemType Directory -Path $root | Out-Null
$old = Get-Content '../docs/releases/2026.11.19.json' -Raw | ConvertFrom-Json
$baseline = $old.artifacts | Where-Object { $_.platform -eq 'windows' -and $_.purpose -eq 'installer' }
$priorInstaller = Join-Path $root 'previous.exe'
Invoke-WebRequest -Uri $baseline.url -OutFile $priorInstaller -TimeoutSec 120
if ((Get-Item $priorInstaller).Length -ne $baseline.bytes -or
    (Get-FileHash $priorInstaller -Algorithm SHA256).Hash.ToLowerInvariant() -ne $baseline.sha256) {
    throw 'The previous public installer does not match its release record.'
}

function Install-Release($File) {
    $process = Start-Process -FilePath $File -ArgumentList @('/S', "/D=$destination") -WindowStyle Hidden -PassThru
    if (!$process.WaitForExit(120000) -or $process.ExitCode -ne 0) { throw 'Silent install or upgrade failed.' }
}

$dataPaths = @((Join-Path $env:APPDATA 'ChatGPT Profiles'), (Join-Path $env:APPDATA 'ChatGPT Profiles Isolated'))
foreach ($data in $dataPaths) {
    if (Test-Path -LiteralPath $data) { throw 'Refusing to touch pre-existing application data.' }
}
Install-Release $priorInstaller
if (Test-Path -LiteralPath "$destination/$launcher") { throw 'Previous install unexpectedly has the isolated entry point.' }
foreach ($data in $dataPaths) {
    New-Item -ItemType Directory -Path $data | Out-Null
    [IO.File]::WriteAllText((Join-Path $data 'fixture.txt'), $data)
}
Install-Release "$output/$($installer.file)"
if ((Get-FileHash "$destination/$launcher").Hash -ne (Get-FileHash "assets/$launcher").Hash -or
    (Get-FileHash "$destination/$config").Hash -ne (Get-FileHash "$output/win-unpacked/$config").Hash) {
    throw 'Installed launcher or cache config differs from the frozen payload.'
}
$regular = Get-Content "$destination/resources/app-update.yml" -Raw | ConvertFrom-Json
$isolated = Get-Content "$destination/$config" -Raw | ConvertFrom-Json
if ($regular.updaterCacheDirName -eq $isolated.updaterCacheDirName -or $regular.url -ne $isolated.url) {
    throw 'The installed instances do not use distinct caches with the same update source.'
}
$shortcut = New-Object -ComObject WScript.Shell
foreach ($directory in @([Environment]::GetFolderPath('DesktopDirectory'), [Environment]::GetFolderPath('Programs'))) {
    foreach ($link in Get-ChildItem -LiteralPath $directory -Filter '*.lnk' -Recurse -ErrorAction SilentlyContinue) {
        if ($shortcut.CreateShortcut($link.FullName).TargetPath -eq "$destination\$launcher") {
            throw 'The installer created an independent launch shortcut outside the app folder.'
        }
    }
}
$uninstaller = Join-Path $destination 'Uninstall ChatGPT Studio.exe'
$remove = Start-Process -FilePath $uninstaller -ArgumentList '/S' -WindowStyle Hidden -PassThru
if (!$remove.WaitForExit(120000) -or $remove.ExitCode -ne 0) {
    throw 'NSIS uninstall did not return successfully.'
}
$installedFiles = @("$destination/ChatGPT Studio.exe", "$destination/$launcher", "$destination/$config")
$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    $remaining = @($installedFiles | Where-Object { Test-Path -LiteralPath $_ })
    if ($remaining.Count -eq 0) { break }
    Start-Sleep -Milliseconds 250
} until ([DateTime]::UtcNow -ge $deadline)
if ($remaining.Count) { throw ('Uninstall left program files behind: ' + ($remaining -join ', ')) }
foreach ($data in $dataPaths) {
    if ([IO.File]::ReadAllText((Join-Path $data 'fixture.txt')) -cne $data) { throw 'Application data changed during install or uninstall.' }
}
[ordered]@{
    sourceCommit = $env:GITHUB_SHA
    version = $info.version
    upgradeFrom = $old.version
    launcherInInstall = $true
    launcherAbsentFromZip = $true
    launcherRemovedOnUninstall = $true
    separateUpdateCaches = $true
    bothDataDirectoriesRetained = $true
} | ConvertTo-Json | Set-Content "$output/windows-isolated-install.json" -Encoding utf8NoBOM
