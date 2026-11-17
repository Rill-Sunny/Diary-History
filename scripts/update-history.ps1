param(
    [string] $OutputPath = (Join-Path $PSScriptRoot '../frontend/src/data/world-history.json')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName Microsoft.VisualBasic

function Convert-EventText([string] $Text) {
    $textOnly = $Text -replace '<ref\b[^>]*>.*?</ref>|<ref\b[^>]*/>|<!--.*?-->', '' `
        -replace '\[\[(?:[^\]|]+\|)?([^\]]+)\]\]', '$1' `
        -replace "'{2,5}", '' `
        -replace '<(?:/?(?:small|sup|sub)|br)\b[^>]*>', ''
    if ($textOnly -match '[\[\]{}<>]|-\{|\}-') { return '' }
    $textOnly = [System.Net.WebUtility]::HtmlDecode($textOnly).Trim()
    return [Microsoft.VisualBasic.Strings]::StrConv($textOnly, 'SimplifiedChinese', 2052)
}

function Get-Events($Page) {
    $revision = $Page.revisions[0]
    $content = $revision.slots.main.content
    $section = [regex]::Match($content, '(?ms)^==\s*(?:大事[记記纪紀]|事件)\s*==[^\S\r\n]*\r?\n(.*?)(?=^==[^=\r\n].*?==\s*$|\z)').Groups[1].Value
    $candidates = foreach ($line in ($section -split '\r?\n')) {
        if ($line -notmatch '^\*\s*(?:\[\[)?((?:前\s*)?\d{1,4})年?(?:\]\])?\s*[：:–—-]\s*(.+)') { continue }
        $year = [int] ($Matches[1] -replace '\s', '' -replace '^前', '-')
        $summary = Convert-EventText $Matches[2]
        if ($summary.Length -gt 110) {
            $summary = [regex]::Match($summary, '^.{10,109}?[。！？]').Value
        }
        if ($summary.Length -lt 10) { continue }
        $score = 0
        if ($summary -match '首次|第一[次个颗位艘部条枚]|成立|建立|独立|签[订署]|通过|发现|发明|发行|发表|问世|登[陆月]|发射|启用|竣工|首[飞映演]|开幕|结束|停战|终战|登基|投降|授予|当选') { $score += 2 }
        if ($summary -match '世界大战|联合国|诺贝尔|宇航|航天|太空|奥林匹克|奥运会|世界杯|条约|人权|探测器|宪法|共和国|独立|停战') { $score += 3 }
        [pscustomobject] @{ year = $year; text = $summary; score = $score }
    }
    $ranked = @($candidates | Sort-Object -Stable -Property @{ Expression = 'score'; Descending = $true })
    # Keep years distinct, with higher-ranked events first.
    $selected = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($event in $ranked) {
        if ($seen.Add($event.year)) { $selected.Add($event) }
        if ($selected.Count -eq 7) { break }
    }
    if ($selected.Count -lt 3) { throw "Too few usable events for $($Page.title)" }
    return [ordered] @{
        revision = $revision.revid
        events = @($selected | ForEach-Object { [ordered] @{ year = $_.year; text = $_.text } })
    }
}

$days = 0..365 | ForEach-Object { [datetime]::new(2000, 1, 1).AddDays($_) }
$snapshots = @{}
for ($start = 0; $start -lt $days.Count; $start += 24) {
    $titles = $days[$start..([math]::Min($start + 23, $days.Count - 1))] | ForEach-Object { "$($_.Month)月$($_.Day)日" }
    $encodedTitles = [uri]::EscapeDataString($titles -join '|')
    $url = "https://zh.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&prop=revisions&rvslots=main&rvprop=ids%7Ccontent&maxlag=5&titles=$encodedTitles"
    $result = Invoke-RestMethod -Uri $url -UserAgent 'LiubaiDiaryCourseProject/1.0 (read-only on-this-day calendar snapshot)' -TimeoutSec 40 -MaximumRetryCount 2
    if ($result.error) { throw "Wikimedia API: $($result.error.info)" }
    foreach ($page in $result.query.pages) {
        if ($page.title -notmatch '^(\d+)月(\d+)日$') { throw "Unexpected title: $($page.title)" }
        $key = '{0:00}-{1:00}' -f [int] $Matches[1], [int] $Matches[2]
        $snapshots[$key] = Get-Events $page
    }
    Write-Output "$($snapshots.Count)/366 calendar days"
    Start-Sleep -Milliseconds 300
}

$orderedDays = [ordered] @{}
foreach ($day in $days) {
    $key = $day.ToString('MM-dd', [cultureinfo]::InvariantCulture)
    if (!$snapshots.ContainsKey($key)) { throw "Missing $key" }
    $orderedDays[$key] = $snapshots[$key]
}

$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($OutputPath)) | Out-Null
$lines = foreach ($key in $orderedDays.Keys) { '  "' + $key + '": ' + ($orderedDays[$key] | ConvertTo-Json -Depth 5 -Compress) }
[System.IO.File]::WriteAllText($OutputPath, "{`n" + ($lines -join ",`n") + "`n}`n", [System.Text.UTF8Encoding]::new($false))
