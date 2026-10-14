param(
    [string]$Source = (Join-Path $PSScriptRoot '..\artwork\smooth-dialogue-launcher.png')
)

# Mechanical scaling/padding only. The supplied source artwork is never distorted.
Add-Type -AssemblyName System.Drawing
$resourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\app\src\main\res'))
$artwork = [Drawing.Image]::FromFile($Source)
try {
    function Get-FittedRectangle([int]$Size, [double]$ContentFraction) {
        $contentBox = $Size * $ContentFraction
        $scale = [Math]::Min($contentBox / $artwork.Width, $contentBox / $artwork.Height)
        $width = [int][Math]::Round($artwork.Width * $scale)
        $height = [int][Math]::Round($artwork.Height * $scale)
        return [Drawing.Rectangle]::new(
            [int][Math]::Floor(($Size - $width) / 2),
            [int][Math]::Floor(($Size - $height) / 2),
            $width,
            $height
        )
    }

    function Save-ScaledArtwork([int]$Size, [double]$ContentFraction, [string]$Target, [bool]$Transparent) {
        $bitmap = [Drawing.Bitmap]::new($Size, $Size)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            if ($Transparent) { $graphics.Clear([Drawing.Color]::Transparent) }
            else { $graphics.Clear([Drawing.Color]::FromArgb(255, 16, 16, 16)) }
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $graphics.DrawImage($artwork, (Get-FittedRectangle $Size $ContentFraction))
            $bitmap.Save($Target, [Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    }

    function Save-MonochromeArtwork([int]$Size, [double]$ContentFraction, [string]$Target) {
        $scaled = [Drawing.Bitmap]::new($Size, $Size)
        $graphics = [Drawing.Graphics]::FromImage($scaled)
        try {
            $graphics.Clear([Drawing.Color]::Transparent)
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $graphics.DrawImage($artwork, (Get-FittedRectangle $Size $ContentFraction))
        } finally { $graphics.Dispose() }

        $monochrome = [Drawing.Bitmap]::new($Size, $Size)
        try {
            for ($y = 0; $y -lt $Size; $y++) {
                for ($x = 0; $x -lt $Size; $x++) {
                    $pixel = $scaled.GetPixel($x, $y)
                    $luminance = [Math]::Max($pixel.R, [Math]::Max($pixel.G, $pixel.B))
                    if ($pixel.A -gt 0 -and $luminance -gt 96) {
                        $mask = [int][Math]::Round($pixel.A * (($luminance - 96) / 159.0))
                        $monochrome.SetPixel($x, $y, [Drawing.Color]::FromArgb($mask, 255, 255, 255))
                    }
                }
            }
            $monochrome.Save($Target, [Drawing.Imaging.ImageFormat]::Png)
        } finally { $scaled.Dispose(); $monochrome.Dispose() }
    }

    # 46dp square inside the adaptive 66dp safe circle: all source corners survive
    # circular and squircle masks. The remaining area is a neutral dark backdrop.
    $foregroundDirectory = Join-Path $resourceRoot 'drawable-nodpi'
    New-Item -ItemType Directory -Path $foregroundDirectory -Force | Out-Null
    Save-ScaledArtwork 432 (46.0 / 108.0) (Join-Path $foregroundDirectory 'smooth_dialogue_launcher_foreground.png') $true
    Save-MonochromeArtwork 432 (46.0 / 108.0) (Join-Path $foregroundDirectory 'smooth_dialogue_launcher_monochrome.png')
    $densities = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
    foreach ($density in $densities.Keys) {
        $directory = Join-Path $resourceRoot "mipmap-$density"
        foreach ($name in @('ic_launcher', 'ic_launcher_round')) {
            Save-ScaledArtwork $densities[$density] (46.0 / 72.0) (Join-Path $directory "$name.png") $false
            $oldAsset = [IO.Path]::GetFullPath((Join-Path $directory "$name.webp"))
            if (-not $oldAsset.StartsWith($resourceRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe resource target.' }
            if (Test-Path -LiteralPath $oldAsset) { Remove-Item -LiteralPath $oldAsset }
        }
    }
    Write-Host "Generated Smooth Dialogue adaptive, monochrome, and five-density legacy icons from $Source"
} finally { $artwork.Dispose() }
