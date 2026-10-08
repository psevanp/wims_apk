# WIMS Rosa Parks – application Android

Application Android (WebView) qui ouvre la classe WIMS de l'élève et se connecte automatiquement.

- Premier lancement : l'élève choisit son professeur (M. POLTEAU ou M. KHOUANI), saisit son identifiant et son mot de passe.
- Les lancements suivants : un clic sur l'icône ouvre directement la page d'accueil du compte de l'élève.
- Identifiant et mot de passe sont stockés chiffrés (EncryptedSharedPreferences, clé dans le Keystore Android) et ne sont envoyés qu'à `wims.univ-amu.fr`.
- Bouton ⚙ en bas à gauche : revenir à l'accueil ou changer de compte.

## Obtenir l'APK (GitHub)

1. Créer un dépôt GitHub (par ex. `wims-apk`) et y déposer **tout le contenu de ce dossier à la racine**
   (le dossier `.github/workflows/` doit être à la racine du dépôt).
2. À chaque push sur `main`, l'action « Construire l'APK » compile l'application (environ 3 à 5 minutes).
   Elle peut aussi être lancée à la main : onglet *Actions* → *Construire l'APK* → *Run workflow*.
3. L'APK est publié dans l'onglet *Releases*. Lien de téléchargement stable, à donner aux élèves (ou à transformer en QR code) :

   `https://github.com/<compte>/<depot>/releases/latest/download/wims-rosaparks.apk`

## Installation sur le téléphone d'un élève

1. Ouvrir le lien de téléchargement depuis le téléphone, télécharger `wims-rosaparks.apk`.
2. Ouvrir le fichier ; Android demande d'autoriser l'installation depuis ce navigateur (« sources inconnues ») : accepter.
3. Lancer « WIMS Rosa Parks », choisir le professeur, saisir identifiant et mot de passe.

L'APK est signé avec la clé de débogage : il s'installe sans problème, mais Google Play Protect peut afficher un avertissement
« application non reconnue » (choisir « Installer quand même »).

## À tester avant de le donner aux élèves

- Que la connexion automatique fonctionne avec un compte élève de chaque classe (le script remplit le premier champ texte
  situé avant le champ mot de passe et clique sur le bouton de validation du formulaire).
- Que la protection anti-robot du serveur (Anubis) se passe normalement dans le navigateur intégré.
- Si le mot de passe est faux, l'application ne renvoie pas le formulaire en boucle (une seule tentative par minute) et
  affiche un message.

## Structure

- `app/src/main/java/fr/rosaparks/wims/MainActivity.kt` : toute la logique (réglages, stockage chiffré, WebView, connexion automatique).
- `app/src/main/res/` : icône, thème, nom de l'application.
- `.github/workflows/build-apk.yml` : compilation et publication automatiques de l'APK.
