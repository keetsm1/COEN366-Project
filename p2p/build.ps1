param(
    [string]$JavaSrcPath = "src/main/java",
    [string]$OutDir = "out"
)

if (!(Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$files = Get-ChildItem -Recurse -Filter *.java -Path $JavaSrcPath | ForEach-Object { $_.FullName }
if ($files.Count -eq 0) { Write-Error "No .java files found under $JavaSrcPath"; exit 1 }
Write-Host "Compiling $($files.Count) Java files..."
javac -encoding UTF-8 -d $OutDir $files
if ($LASTEXITCODE -ne 0) { Write-Error "javac failed"; exit $LASTEXITCODE }
Write-Host "Build succeeded. Classes at: $OutDir"