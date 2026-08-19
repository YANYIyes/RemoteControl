$ErrorActionPreference = 'SilentlyContinue'
Get-CimInstance Win32_Process |
  Select-Object ProcessId, Name, @{n='CMD';e={$_.CommandLine}} |
  ConvertTo-Json -Compress
