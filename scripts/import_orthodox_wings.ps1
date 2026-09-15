param([string]$Archive = 'C:\Users\User\Downloads\Telegram Desktop\Snoodles Barn Owl Wings Base.zip')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Split-Path $PSScriptRoot -Parent
$resources = Join-Path $root 'mods/lg2-0.1.0/src/main/resources'
$assets = Join-Path $resources 'assets/lg2'
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-Json($Path, $Value) {
    [IO.Directory]::CreateDirectory((Split-Path $Path -Parent)) | Out-Null
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 40) + "`n", $utf8)
}
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
    $reader = [IO.StreamReader]::new(($zip.Entries | Where-Object FullName -like '*/model.bbmodel').Open())
    try { $model = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$groups = @{}
function Visit-Groups($Children) {
    foreach ($node in $Children) {
        if ($node -is [string]) { continue }
        $groups[$node.name] = $node
        Visit-Groups $node.children
    }
}
Visit-Groups $model.outliner
$idle = $model.animations | Where-Object name -eq 'idle'
$fly = $model.animations | Where-Object name -eq 'fly'
function Channel($Animation, $Id, $Name) {
    $animator = $Animation.animators.PSObject.Properties[$Id].Value
    foreach ($frame in ($animator.keyframes | Where-Object channel -eq $Name | Sort-Object time)) {
        $point = $frame.data_points[0]
        [ordered]@{time = [double]$frame.time; value = @(
            [double]::Parse($point.x, [Globalization.CultureInfo]::InvariantCulture),
            [double]::Parse($point.y, [Globalization.CultureInfo]::InvariantCulture),
            [double]::Parse($point.z, [Globalization.CultureInfo]::InvariantCulture)
        ); interpolation = $frame.interpolation}
    }
}
$bones = @()
foreach ($side in @('Left', 'Right')) {
    for ($part = 1; $part -le 6; $part++) {
        $bone = $groups["${side}_Wing_P$part"]
        if (!$bone) { throw "Missing wing bone ${side}_Wing_P$part" }
        $cubes = @($model.elements | Where-Object { $bone.children -contains $_.uuid })
        if ($cubes.Count -ne 1) { throw "Unexpected geometry in $($bone.name)" }
        $cube = $cubes[0]
        $from = @(); $to = @()
        for ($axis = 0; $axis -lt 3; $axis++) {
            $from += [double]$cube.from[$axis] - [double]$bone.origin[$axis] + 8 - [double]$cube.inflate
            $to += [double]$cube.to[$axis] - [double]$bone.origin[$axis] + 8 + [double]$cube.inflate
        }
        $faces = [ordered]@{}
        foreach ($direction in @('east', 'west')) {
            $face = $cube.faces.$direction
            $faces[$direction] = @{uv = @($face.uv | ForEach-Object { [double]$_ / 4 }); texture = '#wing'}
        }
        $name = "orthodox_angel_wing_$($side.ToLowerInvariant())_$part"
        Write-Json (Join-Path $assets "models/item/$name.json") ([ordered]@{
            ambientocclusion = $false
            textures = @{wing = 'lg2:item/orthodox_angel_wings'; particle = 'lg2:item/orthodox_angel_wings'}
            elements = @(@{from = $from; to = $to; shade = $false; faces = $faces})
        })
        Write-Json (Join-Path $assets "items/$name.json") @{model = @{type = 'minecraft:model'; model = "lg2:item/$name"}}
        $bones += [ordered]@{
            name = $name; parent = $(if ($part -eq 1) {-1} else {$bones.Count - 1})
            pivot = $bone.origin
            idleRotation = @(Channel $idle $bone.uuid 'rotation')[0].value
            idlePosition = $( $c = @(Channel $idle $bone.uuid 'position'); if ($c.Count) { $c[0].value } else { @(0,0,0) } )
            flyRotation = @(Channel $fly $bone.uuid 'rotation')
        }
    }
}
Write-Json (Join-Path $resources 'lg2/orthodox_wing_rig.json') @{duration = $fly.length; bones = $bones}
$texture = $model.textures | Where-Object name -eq 'barn_owl_wing_txt'
[IO.File]::WriteAllBytes((Join-Path $assets 'textures/item/orthodox_angel_wings.png'), [Convert]::FromBase64String(($texture.source -split ',', 2)[1]))
$pack = Join-Path $root 'polymer/source_assets/assets/lg2'
foreach ($bone in $bones) {
    foreach ($folder in @('items', 'models/item')) {
        Copy-Item -LiteralPath (Join-Path $assets "$folder/$($bone.name).json") -Destination (Join-Path $pack "$folder/$($bone.name).json")
    }
}
Copy-Item -LiteralPath (Join-Path $assets 'textures/item/orthodox_angel_wings.png') -Destination (Join-Path $pack 'textures/item/orthodox_angel_wings.png')
Write-Host 'Imported 12 wing bones, static idle pose, complete fly rotation keys and original 64x64 texture.'
