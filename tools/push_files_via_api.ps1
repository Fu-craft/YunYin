# Pushes a list of files to Fu-craft/YunYin through the GitHub Git Data API.
# github.com:443 is unreachable from this machine; api.github.com is not.
#
# -Deleted removes paths from the tree (the API deletes by setting the entry's sha to null). Without it a
# commit could add edited files but never remove a deleted one, which leaves the remote compiling code that
# no longer exists locally.
param(
    [string]$Message = "chore: sync",
    [string[]]$Files,
    [string[]]$Deleted = @()
)
$ErrorActionPreference = "Stop"
$gh = "C:\Program Files\GitHub CLI\gh.exe"
$repo = "Fu-craft/YunYin"
$root = "D:\AmllPlayer"

function Gh([string]$method, [string]$apiPath, [string]$bodyFile) {
    if ($bodyFile) { $out = & $gh api --method $method $apiPath --input $bodyFile 2>&1 }
    else { $out = & $gh api --method $method $apiPath 2>&1 }
    if ($LASTEXITCODE -ne 0) { throw "gh api $method $apiPath failed: $out" }
    return ($out | Out-String)
}

$ref = Gh "GET" "repos/$repo/git/ref/heads/master" $null | ConvertFrom-Json
$parent = $ref.object.sha
$parentCommit = Gh "GET" "repos/$repo/git/commits/$parent" $null | ConvertFrom-Json
$baseTree = $parentCommit.tree.sha

$entries = @()
foreach ($rel in $Files) {
    $full = Join-Path $root $rel
    if (-not (Test-Path $full)) { throw "missing $rel" }
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($full))
    $tmp = Join-Path $env:TEMP ("blob-" + [guid]::NewGuid().ToString("N") + ".json")
    [IO.File]::WriteAllText($tmp, (@{ content = $b64; encoding = "base64" } | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding($false)))
    $blob = Gh "POST" "repos/$repo/git/blobs" $tmp | ConvertFrom-Json
    Remove-Item $tmp -Force
    $entries += @{ path = $rel; mode = "100644"; type = "blob"; sha = $blob.sha }
    Write-Host "blob $rel -> $($blob.sha.Substring(0,8))"
}

# Deletions: a tree entry with sha = null removes the path from the tree.
foreach ($rel in $Deleted) {
    $entries += @{ path = $rel; mode = "100644"; type = "blob"; sha = $null }
    Write-Host "delete $rel"
}

$tmp = Join-Path $env:TEMP "tree.json"
[IO.File]::WriteAllText($tmp, (@{ base_tree = $baseTree; tree = $entries } | ConvertTo-Json -Depth 6 -Compress), (New-Object System.Text.UTF8Encoding($false)))
$tree = Gh "POST" "repos/$repo/git/trees" $tmp | ConvertFrom-Json
Remove-Item $tmp -Force

$tmp = Join-Path $env:TEMP "commit.json"
[IO.File]::WriteAllText($tmp, (@{ message = $Message; tree = $tree.sha; parents = @($parent) } | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding($false)))
$commit = Gh "POST" "repos/$repo/git/commits" $tmp | ConvertFrom-Json
Remove-Item $tmp -Force

$tmp = Join-Path $env:TEMP "ref.json"
[IO.File]::WriteAllText($tmp, (@{ sha = $commit.sha; force = $false } | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding($false)))
Gh "PATCH" "repos/$repo/git/refs/heads/master" $tmp | Out-Null
Remove-Item $tmp -Force
Write-Host "master -> $($commit.sha)"
