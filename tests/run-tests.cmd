@echo off
rem Windows launcher: runs run-tests.ps1 without changing the PowerShell execution policy.
rem Usage: tests\run-tests.cmd [unit^|integration^|security^|lint^|local^|ui^|all^|coverage]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-tests.ps1" %*
exit /b %ERRORLEVEL%
