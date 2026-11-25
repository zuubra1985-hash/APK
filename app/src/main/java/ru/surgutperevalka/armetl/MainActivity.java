package ru.surgutperevalka.armetl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private WebViewAssetLoader assetLoader;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraPhotoUri;
    private PermissionRequest pendingWebPermissionRequest;
    private Runnable pendingCameraAction;
    private long lastBackPressTime = 0L;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (pendingWebPermissionRequest != null) {
                    if (isGranted) {
                        pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
                    } else {
                        pendingWebPermissionRequest.deny();
                        Toast.makeText(this, "Нужен доступ к камере для сканирования QR", Toast.LENGTH_LONG).show();
                    }
                    pendingWebPermissionRequest = null;
                }
                if (pendingCameraAction != null) {
                    Runnable action = pendingCameraAction;
                    pendingCameraAction = null;
                    if (isGranted) {
                        action.run();
                    } else {
                        Toast.makeText(this, "Доступ к камере отклонён", Toast.LENGTH_SHORT).show();
                        cancelFileChooser();
                    }
                }
            });

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback == null) {
                    return;
                }
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data != null && data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (pendingCameraPhotoUri != null) {
                        results = new Uri[]{pendingCameraPhotoUri};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
                pendingCameraPhotoUri = null;
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectAndroidBridgeShim();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needsCamera = false;
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                            needsCamera = true;
                            break;
                        }
                    }
                    if (needsCamera) {
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                        } else {
                            pendingWebPermissionRequest = request;
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
                        }
                    } else {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                cancelFileChooser();
                MainActivity.this.filePathCallback = filePathCallback;

                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean wantsImage = fileChooserParams.isCaptureEnabled();
                if (!wantsImage && acceptTypes != null) {
                    for (String t : acceptTypes) {
                        if (t != null && t.toLowerCase(Locale.ROOT).contains("image")) {
                            wantsImage = true;
                            break;
                        }
                    }
                }

                if (wantsImage) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        launchImageChooser();
                    } else {
                        pendingCameraAction = MainActivity.this::launchImageChooser;
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
                    }
                } else {
                    launchDocumentChooser();
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.app_name))
                        .setMessage(message)
                        .setPositiveButton("ОК", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.app_name))
                        .setMessage(message)
                        .setPositiveButton("Да", (d, w) -> result.confirm())
                        .setNegativeButton("Отмена", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.app_name))
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("ОК", (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("Отмена", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastBackPressTime < 2000L) {
                        finish();
                    } else {
                        lastBackPressTime = now;
                        Toast.makeText(MainActivity.this, "Нажмите «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(APP_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    private void cancelFileChooser() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        pendingCameraPhotoUri = null;
    }

    private void launchImageChooser() {
        try {
            File camDir = new File(getCacheDir(), "camera");
            if (!camDir.exists()) {
                camDir.mkdirs();
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File photoFile = new File(camDir, "defect_" + stamp + ".jpg");
            pendingCameraPhotoUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photoFile
            );

            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraPhotoUri);
            cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
            galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
            galleryIntent.setType("image/*");

            Intent chooser = Intent.createChooser(galleryIntent, "Сфотографировать или выбрать фото");
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
            fileChooserLauncher.launch(chooser);
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка запуска камеры: " + e.getMessage(), Toast.LENGTH_LONG).show();
            cancelFileChooser();
        }
    }

    private void launchDocumentChooser() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            fileChooserLauncher.launch(Intent.createChooser(intent, "Выберите файл синхронизации (.json)"));
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка выбора файла: " + e.getMessage(), Toast.LENGTH_LONG).show();
            cancelFileChooser();
        }
    }

    private void injectAndroidBridgeShim() {
        String js =
                "(function(){" +
                "  if (window.__armEtlBridgeInstalled) return;" +
                "  window.__armEtlBridgeInstalled = true;" +
                "  function blobToBase64(blob) {" +
                "    return new Promise(function(resolve, reject) {" +
                "      var reader = new FileReader();" +
                "      reader.onloadend = function() {" +
                "        var res = reader.result || '';" +
                "        var idx = res.indexOf(',');" +
                "        resolve(idx >= 0 ? res.slice(idx + 1) : res);" +
                "      };" +
                "      reader.onerror = reject;" +
                "      reader.readAsDataURL(blob);" +
                "    });" +
                "  }" +
                "  var origClick = HTMLAnchorElement.prototype.click;" +
                "  HTMLAnchorElement.prototype.click = function() {" +
                "    if (this.download && this.href && this.href.indexOf('blob:') === 0 && window.AndroidBridge) {" +
                "      var filename = this.download;" +
                "      var href = this.href;" +
                "      fetch(href).then(function(r){ return r.blob(); }).then(function(blob){" +
                "        return blobToBase64(blob).then(function(b64){" +
                "          window.AndroidBridge.saveAndShareFile(b64, filename, blob.type || 'application/octet-stream', true);" +
                "        });" +
                "      }).catch(function(err){ console.error('Bridge download error:', err); });" +
                "      return;" +
                "    }" +
                "    return origClick.apply(this, arguments);" +
                "  };" +
                "  navigator.canShare = function(data) {" +
                "    return !!(data && data.files && data.files.length > 0);" +
                "  };" +
                "  navigator.share = function(data) {" +
                "    return new Promise(function(resolve, reject) {" +
                "      if (!data || !data.files || !data.files.length || !window.AndroidBridge) {" +
                "        return reject(new Error('No files to share'));" +
                "      }" +
                "      var f = data.files[0];" +
                "      blobToBase64(f).then(function(b64){" +
                "        window.AndroidBridge.saveAndShareFile(b64, f.name || 'export.json', f.type || 'application/json', true);" +
                "        resolve();" +
                "      }).catch(reject);" +
                "    });" +
                "  };" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void saveAndShareFile(String base64Data, String rawFilename, String mimeType, boolean openShareSheet) {
            runOnUiThread(() -> {
                try {
                    String safeName = (rawFilename == null || rawFilename.trim().isEmpty())
                            ? "arm_etl_export.dat"
                            : rawFilename.replaceAll("[\\\\/:*?\"<>|]", "_");
                    String mime = (mimeType == null || mimeType.trim().isEmpty())
                            ? "application/octet-stream"
                            : mimeType;
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);

                    saveToDownloads(safeName, mime, bytes);

                    File sharedDir = new File(getCacheDir(), "shared");
                    if (!sharedDir.exists()) {
                        sharedDir.mkdirs();
                    }
                    File outFile = new File(sharedDir, safeName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                    }

                    Toast.makeText(MainActivity.this, "Сохранено в Загрузки: " + safeName, Toast.LENGTH_LONG).show();

                    if (openShareSheet) {
                        Uri contentUri = FileProvider.getUriForFile(
                                MainActivity.this,
                                getPackageName() + ".fileprovider",
                                outFile
                        );
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType(mime);
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, safeName);
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "Отправить / открыть файл: " + safeName));
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка сохранения файла: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void saveToDownloads(String filename, String mimeType, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ARM_ETL");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(data);
                    }
                }
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ARM_ETL");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
        }
    }
}
