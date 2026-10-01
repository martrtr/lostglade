param(
    [string]$BeamModel = 'C:\Users\User\Downloads\Telegram Desktop\beams (4).json',
    [string]$BeamTexture = 'C:\Users\User\Downloads\Telegram Desktop\beams (2).png',
    [string]$WingModel = 'C:\Users\User\Downloads\orthodox_seraph_wings_v2_optimized.bbmodel',
    [string]$WingTexture = 'C:\Users\User\Downloads\orthodox_seraph_wings_v2.png'
)
$ErrorActionPreference = 'Stop'
$assets = Join-Path (Split-Path $PSScriptRoot -Parent) 'mods/lg2-0.1.0/src/main/resources/assets/lg2'
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-Json($path, $value) {
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 30 -Compress), $utf8)
}
function Write-Model($name, $elements, $texture) {
    Write-Json "$assets/models/item/$name.json" ([ordered]@{
        ambientocclusion = $false; gui_light = 'front'
        textures = @{'0'=$texture; particle=$texture}; elements = @($elements)
    })
    Write-Json "$assets/items/$name.json" @{model=@{type='minecraft:model'; model="lg2:item/$name"}}
}

$beams = Get-Content -Raw -LiteralPath $BeamModel | ConvertFrom-Json
if ($beams.groups.Count -ne 16) { throw 'Expected 16 ray groups' }
for ($i=0; $i -lt 16; $i++) {
    $elements = @($beams.groups[$i].children | ForEach-Object { $beams.elements[$_] })
    $cx = (($elements | ForEach-Object { $_.from[0] } | Measure-Object -Minimum).Minimum + ($elements | ForEach-Object { $_.to[0] } | Measure-Object -Maximum).Maximum) / 2
    $cz = (($elements | ForEach-Object { $_.from[2] } | Measure-Object -Minimum).Minimum + ($elements | ForEach-Object { $_.to[2] } | Measure-Object -Maximum).Maximum) / 2
    $bottom = ($elements | ForEach-Object { $_.from[1] } | Measure-Object -Minimum).Minimum
    $origin = @($cx,$bottom,$cz)
    foreach ($element in $elements) {
        foreach ($point in @($element.from,$element.to)) {
            for ($axis=0; $axis -lt 3; $axis++) { $point[$axis] = 8 + ($point[$axis]-$origin[$axis])/2 }
        }
        if ($element.rotation -and $element.rotation.angle -ne 0) { throw 'Unexpected rotated ray' }
        $element.PSObject.Properties.Remove('rotation')
        $element | Add-Member shade $false -Force
    }
    Write-Model "orthodox_eye_beam_$i" $elements 'lg2:item/orthodox_eye_beams'
}
Copy-Item -LiteralPath $BeamTexture -Destination "$assets/textures/item/orthodox_eye_beams.png"

$wings = Get-Content -Raw -LiteralPath $WingModel | ConvertFrom-Json
if ($wings.outliner.Count -ne 8) { throw 'Expected eight wing roots' }
$cubes = @{}; foreach ($cube in $wings.elements) { $cubes[$cube.uuid] = $cube }
$groups = @{}; foreach ($group in $wings.groups) { $groups[$group.uuid] = $group }
function Get-Cubes($node, [bool]$rootNode=$false) {
    if ($node -is [string]) {
        $cube = $cubes[$node]
        if (!$cube -or $cube.type -ne 'cube' -or !$cube.export) { throw "Invalid exported cube $node" }
        return $cube
    }
    $group = $groups[$node.uuid]
    $expected = if ($rootNode) { @(90,0,0) } else { @(0,0,0) }
    if (($group.rotation -join ',') -ne ($expected -join ',')) { throw 'Unexpected group rotation' }
    if ($rootNode -and ($group.origin -join ',') -ne '0,0,0') { throw 'Unexpected wing root pivot' }
    foreach ($child in $node.children) { Get-Cubes $child }
}
for ($i=0; $i -lt 8; $i++) {
    $elements = [Collections.Generic.List[object]]::new()
    # Editor visibility is deliberately ignored: all eight variants were saved hidden.
    foreach ($cube in @(Get-Cubes $wings.outliner[$i] $true)) {
        $faces = [ordered]@{}
        foreach ($property in $cube.faces.PSObject.Properties) {
            $face = $property.Value
            if ($null -eq $face.texture) { continue }
            if ($face.texture -ne 0) { throw 'Unexpected wing texture index' }
            $uv = @($face.uv | ForEach-Object { [double]$_ * 16 / $wings.resolution.width })
            $faces[$property.Name] = @{texture='#0'; uv=$uv}
            if ($face.rotation) { $faces[$property.Name].rotation = $face.rotation }
        }
        # Normalize into vanilla's [-16,32] bounds. ItemDisplay restores the scale.
        $element = [ordered]@{
            from=@($cube.from | ForEach-Object { 8+[double]$_/4 })
            to=@($cube.to | ForEach-Object { 8+[double]$_/4 })
            shade=$false; faces=$faces
        }
        if ($cube.rotation -and ($cube.rotation | Where-Object { $_ -ne 0 }).Count -gt 0) {
            $element.rotation = @{
                origin=@($cube.origin | ForEach-Object { 8+[double]$_/4 })
                x=$cube.rotation[0]; y=$cube.rotation[1]; z=$cube.rotation[2]; rescale=$false
            }
        }
        $elements.Add($element)
    }
    Write-Model "orthodox_eye_wing_$i" $elements 'lg2:item/orthodox_eye_wing_atlas'
}
Copy-Item -LiteralPath $WingTexture -Destination "$assets/textures/item/orthodox_eye_wing_atlas.png"
Write-Host 'Imported 16 rays and all eight volumetric seraph wings; center eye unchanged.'
