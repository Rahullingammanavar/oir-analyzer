@echo off
title OIR File Analyzer - Launcher
echo.
echo  ==========================================
echo   OIR File Analyzer - Starting up...
echo  ==========================================
echo.

set "MVN=mvn"
cd /d "D:\phase-01\oir-analyzer"

echo  Working dir: %CD%
echo  Maven: %MVN%
echo.
echo  Launching app... (this window will stay open for logs)
echo.

"%MVN%" javafx:run

echo.
echo  App closed. Press any key to exit.
pause
