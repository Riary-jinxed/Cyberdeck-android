@echo off
rem Deck Probe: installs the inventory agent on this PC (run as administrator, or as a
rem computer startup script by GPO). Safe to run again: it updates the files and the task.
setlocal
set "SRC=%~dp0"
set "DEST=%ProgramData%\DeckProbe"

net session >nul 2>&1
if errorlevel 1 (
    echo Deck Probe : lance ce fichier en tant qu'administrateur.
    exit /b 1
)

if not exist "%DEST%" mkdir "%DEST%"
copy /y "%SRC%deck-probe.ps1" "%DEST%\" >nul || exit /b 1
copy /y "%SRC%deck-probe.json" "%DEST%\" >nul || exit /b 1
rem The fleet key stays readable by SYSTEM and administrators only.
icacls "%DEST%" /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" >nul

set "RUN=powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"%DEST%\deck-probe.ps1\""
schtasks /create /f /tn "Deck Probe (demarrage)" /ru SYSTEM /sc onstart /delay 0003:00 /tr "%RUN%" >nul || exit /b 1
schtasks /create /f /tn "Deck Probe (quotidien)" /ru SYSTEM /sc daily /st 12:00 /tr "%RUN%" >nul || exit /b 1

echo Deck Probe installe dans %DEST% : inventaire au demarrage et chaque jour a 12:00.
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%DEST%\deck-probe.ps1"
exit /b 0
