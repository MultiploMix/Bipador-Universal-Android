package br.com.multiplomix.bipador;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Leitor nativo CameraX + ML Kit. Fecha no primeiro código válido. */
public class ScannerActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST = 42;
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final AtomicBoolean delivered = new AtomicBoolean(false);
    private ExecutorService analyzerExecutor;
    private ProcessCameraProvider provider;
    private Camera camera;
    private BarcodeScanner detector;
    private PreviewView previewView;
    private Button torchButton;
    private SeekBar zoomBar;
    private TextView status;
    private String mode;
    private boolean torchOn = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "product";
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        buildScreen();
        analyzerExecutor = Executors.newSingleThreadExecutor();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST);
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);view.setTextColor(Color.WHITE);view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private void buildScreen() {
        FrameLayout root = new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1,-1));
        View guide = new Viewfinder(this, "order".equals(mode)||"list".equals(mode));
        root.addView(guide, new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top = new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(16),dp(18),dp(16),dp(14));top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xD7111722);
        Button close=new Button(this);close.setText("✕");close.setTextColor(Color.WHITE);close.setTextSize(22);
        close.setAllCaps(false);close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF39475E));
        top.addView(close,new LinearLayout.LayoutParams(dp(54),dp(52)));close.setOnClickListener(v->finish());
        String title = "order".equals(mode)?"QR do pedido": "list".equals(mode)?"QR da lista": "ean-catalog".equals(mode)?"Cadastrar EAN":"QR ou EAN do produto";
        TextView caption=label(title,18,true);caption.setGravity(Gravity.CENTER);
        top.addView(caption,new LinearLayout.LayoutParams(0,dp(52),1f));
        View pad = new View(this);top.addView(pad,new LinearLayout.LayoutParams(dp(54),dp(1)));
        root.addView(top, new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xD9111722);bottom.setPadding(dp(18),dp(14),dp(18),dp(20));
        status=label("Aponte o código para a câmera · toque na tela para focar",13,false);
        status.setGravity(Gravity.CENTER);bottom.addView(status,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
        torchButton = new Button(this);torchButton.setAllCaps(false);torchButton.setText("🔦 Lanterna");
        torchButton.setTextColor(Color.WHITE);torchButton.setTextSize(14);
        torchButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF384860));
        torchButton.setEnabled(false);tools.addView(torchButton,new LinearLayout.LayoutParams(dp(132),dp(52)));
        torchButton.setOnClickListener(v->toggleTorch());
        TextView zoomLabel=label("  Zoom",14,true);tools.addView(zoomLabel,new LinearLayout.LayoutParams(dp(56),dp(44)));
        zoomBar=new SeekBar(this);zoomBar.setMax(100);zoomBar.setProgress(0);
        zoomBar.setEnabled(false);tools.addView(zoomBar,new LinearLayout.LayoutParams(0,dp(44),1f));
        bottom.addView(tools);
        FrameLayout.LayoutParams bottomParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(bottom,bottomParams);

        previewView.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_UP && camera!=null){
                androidx.camera.core.MeteringPoint point=previewView.getMeteringPointFactory().createPoint(e.getX(),e.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point,FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3,TimeUnit.SECONDS).build());return true;
            }
            return true;
        });
        setContentView(root);
    }

    private void startCamera() {
        try {
            int[] formats;
            if ("order".equals(mode) || "list".equals(mode))
                formats=new int[]{Barcode.FORMAT_QR_CODE};
            else if ("ean-catalog".equals(mode))
                formats=new int[]{Barcode.FORMAT_EAN_13,Barcode.FORMAT_EAN_8,Barcode.FORMAT_UPC_A,Barcode.FORMAT_UPC_E,Barcode.FORMAT_CODE_128};
            else
                formats=new int[]{Barcode.FORMAT_QR_CODE,Barcode.FORMAT_EAN_13,Barcode.FORMAT_EAN_8,Barcode.FORMAT_UPC_A,Barcode.FORMAT_UPC_E,Barcode.FORMAT_CODE_128};
            detector = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(formats[0], java.util.Arrays.copyOfRange(formats,1,formats.length)).build());
            ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
            future.addListener(()->{
                try {
                    if (isFinishing() || isDestroyed()) return;
                    provider=future.get();provider.unbindAll();
                    Preview preview=new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                    ImageAnalysis analysis=new ImageAnalysis.Builder()
                            .setTargetResolution(new Size(1280,720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                    analysis.setAnalyzer(analyzerExecutor, this::analyze);
                    camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis);
                    torchButton.setEnabled(camera.getCameraInfo().hasFlashUnit());
                    zoomBar.setEnabled(true);
                    zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                        @Override public void onProgressChanged(SeekBar bar,int progress,boolean user){if(user && camera!=null)camera.getCameraControl().setLinearZoom(progress/100f);}
                        @Override public void onStartTrackingTouch(SeekBar bar){}
                        @Override public void onStopTrackingTouch(SeekBar bar){}
                    });
                    camera.getCameraInfo().getZoomState().observe(this,zoom->{
                        if(zoom!=null && !zoomBar.isPressed())zoomBar.setProgress(Math.round(zoom.getLinearZoom()*100));
                    });
                } catch(Exception err){showError("Não foi possível abrir a câmera: "+err.getMessage());}
            },ContextCompat.getMainExecutor(this));
        } catch (Exception err) { showError("Leitor indisponível: "+err.getMessage()); }
    }

    @androidx.camera.core.ExperimentalGetImage
    private void analyze(@NonNull ImageProxy frame) {
        if (delivered.get() || !decoding.compareAndSet(false,true)) {frame.close();return;}
        android.media.Image media=frame.getImage();
        if(media==null){decoding.set(false);frame.close();return;}
        InputImage img=InputImage.fromMediaImage(media,frame.getImageInfo().getRotationDegrees());
        detector.process(img).addOnSuccessListener(results->{
            if(delivered.get())return;
            for (Barcode barcode:results){
                String value=barcode.getRawValue();
                if(value==null||value.trim().isEmpty())continue;
                if(!delivered.compareAndSet(false,true))return;
                runOnUiThread(()->{
                    Intent result=new Intent();
                    result.putExtra("mode",mode);
                    result.putExtra("text",value);
                    result.putExtra("format",String.valueOf(barcode.getFormat()));
                    setResult(RESULT_OK,result);
                    finish();
                });
                return;
            }
        }).addOnFailureListener(e->{
            // Uma imagem ruim é normal; não interromper o leitor.
        }).addOnCompleteListener(task->{frame.close();decoding.set(false);});
    }

    private void toggleTorch(){
        if(camera==null||!camera.getCameraInfo().hasFlashUnit())return;
        torchOn=!torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        torchButton.setText(torchOn?"🔦 Desligar":"🔦 Lanterna");
    }

    private void showError(String message){
        runOnUiThread(()->{
            Toast.makeText(this,message,Toast.LENGTH_LONG).show();
            Intent result=new Intent();result.putExtra("error",message);
            setResult(RESULT_FIRST_USER,result);finish();
        });
    }
    @Override public void onRequestPermissionsResult(int code,@NonNull String[] p,@NonNull int[] grant){
        super.onRequestPermissionsResult(code,p,grant);
        if(code!=PERMISSION_REQUEST)return;
        if(grant.length>0&&grant[0]==PackageManager.PERMISSION_GRANTED)startCamera();
        else showError("Permita o uso da câmera nas configurações do Android.");
    }
    @Override protected void onDestroy(){
        delivered.set(true);
        if(provider!=null)provider.unbindAll();
        if(detector!=null)detector.close();
        if(analyzerExecutor!=null)analyzerExecutor.shutdown();
        super.onDestroy();
    }

    /** Guias mínimos: nada fica na frente dos códigos. */
    private static class Viewfinder extends View {
        private final Paint p = new Paint(3);
        private final boolean square;
        Viewfinder(android.content.Context c,boolean square){super(c);this.square=square;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float w=getWidth(),h=getHeight();
            float frameW=w*.82f;
            float frameH=square?frameW:Math.min(frameW*.53f,h*.26f);
            float cx=w/2,cy=h*.45f;
            RectF r=new RectF(cx-frameW/2,cy-frameH/2,cx+frameW/2,cy+frameH/2);
            p.setColor(0xDDFFFFFF);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.012f);p.setStrokeCap(Paint.Cap.ROUND);
            float len=Math.min(frameW,frameH)*.19f;
            for(int ix=0;ix<2;ix++)for(int iy=0;iy<2;iy++){
                float x=ix==0?r.left:r.right,y=iy==0?r.top:r.bottom;
                float sx=ix==0?1:-1,sy=iy==0?1:-1;
                canvas.drawLine(x,y,x+sx*len,y,p);
                canvas.drawLine(x,y,x,y+sy*len,p);
            }
        }
    }
}
