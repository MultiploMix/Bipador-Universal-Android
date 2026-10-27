package br.com.multiplomix.bipador;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Interface V32 offline + leitor de códigos nativo Android.
 * O WebView SOMENTE carrega assets do APK; jamais sites não confiáveis com a bridge ativa.
 */
public class MainActivity extends AppCompatActivity {
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private static final String BASE = "https://appassets.androidplatform.net/";

    private WebView webView;
    private ToneGenerator tone;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private byte[] pendingFile;
    private String pendingShareText;
    private ValueCallbackHolder fileChooser;

    private final ActivityResultLauncher<Intent> scannerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK && data != null) {
                    String mode = data.getStringExtra("mode");
                    String text = data.getStringExtra("text");
                    String format = data.getStringExtra("format");
                    if (mode != null && text != null) {
                        invokeJS("window.__nativeScanResult(" + JSONObject.quote(mode) + "," +
                                JSONObject.quote(text) + "," + JSONObject.quote(format == null ? "" : format) + ");");
                    }
                } else if (data != null && data.hasExtra("error")) {
                    invokeJS("window.__nativeScanError(" + JSONObject.quote(data.getStringExtra("error")) + ");");
                }
            });

    private final ActivityResultLauncher<Intent> openFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileChooser == null) return;
                android.webkit.ValueCallback<Uri[]> callback = fileChooser.callback;
                fileChooser = null;
                Uri[] values = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData());
                callback.onReceiveValue(values);
            });

    private final ActivityResultLauncher<Intent> createDocumentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                boolean saved = false;
                if (pendingFile != null && result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                            if (out == null) throw new IllegalStateException("Não foi possível abrir o arquivo");
                            out.write(pendingFile);
                            out.flush();
                            saved = true;
                            Toast.makeText(this, "Arquivo salvo com sucesso", Toast.LENGTH_SHORT).show();
                        } catch (Exception ex) {
                            Toast.makeText(this, "Falha ao salvar: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
                String shareAfterSaving = pendingShareText;
                pendingFile = null;
                pendingShareText = null;
                if (saved && shareAfterSaving != null && !shareAfterSaving.isEmpty()) {
                    shareTextInAndroid(shareAfterSaving);
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // arquivos escolhidos manualmente via seletor Android
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url.toString().startsWith(BASE)) return loader.shouldInterceptRequest(url);
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // A bridge de acesso ao Android só deve ser usada pelo HTML local do APK.
                return !request.getUrl().toString().startsWith(BASE);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView webView,
                    android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooser != null) fileChooser.callback.onReceiveValue(null);
                fileChooser = new ValueCallbackHolder(callback);
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/json", "application/vnd.ms-excel", "application/octet-stream"});
                try { openFileLauncher.launch(intent); }
                catch (Exception ex) { fileChooser = null; callback.onReceiveValue(null); return false; }
                return true;
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "BipadorAndroid");
        webView.loadUrl(HOME);
    }

    private void invokeJS(String source) {
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(source, null); });
    }

    private static class ValueCallbackHolder {
        final android.webkit.ValueCallback<Uri[]> callback;
        ValueCallbackHolder(android.webkit.ValueCallback<Uri[]> callback) { this.callback = callback; }
    }

    public class AndroidBridge {
        @JavascriptInterface public void scan(String mode) {
            if (!("order".equals(mode) || "list".equals(mode) || "product".equals(mode) || "ean-catalog".equals(mode))) return;
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, ScannerActivity.class);
                intent.putExtra("mode", mode);
                scannerLauncher.launch(intent);
            });
        }

        @JavascriptInterface public void saveText(String filename, String mime, String content) {
            runOnUiThread(() -> selectFileToSave(filename, mime, content, null));
        }

        @JavascriptInterface public void saveReport(String filename, String mime, String content, String shareAfterSaving) {
            runOnUiThread(() -> selectFileToSave(filename, mime, content, shareAfterSaving));
        }

        @JavascriptInterface public void shareText(String text) {
            runOnUiThread(() -> shareTextInAndroid(text));
        }

        @JavascriptInterface public void feedback(boolean ok) {
            runOnUiThread(() -> playFeedback(ok));
        }
    }

    private void selectFileToSave(String filename, String mime, String content, String shareAfterSaving) {
        String safe = (filename == null ? "relatorio.txt" : filename).replaceAll("[\\/:*?\"<>|]", "_");
        if (safe.isEmpty()) safe = "relatorio.txt";
        pendingFile = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        pendingShareText = shareAfterSaving;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime == null || mime.isEmpty() ? "text/plain" : mime);
        intent.putExtra(Intent.EXTRA_TITLE, safe);
        try { createDocumentLauncher.launch(intent); }
        catch (Exception ex) {
            pendingFile = null;
            pendingShareText = null;
            Toast.makeText(this, "Não foi possível abrir o salvamento", Toast.LENGTH_LONG).show();
        }
    }

    private void shareTextInAndroid(String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        try { startActivity(Intent.createChooser(send, "Compartilhar conferência")); }
        catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "Nenhum aplicativo disponível para compartilhar", Toast.LENGTH_LONG).show();
        }
    }

    private void playFeedback(boolean ok) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tone.stopTone();
            if (ok) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 170);
            } else {
                for (int i = 0; i < 6; i++) {
                    int delay = i * 330;
                    handler.postDelayed(() -> {
                        if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 250);
                    }, delay);
                }
            }
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = ok ? new long[]{0, 70} : new long[]{0, 280, 120, 280, 120, 280};
                if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
                else vibrator.vibrate(pattern, -1);
            }
        } catch (Exception ignored) { }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tone != null) { tone.release(); tone = null; }
        if (fileChooser != null) { fileChooser.callback.onReceiveValue(null); fileChooser = null; }
        if (webView != null) { webView.removeJavascriptInterface("BipadorAndroid"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
