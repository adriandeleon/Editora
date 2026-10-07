@echo off
rem A small DOS batch sample for syntax highlighting.
rem Covers variables, delayed expansion, arithmetic, IF/ELSE, FOR loops,
rem labels with CALL, and argument modifiers.
setlocal EnableDelayedExpansion

set "NAME=%~1"
if "%NAME%"=="" set "NAME=world"
set /a MAX_ITEMS=0x40
set /a NORM2=(3*3)+(4*4)

echo Hello, %NAME%. The shelf holds up to %MAX_ITEMS% items.

:: A second comment style: count the items.
set COUNT=0
for %%I in (hammer anvil saw) do (
    set /a COUNT+=1
    echo   !COUNT!. %%I
)

for /l %%N in (1,1,3) do call :square %%N

if %NORM2% GEQ 25 (
    echo norm2 is %NORM2%
) else (
    echo norm2 is small
)

if exist "%~dp0sample.bat" echo Running from %~dp0
if errorlevel 1 goto :fail
goto :done

:square
set /a SQ=%1*%1
echo %1 squared is %SQ%
exit /b 0

:fail
echo Something went wrong. 1>&2
exit /b 1

:done
endlocal
