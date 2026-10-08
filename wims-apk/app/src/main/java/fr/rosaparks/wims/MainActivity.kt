package fr.rosaparks.wims

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private companion object {
        const val HOST = "wims.univ-amu.fr"
        const val URL_POLTEAU =
            "https://wims.univ-amu.fr/wims/wims.cgi?&+lang=fr&+module=adm%2Fclass%2Fclasses&+type=authparticipant&+class=1866219&+subclass=yes"
        const val URL_KHOUANI =
            "https://wims.univ-amu.fr/wims/wims.cgi?+lang=fr&+module=adm%2Fclass%2Fclasses&+type=authparticipant&+class=7458330&+subclass=yes"

        const val PREFS_FILE = "wims_secure"
        const val KEY_PROF = "prof"
        const val KEY_LOGIN = "login"
        const val KEY_PASS = "pass"
        const val PROF_POLTEAU = "polteau"
        const val PROF_KHOUANI = "khouani"

        // Délai minimal entre deux envois automatiques du formulaire :
        // évite de boucler (et de bloquer le compte) si le mot de passe est faux.
        const val DELAI_ENTRE_ESSAIS_MS = 60_000L
    }

    private lateinit var prefs: SharedPreferences
    private var webView: WebView? = null
    private var dernierEssai = 0L

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
            // Fichier illisible (clé perdue, restauration...) : on repart de zéro.
            deleteSharedPreferences(PREFS_FILE)
            creer()
        }
    }

    private fun identifiantsEnregistres(): Boolean =
        !prefs.getString(KEY_PROF, null).isNullOrEmpty() &&
            !prefs.getString(KEY_LOGIN, null).isNullOrEmpty() &&
            !prefs.getString(KEY_PASS, null).isNullOrEmpty()

    private fun urlAccueil(): String =
        if (prefs.getString(KEY_PROF, PROF_POLTEAU) == PROF_KHOUANI) URL_KHOUANI else URL_POLTEAU

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- écran de réglages

    private fun afficherReglages() {
        webView?.destroy()
        webView = null

        val rouge = Color.parseColor("#e11d48")
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
            text = "🎓 Connexion WIMS"
            textSize = 26f
            setTextColor(rouge)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
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
            text = "Mon professeur de mathématiques"
            textSize = 16f
            setTextColor(Color.parseColor("#1f2937"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val groupe = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbPolteau = RadioButton(this).apply {
            id = View.generateViewId()
            text = "M. POLTEAU"
            textSize = 17f
        }
        val rbKhouani = RadioButton(this).apply {
            id = View.generateViewId()
            text = "M. KHOUANI"
            textSize = 17f
        }
        groupe.addView(rbPolteau)
        groupe.addView(rbKhouani)
        colonne.addView(groupe)

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
            setTextColor(rouge)
            setPadding(0, dp(12), 0, 0)
        }
        colonne.addView(message)

        val bouton = Button(this).apply {
            text = "Enregistrer et ouvrir WIMS"
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(rouge)
                cornerRadius = dp(12).toFloat()
            }
        }
        colonne.addView(bouton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(20) })

        colonne.addView(TextView(this).apply {
            text = "L'identifiant et le mot de passe restent chiffrés sur ce téléphone."
            textSize = 12f
            setTextColor(gris)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })

        // Préremplissage (cas « changer de compte » après erreur de saisie)
        when (prefs.getString(KEY_PROF, null)) {
            PROF_KHOUANI -> rbKhouani.isChecked = true
            PROF_POLTEAU -> rbPolteau.isChecked = true
        }
        champLogin.setText(prefs.getString(KEY_LOGIN, "") ?: "")

        bouton.setOnClickListener {
            val prof = when (groupe.checkedRadioButtonId) {
                rbPolteau.id -> PROF_POLTEAU
                rbKhouani.id -> PROF_KHOUANI
                else -> null
            }
            val login = champLogin.text.toString().trim()
            val pass = champPass.text.toString()
            when {
                prof == null -> message.text = "Choisis ton professeur."
                login.isEmpty() -> message.text = "Écris ton identifiant."
                pass.isEmpty() -> message.text = "Écris ton mot de passe."
                else -> {
                    prefs.edit()
                        .putString(KEY_PROF, prof)
                        .putString(KEY_LOGIN, login)
                        .putString(KEY_PASS, pass)
                        .apply()
                    dernierEssai = 0L
                    afficherWeb()
                }
            }
        }

        setContentView(racine)
    }

    // ---------------------------------------------------------------- écran WIMS

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

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val h = u.host ?: ""
                if (u.scheme == "https" && (h == HOST || h.endsWith(".univ-amu.fr"))) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: ActivityNotFoundException) {
                    // lien non ouvrable : on l'ignore
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                connexionAutomatique(view, url)
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
        wv.loadUrl(urlAccueil())
    }

    private fun afficherMenu() {
        AlertDialog.Builder(this)
            .setTitle("WIMS")
            .setItems(arrayOf("🏠 Revenir à l'accueil", "👤 Changer de compte")) { _, which ->
                when (which) {
                    0 -> {
                        dernierEssai = 0L
                        webView?.loadUrl(urlAccueil())
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
        webView?.clearHistory()
        prefs.edit().remove(KEY_PASS).apply()
        afficherReglages()
    }

    // ---------------------------------------------------------------- connexion automatique

    private fun connexionAutomatique(view: WebView, url: String?) {
        // Les identifiants ne sont jamais injectés hors du serveur WIMS.
        val host = url?.let { Uri.parse(it).host } ?: return
        if (host != HOST) return
        val login = prefs.getString(KEY_LOGIN, null) ?: return
        val pass = prefs.getString(KEY_PASS, null) ?: return

        val autorise = System.currentTimeMillis() - dernierEssai > DELAI_ENTRE_ESSAIS_MS
        val js = construireScript(login, pass, autorise)

        view.evaluateJavascript(js) { resultat ->
            when (resultat?.trim('"')) {
                "submitted" -> dernierEssai = System.currentTimeMillis()
                "blocked" -> Toast.makeText(
                    this,
                    "Connexion non réussie : vérifie ton identifiant et ton mot de passe (bouton ⚙).",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Cherche le champ « mot de passe » de la page, puis le champ texte qui le précède
     * dans le même formulaire (l'identifiant), les remplit et clique sur le bouton de validation.
     * Si aucune page de connexion n'est affichée, il ne fait rien.
     */
    private fun construireScript(login: String, pass: String, autorise: Boolean): String {
        val l = JSONObject.quote(login)
        val p = JSONObject.quote(pass)
        return "(function(L,P,ALLOW){" +
            "try{" +
            "var pw=document.querySelector('input[type=password]');" +
            "if(!pw||!pw.form){return 'none';}" +
            "var f=pw.form,u=null,all=f.querySelectorAll('input');" +
            "for(var i=0;i<all.length;i++){" +
            "if(all[i]===pw){break;}" +
            "var t=(all[i].getAttribute('type')||'text').toLowerCase();" +
            "if(t==='text'||t==='email'||t==='number'||t==='tel'){u=all[i];}" +
            "}" +
            "if(!u){return 'none';}" +
            "function setv(el,v){" +
            "var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
            "if(d&&d.set){d.set.call(el,v);}else{el.value=v;}" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "}" +
            "setv(u,L);setv(pw,P);" +
            "if(!ALLOW){return 'blocked';}" +
            "var b=f.querySelector('input[type=submit],button[type=submit],button:not([type])');" +
            "if(b){b.click();}else{f.submit();}" +
            "return 'submitted';" +
            "}catch(e){return 'error';}" +
            "})(" + l + "," + p + "," + autorise + ");"
    }
}
