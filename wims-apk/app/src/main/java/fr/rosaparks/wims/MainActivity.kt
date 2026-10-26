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
import android.webkit.WebStorage
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
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private companion object {
        const val HOST = "wims.univ-amu.fr"
        const val URL_POLTEAU =
            "https://wims.univ-amu.fr/wims/wims.cgi?&+lang=fr&+module=adm%2Fclass%2Fclasses&+type=authparticipant&+class=1866219&+subclass=yes"
        const val URL_KHOUANI =
            "https://wims.univ-amu.fr/wims/wims.cgi?+lang=fr&+module=adm%2Fclass%2Fclasses&+type=authparticipant&+class=7458330&+subclass=yes"

        const val PREFS_FILE = "wims_secure"
        const val KEY_COMPTES = "comptes"   // liste JSON des comptes enregistrés
        const val KEY_ACTIF = "actif"       // identifiant du dernier compte ouvert
        // Anciennes clés (version à un seul compte), migrées au premier lancement
        const val KEY_PROF = "prof"
        const val KEY_LOGIN = "login"
        const val KEY_PASS = "pass"

        const val PROF_POLTEAU = "polteau"
        const val PROF_KHOUANI = "khouani"

        // Délai minimal entre deux envois automatiques du formulaire :
        // évite de boucler (et de bloquer le compte) si le mot de passe est faux.
        const val DELAI_ENTRE_ESSAIS_MS = 60_000L

        val ROUGE: Int = Color.parseColor("#e11d48")
        val BLEU: Int = Color.parseColor("#1a73e8")
        val GRIS: Int = Color.parseColor("#6b7280")
    }

    private class Compte(val prof: String, val login: String, val pass: String) {
        val id: String get() = "$prof|$login"
        val nomProf: String get() = if (prof == "khouani") "M. KHOUANI" else "M. POLTEAU"
        val couleur: Int get() = if (prof == "khouani") BLEU else ROUGE
    }

    private lateinit var prefs: SharedPreferences
    private var webView: WebView? = null
    private var compteActif: Compte? = null
    private var dernierEssai = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ouvrirPrefs()
        migrerAncienCompte()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = webView
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })

        val comptes = lireComptes()
        when {
            comptes.isEmpty() -> afficherAjout()
            comptes.size == 1 -> ouvrirCompte(comptes[0])
            else -> afficherChoix()
        }
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

    private fun lireComptes(): MutableList<Compte> {
        val liste = mutableListOf<Compte>()
        val brut = prefs.getString(KEY_COMPTES, null)
        if (!brut.isNullOrEmpty()) {
            try {
                val tableau = JSONArray(brut)
                for (i in 0 until tableau.length()) {
                    val o = tableau.getJSONObject(i)
                    liste.add(Compte(o.getString("prof"), o.getString("login"), o.getString("pass")))
                }
            } catch (e: Exception) {
                // liste illisible : on la considère vide
            }
        }
        return liste
    }

    private fun ecrireComptes(liste: List<Compte>) {
        val tableau = JSONArray()
        for (c in liste) {
            tableau.put(
                JSONObject().put("prof", c.prof).put("login", c.login).put("pass", c.pass)
            )
        }
        prefs.edit().putString(KEY_COMPTES, tableau.toString()).apply()
    }

    /** Reprend le compte unique des versions précédentes de l'application. */
    private fun migrerAncienCompte() {
        if (prefs.contains(KEY_COMPTES)) return
        val prof = prefs.getString(KEY_PROF, null)
        val login = prefs.getString(KEY_LOGIN, null)
        val pass = prefs.getString(KEY_PASS, null)
        if (!prof.isNullOrEmpty() && !login.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            val ancien = Compte(prof, login, pass)
            ecrireComptes(listOf(ancien))
            prefs.edit().putString(KEY_ACTIF, ancien.id).apply()
        }
        prefs.edit().remove(KEY_PROF).remove(KEY_LOGIN).remove(KEY_PASS).apply()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun urlAccueil(): String =
        if (compteActif?.prof == PROF_KHOUANI) URL_KHOUANI else URL_POLTEAU

    /** Ouvre WIMS avec ce compte (la session précédente est effacée si l'on change de compte). */
    private fun ouvrirCompte(c: Compte) {
        if (prefs.getString(KEY_ACTIF, null) != c.id) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        prefs.edit().putString(KEY_ACTIF, c.id).apply()
        compteActif = c
        dernierEssai = 0L
        afficherWeb()
    }

    private fun quitterWeb() {
        webView?.clearCache(true)
        webView?.destroy()
        webView = null
    }

    // ---------------------------------------------------------------- écran de choix du compte

    private fun nouvelEcran(): Pair<ScrollView, LinearLayout> {
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
        return Pair(racine, colonne)
    }

    private fun afficherChoix() {
        quitterWeb()
        val (racine, colonne) = nouvelEcran()
        val comptes = lireComptes()

        colonne.addView(TextView(this).apply {
            text = "🎓 Qui se connecte ?"
            textSize = 26f
            setTextColor(ROUGE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        colonne.addView(TextView(this).apply {
            text = "Touche ton compte pour ouvrir WIMS."
            textSize = 15f
            setTextColor(GRIS)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(20))
        })

        for (c in comptes) {
            val bouton = Button(this).apply {
                text = c.login + "\n" + c.nomProf
                isAllCaps = false
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(c.couleur)
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener { ouvrirCompte(c) }
                setOnLongClickListener {
                    confirmerSuppression(c)
                    true
                }
            }
            colonne.addView(bouton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76)
            ).apply { topMargin = dp(12) })
        }

        val ajouter = Button(this).apply {
            text = "➕ Ajouter un compte"
            isAllCaps = false
            textSize = 16f
            setTextColor(GRIS)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.parseColor("#d1d5db"))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { afficherAjout() }
        }
        colonne.addView(ajouter, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(24) })

        colonne.addView(TextView(this).apply {
            text = "Appui long sur un compte pour le supprimer."
            textSize = 12f
            setTextColor(GRIS)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(racine)
    }

    private fun confirmerSuppression(c: Compte) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer ce compte ?")
            .setMessage(c.login + " (" + c.nomProf + ") ne sera plus enregistré sur ce téléphone.")
            .setPositiveButton("Supprimer") { _, _ ->
                val reste = lireComptes().filter { it.id != c.id }
                ecrireComptes(reste)
                if (prefs.getString(KEY_ACTIF, null) == c.id) {
                    prefs.edit().remove(KEY_ACTIF).apply()
                    compteActif = null
                }
                if (reste.isEmpty()) afficherAjout() else afficherChoix()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------------------------------------------------------------- écran d'ajout d'un compte

    private fun afficherAjout() {
        quitterWeb()
        val (racine, colonne) = nouvelEcran()
        val existants = lireComptes()

        if (existants.isNotEmpty()) {
            colonne.addView(TextView(this).apply {
                text = "← Retour aux comptes"
                textSize = 16f
                setTextColor(GRIS)
                setPadding(0, 0, 0, dp(16))
                setOnClickListener { afficherChoix() }
            })
        }

        colonne.addView(TextView(this).apply {
            text = if (existants.isEmpty()) "🎓 Connexion WIMS" else "➕ Ajouter un compte"
            textSize = 26f
            setTextColor(ROUGE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        colonne.addView(TextView(this).apply {
            text = "À remplir une seule fois par compte. Ensuite, un clic suffit."
            textSize = 15f
            setTextColor(GRIS)
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
            setTextColor(ROUGE)
            setPadding(0, dp(12), 0, 0)
        }
        colonne.addView(message)

        val bouton = Button(this).apply {
            text = "Enregistrer et ouvrir WIMS"
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(ROUGE)
                cornerRadius = dp(12).toFloat()
            }
        }
        colonne.addView(bouton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(20) })

        colonne.addView(TextView(this).apply {
            text = "L'identifiant et le mot de passe restent chiffrés sur ce téléphone."
            textSize = 12f
            setTextColor(GRIS)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })

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
                    val nouveau = Compte(prof, login, pass)
                    val comptes = lireComptes()
                    val position = comptes.indexOfFirst { it.id == nouveau.id }
                    if (position >= 0) comptes[position] = nouveau else comptes.add(nouveau)
                    ecrireComptes(comptes)
                    ouvrirCompte(nouveau)
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
            .setItems(arrayOf("🏠 Revenir à l'accueil", "👥 Changer de compte")) { _, which ->
                when (which) {
                    0 -> {
                        dernierEssai = 0L
                        webView?.loadUrl(urlAccueil())
                    }
                    1 -> afficherChoix()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------------------------------------------------------------- connexion automatique

    private fun connexionAutomatique(view: WebView, url: String?) {
        // Les identifiants ne sont jamais injectés hors du serveur WIMS.
        val host = url?.let { Uri.parse(it).host } ?: return
        if (host != HOST) return
        val compte = compteActif ?: return

        val autorise = System.currentTimeMillis() - dernierEssai > DELAI_ENTRE_ESSAIS_MS
        val js = construireScript(compte.login, compte.pass, autorise)

        view.evaluateJavascript(js) { resultat ->
            when (resultat?.trim('"')) {
                "submitted" -> dernierEssai = System.currentTimeMillis()
                "blocked" -> Toast.makeText(
                    this,
                    "Connexion non réussie : vérifie ton identifiant et ton mot de passe (⚙ puis Changer de compte).",
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
