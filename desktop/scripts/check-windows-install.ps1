$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'This installation check is restricted to an isolated CI runner.' }
$output = Join-Path (Get-Location) 'release/windows'
$installer = Get-ChildItem $output -Filter '*-setup.exe' | Select-Object -First 1
if (-not $installer) { throw 'Installer missing' }
$data = Join-Path $env:APPDATA 'ChatGPT Profiles'
if (Test-Path $data) { throw 'Refusing to touch pre-existing application data.' }
$installDir = Join-Path $env:RUNNER_TEMP ('profiles-install-' + [guid]::NewGuid().ToString('N'))
$process = $null
$result = @{ os = [Environment]::OSVersion.VersionString; installed = $false; launched = $false; normalExit = $false; uninstalled = $false; accountDataRetained = $false }
try {
    $setup = Start-Process -FilePath $installer.FullName -ArgumentList @('/S', "/D=$installDir") -PassThru
    if (-not $setup.WaitForExit(120000) -or $setup.ExitCode -ne 0) { throw 'Silent installation failed or timed out' }
    $exe = Join-Path $installDir 'ChatGPT Profiles.exe'
    if (-not (Test-Path $exe)) { throw 'Installed application is missing' }
    $result.installed = $true
    $result.productVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($exe).ProductVersion
    $result.signature = (Get-AuthenticodeSignature $exe).Status.ToString()
    $process = Start-Process -FilePath $exe -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do { Start-Sleep -Milliseconds 250; $process.Refresh() } until ($process.HasExited -or ($process.MainWindowHandle -ne 0 -and (Test-Path (Join-Path $data 'state.json'))) -or [DateTime]::UtcNow -gt $deadline)
    if ($process.HasExited -or $process.MainWindowHandle -eq 0) { throw 'Installed application did not open its native window' }
    $stateFile = Join-Path $data 'state.json'
    $state = Get-Content $stateFile -Raw -Encoding utf8 | ConvertFrom-Json
    if ($state.profiles.Count -ne 1 -or $state.tabs.Count -ne 1) { throw 'Unexpected first-launch account state' }
    $result.launched = $true; $result.accountId = $state.activeProfileId
    Start-Sleep -Seconds 6
    try {
        Add-Type -AssemblyName System.Drawing
        Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class PackageWindowCapture {
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int L,T,R,B; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out Rect r);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr c);
}
'@
        [void][PackageWindowCapture]::SetThreadDpiAwarenessContext([IntPtr](-4))
        [void][PackageWindowCapture]::SetForegroundWindow($process.MainWindowHandle)
        Start-Sleep -Milliseconds 300
        $rect = New-Object PackageWindowCapture+Rect
        if (-not [PackageWindowCapture]::GetWindowRect($process.MainWindowHandle, [ref]$rect)) { throw 'Cannot read native bounds' }
        $bitmap = New-Object Drawing.Bitmap(($rect.R - $rect.L), ($rect.B - $rect.T))
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try { $graphics.CopyFromScreen($rect.L, $rect.T, 0, 0, $bitmap.Size); $bitmap.Save((Join-Path $output 'windows-installed.png'), [Drawing.Imaging.ImageFormat]::Png) } finally { $graphics.Dispose(); $bitmap.Dispose() }
        $result.desktopCapture = 'captured'
    } catch { $result.desktopCapture = 'unavailable'; $result.captureError = $_.Exception.Message }
    if (-not $process.CloseMainWindow() -or -not $process.WaitForExit(20000)) { throw 'Native window close did not exit the application' }
    $result.normalExit = $true
    $uninstaller = Get-ChildItem $installDir -Filter 'Uninstall*.exe' | Select-Object -First 1
    if (-not $uninstaller) { throw 'Uninstaller is missing' }
    $remove = Start-Process -FilePath $uninstaller.FullName -ArgumentList '/S' -PassThru
    if (-not $remove.WaitForExit(120000)) { throw 'Uninstall timed out' }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ((Test-Path $exe) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 250 }
    if (Test-Path $exe) { throw 'Uninstaller left the application executable behind' }
    $result.uninstalled = $true
    $after = Get-Content $stateFile -Raw -Encoding utf8 | ConvertFrom-Json
    if ($after.activeProfileId -ne $state.activeProfileId) { throw 'Uninstall lost or replaced the account data' }
    $result.accountDataRetained = $true
} finally {
    if ($process -and -not $process.HasExited) { $process.Kill($true) }
    $result | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'windows-smoke.json') -Encoding utf8
}
$result | ConvertTo-Json -Depth 5
