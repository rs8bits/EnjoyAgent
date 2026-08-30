@echo off
setlocal enabledelayedexpansion
set "MVNW_VERSION=3.9.11"
set "MVNW_SHA512=03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076"
if defined MAVEN_USER_HOME (set "MVNW_USER_HOME=%MAVEN_USER_HOME%") else (set "MVNW_USER_HOME=%USERPROFILE%\.m2")
set "MVNW_INSTALL_DIR=%MVNW_USER_HOME%\wrapper\dists\apache-maven-%MVNW_VERSION%"
set "MVNW_MAVEN=%MVNW_INSTALL_DIR%\bin\mvn.cmd"
if not exist "%MVNW_MAVEN%" (
  set "MVNW_ZIP=%TEMP%\enjoy-agent-maven-%RANDOM%.zip"
  powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MVNW_VERSION%/apache-maven-%MVNW_VERSION%-bin.zip' -OutFile '%MVNW_ZIP%'; if ((Get-FileHash '%MVNW_ZIP%' -Algorithm SHA512).Hash.ToLower() -ne '%MVNW_SHA512%') { throw 'Maven archive checksum mismatch' }; New-Item -ItemType Directory -Force -Path '%MVNW_INSTALL_DIR%\..' | Out-Null; Expand-Archive -Force '%MVNW_ZIP%' '%MVNW_INSTALL_DIR%\..'"
  if errorlevel 1 exit /b 1
  del "%MVNW_ZIP%"
)
call "%MVNW_MAVEN%" %*
exit /b %ERRORLEVEL%
