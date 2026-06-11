$ErrorActionPreference = "Continue"

function Test-IsAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-IsAdmin)) {
    Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        "`"$PSCommandPath`""
    )
    exit
}

$scriptDir = Split-Path -Parent $PSCommandPath
$rootDir = Split-Path -Parent $scriptDir
Set-Location $rootDir

Write-Host "ZeroPad ViGEmBus repair" -ForegroundColor Cyan
Write-Host "======================="
Write-Host
Write-Host "This repairs the virtual Xbox controller driver binding."
Write-Host "A reboot may be needed after this script."
Write-Host

Write-Host "Closing old ZeroPad receiver instances..."
Get-CimInstance Win32_Process |
    Where-Object { $_.CommandLine -like "*zeropad_receiver.py*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Write-Host
Write-Host "Current ViGEmBus service:"
sc.exe query ViGEmBus

Write-Host
Write-Host "Current ViGEmBus devices:"
$devices = @(Get-PnpDevice -PresentOnly |
    Where-Object { $_.FriendlyName -like "*Nefarius Virtual Gamepad*" })
$devices | Select-Object Status, Class, FriendlyName, InstanceId | Format-Table -AutoSize

Write-Host
Write-Host "Removing only broken ViGEmBus devices..."
foreach ($device in $devices | Where-Object { $_.Status -ne "OK" }) {
    Write-Host "Removing $($device.InstanceId)"
    pnputil /remove-device $device.InstanceId
}

Write-Host
Write-Host "Installing newest local ViGEmBus package..."
$newestInf = Get-ChildItem "C:\Windows\System32\DriverStore\FileRepository\vigembus.inf_amd64_*" `
    -Filter "ViGEmBus.inf" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($newestInf) {
    Write-Host "Using $($newestInf.FullName)"
    pnputil /add-driver $newestInf.FullName /install /reboot
} else {
    Write-Host "No local ViGEmBus INF package found." -ForegroundColor Yellow
}

Write-Host
Write-Host "Removing older ViGEmBus driver packages, after verifying provider/name..."
$tmp = New-TemporaryFile
try {
    pnputil /enum-drivers /class System /format csv /output-file $tmp | Out-Null
    $drivers = @(Import-Csv -Path $tmp -Encoding Unicode |
        Where-Object {
            $_.OriginalName -ieq "vigembus.inf" -and
            $_.ProviderName -like "Nefarius*"
        })

    if ($drivers.Count -gt 1) {
        $ranked = $drivers | Sort-Object {
            if ($_.DriverVersion -match "(\d+\.\d+\.\d+\.\d+)$") {
                [version]$matches[1]
            } else {
                [version]"0.0.0.0"
            }
        } -Descending
        $keep = $ranked | Select-Object -First 1
        Write-Host "Keeping $($keep.DriverName) $($keep.DriverVersion)"
        foreach ($old in $ranked | Where-Object { $_.DriverName -ne $keep.DriverName }) {
            Write-Host "Deleting old package $($old.DriverName) $($old.DriverVersion)"
            pnputil /delete-driver $old.DriverName /uninstall /force /reboot
        }
    } elseif ($drivers.Count -eq 1) {
        Write-Host "Only one ViGEmBus package found: $($drivers[0].DriverName) $($drivers[0].DriverVersion)"
    } else {
        Write-Host "No Nefarius ViGEmBus driver packages found in pnputil list." -ForegroundColor Yellow
    }
} finally {
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
}

Write-Host
Write-Host "Scanning devices..."
pnputil /scan-devices

Write-Host
Write-Host "ViGEmBus devices after repair:"
Get-PnpDevice -PresentOnly |
    Where-Object { $_.FriendlyName -like "*Nefarius Virtual Gamepad*" } |
    Select-Object Status, Class, FriendlyName, InstanceId |
    Format-Table -AutoSize

Write-Host
Write-Host "Testing ZeroPad Xbox backend..."
$pythonCandidates = @(
    (Join-Path $env:USERPROFILE ".zeropad-venv311\Scripts\python.exe"),
    (Join-Path $rootDir ".venv311\Scripts\python.exe"),
    (Join-Path $rootDir ".venv\Scripts\python.exe")
)
$python = $pythonCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($python) {
    & $python -B -c "from pc_receiver.zeropad_receiver import setup_logging, XboxBackend; setup_logging(False); b=XboxBackend(); b.release_all(); print('Xbox backend OK')"
} else {
    Write-Host "Python venv not found. Run pc_receiver\setup_pc_env.bat first." -ForegroundColor Yellow
}

Write-Host
Write-Host "If the test still fails, restart Windows and run ZeroPad again." -ForegroundColor Yellow
Read-Host "Press Enter to close"
