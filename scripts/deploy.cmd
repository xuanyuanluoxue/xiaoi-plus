@echo off
rem One-click build + install + launch for xiaoi-plus.
rem Steps: build -> verify signature -> install -> open app -> start agent service -> health.
rem On first run this creates the repo-local venv .venv (gitignored).
rem Usage: scripts\deploy.cmd [--serial 192.168.0.118:5555] [--skip-build] [--no-verify]
setlocal EnableExtensions
cd /d "%~dp0.."

set "PY=.venv\Scripts\python.exe"
if exist "%PY%" goto run

echo [deploy] creating virtual environment .venv ...
where py >nul 2>nul
if errorlevel 1 goto venv-python
py -3 -m venv .venv
goto venv-check

:venv-python
python -m venv .venv

:venv-check
if not exist "%PY%" (
    echo [deploy] venv creation failed. Install Python 3.10+ and retry.
    exit /b 1
)

:run
"%PY%" scripts\deploy.py %*
exit /b %ERRORLEVEL%
