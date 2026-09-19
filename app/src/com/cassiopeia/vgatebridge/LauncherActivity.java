package com.cassiopeia.vgatebridge;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Pantalla de inicio (launcher) de Polar Star.
 *
 * POR QUÉ UN WEBVIEW Y NO UN LAYOUT XML:
 * la interfaz vive en `assets/launcher/` como HTML/CSS/JS, así que se puede montar
 * y revisar en un navegador de Cassiopeia antes de tocar la tablet. Además, un
 * error de JavaScript NO tumba el proceso: el sniffer CAN y el bridge siguen
 * funcionando aunque la pantalla falle. En un SoC flojo como el UIS8581A eso no es
 * teoría, es la diferencia entre "no se ve bien" y "el coche deja de registrar".
 *
 * SIN RED A PROPÓSITO: la pantalla carga solo ficheros locales del APK. El WebView
 * tiene las conexiones de red bloqueadas, así que ningún contenido externo puede
 * pedir datos ni colarse. Todo lo que se muestra viene del propio coche.
 *
 * Esta Activity NO habla con el coche todavía: eso llega con el puente JS (tarea 7
 * del plan). De momento la pantalla detecta que no hay puente y usa datos
 * simulados, para poder revisarla sin tablet.
 */
public class LauncherActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);

        // El launcher está siempre visible mientras se conduce: la pantalla no se
        // apaga sola (si el coche está en marcha, la tablet va alimentada).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings cfg = web.getSettings();
        cfg.setJavaScriptEnabled(true);      // la interfaz es JS
        cfg.setAllowFileAccess(true);        // file:///android_asset
        cfg.setBlockNetworkLoads(true);      // nada de red: todo es local
        cfg.setDomStorageEnabled(false);     // no hace falta y evita estado pegado
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient());   // los enlaces no salen del WebView
        setContentView(web);

        pantallaCompleta();
        web.loadUrl("file:///android_asset/launcher/index.html");
    }

    /** Oculta barras del sistema: en un salpicadero sobran. */
    private void pantallaCompleta() {
        View v = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= 19) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        v.setSystemUiVisibility(flags);
    }

    /**
     * Atrás no debe salir de la pantalla de inicio: en un launcher, "atrás" es una
     * pulsación sin destino. Si se dejara pasar, el botón cerraría la pantalla y
     * dejaría la tablet sin interfaz hasta reiniciar.
     */
    @Override
    public void onBackPressed() {
        // Intencionadamente vacío.
    }

    @Override
    protected void onResume() {
        super.onResume();
        pantallaCompleta();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
