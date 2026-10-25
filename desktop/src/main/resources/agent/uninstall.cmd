@echo off
rem Deck Probe: removes the inventory agent from this PC (run as administrator).
schtasks /delete /f /tn "Deck Probe (demarrage)" >nul 2>&1
schtasks /delete /f /tn "Deck Probe (quotidien)" >nul 2>&1
rmdir /s /q "%ProgramData%\DeckProbe" 2>nul
echo Deck Probe desinstalle.
