package kr.taxiledger;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 화면은 assets/index.html (웹 화면) 이 그리고,
 * 이 클래스는 폰 기능(카메라 + 글자 읽기, 엑셀 저장, 백업)만 맡습니다.
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private WebView web;
    private Uri photoUri;
    private File photoFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100); // 폰 글꼴 크기 설정으로 화면이 깨지지 않게 (앱 자체가 큰 글씨)

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ---------- 카메라 → 글자 읽기 ----------
    private void startCamera() {
        try {
            File f = new File(getCacheDir(), "receipt.jpg");
            if (f.exists()) f.delete();
            photoFile = f;
            photoUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            js("Ledger.onScan({error:'camera'})");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAMERA) return;
        if (resultCode != RESULT_OK) { js("Ledger.onScan({error:'cancel'})"); return; }
        js("Ledger.onScanStart()");
        try {
            if (photoFile == null || !photoFile.exists() || photoFile.length() == 0) { js("Ledger.onScan({error:'nophoto'})"); return; }
            final Bitmap bmp = loadUpright(photoFile);
            if (bmp == null) { js("Ledger.onScan({error:'nophoto'})"); return; }
            InputImage img = InputImage.fromBitmap(bmp, 0);
            TextRecognizer rec = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            rec.process(img)
                .addOnSuccessListener(text -> js("Ledger.onScan(" + toJson(text, bmp.getWidth(), bmp.getHeight()) + ")"))
                .addOnFailureListener(e -> js("Ledger.onScan({error:'ocr'})"));
        } catch (Exception e) {
            js("Ledger.onScan({error:'ocr'})");
        }
    }

    /**
     * 갤럭시 카메라는 사진을 돌려 저장하지 않고 '방향 표시'만 붙인다.
     * 그 표시대로 바로 세우고, 너무 큰 사진은 2000px 정도로 줄여서 글자 인식에 넘긴다.
     */
    private Bitmap loadUpright(File f) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int max = Math.max(o.outWidth, o.outHeight), sample = 1;
        while (max / (sample * 2) >= 2000) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o2);
        if (b == null) return null;
        int rot = 0;
        switch (new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            case ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
            case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
            case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
        }
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        return b;
    }

    /** 읽은 글자를 줄 단위로, 위치와 함께 넘긴다. 해석은 index.html 의 parseReceipt 가 한다. */
    private String toJson(Text text, int w, int h) {
        JSONArray lines = new JSONArray();
        try {
            for (Text.TextBlock b : text.getTextBlocks()) {
                for (Text.Line l : b.getLines()) {
                    Rect r = l.getBoundingBox();
                    JSONObject o = new JSONObject();
                    o.put("t", l.getText());
                    if (r != null) { o.put("x", r.left); o.put("y", r.top); o.put("w", r.width()); o.put("h", r.height()); }
                    lines.put(o);
                }
            }
            JSONObject out = new JSONObject();
            out.put("lines", lines);
            out.put("w", w);
            out.put("h", h);
            return out.toString();
        } catch (Exception e) {
            return "{\"error\":\"ocr\"}";
        }
    }

    // ---------- 웹 화면에서 부르는 기능 ----------
    private class Bridge {
        @JavascriptInterface
        public void scanReceipt() {
            runOnUiThread(MainActivity.this::startCamera);
        }

        /** 엑셀 파일을 '다운로드' 폴더에 저장하고, 카톡 등으로 보낼 수 있게 공유 창을 연다. */
        @JavascriptInterface
        public void saveXlsx(String base64, String name) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                Uri saved = null;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    saved = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (saved != null) try (OutputStream os = getContentResolver().openOutputStream(saved)) { os.write(bytes); }
                }
                File share = new File(getCacheDir(), name);
                try (FileOutputStream fos = new FileOutputStream(share)) { fos.write(bytes); }
                Uri shareUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", share);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, shareUri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "엑셀 파일 보내기")));
                toast(saved != null ? "다운로드 폴더에 " + name + " 저장했어요" : name + " 만들었어요");
            } catch (Exception e) {
                toast("엑셀 파일을 저장하지 못했어요");
            }
        }

        /** 기록 백업: 앱 저장공간에 한 벌 더 보관 */
        @JavascriptInterface
        public void backup(String json) {
            try (FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), "backup.json"))) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String restore() {
            File f = new File(getFilesDir(), "backup.json");
            if (!f.exists()) return "";
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int n = fis.read(buf);
                return new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
