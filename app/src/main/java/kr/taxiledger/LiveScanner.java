package kr.taxiledger;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 영수증 위에 폰을 대고 있으면 카메라 화면을 계속 읽다가,
 * 웹 화면(Ledger.onLiveFrame)이 "ok" 라고 답하면 소리·진동과 함께 닫힌다.
 * 영수증 해석은 index.html 의 parseReceipt 한 곳에서만 한다.
 */
class LiveScanner {

    interface Host {
        /** 읽은 줄(JSON)을 웹 화면에 넘기고, 답("ok", "closer" ...)을 콜백으로 받는다. */
        void sendFrame(String json, java.util.function.Consumer<String> reply);
        void onClosed(boolean success);
        void takePhotoInstead();
    }

    private final ComponentActivity act;
    private final Host host;
    private final FrameLayout overlay;
    private final PreviewView preview;
    private final TextView guide;
    private final Button torchBtn;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

    private ProcessCameraProvider provider;
    private Camera camera;
    private volatile boolean busy = false, running = false;
    private boolean torchOn = false;
    private long startedAt;

    LiveScanner(ComponentActivity act, FrameLayout root, Host host) {
        this.act = act;
        this.host = host;

        overlay = new FrameLayout(act);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);

        preview = new PreviewView(act);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        overlay.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        // 영수증을 맞출 테두리
        View frame = new View(act);
        GradientDrawable border = new GradientDrawable();
        border.setStroke(dp(3), Color.parseColor("#3FC79C"));
        border.setCornerRadius(dp(16));
        frame.setBackground(border);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, -1);
        flp.setMargins(dp(20), dp(24), dp(20), dp(190));
        overlay.addView(frame, flp);

        // 아래쪽 안내 + 버튼
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#E6000000"));
        panel.setPadding(dp(16), dp(14), dp(16), dp(18));

        guide = new TextView(act);
        guide.setTextColor(Color.WHITE);
        guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        guide.setGravity(Gravity.CENTER);
        guide.setMinLines(2);
        panel.addView(guide, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(60));
        rowLp.topMargin = dp(12);
        torchBtn = button("불 켜기", v -> toggleTorch());
        row.addView(torchBtn, weight());
        row.addView(button("사진으로", v -> { stop(); host.takePhotoInstead(); }), weight());
        row.addView(button("닫기", v -> close(false)), weight());
        panel.addView(row, rowLp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        overlay.addView(panel, plp);

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    boolean isOpen() { return overlay.getVisibility() == View.VISIBLE; }

    void open() {
        overlay.setVisibility(View.VISIBLE);
        guide.setText("영수증 위에 폰을 대 주세요\n자동으로 읽어요");
        startedAt = System.currentTimeMillis();
        running = true;
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(act);
        f.addListener(() -> {
            try {
                provider = f.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                ResolutionSelector rs = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1600, 1200),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ImageAnalysis ana = new ImageAnalysis.Builder()
                        .setResolutionSelector(rs)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                ana.setAnalyzer(exec, this::analyze);
                provider.unbindAll();
                camera = provider.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, pv, ana);
                torchOn = false;
                torchBtn.setText("불 켜기");
            } catch (Exception e) {
                guide.setText("카메라를 열지 못했어요\n'사진으로'를 눌러 주세요");
            }
        }, ContextCompat.getMainExecutor(act));
    }

    void close(boolean success) {
        stop();
        overlay.setVisibility(View.GONE);
        host.onClosed(success);
    }

    private void stop() {
        running = false;
        if (provider != null) provider.unbindAll();
        camera = null;
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        torchBtn.setText(torchOn ? "불 끄기" : "불 켜기");
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy proxy) {
        if (!running || busy || proxy.getImage() == null) { proxy.close(); return; }
        busy = true;
        InputImage img = InputImage.fromMediaImage(proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        recognizer.process(img)
                .addOnSuccessListener(text -> {
                    String json = toJson(text);
                    act.runOnUiThread(() -> host.sendFrame(json, this::onReply));
                })
                .addOnFailureListener(e -> busy = false)
                .addOnCompleteListener(t -> proxy.close());
    }

    private void onReply(String r) {
        busy = false;
        if (!running) return;
        if ("ok".equals(r)) {
            beep();
            close(true);
            return;
        }
        boolean slow = System.currentTimeMillis() - startedAt > 12000;
        String msg;
        switch (r == null ? "" : r) {
            case "hold": msg = "좋아요! 그대로 잠깐만 멈춰 주세요"; break;
            case "more": msg = "총합계부터 결제수단 금액까지\n한 화면에 다 보이게 해 주세요"; break;
            case "closer": msg = "영수증에 조금 더 가까이 대 주세요"; break;
            default: msg = "영수증 위에 폰을 대 주세요\n자동으로 읽어요";
        }
        if (slow && !"hold".equals(r)) msg += torchOn ? "\n(영수증을 평평하게 펴 주세요)" : "\n(어두우면 '불 켜기'를 눌러 보세요)";
        guide.setText(msg);
    }

    private void beep() {
        try { new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_ACK, 250); } catch (Exception ignored) { }
        try {
            Vibrator v = (Vibrator) act.getSystemService(ComponentActivity.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(150);
            }
        } catch (Exception ignored) { }
    }

    static String toJson(Text text) {
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
            return new JSONObject().put("lines", lines).put("live", true).toString();
        } catch (Exception e) {
            return "{\"lines\":[]}";
        }
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
