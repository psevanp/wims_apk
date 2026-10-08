# Messagerie Éducation – application Android

Ouvre la messagerie professionnelle (https://messagerie.education.gouv.fr/) dans un navigateur intégré,
choisit l'académie et se connecte automatiquement.

- Premier lancement : choisir l'académie, saisir identifiant et mot de passe.
- Lancements suivants : un clic sur l'icône ouvre la messagerie, connectée.
- Identifiant et mot de passe : chiffrés sur le téléphone (EncryptedSharedPreferences) et injectés uniquement sur le
  domaine de l'académie choisie (par ex. `*.ac-aix-marseille.fr`).
- Bouton ⚙ en bas à gauche : revenir à la messagerie ou changer de compte (efface aussi la session).

## Limites de cette première version

- Testée uniquement sur le papier : le choix de l'académie sur la page de découverte et le remplissage du formulaire
  reposent sur la structure des pages, qui n'a pas pu être lue à l'avance.
- Si une double authentification (code SMS, application, clé) est demandée, elle reste à saisir à la main.
- Les pièces jointes (envoi et téléchargement) ne sont pas gérées par le navigateur intégré : utiliser le navigateur
  habituel pour cela.
- Seule l'académie d'Aix-Marseille a son domaine vérifié ; pour les autres, le domaine est déduit du nom
  (`ac-<nom>.fr`). Si la déduction est fausse, la connexion automatique ne se déclenche pas (échec sans risque).

## Obtenir l'APK

Le workflow `.github/workflows/build-messagerie.yml` (à la racine du dépôt) compile l'application à chaque modification
de ce dossier. L'APK est publié dans la release `messagerie-latest` :

`https://github.com/psevanp/wims_apk/releases/download/messagerie-latest/messagerie-education.apk`
