<#
  Starts PawCare, the pet first-aid app built on Continuum.

  Usage (PowerShell, from this folder):
      .\start.ps1
      .\start.ps1 -Port 5071
      .\start.ps1 -Reconfigure        # forget the saved key and pipeline, ask again

  The first start asks for your Continuum API key (input hidden) and the name
  of your photo pipeline, and keeps them in pawcare.local, which git ignores.

  If Windows refuses to run scripts, run this once in the same window:
      Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
#>
param(
    [int]$Port = 5070,
    [switch]$Reconfigure
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot
$env:PYTHONIOENCODING = "utf-8"
$env:PAWCARE_PORT = "$Port"

if ($Reconfigure -and (Test-Path "pawcare.local")) {
    Remove-Item "pawcare.local"
    Write-Host "Saved settings removed; you will be asked again." -ForegroundColor Yellow
}

$python = $null
if (Get-Command py -ErrorAction SilentlyContinue) { $python = @("py", "-3") }
elseif (Get-Command python -ErrorAction SilentlyContinue) { $python = @("python") }
else {
    Write-Host "Python 3 was not found. Install it from https://www.python.org/downloads/ (tick 'Add python.exe to PATH') and run this again." -ForegroundColor Red
    exit 1
}

$exe = $python[0]
$pre = @()
if ($python.Count -gt 1) { $pre = $python[1..($python.Count - 1)] }
& $exe @pre server.py
exit $LASTEXITCODE
