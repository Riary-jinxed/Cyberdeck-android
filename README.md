# Netrunner — launcher Cyberpunk 2077 pour Android

Un écran d'accueil Android inspiré de l'interface de Cyberpunk 2077 : jaune néon, cyan, glitchs, lignes de balayage CRT et police HUD. Pensé pour le Xiaomi 13 Pro (HyperOS, Android 13+), il marche sur tout Android 10+.

## Ce qu'il fait

- **HUD d'accueil** : grosse horloge jaune qui « glitche » de temps en temps, date, batterie segmentée, ligne de terminal animée.
- **Quickhacks** : tes applis favorites en tuiles angulaires (appui long dans le tiroir pour épingler).
- **Accès réseau** : tiroir de toutes les applis avec recherche (Entrée lance le premier résultat).
- **Gestes** : glisser vers le haut ouvre le tiroir, vers le bas ouvre les notifications.
- Mise à jour automatique de la liste quand tu installes ou supprimes une appli.

## Installer sur le téléphone

1. Récupère l'APK : onglet **Actions** du dépôt GitHub › dernier build › artefact `netrunner-launcher` (ou construis-le, voir plus bas).
2. Copie l'APK sur le téléphone et ouvre-le (autorise l'installation depuis le gestionnaire de fichiers).
3. Appuie sur le bouton Accueil, ou va dans Paramètres › Applications › Applications par défaut › **Écran d'accueil**, et choisis **Netrunner**.

Sur HyperOS, les gestes plein écran fonctionnent avec un launcher tiers, mais l'animation des applis récentes est un peu plus simple qu'avec le launcher Xiaomi. Pour revenir en arrière, rechoisis « Écran d'accueil système » au même endroit.

## Construire soi-même

Avec Android Studio (Ladybug ou plus récent) : ouvre le dossier, laisse Gradle se synchroniser, puis **Run**. En ligne de commande, avec le SDK Android installé :

```bash
./gradlew assembleRelease
# APK : app/build/outputs/apk/release/app-release.apk
```

## Structure

| Fichier | Rôle |
| --- | --- |
| `MainActivity.kt` | Activité « HOME » déclarée comme écran d'accueil |
| `AppRepository.kt` | Liste des applis (LauncherApps), lancement, favoris |
| `LauncherViewModel.kt` | État de l'écran, suivi des installations |
| `ui/Theme.kt` | Palette Night City et police |
| `ui/Effects.kt` | Glitch, scanlines, grille, panneaux angulaires |
| `ui/HomeScreen.kt` | HUD, quickhacks, tiroir d'applis |

## Idées pour la suite

- Widgets Android (météo, musique) dans un cadre HUD
- Pack d'icônes monochromes jaune/cyan
- Police Rajdhani/Orbitron embarquée
- Fond d'écran animé (pluie néon)
