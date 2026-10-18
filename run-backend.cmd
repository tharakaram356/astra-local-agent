@echo off
set "PATH=%~dp0..\apache-maven-3.9.9\bin;%~dp0apache-maven-3.9.9\bin;%PATH%"
cd /d "%~dp0astra-backend"
mvn spring-boot:run
pause
