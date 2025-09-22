@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0run.ps1" -mvnArgs "spring-boot:run"
