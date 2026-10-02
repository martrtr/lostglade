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

# Keep the supplied replacement rays and seraph wings in sync; never regenerate
# the old procedural wing placeholders over the imported models.
& "$PSScriptRoot/import_divine_eye_composition.ps1" `
    -BeamModel "$InputDirectory/beams (4).json" `
    -BeamTexture "$InputDirectory/beams (2).png"