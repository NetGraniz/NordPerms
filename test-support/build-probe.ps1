param([string]$MavenCommand = 'mvn', [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
& $MavenCommand -B -ntp -f (Join-Path $project 'pom.xml') dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt' '-DincludeScope=test'
if ($LASTEXITCODE -ne 0) { throw 'Could not resolve test dependencies' }
$classes = Join-Path $PSScriptRoot 'build/classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$deps = (Get-Content -Raw (Join-Path $project 'target/test-classpath.txt')).Trim()
& (Join-Path $JavaHome 'bin/javac.exe') --release 25 -encoding UTF-8 -classpath $deps -d $classes (Join-Path $PSScriptRoot 'probe/PermsTestProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'probe/plugin.yml') -Destination $classes
& (Join-Path $JavaHome 'bin/jar.exe') --create --file (Join-Path $PSScriptRoot 'build/PermsTestProbe.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }
