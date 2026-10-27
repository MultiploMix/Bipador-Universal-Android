# Bipador Universal — Android V1

Aplicativo Android **híbrido local**: interface e regras da versão web V32 dentro do aplicativo, com scanner **nativo Android CameraX + ML Kit**.

## Funções

- QR Code das etiquetas (`P:`, `Sku:`, `Q:`), QR de lista e QR dos SKUs completos.
- EAN-13, EAN-8, UPC-A, UPC-E e Code 128, com cadastro EAN ↔ SKU.
- EAN exclusivo ligado ao SKU completo: conferência direta; SKU base: confirmação de variação quando necessária; EAN compartilhado: confirmação visual.
- Histórico de pedidos conferidos, prevenção de duplicados, listas esperadas e relatórios.
- Funcionário, conta, lista, data/hora, backup CSV/JSON, compartilhamento pelo Android (incluindo WhatsApp).
- Scanner Android com câmera traseira, foco por toque, lanterna e zoom.
- O scanner não emite som ao ler a etiqueta; o resultado da conferência do produto aciona os sinais.

## Compilar e instalar no Windows

1. Instale **Android Studio** (JDK 17) e o SDK 35.
2. Abra a pasta `Bipador_Universal_Android_V1` em **File > Open**.
3. Aguarde Gradle Sync. O projeto usa Android Gradle Plugin 8.7.3 e Gradle 8.9.
4. **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Instale `app/build/outputs/apk/debug/app-debug.apk` em um Android com versão 6.0+.

Alternativa: envie o projeto para um repositório separado no GitHub e execute **Actions → Gerar APK Bipador Universal → Run workflow**. O APK aparece como artefato da execução.

## Organização

- `app/src/main/assets/index.html`: interface e regras da V32, adaptadas para a câmera nativa.
- `MainActivity.java`: hospeda a interface local e abre o scanner; arquivos CSV/JSON/HTML via sistema Android.
- `ScannerActivity.java`: CameraX/ML Kit, foco, lanterna, zoom, QR/EAN.
- `.github/workflows/gerar-apk.yml`: compila APK debug no GitHub Actions.

## Privacidade e limitações

Os dados são gravados localmente no WebView (mesmos formatos de backup da versão web). **Dados do Safari não migram automaticamente.** Use os backups para transferir cadastros. Não há backend/sincronização, nem uso de contas de marketplaces. O WebView carrega somente arquivos empacotados e a aplicação não solicita permissão de Internet. O modelo de reconhecimento fica embarcado.

O projeto-fonte foi criado aqui, mas **não foi compilado nem testado em um aparelho Android** devido à indisponibilidade de SDK e dependências de build neste ambiente. Faça um piloto de leitura real e backup antes de usar na expedição. O APK debug não é uma versão de distribuição pela Play Store.
