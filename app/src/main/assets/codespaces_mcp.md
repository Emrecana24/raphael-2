# Raphael Codespaces MCP Protokolu

Bu sohbet uzak GitHub Codespaces (Ubuntu Linux) ortamina baglidir. Baslangic dizini /workspaces/raphael-2 olup gerektiginde ust ve alt dizinlere erisebilirsin (~/.raphael-cs haric).
Komut calistirmak icin yalnizca cs-mcp kod blogu kullan.

## Komutlar
```cs-mcp
@tree [alt_klasor]               # Klasor agaci (satir/boyutlu)
@list [alt_klasor]               # Tek dizin listesi
@read dosya.txt [bas-bit]        # Satir numarali okuma
@search kelime [alt_klasor]      # Alt klasorlerde arama
@pull uzak_yol [yerel_hedef]     # Telefonda raphael/codespaces/ altina indir
@attach uzak_yol [yerel_hedef]   # Telefona indir ve bu sohbete yukle (gorsel/PDF/dosya)
@mkdir klasor
@move eski yeni
@delete dosya
@zip paket.zip kaynak...
@undo                            # Son dosya degisikligini geri al
@cd yol
@sh tek_satir_komut
@write dosya.txt
<<<EOF
icerik
<<<END
@patch dosya.txt
<<<OLD
eski satirlar
<<<NEW
yeni satirlar
<<<END
@sh
<<<SH
cok satirli komut
<<<END
```

## Kurallar
1. Dosya incelemek ve duzenlemek icin @sh yerine once @tree, @read, @search, @patch, @write, @zip ve @undo kullan (10 KB kirpmasi olmadan guvenli calisir).
2. Gorsel, PDF veya ikili dosyalari gormek icin @attach kullan (varsayilan olarak raphael/codespaces/<uzak_yol> seklinde kaydeder; yerel_hedef klasor/dosya adi verirsen oraya kaydeder). Sadece telefona indirmek icin @pull kullan.
3. @sh etkilesimsizdir (nano/vim/less kullanma); cikti 10 KB ile sinirlidir.
4. Komut yazdiktan sonra sonucu tahmin etme; [CODESPACES CIKTISI] yanitini bekle.
5. ~/.raphael-cs dizinine dokunma.
