# Registro de alterações

## 2026-11-03 — apresentação do clima

- O céu nublado mantém tons cinza também à noite. As nuvens texturizadas se
  movem suavemente sem mudar sua aparência ou deixar o céu vazio.
- Sol, céu parcialmente nublado, neblina, neve e noite limpa receberam cenas
  animadas próprias. A textura das nuvens está incluída no aplicativo.
- A chuva passou a ter gotas em três profundidades, com variação de posição,
  comprimento, brilho, velocidade e inclinação. A tempestade aumenta a
  quantidade de gotas e o vento; o relâmpago permanece ao fundo.
- O desenho das gotas usa lotes de linhas, evitando o custo de criar e renderizar
  centenas de caminhos gráficos por quadro no Android 4.4.
- Todos os cartões da aba Clima passaram a cerca de 95% de opacidade. As cores
  de cada clima e as bordas foram mantidas, com menos interferência do fundo
  animado sobre textos e gráficos.
- A pasta `previews/clima` contém capturas e vídeos dos oito climas com dados de
  exemplo. O arquivo `galeria.png` permite comparar os visuais de uma vez.

Validação: `gradlew.bat assembleDebug`, instalação e inspeção das cenas no tablet
T1060B com Android 4.4. As capturas locais com fotos pessoais não são incluídas
no repositório.
