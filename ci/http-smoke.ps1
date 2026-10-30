param(
    [string]$Executable = 'release/YunX-Desktop/YunX-Desktop.exe',
    [ValidateSet('pan123', 'quark')][string]$LoginPlatform = 'pan123'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:YUNX_HTTP_PORT = '17891'
$env:YUNX_HTTP_TOKEN = [Guid]::NewGuid().ToString('N')
$env:YUNX_DESKTOP_DATA_DIR = Join-Path $root ('.test-data/http-' + [Guid]::NewGuid().ToString('N'))
$logDir = Join-Path $root 'build/http-smoke'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$process = Start-Process -FilePath (Resolve-Path $Executable).Path -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir 'stdout.log') -RedirectStandardError (Join-Path $logDir 'stderr.log')
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($process.HasExited) { throw "HTTP service exited with code $($process.ExitCode)" }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$env:YUNX_HTTP_PORT/api/v1/health" -Headers @{ Authorization = "Bearer $env:YUNX_HTTP_TOKEN" } -TimeoutSec 1
            if ($health.status -eq 'ok') { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'HTTP service did not become ready' }
    & (Join-Path $root 'build/cpp/smoke.exe')
    if ($LASTEXITCODE -ne 0) { throw 'C++ HTTP smoke failed' }
    $headers = @{ Authorization = "Bearer $env:YUNX_HTTP_TOKEN" }
    $base = "http://127.0.0.1:$env:YUNX_HTTP_PORT/api/v1"
    $loginBody = @{ platform = $LoginPlatform } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod "$base/auth/logins" -Method Post -Headers $headers -ContentType 'application/json' -Body $loginBody
    $windowOpened = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $process.Refresh()
        if ($process.MainWindowHandle -ne [IntPtr]::Zero) { $windowOpened = $true; break }
        if ($process.HasExited) { throw 'Service exited while opening login window' }
        Start-Sleep -Milliseconds 500
    }
    if (-not $windowOpened) { throw 'Login window did not open' }
    if ($LoginPlatform -eq 'quark') {
        $browserReady = $false
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            if ($process.HasExited) { throw 'Service exited while initializing Chromium' }
            $browserLog = Get-Content (Join-Path $logDir 'stdout.log') -Raw
            if ($browserLog -match 'JCEF initialized') { $browserReady = $true; break }
            if ($browserLog -match 'JCEF init failed after') { throw 'Chromium initialization failed' }
            Start-Sleep -Milliseconds 500
        }
        if (-not $browserReady) { throw 'Chromium initialization did not finish; check for a UI thread deadlock' }
    }
    if (-not $process.CloseMainWindow()) { throw 'Could not close login window' }
    $cancelled = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $state = Invoke-RestMethod "$base/auth/logins/$($login.id)" -Headers $headers
        if ($state.status -eq 'cancelled') { $cancelled = $true; break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $cancelled) { throw 'Closing login window did not cancel login task' }
    Invoke-RestMethod "http://127.0.0.1:$env:YUNX_HTTP_PORT/api/v1/health" -Headers @{ Authorization = "Bearer $env:YUNX_HTTP_TOKEN" } | Out-Null
    Write-Output "Login window lifecycle passed: $LoginPlatform"
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
}
