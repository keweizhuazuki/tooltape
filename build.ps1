$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
@('target/classes', 'target/test-classes', 'target/example-classes') | ForEach-Object { if (Test-Path $_) { Remove-Item $_ -Recurse -Force } }
New-Item -ItemType Directory -Force target/classes,target/test-classes,target/example-classes | Out-Null
$main = Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 17 -d target/classes $main
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& jar --create --date=2026-10-10T00:00:00Z --file target/tooltape.jar --main-class dev.tooltape.Main -C target/classes .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$tests = Get-ChildItem src/test/java -Recurse -Filter *.java | Where-Object Name -NotLike *JUnitTest.java | ForEach-Object FullName
& javac --release 17 -cp target/classes -d target/test-classes $tests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -ea -cp 'target/classes;target/test-classes' dev.tooltape.CoreTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 17 -cp target/classes -d target/example-classes examples/RetryRegression.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output 'Built target/tooltape.jar'
