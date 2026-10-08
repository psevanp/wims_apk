package fr.rosaparks.messagerie

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private companion object {
        const val URL_ACCUEIL = "https://messagerie.education.gouv.fr/"

        const val PREFS_FILE = "messagerie_secure"
        const val KEY_ACAD = "academie"
        const val KEY_LOGIN = "login"
        const val KEY_PASS = "pass"

        // Délais minimaux entre deux envois automatiques : évite de boucler
        // (et de bloquer le compte) si le mot de passe est faux.
        const val DELAI_LOGIN_MS = 60_000L
        const val DELAI_ACADEMIE_MS = 15_000L

        val ACADEMIES = arrayOf(
            "Aix-Marseille", "Amiens", "Besançon", "Bordeaux", "Clermont-Ferrand", "Corse",
            "Créteil", "Dijon", "Grenoble", "Guadeloupe", "Guyane", "La Réunion", "Lille",
            "Limoges", "Lyon", "Martinique", "Mayotte", "Montpellier", "Nancy-Metz", "Nantes",
            "Nice", "Normandie", "Orléans-Tours", "Paris", "Poitiers", "Reims", "Rennes",
            "Strasbourg", "Toulouse", "Versailles"
        )

        // Choix de l'académie sur la page du service de découverte (hub.phm.education.gouv.fr/ds).
        // Cherche un élément cliquable dont le texte correspond au nom de l'académie.
        val SCRIPT_ACADEMIE = """
            (function(NAME){
              if(window.__acadDone){return;}
              function norm(s){return (s||'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();}
              var cible=norm(NAME);
              function essai(){
                var els=document.querySelectorAll('a,button,input[type=submit],input[type=button],label,li,option,[role=button],[role=option],[role=link]');
                for(var i=0;i<els.length;i++){
                  var el=els[i];
                  var t=norm(el.tagName==='INPUT'?el.value:el.textContent);
                  if(t.indexOf(cible)<0||t.length>cible.length+30){continue;}
                  window.__acadDone=true;
                  if(el.tagName==='OPTION'){
                    var sel=el.parentNode;
                    while(sel&&sel.tagName!=='SELECT'){sel=sel.parentNode;}
                    if(sel){
                      sel.value=el.value;
                      sel.dispatchEvent(new Event('change',{bubbles:true}));
                      var f=sel.form;
                      if(f){
                        var b=f.querySelector('input[type=submit],button[type=submit],button:not([type])');
                        if(b){b.click();}else{f.submit();}
                      }
                    }
                    return true;
                  }
                  el.click();
                  if(el.tagName==='LABEL'||el.tagName==='LI'){
                    var f2=el.closest('form');
                    if(f2){
                      var b2=f2.querySelector('input[type=submit],button[type=submit]');
                      if(b2){setTimeout(function(){b2.click();},300);}
                    }
                  }
                  return true;
                }
                return false;
              }
              var n=0;
              var timer=setInterval(function(){
                n++;
                if(window.__acadDone||essai()||n>40){clearInterval(timer);}
              },300);
            })(__NAME__);
        """.trimIndent()

        // Remplit identifiant + mot de passe sur la page de connexion de l'académie
        // et valide le formulaire. N'agit que s'il trouve un champ mot de passe.
        val SCRIPT_LOGIN = """
            (function(L,P){
              if(window.__loginDone){return;}
              function setv(el,v){
                var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
                if(d&&d.set){d.set.call(el,v);}else{el.value=v;}
                el.dispatchEvent(new Event('input',{bubbles:true}));
                el.dispatchEvent(new Event('change',{bubbles:true}));
              }
              function essai(){
                var pw=document.querySelector('input[type=password]');
                if(!pw){return false;}
                var racine=pw.form||document;
                var all=racine.querySelectorAll('input');
                var u=null;
                for(var i=0;i<all.length;i++){
                  if(all[i]===pw){break;}
                  var t=(all[i].getAttribute('type')||'text').toLowerCase();
                  if((t==='text'||t==='email'||t==='tel'||t==='number')&&!all[i].disabled&&all[i].offsetParent!==null){u=all[i];}
                }
                if(!u){return false;}
                window.__loginDone=true;
                setv(u,L);
                setv(pw,P);
                try{AndroidBridge.soumis();}catch(e){}
                var b=racine.querySelector('input[type=submit],button[type=submit],button:not([type])');
                setTimeout(function(){
                  if(b){b.click();}else if(pw.form){pw.form.submit();}
                },200);
                return true;
              }
              var n=0;
              var timer=setInterval(function(){
                n++;
                if(window.__loginDone||essai()||n>40){clearInterval(timer);}
              },300);
            })(__L__,__P__);
        """.trimIndent()
    }

    private lateinit var prefs: SharedPreferences
    private var webView: WebView? = null

    @Volatile private var dernierEssai = 0L
    private var dernierChoix = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ouvrirPrefs()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = webView
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })

        if (identifiantsEnregistres()) afficherWeb() else afficherReglages()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- stockage chiffré

    private fun ouvrirPrefs(): SharedPreferences {
        fun creer(): SharedPreferences {
            val cle = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                PREFS_FILE,
                cle,
                this,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return try {
            creer()
        } catch (e: Exception) {
            deleteSharedPreferences(PREFS_FILE)
            creer()
        }
    }

    private fun identifiantsEnregistres(): Boolean =
        !prefs.getString(KEY_ACAD, null).isNullOrEmpty() &&
            !prefs.getString(KEY_LOGIN, null).isNullOrEmpty() &&
            !prefs.getString(KEY_PASS, null).isNullOrEmpty()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- domaines de confiance

    /** Domaine de l'académie choisie, par ex. ac-aix-marseille.fr (seul domaine où les identifiants sont injectés). */
    private fun domaineAcademie(): String {
        val nom = prefs.getString(KEY_ACAD, "Aix-Marseille") ?: "Aix-Marseille"
        val slug = when (nom) {
            "La Réunion" -> "reunion"
            "Clermont-Ferrand" -> "clermont"
            else -> Normalizer.normalize(nom, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
        }
        return "ac-$slug.fr"
    }

    private fun surDomaine(host: String, domaine: String): Boolean =
        host == domaine || host.endsWith(".$domaine")

    // ---------------------------------------------------------------- écran de réglages

    private fun afficherReglages() {
        webView?.destroy()
        webView = null

        val bleu = Color.parseColor("#1a73e8")
        val gris = Color.parseColor("#6b7280")

        val racine = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#f6f7fb"))
            isFillViewport = true
        }
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        racine.addView(
            colonne,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        colonne.addView(TextView(this).apply {
            text = "✉️ Messagerie Éducation"
            textSize = 26f
            setTextColor(bleu)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        colonne.addView(TextView(this).apply {
            text = "À remplir une seule fois. Ensuite, un clic suffit."
            textSize = 15f
            setTextColor(gris)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(24))
        })

        colonne.addView(TextView(this).apply {
            text = "Mon académie"
            textSize = 16f
            setTextColor(Color.parseColor("#1f2937"))
            setTypeface(typeface, Typeface.BOLD)
        })

        val choixAcademie = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ACADEMIES
            )
        }
        colonne.addView(choixAcademie, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        val champLogin = EditText(this).apply {
            hint = "Identifiant"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val champPass = EditText(this).apply {
            hint = "Mot de passe"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        colonne.addView(champLogin, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        colonne.addView(champPass, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        val message = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#e11d48"))
            setPadding(0, dp(12), 0, 0)
        }
        colonne.addView(message)

        val bouton = Button(this).apply {
            text = "Enregistrer et ouvrir la messagerie"
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(bleu)
                cornerRadius = dp(12).toFloat()
            }
        }
        colonne.addView(bouton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(20) })

        colonne.addView(TextView(this).apply {
            text = "L'identifiant et le mot de passe restent chiffrés sur ce téléphone " +
                "et ne sont envoyés qu'au site de ton académie."
            textSize = 12f
            setTextColor(gris)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })

        // Préremplissage (cas « changer de compte »)
        val acadEnregistree = prefs.getString(KEY_ACAD, null)
        val position = ACADEMIES.indexOf(acadEnregistree)
        if (position >= 0) choixAcademie.setSelection(position)
        champLogin.setText(prefs.getString(KEY_LOGIN, "") ?: "")

        bouton.setOnClickListener {
            val academie = choixAcademie.selectedItem as String
            val login = champLogin.text.toString().trim()
            val pass = champPass.text.toString()
            when {
                login.isEmpty() -> message.text = "Écris ton identifiant."
                pass.isEmpty() -> message.text = "Écris ton mot de passe."
                else -> {
                    prefs.edit()
                        .putString(KEY_ACAD, academie)
                        .putString(KEY_LOGIN, login)
                        .putString(KEY_PASS, pass)
                        .apply()
                    dernierEssai = 0L
                    dernierChoix = 0L
                    afficherWeb()
                }
            }
        }

        setContentView(racine)
    }

    // ---------------------------------------------------------------- pont JavaScript

    inner class Pont {
        /** Appelé par le script quand le formulaire de connexion vient d'être envoyé. */
        @JavascriptInterface
        fun soumis() {
            dernierEssai = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- écran de la messagerie

    @SuppressLint("SetJavaScriptEnabled")
    private fun afficherWeb() {
        val cadre = FrameLayout(this)
        val wv = WebView(this)
        webView = wv

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(Pont(), "AndroidBridge")

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val h = u.host ?: ""
                val interne = u.scheme == "https" && (
                    surDomaine(h, "gouv.fr") ||
                        surDomaine(h, "education.fr") ||
                        surDomaine(h, domaineAcademie())
                    )
                if (interne) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: ActivityNotFoundException) {
                    // lien non ouvrable : on l'ignore
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val u = Uri.parse(url ?: return)
                val host = u.host ?: return
                if (host.startsWith("hub.") && (u.path ?: "").contains("/ds")) {
                    choisirAcademie(view)
                } else if (surDomaine(host, domaineAcademie())) {
                    connexionAutomatique(view)
                }
            }
        }

        cadre.addView(
            wv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Petit bouton ⚙ discret en bas à gauche
        val engrenage = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#374151"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6FFFFFF"))
                setStroke(dp(1), Color.parseColor("#d1d5db"))
            }
            alpha = 0.8f
            setOnClickListener { afficherMenu() }
        }
        cadre.addView(
            engrenage,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(12), 0, 0, dp(12))
            }
        )

        setContentView(cadre)
        wv.loadUrl(URL_ACCUEIL)
    }

    private fun afficherMenu() {
        AlertDialog.Builder(this)
            .setTitle("Messagerie")
            .setItems(arrayOf("🏠 Revenir à la messagerie", "👤 Changer de compte")) { _, which ->
                when (which) {
                    0 -> {
                        dernierEssai = 0L
                        dernierChoix = 0L
                        webView?.loadUrl(URL_ACCUEIL)
                    }
                    1 -> changerDeCompte()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun changerDeCompte() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView?.clearCache(true)
        webView?.clearHistory()
        prefs.edit().remove(KEY_PASS).apply()
        afficherReglages()
    }

    // ---------------------------------------------------------------- automatismes

    private fun choisirAcademie(view: WebView) {
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierChoix < DELAI_ACADEMIE_MS) return
        dernierChoix = maintenant
        val nom = prefs.getString(KEY_ACAD, null) ?: return
        view.evaluateJavascript(SCRIPT_ACADEMIE.replace("__NAME__", JSONObject.quote(nom)), null)
    }

    private fun connexionAutomatique(view: WebView) {
        val login = prefs.getString(KEY_LOGIN, null) ?: return
        val pass = prefs.getString(KEY_PASS, null) ?: return

        if (System.currentTimeMillis() - dernierEssai < DELAI_LOGIN_MS) {
            // Une connexion vient d'être tentée : si le formulaire est de retour, c'est un échec.
            view.evaluateJavascript("document.querySelector('input[type=password]')!==null") { r ->
                if (r == "true") {
                    Toast.makeText(
                        this,
                        "Connexion non réussie : vérifie ton identifiant et ton mot de passe (bouton ⚙).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return
        }

        val js = SCRIPT_LOGIN
            .replace("__L__", JSONObject.quote(login))
            .replace("__P__", JSONObject.quote(pass))
        view.evaluateJavascript(js, null)
    }
}
