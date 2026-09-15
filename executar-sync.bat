@echo off
cd /d "%~dp0"
if not exist logs mkdir logs
node sync.js >> logs\sync.log 2>&1
