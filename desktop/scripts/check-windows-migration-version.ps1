$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Resolve-Path 'assets/windows-migration.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Migration script parse failed'}
foreach($name in @('StudioVersion','CompareStudioVersions')) {
  $functions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$false))
  if($functions.Count -ne 1){throw 'Missing unique version function'}
  . ([scriptblock]::Create($functions[0].Extent.Text))
}
$cases=@(
  @('3.0.0-alpha.24','3.0.0-alpha.9',1),
  @('2026.11.9','3.0.0-alpha.24',1),
  @('2026.11.9','2026.11.10',-1),
  @('2026.11.9','2026.12.1',-1),
  @('2026.11.9','2027.1.1',-1),
  @('2026.11.9','2026.11.9',0),
  @('3.0.0','3.0.0-alpha.24',1),
  @('2026.11.9-alpha.1','2026.11.9',-1)
)
foreach($case in $cases){$actual=[Math]::Sign((CompareStudioVersions $case[0] $case[1]));if($actual -ne $case[2]){throw ('Version ordering failed: '+$case[0]+' / '+$case[1])}}
$invalid=@('2026.011.9','2026.11.9.0','2026.11.9-beta.1','3.0.0-alpha.024','x',('9'*101))
foreach($value in $invalid){$rejected=$false;try{[void](StudioVersion $value)}catch{$rejected=$true};if(!$rejected){throw ('Invalid version accepted: '+$value)}}
Write-Output ('Migration version checks passed: '+($cases.Count+$invalid.Count))
