param([Parameter(Mandatory = $true)][string]$InputDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assets = Join-Path $PSScriptRoot '../src/main/resources/assets/lg2'
$sprites = Join-Path $PSScriptRoot '../src/main/resources/assets/minecraft/textures/gui/sprites/boss_bar'
$frame = [Drawing.Bitmap]::new((Join-Path $InputDirectory 'alex3_frame.png'))
$empty = [Drawing.Bitmap]::new((Join-Path $InputDirectory 'alex3_emptyl.png'))
$full = [Drawing.Bitmap]::new((Join-Path $InputDirectory 'alex3_full.png'))
try {
    if ($frame.Width -ne 218 -or $frame.Height -ne 66 -or $empty.Width -notin @(181, 182) -or $empty.Height -ne 5 -or $full.Size -ne $empty.Size) {
        throw 'Unexpected mana texture dimensions.'
    }
    [IO.Directory]::CreateDirectory($sprites) | Out-Null
    foreach ($entry in @(
        @{ Image = $empty; Name = 'white_background.png' },
        @{ Image = $full; Name = 'white_progress.png' }
    )) {
        # Preserve authored pixels; pad only when the source is narrower than vanilla's 182 pixels.
        $sprite = [Drawing.Bitmap]::new(182, 5)
        try {
            for ($x = 0; $x -lt $entry.Image.Width; $x++) {
                for ($y = 0; $y -lt $entry.Image.Height; $y++) {
                    $sprite.SetPixel($x, $y, $entry.Image.GetPixel($x, $y))
                }
            }
            $sprite.Save((Join-Path $sprites $entry.Name), [Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $sprite.Dispose()
        }
    }
    $shiftedFrame = [Drawing.Bitmap]::new($frame.Width, $frame.Height)
    try {
        # Keep the native bossbar fixed and move only the authored frame four pixels left.
        for ($x = 4; $x -lt $frame.Width; $x++) {
            for ($y = 0; $y -lt $frame.Height; $y++) {
                $shiftedFrame.SetPixel($x - 4, $y, $frame.GetPixel($x, $y))
            }
        }
        $shiftedFrame.Save((Join-Path $assets 'textures/font/necromancer_mana_bar.png'), [Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $shiftedFrame.Dispose()
    }
    $font = @{ providers = @(
        @{ type = 'bitmap'; file = 'lg2:font/necromancer_mana_bar.png'; ascent = 23; height = 66; chars = @([string][char]0xEA00) },
        @{ type = 'reference'; id = 'minecraft:default' }
    ) }
    [IO.File]::WriteAllText((Join-Path $assets 'font/necromancer_mana_bar.json'), ($font | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
} finally {
    $frame.Dispose()
    $empty.Dispose()
    $full.Dispose()
}
