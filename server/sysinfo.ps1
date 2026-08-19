# 远程控制 - 系统信息采集 (UTF-8 BOM) 输出 JSON 到 stdout
$ErrorActionPreference = 'SilentlyContinue'
$os = Get-CimInstance Win32_OperatingSystem
$cs = Get-CimInstance Win32_ComputerSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$uptime = (Get-Date) - $os.LastBootUpTime
$cpuLoad = Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average | Select-Object -ExpandProperty Average
$totalMem = $cs.TotalPhysicalMemory
$freeMem = $os.FreePhysicalMemory * 1KB
$disks = @(Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object {
    [PSCustomObject]@{ Drive = $_.DeviceID; Total = $_.Size; Free = $_.FreeSpace }
})
$obj = @{
    osName = "$($os.Caption) $($os.Version)"
    hostName = $cs.Name
    cpuName = "$($cpu.Name)".Trim()
    cpuLoad = [math]::Round($cpuLoad, 1)
    totalMem = $totalMem
    freeMem = [long]$freeMem
    upDays = [math]::Floor($uptime.TotalDays)
    upHours = [math]::Floor($uptime.Hours)
    upMins = [math]::Floor($uptime.Minutes)
    disks = $disks
}
$obj | ConvertTo-Json -Depth 4 -Compress
