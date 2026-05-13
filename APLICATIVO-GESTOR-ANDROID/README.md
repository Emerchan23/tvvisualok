# Aplicativo Gestor Painel TV

Este APK e o aplicativo de bolso do administrador. Ele nao substitui o painel desktop, mas facilita o trabalho no local da TV.

## Funcionalidades

- Ler o QR Code exibido no app da TV.
- Aprovar pareamento sem digitar token grande na TV.
- Salvar endereco do servidor e token admin.
- Listar TVs cadastradas no servidor.
- Exibir status, IP, audio, ultimo sinal e URL atual da TV.
- Enviar comandos para cada TV:
  - Recarregar painel.
  - Reiniciar tela WebView.
  - Reiniciar aplicativo da TV.
  - Limpar cache.
  - Mutar ou ativar audio.

## Uso

1. Instale o APK no celular Android do gestor.
2. Abra o app.
3. Informe o servidor do painel, por exemplo:

```text
http://192.168.68.112:9090
```

4. Informe o token admin.
5. Toque em `Salvar`.
6. Para parear uma TV, toque em `Ler QR da TV` e aponte para a tela.
7. Para controlar TVs ja cadastradas, toque em `Atualizar TVs`.

## Observacoes

- O celular precisa estar na mesma rede do computador servidor, salvo se o servidor estiver exposto corretamente na rede.
- Nao use `localhost` no celular. Use o IP do computador onde o Painel TV esta rodando.
- O token admin padrao local e `admin123`, mas deve ser trocado em producao.

## Gerar APK release

```bat
build-release-apk.bat
```

O APK assinado fica em:

```text
app\build\outputs\apk\release\Gestor-Painel-TV-release.apk
```
