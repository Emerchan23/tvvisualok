# Plano de Testes de Estabilidade

## Piloto minimo

- 1 desktop/servidor.
- 2 Android TVs.
- 1 Fire TV Stick.
- Duracao: 7 dias.

## Testes funcionais

- Cadastrar 10 TVs.
- Parear cliente usando token.
- Alterar URL individual em tempo real.
- Aplicar comando de reload.
- Mutar e reativar audio.
- Ver TV offline em ate 60 segundos apos desconectar rede.
- Confirmar logs para comando, conexao, desconexao e erro.

## Testes de recuperacao

- Derrubar rede por 2 minutos e restaurar.
- Forcar URL invalida e validar fallback.
- Simular travamento do player.
- Simular memoria acima do limite.
- Validar reload leve, recriacao de WebView e reinicio do app.

## Homologacao por dispositivo

Registrar:

- Modelo.
- Versao do sistema.
- Tipo de instalacao.
- URL testada.
- Estabilidade apos 24h.
- Estabilidade apos 48h.
- Estabilidade apos 7 dias.
- Memoria media e pico.
- Quantidade de reloads automaticos.
- Observacoes de audio/video.

## Criterio de aprovacao do piloto

- 10 TVs cadastradas.
- 5 TVs em operacao real por pelo menos 48h.
- Nenhuma TV exigindo intervencao manual frequente.
- Offline detectado dentro de 60 segundos.
- Logs suficientes para suporte tecnico.
