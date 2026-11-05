!ifndef BUILD_UNINSTALLER
!macro customHeader
Var StudioState
Var StudioStatus
Var StudioOutput
Var StudioRequested
Var StudioDestination
Var StudioFailure
Var StudioPrograms
Var StudioMode

Function StudioRun
  Push $0
  Push $1
  nsExec::ExecToStack /TIMEOUT=180000 '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\studio-migration.ps1" -Mode "$StudioMode" -StateFile "$StudioState" -Destination "$INSTDIR" -Requested "$StudioRequested" -InstallKey "${INSTALL_REGISTRY_KEY}" -UninstallKey "${UNINSTALL_REGISTRY_KEY}" -AppGuid "${APP_GUID}" -Version "${VERSION}" -InstallMode "$installMode" -ProgramsRoot "$StudioPrograms" -Inventory "$PLUGINSDIR\studio-payload.json" -UninstallerTemplate "$PLUGINSDIR\studio-uninstaller.exe" -UninstallerIcon "$PLUGINSDIR\studio-uninstaller.ico"'
  Pop $StudioStatus
  Pop $StudioOutput
  Pop $1
  Pop $0
FunctionEnd

Function StudioRollback
  StrCpy $StudioMode "Rollback"
  Call StudioRun
  ${If} $StudioStatus != 0
    MessageBox MB_OK|MB_ICONSTOP "程序回退需要人工处理。已创建的原程序备份将保留，账号数据目录未移动。$\r$\n$StudioOutput" /SD IDOK
  ${EndIf}
FunctionEnd

Function StudioRequireSuccess
  ${If} $StudioStatus != 0
    DetailPrint "$StudioOutput"
    ReadINIStr $StudioFailure "$StudioState.error.ini" "migration" "Message"
    ${If} $StudioFailure == ""
      StrCpy $StudioFailure "无法完成安装校验。"
    ${EndIf}
    Call StudioRollback
    MessageBox MB_OK|MB_ICONSTOP "安装未完成，请先退出应用并检查目标目录后重试。$\r$\n$StudioFailure" /SD IDOK
    SetErrorLevel 3
    Quit
  ${EndIf}
FunctionEnd

Function StudioReadDestination
  ReadINIStr $StudioDestination "$StudioState.ini" "migration" "Destination"
  ${If} $StudioDestination == ""
    SetErrorLevel 3
    Quit
  ${EndIf}
  StrCpy $INSTDIR $StudioDestination
FunctionEnd

Function StudioPreparePage
  StrCpy $StudioRequested $INSTDIR
  Call instFilesPre
  StrCpy $StudioMode "Prepare"
  Call StudioRun
  Call StudioRequireSuccess
  Call StudioReadDestination
FunctionEnd

!macroend

!macro customInit
  InitPluginsDir
  File "/oname=$PLUGINSDIR\studio-migration.ps1" "${BUILD_RESOURCES_DIR}\windows-migration.ps1"
  File "/oname=$PLUGINSDIR\studio-payload.json" "${BUILD_RESOURCES_DIR}\windows-payload.generated.json"
  File "/oname=$PLUGINSDIR\studio-uninstaller.exe" "${UNINSTALLER_OUT_FILE}"
  !ifdef UNINSTALLER_ICON
    File "/oname=$PLUGINSDIR\studio-uninstaller.ico" "${UNINSTALLER_ICON}"
  !endif
  Push $0
  Push $1
  Push $2
  StrCpy $StudioPrograms "$LOCALAPPDATA\Programs"
  StrCpy $2 0
  System::Call 'SHELL32::SHGetKnownFolderPath(g "${FOLDERID_UserProgramFiles}", i 0, p 0, *p .r2)i.r1'
  ${If} $1 == 0
    System::Call 'KERNEL32::lstrcpynW(w .r0, p r2, i ${NSIS_MAX_STRLEN})p'
    StrCpy $StudioPrograms $0
  ${EndIf}
  ${If} $2 != 0
    System::Call 'OLE32::CoTaskMemFree(p r2)'
  ${EndIf}
  Pop $2
  Pop $1
  Pop $0
  StrCpy $StudioState "$PLUGINSDIR\studio-state.json"
  StrCpy $StudioRequested $INSTDIR
  StrCpy $StudioMode "Plan"
  Call StudioRun
  Call StudioRequireSuccess
  Call StudioReadDestination
  ${If} ${Silent}
    StrCpy $StudioRequested $INSTDIR
    StrCpy $StudioMode "Prepare"
    Call StudioRun
    Call StudioRequireSuccess
    Call StudioReadDestination
  ${EndIf}
!macroend

!macro customPageAfterChangeDir
  !undef MUI_PAGE_CUSTOMFUNCTION_PRE
  !define MUI_PAGE_CUSTOMFUNCTION_PRE StudioPreparePage
!macroend

!macro customCheckAppRunning
  StrCpy $StudioMode "Wait"
  Call StudioRun
  Call StudioRequireSuccess
!macroend

!macro customInstall
  StrCpy $StudioMode "Commit"
  Call StudioRun
  Call StudioRequireSuccess
!macroend

Function .onInstFailed
  Call StudioRollback
FunctionEnd
Function .onGUIEnd
  Call StudioRollback
FunctionEnd
!endif
