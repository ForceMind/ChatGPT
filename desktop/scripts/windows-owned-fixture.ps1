# Dot-sourced only by the disposable native install/upgrade checks.
function Start-StudioOwnedFixture {
    if ($env:GITHUB_ACTIONS -ne 'true') { throw 'Isolated Windows CI only' }
    $folder = Join-Path $env:RUNNER_TEMP ('studio-owned-tls-' + [guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $folder)
    $process = $null
    try {
        $script = (Resolve-Path 'scripts/windows-install-fixture.mjs').Path
        $process = Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"' + $script + '"'), ('"' + $folder + '"')) -RedirectStandardOutput (Join-Path $folder 'stdout.log') -RedirectStandardError (Join-Path $folder 'stderr.log') -PassThru
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        while (!(Test-Path (Join-Path $folder 'ready.json')) -and !$process.HasExited -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
        if (!(Test-Path (Join-Path $folder 'ready.json'))) { throw ('Owned TLS fixture failed: ' + (Get-Content (Join-Path $folder 'stderr.log') -Raw)) }
        $ready = Get-Content (Join-Path $folder 'ready.json') -Raw | ConvertFrom-Json
        return @{ process = $process; folder = $folder; arguments = $ready.arguments }
    } catch {
        if ($process -and !$process.HasExited) { $process.Kill($true) }
        Remove-Item -LiteralPath $folder -Recurse -Force
        throw
    }
}
function Stop-StudioOwnedFixture($Fixture, $Evidence) {
    if (!$Fixture) { return }
    if (!$Fixture.process.HasExited) { $Fixture.process.Kill($true); [void]$Fixture.process.WaitForExit(10000) }
    $requests = Join-Path $Fixture.folder 'requests.json'
    if (Test-Path $requests) { Copy-Item -LiteralPath $requests -Destination $Evidence }
    Remove-Item -LiteralPath $Fixture.folder -Recurse -Force
}
