param([string]$Destination = "$PSScriptRoot/../docs/samples/lumbre-demo.epub")
$ErrorActionPreference = 'Stop'
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($destinationPath)) | Out-Null
Add-Type -AssemblyName System.Drawing
$bitmap = [System.Drawing.Bitmap]::new(400, 600)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#254C3A'))
$brush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#F5E7B2'))
$font = [System.Drawing.Font]::new('Georgia', 32)
$small = [System.Drawing.Font]::new('Georgia', 15)
$graphics.DrawString("LA LUZ`nDEL PUERTO", $font, $brush, 36, 140)
$graphics.DrawString('Un libro de prueba', $small, $brush, 40, 360)
$imageStream = [System.IO.MemoryStream]::new()
$bitmap.Save($imageStream, [System.Drawing.Imaging.ImageFormat]::Png)
$cover = $imageStream.ToArray()
$imageStream.Dispose(); $graphics.Dispose(); $bitmap.Dispose(); $brush.Dispose(); $font.Dispose(); $small.Dispose()
$container = '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'
$opf = '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:lumbre-test-book</dc:identifier><dc:title>La luz del puerto</dc:title><dc:creator>Biblioteca Lumbre</dc:creator><dc:language>es</dc:language><dc:publisher>Edición de prueba</dc:publisher><meta property="dcterms:modified">2026-09-24T00:00:00Z</meta></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/><item id="css" href="style.css" media-type="text/css"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>'
$nav = '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contenido</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">La llegada</a></li><li><a href="two.xhtml#faro">El faro</a></li></ol></nav></body></html>'
$paragraphs = (1..100 | ForEach-Object { "<p id='p$_'>Párrafo $_. Al caer la tarde, Clara llegó al puerto. El aire olía a sal y las ventanas guardaban la última luz del día. Caminó despacio junto al agua, con la esperanza de encontrar el camino de regreso. Cada libro era una puerta abierta a un lugar diferente.</p>" }) -join "`n"
$one = "<html xmlns='http://www.w3.org/1999/xhtml' lang='es'><head><title>La llegada</title><link rel='stylesheet' href='style.css'/></head><body><h1>La llegada</h1><p>Este EPUB original permite comprobar <em>cursivas</em>, <strong>negritas</strong>, imágenes y progreso.</p><p><a href='two.xhtml#faro'>Ir al faro</a></p><img src='cover.png' alt='Portada del libro' style='width:120px'/><ul><li>Leer sin conexión</li><li>Guardar el lugar</li></ul>$paragraphs</body></html>"
$two = "<html xmlns='http://www.w3.org/1999/xhtml' lang='es'><head><title>El faro</title><link rel='stylesheet' href='style.css'/></head><body><h1 id='faro'>El faro</h1><p>La esperanza tenía el color de las luces del puerto. Había llegado el momento de comenzar otra historia.</p><p><a href='one.xhtml#p20'>Volver al párrafo veinte</a></p>$paragraphs</body></html>"
$file = [System.IO.File]::Open($destinationPath, [System.IO.FileMode]::Create)
$zip = [System.IO.Compression.ZipArchive]::new($file, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $entries = [ordered]@{ 'mimetype' = 'application/epub+zip'; 'META-INF/container.xml' = $container; 'OPS/book.opf' = $opf; 'OPS/nav.xhtml' = $nav; 'OPS/one.xhtml' = $one; 'OPS/two.xhtml' = $two; 'OPS/style.css' = 'h1 { font-family:serif; } img { display:block; margin:1em auto; }'; 'OPS/cover.png' = $cover }
    foreach ($name in $entries.Keys) {
        $entry = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::NoCompression)
        $stream = $entry.Open()
        try {
            $bytes = if ($entries[$name] -is [byte[]]) { $entries[$name] } else { [System.Text.Encoding]::UTF8.GetBytes($entries[$name]) }
            $stream.Write($bytes, 0, $bytes.Length)
        } finally { $stream.Dispose() }
    }
} finally { $zip.Dispose(); $file.Dispose() }
Write-Output $destinationPath
