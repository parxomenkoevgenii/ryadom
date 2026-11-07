$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
$ProjectRoot=Split-Path -Parent $PSScriptRoot
$Bitmap=New-Object System.Drawing.Bitmap(64,64)
$Graphics=[System.Drawing.Graphics]::FromImage($Bitmap)
$Graphics.SmoothingMode=[System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$Graphics.Clear([System.Drawing.Color]::FromArgb(23,107,86))
$Pen=New-Object System.Drawing.Pen([System.Drawing.Color]::White,4)
$Pen.LineJoin=[System.Drawing.Drawing2D.LineJoin]::Round
$Points=@([System.Drawing.Point]::new(18,19),[System.Drawing.Point]::new(46,19),[System.Drawing.Point]::new(46,41),[System.Drawing.Point]::new(30,41),[System.Drawing.Point]::new(18,49))
$Graphics.DrawPolygon($Pen,$Points)
$Graphics.FillEllipse([System.Drawing.Brushes]::White,25,28,4,4)
$Graphics.FillEllipse([System.Drawing.Brushes]::White,35,28,4,4)
$Icon=[System.Drawing.Icon]::FromHandle($Bitmap.GetHicon())
$Stream=[IO.File]::Create((Join-Path $ProjectRoot '.build\ryadom.ico'))
$Icon.Save($Stream)
$Stream.Dispose();$Icon.Dispose();$Pen.Dispose();$Graphics.Dispose();$Bitmap.Dispose()
