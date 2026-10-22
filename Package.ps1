#Requires -Version 7.0
param([Parameter(Mandatory)][string]$SigningKey,[string]$AndroidSdk=$env:ANDROID_HOME,[switch]$SkipBuild)
& (Join-Path $PSScriptRoot '.sdk/tools/Package-Plugin.ps1') -ProjectRoot $PSScriptRoot -SigningKey $SigningKey -AndroidSdk $AndroidSdk -SkipBuild:$SkipBuild
