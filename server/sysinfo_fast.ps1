# 远程控制 - 轻量系统信息采集 (UTF-8 BOM) 快速版, 适合高频轮询
# 减少 CIM 调用, 用 WMI 替代 Get-CimInstance 提高响应速度
$ErrorActionPreference = 'SilentlyContinue'

# WMI 一次性查询 (比 Get-CimInstance 快)
$os = ([wmi]"\\.\root\cimv2:Win32_OperatingSystem=@")
$cs = ([wmi]"\\.\root\cimv2:Win32_ComputerSystem=@")
$cpu = Get-WmiObject -Class Win32_Processor | Select-Object -First 1
$cpuLoad = (Get-WmiObject -Class Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
$uptime = (Get-Date) - [Management.ManagementDateTimeConverter]::ToDateTime($os.LastBootUpTime)

# 磁盘 (同时查)
$disks = @()
Get-WmiObject -Class Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object {
    $disks += [PSCustomObject]@{
        Drive = $_.DeviceID
        Total = [long]$_.Size
        Free  = [long]$_.FreeSpace
    }
}

$obj = @{
    ok      = $true
    osName  = "$($os.Caption) $($os.Version)"
    hostName = $cs.Name
    cpuName = "$($cpu.Name)".Trim()
    cpuLoad = [math]::Round($cpuLoad, 1)
    totalMem = [long]$cs.TotalPhysicalMemory
    freeMem  = [long]$os.FreePhysicalMemory * 1KB
    upDays   = [math]::Floor($uptime.TotalDays)
    upHours  = [math]::Floor($uptime.Hours)
    upMins   = [math]::Floor($uptime.Minutes)
    disks    = $disks
    ts       = [long](Get-Date -UFormat %s)  # 服务端时间戳 (毫秒)
}
$obj | ConvertTo-Json -Depth 4 -Compress
