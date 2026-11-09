param(
  [ValidateSet('Plan','Prepare','Commit','Rollback','Wait')][string]$Mode,
  [string]$StateFile,[string]$Destination,[string]$Requested,
  [string]$InstallKey,[string]$UninstallKey,[string]$AppGuid,
  [string]$Version,[string]$Inventory,[string]$UninstallerTemplate,
  [string]$UninstallerIcon,[string]$InstallMode,[string]$ProgramsRoot
)
$ErrorActionPreference = 'Stop'
$studioState = $null
function Full([string]$Path) { [IO.Path]::GetFullPath($Path).TrimEnd('\') }
function Same($A,$B) { [String]::Equals((Full $A),(Full $B),[StringComparison]::OrdinalIgnoreCase) }
function Within($A,$B) { ((Full $A)+'\').StartsWith((Full $B)+'\',[StringComparison]::OrdinalIgnoreCase) }
function Sha($Path) { $algorithm=[Security.Cryptography.SHA256]::Create();$stream=[IO.File]::OpenRead($Path);try{[BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$algorithm.Dispose()} }
function WriteJson($Path,$Value) { [IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 50),[Text.UTF8Encoding]::new($false)) }
function ReadJson($Path) { [IO.File]::ReadAllText($Path,[Text.Encoding]::UTF8)|ConvertFrom-Json }
function Prop($Object,$Name) { $p=$Object.PSObject.Properties[$Name]; if($p){$p.Value} }
# Accept the shipped alpha series and stable SemVer; reject unknown version shapes.
function StudioVersion([string]$Text) {
  if($Text.Length -gt 100 -or $Text -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-alpha\.(0|[1-9][0-9]*))?$'){throw '无法识别的程序版本。'}
  $core=[version]($Matches[1]+'.'+$Matches[2]+'.'+$Matches[3])
  $alpha=if($Matches.ContainsKey(4)){[long]$Matches[4]}else{-1L}
  return @{core=$core;alpha=$alpha}
}
function CompareStudioVersions([string]$Left,[string]$Right) {
  $a=StudioVersion $Left;$b=StudioVersion $Right
  $core=$a.core.CompareTo($b.core);if($core -ne 0){return $core}
  if($a.alpha -eq $b.alpha){return 0}
  if($a.alpha -eq -1){return 1};if($b.alpha -eq -1){return -1}
  return $a.alpha.CompareTo($b.alpha)
}
function SafePath($Path) {
  $p=Full $Path
  if($p -notmatch '^[a-zA-Z]:\\' -or $p.Length -lt 4 -or $p.Substring(2).Contains(':') -or $p -match '[\x00-\x1f]'){throw '安装路径必须是本机独立目录。'}
  foreach($protected in @((Join-Path $env:APPDATA 'ChatGPT Profiles'),(Join-Path $env:APPDATA 'ChatGPT Studio'),$env:WINDIR)) {if(Within $p $protected){throw '不能将程序安装或迁移到账号数据或系统目录。'}}
  if(Same $p $env:USERPROFILE){throw '不能使用用户主目录作为安装目录。'}
  $parent=$p
  while($parent -and $parent.Length -gt 3){if(Test-Path -LiteralPath $parent){if((Get-Item -LiteralPath $parent -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw '安装目录不能包含目录联接或符号链接。'}};$parent=[IO.Path]::GetDirectoryName($parent)}
  return $p
}
function AsarInfo($Directory) {
  $file=Join-Path $Directory 'resources\app.asar';$stream=[IO.File]::OpenRead($file)
  try {
    $reader=[IO.BinaryReader]::new($stream);if($reader.ReadUInt32() -ne 4){throw '应用包头无效。'}
    $headerSize=$reader.ReadUInt32();[void]$reader.ReadUInt32();$jsonSize=$reader.ReadUInt32()
    if($headerSize -gt 33554432 -or $jsonSize -gt $headerSize-8){throw '应用包索引无效。'}
    $header=([Text.Encoding]::UTF8.GetString($reader.ReadBytes($jsonSize))|ConvertFrom-Json)
    $entry=$header.files.'package.json';if(!$entry -or (Prop $entry 'unpacked') -or [long]$entry.size -gt 1048576){throw '应用身份记录无效。'}
    $at=8L+$headerSize+[long]$entry.offset;if($at -lt 0 -or $at+[long]$entry.size -gt $stream.Length){throw '应用身份记录越界。'}
    [void]$stream.Seek($at,[IO.SeekOrigin]::Begin);$pkg=([Text.Encoding]::UTF8.GetString($reader.ReadBytes([int]$entry.size))|ConvertFrom-Json)
    if($pkg.name -ne 'chatgpt-profiles'){throw '目录不属于可识别的ChatGPT Studio版本。'}
    [void](StudioVersion $pkg.version)
    $unpacked=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    function Walk($Node,$Prefix,$Inherited) {
      $flag=$Inherited -or [bool](Prop $Node 'unpacked');$children=Prop $Node 'files'
      if($children){foreach($child in $children.PSObject.Properties){$rel=if($Prefix){$Prefix+'/'+$child.Name}else{$child.Name};if($rel -match '(^|/)\.\.(/|$)|[\\:]'){throw '应用包路径不安全。'};Walk $child.Value $rel $flag}}
      elseif($flag){[void]$unpacked.Add(('resources/app.asar.unpacked/'+$Prefix))}
    }
    Walk $header '' $false
    return @{Package=$pkg;Unpacked=$unpacked}
  } finally {$stream.Dispose()}
}
function ProgramInfo($Directory) {
  $info=AsarInfo $Directory;$exe=Join-Path $Directory 'ChatGPT Profiles.exe';if(!(Test-Path -LiteralPath $exe)){$exe=Join-Path $Directory 'ChatGPT Studio.exe'}
  if(!(Test-Path -LiteralPath $exe) -or [Diagnostics.FileVersionInfo]::GetVersionInfo($exe).ProductName -notin @('ChatGPT Studio','ChatGPT Profiles')){throw '旧程序身份不符，未迁移。'}
  return $info
}
function Tree($Directory,$Info) {
  $known=@('ChatGPT Profiles.exe','ChatGPT Studio.exe','Uninstall ChatGPT Profiles.exe','Uninstall ChatGPT Studio.exe','LICENSE.electron.txt','LICENSES.chromium.html','chrome_100_percent.pak','chrome_200_percent.pak','d3dcompiler_47.dll','dxcompiler.dll','dxil.dll','ffmpeg.dll','icudtl.dat','libEGL.dll','libGLESv2.dll','resources.pak','snapshot_blob.bin','v8_context_snapshot.bin','vk_swiftshader.dll','vk_swiftshader_icd.json','vulkan-1.dll','chrome_crashpad_handler.exe','crashpad_handler.exe','uninstallerIcon.ico','resources/app.asar','resources/app-update.yml','resources/elevate.exe','resources/LICENSE.txt')
  $result=@()
  foreach($item in Get-ChildItem -LiteralPath $Directory -Recurse -Force){
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw '旧目录存在链接，未迁移。'}
    if($item.PSIsContainer){continue};$relative=$item.FullName.Substring($Directory.Length+1).Replace('\','/')
    if($relative -notin $known -and $relative -notin @($script:studioState.newFiles|ForEach-Object {$_.path}) -and $relative -notmatch '^locales/[a-zA-Z0-9_-]+\.pak$' -and !$Info.Unpacked.Contains($relative)){throw ('旧程序目录含未知文件，请先妥善移出后重试：'+$relative)}
    $result+=@{path=$relative;sha256=(Sha $item.FullName)}
  };return ,$result
}
$registry=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser,[Microsoft.Win32.RegistryView]::Registry64)
function RegistrySnapshot($Key) {
  $k=$registry.OpenSubKey($Key);$values=@();if($k){try{foreach($n in $k.GetValueNames()){$values+=@{name=$n;kind=[int]$k.GetValueKind($n);value=$k.GetValue($n,$null,[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)}}}finally{$k.Dispose()}}
  return @{path=$Key;existed=[bool]$k;values=$values}
}
function RegistryValue($Key,$Name) {$k=$registry.OpenSubKey($Key);try{if($k){return $k.GetValue($Name)}}finally{if($k){$k.Dispose()}}}
function SaveState {WriteJson $StateFile $script:studioState;if($script:studioState.backup -and (Test-Path -LiteralPath $script:studioState.backup)){WriteJson (Join-Path $script:studioState.backup 'migration-journal.json') $script:studioState}}
function Proof {
  if($env:GITHUB_ACTIONS -eq 'true' -and $env:STUDIO_MIGRATION_EVIDENCE -and $env:RUNNER_TEMP -and (Within $env:STUDIO_MIGRATION_EVIDENCE $env:RUNNER_TEMP)){WriteJson $env:STUDIO_MIGRATION_EVIDENCE $script:studioState}
}
function LinkPaths {
  @([Environment]::GetFolderPath('DesktopDirectory'),[Environment]::GetFolderPath('Programs'),(Join-Path $env:APPDATA 'Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar'))
}
function SnapshotLinks($Source,$Backup) {
  $shell=New-Object -ComObject WScript.Shell;$links=@();$index=0
  foreach($folder in LinkPaths){if(!(Test-Path -LiteralPath $folder)){continue};foreach($file in Get-ChildItem -LiteralPath $folder -Filter '*.lnk' -Recurse -File){$link=$shell.CreateShortcut($file.FullName);if($link.TargetPath -and ((Same $link.TargetPath (Join-Path $Source 'ChatGPT Profiles.exe')) -or (Same $link.TargetPath (Join-Path $Source 'ChatGPT Studio.exe')))){$copy=Join-Path $Backup ('link-'+$index+'.bin');Copy-Item -LiteralPath $file.FullName -Destination $copy;$links+=@{path=$file.FullName;copy=$copy;sha256=(Sha $file.FullName);pinned=$file.FullName.Contains('\User Pinned\')};$index++}}};return ,$links
}
function NestedLegacyTarget($Source,$Target) {
  return (Same $Target (Join-Path $ProgramsRoot 'ChatGPT Studio')) -and (Same $Source (Join-Path $Target 'ChatGPT Profiles'))
}
function BackupParent($Source,$Target) {
  if(NestedLegacyTarget $Source $Target){return [IO.Path]::GetDirectoryName($Target)}
  return [IO.Path]::GetDirectoryName($Source)
}
function Plan {
  if($InstallMode -ne 'CurrentUser'){throw '此候选仅支持当前用户安装。'}
  $g=[Guid]$AppGuid;$guidPattern='\{?'+[regex]::Escape($g.ToString())+'\}?'
  if($InstallKey -notmatch ('^Software\\'+$guidPattern+'$') -or $UninstallKey -notmatch ('^Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\'+$guidPattern+'(?:\.nsis)?$')){throw '安装注册身份无效。'}
  $source=RegistryValue $InstallKey 'InstallLocation';$uninstall=RegistryValue $UninstallKey 'UninstallString'
  if(!$source -and $uninstall -match '^"([^"\r\n]+)"(?:\s+/currentuser)?\s*$'){$source=[IO.Path]::GetDirectoryName($Matches[1])}
  $dest=SafePath $Destination;$active=$false
  if($source -and (Test-Path -LiteralPath $source)){
    $source=SafePath $source;$info=ProgramInfo $source
    if($uninstall -notmatch '^"([^"\r\n]+)"(?:\s+/currentuser)?\s*$' -or !(Same ([IO.Path]::GetDirectoryName($Matches[1])) $source) -or [IO.Path]::GetFileName($Matches[1]) -notin @('Uninstall ChatGPT Profiles.exe','Uninstall ChatGPT Studio.exe')){throw '卸载记录与旧程序目录不一致。'}
    if((CompareStudioVersions $info.Package.version $Version) -gt 0){throw '不能以旧版本覆盖较新程序。'}
    $oldDefault=Join-Path $ProgramsRoot 'ChatGPT Profiles'
    $modernDefault=Join-Path $ProgramsRoot 'ChatGPT Studio'
    $nestedDefault=Join-Path $modernDefault 'ChatGPT Profiles'
    if((Same $source $oldDefault) -and ((Same $dest $source) -or ($Requested -and (Same $Requested $source)))){$dest=Join-Path ([IO.Path]::GetDirectoryName($source)) 'ChatGPT Studio'}
    elseif((Same $source $nestedDefault) -and ((Same $dest $source) -or (Same $dest $modernDefault))){$dest=$modernDefault}
    elseif($Requested -and (Same $Requested $source)){$dest=$source}
    if(!(Same $source $dest) -and ((Within $source $dest) -or (Within $dest $source)) -and !(NestedLegacyTarget $source $dest)){throw '新旧程序目录不能相互包含。'}
    $active=$true
  }elseif($source -and (Same $source (Join-Path $ProgramsRoot 'ChatGPT Profiles')) -and (Same $dest $source)){$dest=Join-Path $ProgramsRoot 'ChatGPT Studio'}
  if((Test-Path -LiteralPath $dest) -and (!$active -or !(Same $dest $source))){
    $entries=@(Get-ChildItem -LiteralPath $dest -Force)
    $ownedNested=$active -and (NestedLegacyTarget $source $dest) -and $entries.Count -eq 1 -and $entries[0].PSIsContainer -and (Same $entries[0].FullName $source)
    if($entries.Count -gt 0 -and !$ownedNested){throw '目标目录已有内容，未覆盖；请选择独立空目录。'}
  }
  return @{schema=1;source=$source;destination=$dest;active=$active;prepared=$false;committed=$false;rolledBack=$false;backup=$null;files=@();links=@();registry=@();newFiles=@();error=$null;version=$Version;installKey=$InstallKey;uninstallKey=$UninstallKey}
}
function WaitForApp {
  $end=[DateTime]::UtcNow.AddSeconds(30)
  do{$running=@(Get-Process -Name 'ChatGPT Profiles','ChatGPT Studio' -ErrorAction SilentlyContinue|Where-Object {try{$p=$_.Path; $p -and [Diagnostics.FileVersionInfo]::GetVersionInfo($p).ProductName -in @('ChatGPT Studio','ChatGPT Profiles')}catch{$false}});if(!$running.Count){return};Start-Sleep -Milliseconds 250}while([DateTime]::UtcNow -lt  $end)
  throw '请先从菜单真正退出ChatGPT Studio，再重新安装；未强制终止任何进程。'
}
function Prepare {
  WaitForApp;$script:studioState=Plan
  $payload=ReadJson $Inventory;if($payload.version -ne $Version){throw '安装程序清单版本不一致。'}
  $script:studioState.newFiles=@($payload.files)+@(@{path='Uninstall ChatGPT Studio.exe';sha256=(Sha $UninstallerTemplate)})
  if($UninstallerIcon -and (Test-Path -LiteralPath $UninstallerIcon)){$script:studioState.newFiles+=@{path='uninstallerIcon.ico';sha256=(Sha $UninstallerIcon)}}
  $shell=New-Object -ComObject WScript.Shell
  foreach($folder in LinkPaths){$linkFile=Join-Path $folder 'ChatGPT Studio.lnk';if(!(Test-Path -LiteralPath $linkFile)){continue};$link=$shell.CreateShortcut($linkFile);if(!$script:studioState.active -or !$link.TargetPath -or (!(Same $link.TargetPath (Join-Path $script:studioState.source 'ChatGPT Profiles.exe')) -and !(Same $link.TargetPath (Join-Path $script:studioState.source 'ChatGPT Studio.exe')))){throw '已有同名快捷方式不属于当前安装，未覆盖。'}}
  $script:studioState.registry=@((RegistrySnapshot $InstallKey),(RegistrySnapshot $UninstallKey))
  if($script:studioState.active){
    $info=ProgramInfo $script:studioState.source;$script:studioState.files=Tree $script:studioState.source $info
    $backup=Join-Path (BackupParent $script:studioState.source $script:studioState.destination) ('.ChatGPTStudio-rollback-'+[Guid]::NewGuid().ToString('N'));[void](New-Item -ItemType Directory -Path $backup);[IO.File]::SetAttributes($backup,([IO.File]::GetAttributes($backup) -bor [IO.FileAttributes]::Hidden));$script:studioState.backup=$backup
    SaveState
    foreach($item in $script:studioState.files){$target=Join-Path $backup ('program\'+$item.path.Replace('/','\'));if($target.Length -gt 248){throw '备份路径过长，未移除旧程序。'};[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target));Copy-Item -LiteralPath (Join-Path $script:studioState.source $item.path) -Destination $target;if((Sha $target) -ne $item.sha256 -or (Sha (Join-Path $script:studioState.source $item.path)) -ne $item.sha256){throw '旧程序在备份时发生变化，未移除。'}}
    $script:studioState.links=SnapshotLinks $script:studioState.source $backup
  }
  $script:studioState.prepared=$true;SaveState
}
function RestoreRegistry($Snapshot) {
  $k=$registry.CreateSubKey($Snapshot.path)
  $known=@('InstallLocation','KeepShortcuts','ShortcutName','MenuDirectory','DisplayName','UninstallString','QuietUninstallString','DisplayVersion','DisplayIcon','Publisher','Comments','HelpLink','URLInfoAbout','URLUpdateInfo','Readme','NoModify','NoRepair','EstimatedSize','InstallDate')
  try {
    $original=@($Snapshot.values|ForEach-Object {$_.name})
    foreach($name in $k.GetValueNames()){if($name -in $known -and $name -notin $original){$k.DeleteValue($name)}}
    foreach($value in $Snapshot.values){$kind=[Microsoft.Win32.RegistryValueKind]$value.kind;$data=$value.value;if($kind -eq [Microsoft.Win32.RegistryValueKind]::Binary){$data=[byte[]]$data};if($kind -eq [Microsoft.Win32.RegistryValueKind]::MultiString){$data=[string[]]$data};if($kind -eq [Microsoft.Win32.RegistryValueKind]::DWord){$data=[int]$data};if($kind -eq [Microsoft.Win32.RegistryValueKind]::QWord){$data=[long]$data};$k.SetValue($value.name,$data,$kind)}
    $empty=($k.GetValueNames().Length -eq 0 -and $k.GetSubKeyNames().Length -eq 0)
  }finally{$k.Dispose()}
  if(!$Snapshot.existed -and $empty){$registry.DeleteSubKey($Snapshot.path,$false)}
}

function RemoveBackup {
  if(!$script:studioState.backup){return}
  $backup=SafePath $script:studioState.backup
  if([IO.Path]::GetFileName($backup) -notmatch '^\.ChatGPTStudio-rollback-[a-f0-9]{32}$' -or !(Same ([IO.Path]::GetDirectoryName($backup)) (BackupParent $script:studioState.source $script:studioState.destination))){throw '备份归属不明确，未删除。'}
  if(Test-Path -LiteralPath $backup){Remove-Item -LiteralPath $backup -Recurse -Force}
}
function Rollback {
  if(!(Test-Path -LiteralPath $StateFile)){return};$script:studioState=ReadJson $StateFile
  if($script:studioState.committed -or $script:studioState.rolledBack){return}
  if($script:studioState.prepared){
    $dest=SafePath $Destination
    foreach($item in $script:studioState.newFiles){$target=Join-Path $dest $item.path;if(Test-Path -LiteralPath $target){[void](SafePath ([IO.Path]::GetDirectoryName($target)));if((Sha $target) -ne $item.sha256){throw '回退检测到程序文件被改动，备份已保留。'};Remove-Item -LiteralPath $target -Force}}
    if($script:studioState.active -and !(Same $script:studioState.source $dest) -and (Test-Path -LiteralPath $dest)){
      foreach($directory in @(Get-ChildItem -LiteralPath $dest -Recurse -Directory -Force|Sort-Object {$_.FullName.Length} -Descending)){
        if(!(Within $directory.FullName $script:studioState.source)){
          [void](SafePath $directory.FullName)
          if(@(Get-ChildItem -LiteralPath $directory.FullName -Force).Count -eq 0){[IO.Directory]::Delete($directory.FullName,$false)}
        }
      }
    }
    if($script:studioState.active){
      $source=SafePath $script:studioState.source
      foreach($item in $script:studioState.files){$target=Join-Path $source $item.path;if((Test-Path -LiteralPath $target) -and (Sha $target) -ne $item.sha256){throw '旧位置已有不同内容，备份已保留。'};[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target));Copy-Item -LiteralPath (Join-Path $script:studioState.backup ('program\'+$item.path)) -Destination $target -Force}
      foreach($link in $script:studioState.links){[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($link.path));Copy-Item -LiteralPath $link.copy -Destination $link.path -Force}
    }
    foreach($snap in $script:studioState.registry){RestoreRegistry $snap}
    foreach($folder in LinkPaths){$file=Join-Path $folder 'ChatGPT Studio.lnk';if(!(Test-Path -LiteralPath $file)){continue};$prior=@($script:studioState.links|Where-Object {$_.path -eq $file});if(!$prior.Count){$link=(New-Object -ComObject WScript.Shell).CreateShortcut($file);if($link.TargetPath -and (Same $link.TargetPath (Join-Path $dest 'ChatGPT Studio.exe'))){Remove-Item -LiteralPath $file -Force}}}
  }
  $script:studioState.rolledBack=$true;SaveState;Proof;RemoveBackup
}

function Commit {
  $script:studioState=ReadJson $StateFile;if(!$script:studioState.prepared){throw '缺少安装准备记录。'};$dest=SafePath $Destination
  foreach($item in $script:studioState.newFiles){$target=Join-Path $dest $item.path;if(!(Test-Path -LiteralPath $target) -or (Sha $target) -ne $item.sha256){throw '已安装程序与校验清单不一致，正在回退。'}}
  $info=ProgramInfo $dest;if($info.Package.version -ne $Version -or !(Test-Path -LiteralPath (Join-Path $dest 'ChatGPT Studio.exe'))){throw '新程序名称或版本不一致。'}
  if(!(Same (RegistryValue $InstallKey 'InstallLocation') $dest)){throw '新安装记录未正确更新。'}
  if($script:studioState.active -and !(Same $dest $script:studioState.source) -and (Test-Path -LiteralPath $script:studioState.source)){if(@(Get-ChildItem -LiteralPath $script:studioState.source -Recurse -File -Force).Count -gt 0){throw '旧程序仍有残留，正在回退。'};foreach($directory in @(Get-ChildItem -LiteralPath $script:studioState.source -Recurse -Directory -Force|Sort-Object {$_.FullName.Length} -Descending)){[IO.Directory]::Delete($directory.FullName,$false)};[IO.Directory]::Delete($script:studioState.source,$false)}
  if($env:GITHUB_ACTIONS -eq 'true'-and$env:STUDIO_MIGRATION_FAIL_AFTER_INSTALL -eq '1'){throw 'Synthetic commit failure after legacy uninstall'}
  $shell=New-Object -ComObject WScript.Shell
  foreach($entry in $script:studioState.links){$path=$entry.path;if(!(Test-Path -LiteralPath $path)){Copy-Item -LiteralPath $entry.copy -Destination $path};$link=$shell.CreateShortcut($path);$old=Join-Path $script:studioState.source 'ChatGPT Profiles.exe';$oldStudio=Join-Path $script:studioState.source 'ChatGPT Studio.exe';if((Same $link.TargetPath $old) -or (Same $link.TargetPath $oldStudio) -or (Same $link.TargetPath (Join-Path $dest 'ChatGPT Studio.exe'))){$link.TargetPath=Join-Path $dest 'ChatGPT Studio.exe';if($link.WorkingDirectory -and (Same $link.WorkingDirectory $script:studioState.source)){$link.WorkingDirectory=$dest};if(!$link.IconLocation -or $link.IconLocation.StartsWith($old,[StringComparison]::OrdinalIgnoreCase) -or $link.IconLocation.StartsWith($oldStudio,[StringComparison]::OrdinalIgnoreCase)){$link.IconLocation=(Join-Path $dest 'ChatGPT Studio.exe')+',0'};$link.Description='ChatGPT Studio';$link.Save();if(!$entry.pinned -and [IO.Path]::GetFileName($path) -eq 'ChatGPT Profiles.lnk'){$newLink=Join-Path ([IO.Path]::GetDirectoryName($path)) 'ChatGPT Studio.lnk';if(!(Test-Path -LiteralPath $newLink)){Move-Item -LiteralPath $path -Destination $newLink}else{$existing=$shell.CreateShortcut($newLink);if(Same $existing.TargetPath (Join-Path $dest 'ChatGPT Studio.exe')){Remove-Item -LiteralPath $path -Force}else{throw '新快捷方式已被其他内容占用，正在回退。'}}}}}
  $script:studioState.destination=$dest;$script:studioState.committed=$true;SaveState;Proof
  RemoveBackup
}
try {
  switch($Mode){
    Plan {$studioState=Plan;SaveState}
    Prepare {Prepare}
    Commit {Commit}
    Rollback {Rollback}
    Wait {WaitForApp}
  }
  if($Mode -in @('Plan','Prepare')){[IO.File]::WriteAllText(($StateFile+'.ini'),("[migration]`r`nDestination="+$studioState.destination+"`r`n"),[Text.Encoding]::Unicode)}
  exit 0
}catch {$message=$_.Exception.Message.Replace("`r",' ').Replace("`n",' ');if(!$studioState){$studioState=@{schema=1;source=$null;destination=$Destination;active=$false;prepared=$false;committed=$false;rolledBack=$false;backup=$null;files=@();links=@();registry=@();newFiles=@();error=$null;mode=$Mode}};[IO.File]::WriteAllText(($StateFile+'.error.ini'),("[migration]`r`nMessage="+$message+"`r`n"),[Text.Encoding]::Unicode);if($studioState){$studioState.error=$message;SaveState;Proof};Write-Output $message;exit 1}finally{$registry.Dispose()}
