$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'mods/lg2-0.1.0/src/main/resources/assets/lg2'
$utf8 = [Text.UTF8Encoding]::new($false)
$display = (Get-Content "$assets/models/item/monitor_display.json" -Raw | ConvertFrom-Json).display
$speakerBodyPath = "$assets/textures/block/speaker_body.png"

# Each model unit maps to four texels; adjoining bars never overlap.
function New-Bar([double]$x1, [double]$y1, [double]$x2, [double]$y2, [double]$z1 = 7, [double]$z2 = 8) {
    return [ordered]@{
        from = @($x1, $y1, $z1)
        to = @($x2, $y2, $z2)
        faces = [ordered]@{
            north = @{ uv = @((16-$x2), (16-$y2), (16-$x1), (16-$y1)); texture = '#front' }
            south = @{ uv = @($x1, (16-$y2), $x2, (16-$y1)); texture = '#front' }
            east = @{ uv = @($z1, (16-$y2), $z2, (16-$y1)); texture = '#shell' }
            west = @{ uv = @($z1, (16-$y2), $z2, (16-$y1)); texture = '#shell' }
            up = @{ uv = @($x1, $z1, $x2, $z2); texture = '#shell' }
            down = @{ uv = @($x1, $z1, $x2, $z2); texture = '#shell' }
        }
    }
}

function Write-MonitorModel([string]$name, [int]$mask, [bool]$screen) {
    $parts = [Collections.Generic.List[object]]::new()
    $bottom = 0
    $top = 16
    if (($mask -band 4) -eq 0) { $parts.Add((New-Bar 0 15 16 16)); $top = 15 }
    if (($mask -band 8) -eq 0) { $parts.Add((New-Bar 0 0 16 1)); $bottom = 1 }
    if (($mask -band 1) -eq 0) { $parts.Add((New-Bar 0 $bottom 1 $top)) }
    if (($mask -band 2) -eq 0) { $parts.Add((New-Bar 15 $bottom 16 $top)) }
    if ($screen) { $parts.Add((New-Bar 1 1 15 15 7.25 7.75)) }
    $model = [ordered]@{
        gui_light = 'front'
        textures = [ordered]@{
            front = 'lg2:item/monitor_frame'
            shell = 'lg2:item/monitor_shell'
            particle = 'lg2:item/monitor_shell'
        }
        display = $display
        elements = $parts.ToArray()
    }
    [IO.File]::WriteAllText("$assets/models/item/$name.json", ($model | ConvertTo-Json -Depth 16), $utf8)
}

$front = [Drawing.Bitmap]::new(64, 64)
$shell = [Drawing.Bitmap]::new(16, 16)
$screen = [Drawing.Bitmap]::new(16, 16)
$speakerBody = [Drawing.Bitmap]::new($speakerBodyPath)
try {
    $smoothed = [double[]]::new(16 * 16 * 3)
    $weights = @(1, 2, 1)
    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            $index = ($y * 16 + $x) * 3
            for ($dy = -1; $dy -le 1; $dy++) {
                for ($dx = -1; $dx -le 1; $dx++) {
                    $pixel = $speakerBody.GetPixel(($x + $dx + 16) % 16, ($y + $dy + 16) % 16)
                    $weight = $weights[$dx + 1] * $weights[$dy + 1] / 16.0
                    $smoothed[$index] += $pixel.R * $weight
                    $smoothed[$index + 1] += $pixel.G * $weight
                    $smoothed[$index + 2] += $pixel.B * $weight
                }
            }
            $shell.SetPixel($x, $y, [Drawing.Color]::FromArgb(
                [int][Math]::Round($smoothed[$index] * 0.76),
                [int][Math]::Round($smoothed[$index + 1] * 0.76),
                [int][Math]::Round($smoothed[$index + 2] * 0.76)
            ))
            $screen.SetPixel($x, $y, [Drawing.Color]::FromArgb(8, 10, 13))
        }
    }
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $sx = [int][Math]::Floor($x / 4)
            $sy = [int][Math]::Floor($y / 4)
            $fx = ($x % 4) / 4.0
            $fy = ($y % 4) / 4.0
            $rgb = @(0, 0, 0)
            for ($channel = 0; $channel -lt 3; $channel++) {
                $top = $smoothed[(($sy * 16 + $sx) * 3 + $channel)] * (1 - $fx) + $smoothed[(($sy * 16 + (($sx + 1) % 16)) * 3 + $channel)] * $fx
                $bottom = $smoothed[((($sy + 1) % 16 * 16 + $sx) * 3 + $channel)] * (1 - $fx) + $smoothed[((($sy + 1) % 16 * 16 + (($sx + 1) % 16)) * 3 + $channel)] * $fx
                $rgb[$channel] = [int][Math]::Round(($top * (1 - $fy) + $bottom * $fy) * 0.76)
            }
            $front.SetPixel($x, $y, [Drawing.Color]::FromArgb(255, $rgb[0], $rgb[1], $rgb[2]))
        }
    }
    $front.Save("$assets/textures/item/monitor_frame.png", [Drawing.Imaging.ImageFormat]::Png)
    $shell.Save("$assets/textures/item/monitor_shell.png", [Drawing.Imaging.ImageFormat]::Png)
    $screen.Save("$assets/textures/item/monitor_screen.png", [Drawing.Imaging.ImageFormat]::Png)
} finally {
    $front.Dispose()
    $shell.Dispose()
    $screen.Dispose()
    $speakerBody.Dispose()
}

Write-MonitorModel 'monitor_display' 0 $false
for ($mask = 1; $mask -le 15; $mask++) {
    Write-MonitorModel "monitor_display_$mask" $mask $false
}
Write-Output 'Generated 16 monitor display models and 3 matching textures.'
