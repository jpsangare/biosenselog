# Publication sur Google Play : BioSense Log

Ce guide reprend les points 1 à 5 de la liste avant publication. Les points 6 à 8 (test fermé, tests des vrais capteurs, relecture réglementaire) restent à faire à part.

Fichiers prêts dans le dépôt :

| Élément | Emplacement |
|---|---|
| Politique de confidentialité (EN + FR) | `docs/privacy-policy.html` |
| Icône de l'app (vectorielle, adaptative et à thème) | `app/src/main/res/drawable/ic_launcher_*.xml` |
| Icône Play Store 512×512 | `fastlane/metadata/android/en-US/images/icon.png` |
| Bannière 1024×500 | `fastlane/metadata/android/en-US/images/featureGraphic.png` |
| Sources des visuels | `play-store/art/` (`icon.svg`, `feature-graphic.html`) |
| Textes de la fiche, 8 langues | `fastlane/metadata/android/<langue>/` |

---

## 1. Clé d'importation (upload key)

La build release lit déjà `my-upload-key.jks` à la racine du projet (ou le chemin indiqué par `KEYSTORE_PATH`), avec l'alias `upload` et les mots de passe `STORE_PASSWORD` et `KEY_PASSWORD`. Les fichiers `*.jks` et `*.keystore` sont exclus de git.

**Créer la clé** (une seule fois, à la racine du projet) :

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore my-upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

- keytool demande un mot de passe, puis votre nom et votre organisation. Ces informations sont visibles dans le certificat.
- Le fichier créé est au format PKCS12 : la clé reprend le mot de passe du magasin. `KEY_PASSWORD` = `STORE_PASSWORD`.
- **Sauvegardez** le fichier `.jks` et son mot de passe hors de ce PC, par exemple dans un gestionnaire de mots de passe. Si vous les perdez, il faudra demander une réinitialisation de la clé à Google, ce qui prend plusieurs jours.

**Construire le bundle à envoyer sur Play** :

```powershell
$p = Read-Host 'Mot de passe de la clé' -AsSecureString
$env:STORE_PASSWORD = [Net.NetworkCredential]::new('', $p).Password
$env:KEY_PASSWORD = $env:STORE_PASSWORD
.\gradlew.bat :app:bundleRelease
```

Le fichier produit est `app/build/outputs/bundle/release/app-release.aab`.

- Lors du premier envoi, gardez **Signature d'application par Google Play** (proposée par défaut). Google conserve la clé de signature finale ; votre fichier `.jks` sert seulement de clé d'importation.
- Augmentez `versionCode` dans `app/build.gradle.kts` avant chaque nouvel envoi.

## 2. Politique de confidentialité

1. Le nom et l'e-mail de contact sont déjà renseignés dans `docs/privacy-policy.html` (ils seront publics).
2. Hébergez le fichier à une adresse HTTPS publique. Trois possibilités :
   - un petit dépôt GitHub **public** séparé, qui ne contient que ce fichier (renommé `index.html`), avec GitHub Pages activé ;
   - Cloudflare Pages ;
   - Google Sites.

   Ne rendez pas ce dépôt-ci public juste pour ça.
3. Collez l'adresse dans Play Console > Contenu de l'application > Règles de confidentialité, et dans la fiche Play Store.

La politique décrit l'app telle qu'elle est aujourd'hui : aucune permission INTERNET, mesures et nombre de pas exclus de la sauvegarde cloud. Si l'un de ces points change un jour, mettez d'abord à jour la page **et** le formulaire Data Safety.

## 3. Icône

- Icône de l'app : un cœur blanc avec une courbe de pouls, sur un dégradé sarcelle.
  - Android 8 et plus : icône adaptative.
  - Android 13 et plus : version monochrome pour les icônes à thème.
  - Android 7 : même dessin, à partir des mêmes vecteurs.
- Icône Play Store : `icon.png`, 512×512, sans transparence. Google arrondit lui-même les coins.
- Pour modifier un visuel, éditez `play-store/art/` puis régénérez le PNG avec Chrome en mode headless :

  ```powershell
  & "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --hide-scrollbars --window-size=512,512 --screenshot=icon.png play-store\art\icon.svg
  ```

  Pour la bannière, c'est la même commande avec `--window-size=1024,500` et `feature-graphic.html`.

## 4. Fiche Play Store

- **Textes** : `title.txt` (30 caractères max), `short_description.txt` (80 max) et `full_description.txt` (4000 max), pour les langues en-US, fr-FR, es-ES, de-DE, pt-BR, zh-CN, ar et hi-IN.
  - Dans la Play Console, définissez en-US comme langue par défaut, puis ajoutez les traductions.
  - Le dossier suit le format **fastlane supply** ; cela permettra d'automatiser l'envoi plus tard.
- **Ce qui reste à fournir** :
  - **Captures d'écran** : au moins 2 pour téléphone, au format 9:16, entre 320 et 3840 px. Prenez-les sur votre téléphone, avec de vraies mesures : écran Pouls, Historique, Tremblement, Respiration.
  - **E-mail de contact public**, obligatoire.
- **Catégorie** : Santé et remise en forme.
- **Règles respectées par les textes** :
  - aucune promesse médicale ;
  - avertissement « pas un dispositif médical » à la fin de chaque description ;
  - aucune incitation à laisser un avis ;
  - pas de « gratuit » ni de superlatif dans le titre.

## 5. Formulaires « Contenu de l'application »

### Sécurité des données (Data Safety)

Selon Google, une donnée est « collectée » seulement si elle est transmise hors de l'appareil. Le traitement local ne compte pas. Un partage lancé par l'utilisateur lui-même, via la feuille de partage Android, ne compte pas comme un « partage » au sens du formulaire.

| Question | Réponse |
|---|---|
| L'app collecte-t-elle ou partage-t-elle des types de données requis ? | **Non** |
| Chiffrement en transit | Sans objet (rien n'est transmis) |
| Moyen de demander la suppression des données | Sans objet. On peut toutefois préciser que tout se supprime dans l'app ou en la désinstallant. |

Le résumé public indiquera « Aucune donnée collectée » et « Aucune donnée partagée avec des tiers ».

### Déclaration des applications de santé

- Cochez uniquement les fonctions bien-être qui correspondent à l'app, par exemple « Activité et remise en forme » (pas, pouls) ou « Gestion de la santé / suivi personnel », selon les libellés affichés.
- **Ne cochez pas** les catégories « Médical », « Aide à la décision clinique » ni « Dispositif médical réglementé » : l'app ne revendique aucun usage médical.
- Health Connect : **non utilisé**.

### Autres rubriques

| Rubrique | Réponse |
|---|---|
| Annonces | Non, aucune publicité |
| Accès à l'application | Toutes les fonctions sont accessibles sans identifiants |
| Public cible | 18 ans et plus (évite les exigences du programme Familles ; cohérent avec la politique) |
| Classification du contenu (questionnaire IARC) | Catégorie Utilitaire / Santé ; « Non » à toutes les questions sur la violence, les jeux d'argent, les échanges entre utilisateurs et le partage de position |
| Application gouvernementale, financière, d'actualités | Non |
| Autorisations sensibles | CAMERA et ACTIVITY_RECOGNITION ne demandent aucune déclaration spéciale |

Avant d'envoyer les formulaires, vérifiez le manifeste final. La release ne doit demander que `CAMERA` et `ACTIVITY_RECOGNITION` (plus une permission interne d'AndroidX), sans `INTERNET` :

```powershell
.\gradlew.bat :app:processReleaseMainManifest
Select-String -Path app\build\intermediates\merged_manifest\release\processReleaseMainManifest\AndroidManifest.xml -Pattern uses-permission
```
