param(
    [string]$InputDirectory = 'C:\Users\User\Downloads\Telegram Desktop'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'mods/lg2-0.1.0/src/main/resources/assets/lg2'
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-Json($path, $value) {
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 40), $utf8)
}
function Write-Model($name, $elements, $textures) {
    Write-Json "$assets/models/item/$name.json" ([ordered]@{
        ambientocclusion = $false; gui_light = 'front'; textures = $textures; elements = @($elements)
    })
    Write-Json "$assets/items/$name.json" @{model = @{type = 'minecraft:model'; model = "lg2:item/$name"}}
}

# Model-space 8,8,8 is the ItemDisplay origin. Preserve the authored UV coordinates.
$eye = Get-Content -Raw -LiteralPath "$InputDirectory/center_eye.json" | ConvertFrom-Json
foreach ($element in $eye.elements) {
    $element.from[1] += 7
    $element.to[1] += 7
    if ($element.rotation) { $element.rotation.origin[1] += 7 }
    $element | Add-Member shade $false -Force
}
Write-Model 'orthodox_divine_eye' $eye.elements @{'1'='lg2:item/orthodox_divine_eye'; particle='lg2:item/orthodox_divine_eye'}
Copy-Item -LiteralPath "$InputDirectory/center_eye.png" -Destination "$assets/textures/item/orthodox_divine_eye.png"

$beams = Get-Content -Raw -LiteralPath "$InputDirectory/beams.json" | ConvertFrom-Json
if ($beams.groups.Count -ne 16) { throw 'Expected 16 authored beam groups' }
for ($i = 0; $i -lt 16; $i++) {
    $elements = @($beams.groups[$i].children | ForEach-Object { $beams.elements[$_] })
    $cx = (($elements | ForEach-Object { $_.from[0] } | Measure-Object -Minimum).Minimum + ($elements | ForEach-Object { $_.to[0] } | Measure-Object -Maximum).Maximum) / 2
    $cz = (($elements | ForEach-Object { $_.from[2] } | Measure-Object -Minimum).Minimum + ($elements | ForEach-Object { $_.to[2] } | Measure-Object -Maximum).Maximum) / 2
    $bottom = ($elements | ForEach-Object { $_.from[1] } | Measure-Object -Minimum).Minimum
    $origin = @($cx, $bottom, $cz)
    foreach ($element in $elements) {
        foreach ($point in @($element.from, $element.to)) {
            for ($axis=0; $axis -lt 3; $axis++) { $point[$axis] = 8 + ($point[$axis] - $origin[$axis]) / 2 }
        }
        # All authored rotations are zero; removing their obsolete pivot avoids offsets.
        if ($element.rotation -and $element.rotation.angle -ne 0) { throw 'Unexpected rotated beam' }
        $element.PSObject.Properties.Remove('rotation')
        $element | Add-Member shade $false -Force
    }
    Write-Model "orthodox_eye_beam_$i" $elements @{'0'='lg2:item/orthodox_eye_beams'; particle='lg2:item/orthodox_eye_beams'}
}
Copy-Item -LiteralPath "$InputDirectory/beams.png" -Destination "$assets/textures/item/orthodox_eye_beams.png"

# Layered tapered solids, not flat wing sprites. Each feather has a raised shaft,
# stepped tip and its own depth. The two raised eye faces use separate atlas tiles.
function Add-Cuboid($list, $from, $to, $uv, $angle=0, $pivot=@(8,8,8)) {
    $faces = @{}
    foreach ($face in 'north','south','east','west','up','down') {
        $faces[$face] = @{texture='#0'; uv=@($uv)}
    }
    $e = @{from=@($from); to=@($to); faces=$faces; shade=$false}
    if ($angle -ne 0) { $e.rotation=@{angle=$angle; axis='z'; origin=@($pivot)} }
    $list.Add($e)
}
for ($variant=0; $variant -lt 8; $variant++) {
    $parts = [Collections.Generic.List[object]]::new()
    $wide = 0.85 + ($variant % 4) * 0.07
    $long = 0.88 + [Math]::Floor($variant / 2) * 0.05
    $sweep = if ($variant % 2 -eq 0) { 1 } else { -1 }
    Add-Cuboid $parts @(7.2,8,7.6) @(8.8,13,9) @(0.1,0.1,7.9,7.9)
    for ($layer=0; $layer -lt 3; $layer++) {
        $count = 11 - $layer * 2
        for ($f=0; $f -lt $count; $f++) {
            $t = $f / ($count - 1.0)
            $x = 8 + ($t - 0.5) * (12 - $layer * 2) * $wide + $sweep * $layer * 0.4
            $y = 9 + $layer * 1.0 + [Math]::Sin($t * [Math]::PI) * 2 + $sweep * ($t - 0.5) * 2
            $length = (14 - $layer * 3) * $long * (0.6 + 0.4 * [Math]::Sin($t * [Math]::PI)) + $sweep * ($t - 0.5) * 5
            $length += [Math]::Sin(($f + 1) * ($variant + 1) * 1.7) * 0.65
            $angle = -$sweep * 22.5
            $z = 7.5 + $layer * 0.65
            $width = (2.15 - $layer * 0.15) * $wide
            $uv = @(0.1,0.1,7.9,7.9)
            $pivot = @($x,$y,$z)
            Add-Cuboid $parts @(($x-$width/2),$y,($z-0.35)) @(($x+$width/2),($y+$length*0.76),($z+0.35)) $uv $angle $pivot
            Add-Cuboid $parts @(($x-$width*0.32),($y+$length*0.76),($z-0.25)) @(($x+$width*0.32),($y+$length*0.94),($z+0.25)) $uv $angle $pivot
            Add-Cuboid $parts @(($x-0.18),($y+$length*0.94),($z-0.12)) @(($x+0.18),($y+$length),($z+0.12)) $uv $angle $pivot
            Add-Cuboid $parts @(($x-0.07),$y,($z+0.35)) @(($x+0.07),($y+$length*0.86),($z+0.47)) @(8.5,0.5,9,7.5) $angle $pivot
        }
    }
    # Continuous atlas mapping across a rounded almond, with no rectangular plaque.
    $tileX = if ($variant % 2 -eq 0) { 0 } else { 8 }
    for ($band=0; $band -lt 12; $band++) {
        $x0 = 5 + $band * 0.5
        $x1 = $x0 + 0.5
        $curve = [Math]::Sin(($band + 0.5) / 12 * [Math]::PI)
        $halfHeight = 0.2 + 1.0 * $curve
        $depth = 0.25 + 0.35 * $curve
        $u0 = $tileX + $band / 12 * 8
        $u1 = $tileX + ($band+1) / 12 * 8
        $v0 = 12 - $halfHeight / 1.5 * 4
        $v1 = 12 + $halfHeight / 1.5 * 4
        Add-Cuboid $parts @($x0,(14.2-$halfHeight),(7-$depth)) @($x1,(14.2+$halfHeight),(9.4+$depth)) @($u0,$v0,$u1,$v1)
        $eyeBand = $parts[$parts.Count - 1]
        $eyeBand.faces.north.uv = @($u1,$v0,$u0,$v1)
        foreach ($side in 'east','west','up','down') { $eyeBand.faces[$side].uv = @(0.1,0.1,7.9,7.9) }
    }
    Write-Model "orthodox_eye_wing_$variant" $parts @{'0'='lg2:item/orthodox_eye_wing_atlas'; particle='lg2:item/orthodox_eye_wing_atlas'}
}
Write-Host 'Generated center, 16 root-pivoted rays and 8 volumetric wing variants.'
