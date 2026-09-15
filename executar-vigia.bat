@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
node vigia.js >> logs\vigia.log 2>&1
