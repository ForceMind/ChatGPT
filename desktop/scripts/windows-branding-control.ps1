param([string]$Operation,[string]$File,[string]$Directory,[string]$Data,[string]$Config,[string]$Output,[string]$Evidence,[string]$Executable='ChatGPT Profiles.exe',[switch]$FailCommit)
$ErrorActionPreference='Stop'
if($env:GITHUB_ACTIONS -ne 'true'){throw 'Disposable CI only'}
switch($Operation){
  Guard {if(@(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue|Where-Object {$_.DisplayName -eq 'ChatGPT Studio'}).Count){throw 'Refuse pre-existing installation registry'}}
  Install {
    $env:STUDIO_MIGRATION_EVIDENCE=$Evidence
    if($FailCommit){$env:STUDIO_MIGRATION_FAIL_AFTER_INSTALL='1'}else{Remove-Item Env:STUDIO_MIGRATION_FAIL_AFTER_INSTALL -ErrorAction SilentlyContinue}
    $arguments=@('/S');if($Directory){$arguments+=('/D='+$Directory)}
    $p=Start-Process -FilePath $File -ArgumentList $arguments -PassThru
    if(!$p.WaitForExit(180000)){$p.Kill();throw 'Installer timed out'}
    @{exitCode=$p.ExitCode}|ConvertTo-Json|Set-Content -Encoding utf8 $Output
  }
  Uninstall {
    $uninstaller=Get-ChildItem -LiteralPath $Directory -Filter 'Uninstall*.exe'|Select-Object -First 1
    if(!$uninstaller){throw 'Missing uninstaller'}
    $p=Start-Process $uninstaller.FullName -ArgumentList '/S' -PassThru
    if(!$p.WaitForExit(120000) -or $p.ExitCode -ne 0){throw 'Uninstall failed'}
    $end=[DateTime]::UtcNow.AddSeconds(30)
    $pending=$true
    do {
      try {
        if(Test-Path -LiteralPath $Directory){[void](Get-ChildItem -LiteralPath $Directory -Force)}
        $pending=(Test-Path -LiteralPath (Join-Path $Directory 'ChatGPT Studio.exe')) -or (Test-Path -LiteralPath (Join-Path $Directory 'ChatGPT Profiles.exe'))
      }catch [UnauthorizedAccessException]{$pending=$true}
      if($pending){Start-Sleep -Milliseconds 200}
    }while($pending -and [DateTime]::UtcNow -lt $end)
    if($pending){throw 'Uninstaller did not finish removing the program within 30 seconds'}
  }
  Inspect {
    $exe=Join-Path $Directory 'ChatGPT Studio.exe';if(!(Test-Path -LiteralPath $exe)){throw 'New executable missing'}
    if(Test-Path -LiteralPath (Join-Path $Directory 'ChatGPT Profiles.exe')){throw 'Legacy exe survived'}
    $info=[Diagnostics.FileVersionInfo]::GetVersionInfo($exe);if($info.ProductName -ne 'ChatGPT Studio'){throw 'Wrong product'}
    $items=@(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue|Where-Object {$_.DisplayName -eq 'ChatGPT Studio'})
    if($items.Count -ne 1){throw 'Expected exactly one owned uninstall entry'}
    if($items[0].UninstallString -notlike ('*'+$Directory+'\Uninstall ChatGPT Studio.exe*')){throw 'Uninstall target not updated'}
    $installKey=$items[0].PSChildName -replace '\.nsis$',''
    $location=(Get-ItemProperty -LiteralPath ('HKCU:\Software\'+$installKey)).InstallLocation
    if([IO.Path]::GetFullPath($location).TrimEnd('\') -ne [IO.Path]::GetFullPath($Directory).TrimEnd('\')){throw 'Automatic update installation registry not updated'}
    $shell=New-Object -ComObject WScript.Shell;$links=@()
    foreach($folder in @([Environment]::GetFolderPath('DesktopDirectory'),[Environment]::GetFolderPath('Programs'))){$path=Join-Path $folder 'ChatGPT Studio.lnk';if(!(Test-Path -LiteralPath $path)){throw 'Shortcut missing'};$link=$shell.CreateShortcut($path);if($link.TargetPath -ne $exe){throw 'Shortcut still targets old program'};$links+=@{path=$path;target=$link.TargetPath}}
    @{productName=$info.ProductName;productVersion=$info.ProductVersion;installLocation=$location;uninstaller=$items[0].UninstallString;links=$links}|ConvertTo-Json -Depth 6|Set-Content -Encoding utf8 $Output
  }
  Pin {
    $folder=Join-Path $env:APPDATA 'Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar';[void][IO.Directory]::CreateDirectory($folder)
    if(Test-Path -LiteralPath $File){throw 'Synthetic pin already exists'}
    if($Executable -notin @('ChatGPT Profiles.exe','ChatGPT Studio.exe')){throw 'Unexpected synthetic executable'}
    $link=(New-Object -ComObject WScript.Shell).CreateShortcut($File);$link.TargetPath=Join-Path $Directory $Executable;$link.WorkingDirectory=$Directory;$link.IconLocation=$link.TargetPath+',0';$link.Description='Owned CI pinned shortcut';$link.Save()
  }
  CheckPin {
    $link=(New-Object -ComObject WScript.Shell).CreateShortcut($File);if($link.TargetPath -ne (Join-Path $Directory 'ChatGPT Studio.exe')){throw 'Pinned shortcut target not updated'}
    if($link.WorkingDirectory -ne $Directory -or $link.IconLocation -ne ((Join-Path $Directory 'ChatGPT Studio.exe')+',0')){throw 'Pinned shortcut directory or icon not updated'}
  }
  Tls {
    $c=Get-Content -Raw -Encoding utf8 $Config|ConvertFrom-Json
    $rsa=[Security.Cryptography.RSACng]::new(2048)
    try {
      $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=chatgpt.com',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
      $cert=$request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5),[DateTimeOffset]::UtcNow.AddHours(6))
      try {
        [IO.File]::WriteAllBytes($c.cer,$cert.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert))
        $parameters=$rsa.ExportParameters($true);$jwk=@{kty='RSA'}
        foreach($pair in @{n='Modulus';e='Exponent';d='D';p='P';q='Q';dp='DP';dq='DQ';qi='InverseQ'}.GetEnumerator()){$jwk[$pair.Key]=[Convert]::ToBase64String($parameters.($pair.Value)).Replace('+','-').Replace('/','_').TrimEnd('=')}
        [IO.File]::WriteAllText($c.key,($jwk|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
        $cert.Thumbprint|Set-Content -Encoding ascii $Output
      }finally{$cert.Dispose()}
    }finally{$rsa.Dispose()}

  }
}
