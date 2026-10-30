param([switch]$Tests)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$out = Join-Path $root 'build/cpp'
$deps = Join-Path $out 'deps/nlohmann'
New-Item -ItemType Directory -Force -Path $deps | Out-Null
$json = Join-Path $deps 'json.hpp'
if (-not (Test-Path $json)) {
    Invoke-WebRequest 'https://raw.githubusercontent.com/nlohmann/json/v3.11.3/single_include/nlohmann/json.hpp' -OutFile $json -UseBasicParsing
}
& (Join-Path $root 'api/generate.ps1')
$sources = @('example')
if ($Tests) { $sources += 'smoke' }
$compiler = Get-Command cl.exe -ErrorAction SilentlyContinue
if (-not $compiler) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    if (Test-Path $vswhere) {
        $vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if ($vs) {
            $vcvars = Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat'
            $envLines = & cmd /c "call `"$vcvars`" >nul && set"
            foreach ($line in $envLines) {
                if ($line -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
            }
            $compiler = Get-Command cl.exe -ErrorAction SilentlyContinue
        }
    }
}
foreach ($name in $sources) {
    $source = if ($name -eq 'smoke') { Join-Path $PSScriptRoot 'tests/smoke.cpp' } else { Join-Path $PSScriptRoot 'example.cpp' }
    $exe = Join-Path $out "$name.exe"
    if ($compiler) {
        & cl.exe /nologo /std:c++17 /EHsc /utf-8 /W4 /MT "/I$PSScriptRoot/include" "/I$out/deps" $source "/Fe:$exe" "/Fo:$out/$name.obj" /link winhttp.lib
    } else {
        & g++ -std=c++17 -Wall -Wextra -static-libgcc -static-libstdc++ "-I$PSScriptRoot/include" "-I$out/deps" $source -o $exe -lwinhttp
    }
    if ($LASTEXITCODE -ne 0) { throw "C++ compilation failed: $name" }
}
