<#
  Fills your Continuum account with real activity so every console page has
  something to show. It only adds: nothing is deleted, and running it again
  adds more.

  Usage (PowerShell, from this folder):
      .\seed-demo-data.ps1
      .\seed-demo-data.ps1 -Email you@example.com
      .\seed-demo-data.ps1 -Email you@example.com -Pace 3     # slower, for a free provider tier

  Before running: Continuum's backend is running (http://localhost:8080) with a
  Gemini or Groq key set up. To fill the Compensation (saga) page too, start
  the backend with:  $env:CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS="true"

  If Windows refuses to run scripts, run this once in the same window:
      Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
#>
param(
    [string]$Email,
    [string]$Url = "http://localhost:8080",
    [double]$Pace = 1.0,
    [string[]]$Skip = @()
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot
$env:PYTHONIOENCODING = "utf-8"

# Python: the "py" launcher if installed, otherwise python on PATH.
$python = $null
if (Get-Command py -ErrorAction SilentlyContinue) { $python = @("py", "-3") }
elseif (Get-Command python -ErrorAction SilentlyContinue) { $python = @("python") }
else {
    Write-Host "Python 3 was not found. Install it from https://www.python.org/downloads/ (tick 'Add python.exe to PATH') and run this again." -ForegroundColor Red
    exit 1
}

# Is Continuum up?
try { Invoke-WebRequest -Uri "$Url/api/meta" -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch {
    Write-Host "Continuum is not answering at $Url. Start the backend first, then run this again." -ForegroundColor Red
    exit 1
}

$argsList = @("seed_demo_data.py", "--url", $Url, "--pace", $Pace)
if ($Email) { $argsList += @("--email", $Email) }
if ($Skip.Count -gt 0) { $argsList += "--skip"; $argsList += $Skip }

$exe = $python[0]
$pre = @()
if ($python.Count -gt 1) { $pre = $python[1..($python.Count - 1)] }
& $exe @pre @argsList
exit $LASTEXITCODE
