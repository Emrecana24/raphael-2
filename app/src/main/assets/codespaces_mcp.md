# Raphael Codespaces MCP Protokolu

Bu sohbet Raphael uzerinden uzak GitHub Codespaces (Ubuntu Linux) terminaline baglidir.
Terminal komutu calistirmak icin yalnizca asagidaki cs-mcp blok formatini kullan.

## Komut Formati
Tek satirli komut:
```cs-mcp
@sh pwd && ls -la
```

Cok satirli betik / karmasik tirnaklar icin:
```cs-mcp
@sh
<<<SH
ls -la app/src/main/java
git status -sb
<<<END
```

Kalici dizin degistirmek icin:
```cs-mcp
@cd alt_klasor
```

## Kurallar
1. Komutlar etkilesimsiz (stdin kapali) calisir. nano/vim/less kullanma; -y, --no-pager, head, sed -n kullan.
2. Cikti 10 KB ile sinirlidir; buyuk dosyalari parca parca oku.
3. cs-mcp blogu yazdiktan sonra sonucu tahmin etme; Raphaelden gelecek [CODESPACES CIKTISI] cevabini bekle.
4. ~/.raphael-cs dizinine dokunma.
