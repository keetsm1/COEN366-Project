param(
    [Parameter(Mandatory=$true)][string]$Command,
    [string]$Arg1,
    [string]$Config = 'config.properties'
)

if (!(Test-Path out)) { Write-Error "Build output 'out' not found. Run ./build.ps1 first."; exit 1 }
Write-Host "Running peer: $Command $Arg1 (config=$Config)"
java -cp out "-Dconfig=$Config" p2p.PeerMain $Command $Arg1