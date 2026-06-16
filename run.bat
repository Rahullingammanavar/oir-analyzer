@echo off
title OIR File Analyzer - Launcher
echo.
echo  ==========================================
echo   OIR File Analyzer - Starting up...
echo  ==========================================
echo.

set "MVN=C:\Users\Rahul K Lingammanava\AppData\Local\apache-maven\apache-maven-3.9.9\bin\mvn.cmd"
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
